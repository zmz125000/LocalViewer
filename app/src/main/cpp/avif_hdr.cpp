/*
 * AVIF still decode via libavif → Ultra HDR JPEG (libultrahdr).
 *
 * - Gain-map AVIF: prefer Android 14+ ImageDecoder (Kotlin); not re-encoded here.
 * - Absolute PQ/HLG AVIF: decode with libavif, linearize in **source primaries**,
 *   encode content-matched Ultra HDR JPEG tagged with matching libultrahdr gamut
 *   (BT.2100 / Display P3 / BT.709). Do not force BT.709 clip when the format can
 *   carry the source gamut.
 *
 * JNI: com.hippo.ehviewer.jni.HdrConvertKt.convertAvifBytesToUltraHdr
 */
#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "avif/avif.h"
#include "hdr_encode.h"
#include "ultrahdr_api.h"

#define LOG_TAG "AvifHdr"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

uint16_t float_to_half(float f) {
    union {
        float f;
        uint32_t u;
    } v{f};
    uint32_t x = v.u;
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffffu;
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        uint32_t t = 14 - exp;
        uint32_t m = mant >> t;
        if ((mant >> (t - 1)) & 1u) m++;
        return static_cast<uint16_t>(sign | m);
    }
    if (exp >= 31) {
        if (mant) return static_cast<uint16_t>(sign | 0x7e00u);
        return static_cast<uint16_t>(sign | 0x7c00u);
    }
    uint32_t half = sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) half++;
    return static_cast<uint16_t>(half);
}

// SMPTE ST 2084 PQ EOTF → linear relative luminance [0, 10000] nits-ish (absolute).
float pq_eotf(float n) {
    if (n <= 0.f) return 0.f;
    if (n >= 1.f) n = 1.f;
    const float m1 = 0.1593017578125f;
    const float m2 = 78.84375f;
    const float c1 = 0.8359375f;
    const float c2 = 18.8515625f;
    const float c3 = 18.6875f;
    const float np = std::pow(n, 1.f / m2);
    float num = np - c1;
    if (num < 0.f) num = 0.f;
    const float den = c2 - c3 * np;
    if (den <= 1e-6f) return 10000.f;
    return 10000.f * std::pow(num / den, 1.f / m1);
}

// HLG inverse OETF (BT.2100) → relative scene-linear [0, 12] roughly, then scale.
float hlg_inv_oetf(float x) {
    if (x <= 0.f) return 0.f;
    if (x >= 1.f) x = 1.f;
    const float a = 0.17883277f;
    const float b = 0.28466892f;
    const float c = 0.55991073f;
    if (x <= 0.5f) {
        return (x * x) / 3.f;
    }
    return (std::exp((x - c) / a) + b) / 12.f;
}

// BT.2020 → BT.709 linear matrix (fallback only when we cannot tag source gamut).
void bt2020_to_bt709(float& r, float& g, float& b) {
    const float rr = 1.6605f * r - 0.5876f * g - 0.0728f * b;
    const float gg = -0.1246f * r + 1.1329f * g - 0.0083f * b;
    const float bb = -0.0182f * r - 0.1006f * g + 1.1187f * b;
    r = rr;
    g = gg;
    b = bb;
}

/**
 * Map AVIF CICP primaries (+ transfer) → libultrahdr gamut tag.
 * Values and tags must stay consistent: if we keep BT.2020 RGB, tag BT_2100.
 */
uhdr_color_gamut_t map_avif_primaries_to_uhdr_cg(avifColorPrimaries primaries,
                                                   bool is_pq_or_hlg) {
    switch (primaries) {
        case AVIF_COLOR_PRIMARIES_BT2020:
            return UHDR_CG_BT_2100;
        case AVIF_COLOR_PRIMARIES_SMPTE432:  // Display P3 (D65)
            return UHDR_CG_DISPLAY_P3;
        case AVIF_COLOR_PRIMARIES_SMPTE431:  // DCI-P3 — closest Ultra HDR tag is Display P3
            return UHDR_CG_DISPLAY_P3;
        case AVIF_COLOR_PRIMARIES_BT709:
            return UHDR_CG_BT_709;
        default:
            // PQ/HLG with unspecified/unknown primaries: industry default is BT.2020.
            if (is_pq_or_hlg) return UHDR_CG_BT_2100;
            return UHDR_CG_BT_709;
    }
}

/**
 * Decode AVIF → linear RGBA half in source (or fallback BT.709) primaries.
 * 1.0 = SDR white / 203 nits for PQ.
 *
 * @param out_cg libultrahdr gamut matching out_rgba (required for accurate encode).
 * @return 0 OK, non-zero on failure. Sets out_has_gainmap if libavif reports a gain map.
 */
int decode_avif_to_linear_f16(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                              unsigned& w, unsigned& h, int* out_has_gainmap,
                              int* out_transfer /* 16=PQ, 18=HLG, else other */,
                              uhdr_color_gamut_t* out_cg) {
    if (out_has_gainmap) *out_has_gainmap = 0;
    if (out_transfer) *out_transfer = 0;
    if (out_cg) *out_cg = UHDR_CG_BT_709;
    if (!data || len == 0) return -1;

    avifDecoder* dec = avifDecoderCreate();
    if (!dec) return -2;
    dec->ignoreExif = AVIF_TRUE;
    dec->ignoreXMP = AVIF_TRUE;
    // Prefer progressive; max threads leave default.
    avifResult r = avifDecoderSetIOMemory(dec, data, len);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderSetIOMemory: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -3;
    }
    r = avifDecoderParse(dec);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderParse: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -4;
    }
    r = avifDecoderNextImage(dec);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderNextImage: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -5;
    }

    avifImage* image = dec->image;
    if (!image || image->width == 0 || image->height == 0) {
        avifDecoderDestroy(dec);
        return -6;
    }
    w = image->width;
    h = image->height;
    if (w > 16384 || h > 16384) {
        avifDecoderDestroy(dec);
        return -7;
    }

#if defined(AVIF_ENABLE_EXPERIMENTAL_GAIN_MAP) || 1
    // libavif 1.0+: gain map image pointer when present.
    if (image->gainMap != nullptr && out_has_gainmap) {
        *out_has_gainmap = 1;
    }
#endif

    const avifTransferCharacteristics tc = image->transferCharacteristics;
    if (out_transfer) *out_transfer = static_cast<int>(tc);
    const bool is_pq = (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084);
    const bool is_hlg = (tc == AVIF_TRANSFER_CHARACTERISTICS_HLG);
    const avifColorPrimaries primaries = image->colorPrimaries;
    const bool is_bt2020 = (primaries == AVIF_COLOR_PRIMARIES_BT2020);

    // Preserve source gamut when libultrahdr can tag it (accuracy-first).
    // Policy B (convert → BT.709) only if we ever tag 709 while source was BT.2020.
    uhdr_color_gamut_t cg = map_avif_primaries_to_uhdr_cg(primaries, is_pq || is_hlg);
    const bool need_bt2020_to_709 = (cg == UHDR_CG_BT_709 && is_bt2020);
    if (out_cg) *out_cg = cg;

    avifRGBImage rgb;
    memset(&rgb, 0, sizeof(rgb));
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 16;
    rgb.chromaUpsampling = AVIF_CHROMA_UPSAMPLING_BEST_QUALITY;

    r = avifRGBImageAllocatePixels(&rgb);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifRGBImageAllocatePixels: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -8;
    }
    r = avifImageYUVToRGB(image, &rgb);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifImageYUVToRGB: %s", avifResultToString(r));
        avifRGBImageFreePixels(&rgb);
        avifDecoderDestroy(dec);
        return -9;
    }

    const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
    out_rgba.resize(pixels * 4);
    const uint8_t* row_base = rgb.pixels;
    const uint32_t row_bytes = rgb.rowBytes;

    for (unsigned y = 0; y < h; y++) {
        const uint16_t* row = reinterpret_cast<const uint16_t*>(row_base + static_cast<size_t>(y) * row_bytes);
        for (unsigned x = 0; x < w; x++) {
            const uint16_t* p = row + x * 4;
            // 16-bit full range normalized (transfer-encoded if PQ/HLG)
            float rn = p[0] / 65535.f;
            float gn = p[1] / 65535.f;
            float bn = p[2] / 65535.f;
            float an = p[3] / 65535.f;

            float rl, gl, bl;
            if (is_pq) {
                // PQ code → nits → linear relative to 203 nits SDR white (source primaries).
                rl = pq_eotf(rn) / kSdrWhiteNits;
                gl = pq_eotf(gn) / kSdrWhiteNits;
                bl = pq_eotf(bn) / kSdrWhiteNits;
            } else if (is_hlg) {
                // HLG → scene linear, scale so reference white ≈ 1.
                rl = hlg_inv_oetf(rn) * 12.f;
                gl = hlg_inv_oetf(gn) * 12.f;
                bl = hlg_inv_oetf(bn) * 12.f;
            } else if (tc == AVIF_TRANSFER_CHARACTERISTICS_LINEAR) {
                rl = rn;
                gl = gn;
                bl = bn;
            } else {
                // sRGB / 709 / IEC61966 / unspecified gamma-encoded → linear (IEC sRGB EOTF).
                // libavif YUVToRGB returns transfer-encoded samples for non-PQ/HLG.
                rl = srgb_eotf(rn);
                gl = srgb_eotf(gn);
                bl = srgb_eotf(bn);
            }

            // Policy B only: values must match the BT.709 tag. Preserve path never rematrixes.
            if (need_bt2020_to_709) {
                bt2020_to_bt709(rl, gl, bl);
            }

            // Clamp to Ultra HDR LINEAR nominal max (10000/203).
            auto clamp_hf = [](float v) {
                if (!std::isfinite(v) || v < 0.f) return 0.f;
                if (v > kMaxLinear) return kMaxLinear;
                return v;
            };
            rl = clamp_hf(rl);
            gl = clamp_hf(gl);
            bl = clamp_hf(bl);
            if (an < 0.f) an = 0.f;
            if (an > 1.f) an = 1.f;

            const size_t i = static_cast<size_t>(y) * w + x;
            out_rgba[i * 4 + 0] = float_to_half(rl);
            out_rgba[i * 4 + 1] = float_to_half(gl);
            out_rgba[i * 4 + 2] = float_to_half(bl);
            out_rgba[i * 4 + 3] = float_to_half(an);
        }
    }

    ALOGI("AVIF %ux%u tc=%d primaries=%d pq=%d hlg=%d cg=%d rematrix709=%d gainmap=%d", w, h,
          (int)tc, (int)primaries, is_pq ? 1 : 0, is_hlg ? 1 : 0, (int)cg,
          need_bt2020_to_709 ? 1 : 0, out_has_gainmap ? *out_has_gainmap : 0);

    avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(dec);
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdr(JNIEnv* env, jclass,
                                                                    jbyteArray jInput,
                                                                    jstring jOutput) {
    if (!jInput || !jOutput) return -10;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return -12;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return -13;
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) {
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg);
    if (rc != 0) {
        ALOGE("AVIF decode failed rc=%d", rc);
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    // Gain-map AVIF should use platform path; if caller still converts, we encode base HDR.
    if (has_gm) {
        ALOGI("AVIF has embedded gain map — encoding linearized base to Ultra HDR JPEG");
    }

    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg, 0.f, force_hdr);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/** AVIF → Ultra HDR with optional long-edge cap (0 = full res). Used for thumbs. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdrMaxEdge(
        JNIEnv* env, jclass, jbyteArray jInput, jstring jOutput, jint maxEdge) {
    if (!jInput || !jOutput) return -10;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return -12;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return -13;
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) {
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg);
    if (rc != 0) {
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    // Thumbs: known TF only — 203 nits SDR / 1000 nits HDR; no peak scan.
    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg,
                                       thumb_fixed_peak_nits(force_hdr), force_hdr);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/**
 * PQ/HLG AVIF → direct display pixels (skip UHDR JPEG).
 * outInfo int[≥6]: w, h, format, isHdr, gamut, transferCICP
 * outBoost float[1]: contentHdrBoost
 * advancedColor: WCG preserve + high bit depth
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeAvifBytesToDirect(JNIEnv* env, jclass,
                                                                 jbyteArray jInput, jint maxEdge,
                                                                 jboolean advancedColor,
                                                                 jintArray jOutInfo,
                                                                 jfloatArray jOutBoost) {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return nullptr;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    jbyteArray result = nullptr;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg);
    if (rc == 0) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        const bool force_hdr = (transfer == 16 || transfer == 18);
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer, jOutInfo,
                                            jOutBoost);
    } else {
        ALOGE("AVIF direct decode failed rc=%d", rc);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}

/**
 * Probe: 0 = not AVIF / error, 1 = gain-map AVIF, 2 = absolute PQ/HLG, 3 = other AVIF.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeAvifHdrKind(JNIEnv* env, jclass, jbyteArray jInput) {
    if (!jInput) return 0;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return 0;

    avifDecoder* dec = avifDecoderCreate();
    jint result = 0;
    if (dec) {
        avifResult r =
            avifDecoderSetIOMemory(dec, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
        if (r == AVIF_RESULT_OK) {
            r = avifDecoderParse(dec);
            if (r == AVIF_RESULT_OK && dec->image) {
                result = 3;
                if (dec->image->gainMap != nullptr) {
                    result = 1;
                } else {
                    const auto tc = dec->image->transferCharacteristics;
                    if (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084 ||
                        tc == AVIF_TRANSFER_CHARACTERISTICS_HLG) {
                        result = 2;
                    }
                }
            }
        }
        avifDecoderDestroy(dec);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}
