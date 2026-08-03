/*
 * JPEG XL → Ultra HDR JPEG via libjxl + libultrahdr.
 *
 * Always-convert path (platform ImageDecoder is unreliable for HDR JXL).
 * Linear half-float in source-ish primaries → encode_linear_rgba_f16_to_uhdr.
 *
 * JNI: com.hippo.ehviewer.jni.HdrConvertKt.convertJxlBytesToUltraHdr
 */
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include <jxl/decode.h>
#include <jxl/decode_cxx.h>
#include <jxl/resizable_parallel_runner_cxx.h>

#include "hdr_encode.h"
#include "ultrahdr_api.h"

#define LOG_TAG "JxlHdr"
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

float hlg_inv_oetf(float x) {
    if (x <= 0.f) return 0.f;
    if (x >= 1.f) x = 1.f;
    const float a = 0.17883277f;
    const float b = 0.28466892f;
    const float c = 0.55991073f;
    if (x <= 0.5f) return (x * x) / 3.f;
    return (std::exp((x - c) / a) + b) / 12.f;
}

uhdr_color_gamut_t map_jxl_primaries(const JxlColorEncoding& enc) {
    // JXL primaries enum: 1=sRGB, 9=P3, 11=BT.2100/BT.2020 (values follow CICP-ish)
    switch (enc.primaries) {
        case JXL_PRIMARIES_SRGB:
            return UHDR_CG_BT_709;
        case JXL_PRIMARIES_P3:
            return UHDR_CG_DISPLAY_P3;
        case JXL_PRIMARIES_2100:
            return UHDR_CG_BT_2100;
        default:
            if (enc.transfer_function == JXL_TRANSFER_FUNCTION_PQ ||
                enc.transfer_function == JXL_TRANSFER_FUNCTION_HLG) {
                return UHDR_CG_BT_2100;
            }
            return UHDR_CG_BT_709;
    }
}

/**
 * Decode JXL → linear RGBA half (1.0 ≈ SDR white).
 * @return 0 OK
 */
int decode_jxl_to_linear_f16(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                             unsigned& w, unsigned& h, uhdr_color_gamut_t* out_cg) {
    if (out_cg) *out_cg = UHDR_CG_BT_709;
    if (!data || len == 0) return -1;

    auto runner = JxlResizableParallelRunnerMake(nullptr);
    auto dec = JxlDecoderMake(nullptr);
    if (!dec) return -2;

    if (JxlDecoderSubscribeEvents(dec.get(),
                                  JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) !=
        JXL_DEC_SUCCESS) {
        return -3;
    }
    JxlDecoderSetParallelRunner(dec.get(), JxlResizableParallelRunner, runner.get());

    JxlDecoderSetInput(dec.get(), data, len);
    JxlDecoderCloseInput(dec.get());

    JxlBasicInfo info{};
    JxlColorEncoding color_encoding{};
    bool have_color = false;
    std::vector<float> pixels;
    JxlPixelFormat format = {4, JXL_TYPE_FLOAT, JXL_NATIVE_ENDIAN, 0};

    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
        if (status == JXL_DEC_ERROR) {
            ALOGE("JxlDecoderProcessInput error");
            return -4;
        }
        if (status == JXL_DEC_NEED_MORE_INPUT) {
            ALOGE("JXL truncated");
            return -5;
        }
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) return -6;
            w = info.xsize;
            h = info.ysize;
            if (w == 0 || h == 0 || w > 16384 || h > 16384) return -7;
            JxlResizableParallelRunnerSetThreads(
                runner.get(), JxlResizableParallelRunnerSuggestThreads(w, h));
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            if (JxlDecoderGetColorAsEncodedProfile(dec.get(), JXL_COLOR_PROFILE_TARGET_DATA,
                                                   &color_encoding) == JXL_DEC_SUCCESS) {
                have_color = true;
            }
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t buffer_size = 0;
            if (JxlDecoderImageOutBufferSize(dec.get(), &format, &buffer_size) != JXL_DEC_SUCCESS) {
                return -8;
            }
            pixels.resize(buffer_size / sizeof(float));
            if (JxlDecoderSetImageOutBuffer(dec.get(), &format, pixels.data(), buffer_size) !=
                JXL_DEC_SUCCESS) {
                return -9;
            }
        } else if (status == JXL_DEC_FULL_IMAGE) {
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        }
    }

    if (pixels.empty() || w == 0 || h == 0) return -10;

    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool is_pq = false;
    bool is_hlg = false;
    bool is_linear = true;
    if (have_color) {
        cg = map_jxl_primaries(color_encoding);
        is_pq = color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_PQ;
        is_hlg = color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_HLG;
        is_linear = color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_LINEAR ||
            color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_PQ ||
            color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_HLG;
        // PQ/HLG code values need EOTF; LINEAR already scene/display linear.
        if (color_encoding.transfer_function == JXL_TRANSFER_FUNCTION_LINEAR) {
            is_linear = true;
            is_pq = false;
            is_hlg = false;
        }
    }
    if (out_cg) *out_cg = cg;

    const size_t pixels_n = static_cast<size_t>(w) * static_cast<size_t>(h);
    out_rgba.resize(pixels_n * 4);

    // BasicInfo.intensity_target is nits of the white point when meaningful.
    const float intensity =
        (info.intensity_target > 1.f) ? info.intensity_target : 203.f;

    for (size_t i = 0; i < pixels_n; i++) {
        float r = pixels[i * 4 + 0];
        float g = pixels[i * 4 + 1];
        float b = pixels[i * 4 + 2];
        float a = pixels[i * 4 + 3];

        float rl, gl, bl;
        if (is_pq) {
            rl = pq_eotf(r) / 203.f;
            gl = pq_eotf(g) / 203.f;
            bl = pq_eotf(b) / 203.f;
        } else if (is_hlg) {
            rl = hlg_inv_oetf(r) * 12.f;
            gl = hlg_inv_oetf(g) * 12.f;
            bl = hlg_inv_oetf(b) * 12.f;
        } else if (is_linear) {
            // Relative linear from libjxl; values are typically relative to peak/white.
            // If intensity_target is HDR nits, scale so 1.0 white → intensity/203.
            if (intensity > 250.f) {
                const float s = intensity / 203.f;
                rl = r * s;
                gl = g * s;
                bl = b * s;
            } else {
                rl = r;
                gl = g;
                bl = b;
            }
        } else {
            // Assume sRGB-ish encoded 0..1 — approximate linear via square (cheap).
            rl = r * r;
            gl = g * g;
            bl = b * b;
        }

        auto clamp_hf = [](float v) {
            if (!std::isfinite(v) || v < 0.f) return 0.f;
            if (v > 64.f) return 64.f;
            return v;
        };
        rl = clamp_hf(rl);
        gl = clamp_hf(gl);
        bl = clamp_hf(bl);
        if (a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;

        out_rgba[i * 4 + 0] = float_to_half(rl);
        out_rgba[i * 4 + 1] = float_to_half(gl);
        out_rgba[i * 4 + 2] = float_to_half(bl);
        out_rgba[i * 4 + 3] = float_to_half(a);
    }

    ALOGI("JXL %ux%u cg=%d pq=%d hlg=%d", w, h, (int)cg, is_pq ? 1 : 0, is_hlg ? 1 : 0);
    return 0;
}

/** True when metadata says absolute HDR (PQ/HLG/high intensity linear). */
bool jxl_metadata_is_hdr(const JxlBasicInfo& info, const JxlColorEncoding& enc, bool have_color) {
    if (info.intensity_target > 250.f) return true;
    if (!have_color) {
        // No profile: 8-bit stills are SDR; float/high bit-depth often HDR.
        return info.bits_per_sample > 8 || info.exponent_bits_per_sample > 0;
    }
    if (enc.transfer_function == JXL_TRANSFER_FUNCTION_PQ) return true;
    if (enc.transfer_function == JXL_TRANSFER_FUNCTION_HLG) return true;
    if (enc.transfer_function == JXL_TRANSFER_FUNCTION_LINEAR && info.intensity_target > 250.f) {
        return true;
    }
    // Linear + float samples without SDR intensity → treat as HDR scRGB-like.
    if (enc.transfer_function == JXL_TRANSFER_FUNCTION_LINEAR &&
        (info.bits_per_sample > 8 || info.exponent_bits_per_sample > 0)) {
        return true;
    }
    return false;
}

/**
 * Parse BASIC_INFO + COLOR_ENCODING only (no full pixels).
 * @return 0 error, 1 SDR, 2 HDR
 */
int probe_jxl_content(const uint8_t* data, size_t len) {
    if (!data || len == 0) return 0;
    auto runner = JxlResizableParallelRunnerMake(nullptr);
    auto dec = JxlDecoderMake(nullptr);
    if (!dec) return 0;
    if (JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING) !=
        JXL_DEC_SUCCESS) {
        return 0;
    }
    JxlDecoderSetParallelRunner(dec.get(), JxlResizableParallelRunner, runner.get());
    JxlDecoderSetInput(dec.get(), data, len);
    JxlDecoderCloseInput(dec.get());

    JxlBasicInfo info{};
    JxlColorEncoding color_encoding{};
    bool have_basic = false;
    bool have_color = false;
    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
        if (status == JXL_DEC_ERROR || status == JXL_DEC_NEED_MORE_INPUT) return 0;
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) return 0;
            have_basic = true;
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            if (JxlDecoderGetColorAsEncodedProfile(dec.get(), JXL_COLOR_PROFILE_TARGET_DATA,
                                                   &color_encoding) == JXL_DEC_SUCCESS) {
                have_color = true;
            }
            // Enough metadata for classification.
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        }
    }
    if (!have_basic) return 0;
    const bool hdr = jxl_metadata_is_hdr(info, color_encoding, have_color);
    ALOGI("probe JXL %ux%u bits=%u exp=%u intensity=%.1f hdr=%d", info.xsize, info.ysize,
          info.bits_per_sample, info.exponent_bits_per_sample, info.intensity_target, hdr ? 1 : 0);
    return hdr ? 2 : 1;
}

/**
 * Decode JXL as SDR RGBA8888 (premultiplied=false, sRGB-ish 0..255).
 * max_edge 0 = full res. @return 0 OK.
 */
int decode_jxl_sdr_rgba8(const uint8_t* data, size_t len, std::vector<uint8_t>& out_rgba,
                         unsigned& w, unsigned& h, unsigned max_edge) {
    if (!data || len == 0) return -1;
    auto runner = JxlResizableParallelRunnerMake(nullptr);
    auto dec = JxlDecoderMake(nullptr);
    if (!dec) return -2;
    if (JxlDecoderSubscribeEvents(dec.get(),
                                  JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) !=
        JXL_DEC_SUCCESS) {
        return -3;
    }
    JxlDecoderSetParallelRunner(dec.get(), JxlResizableParallelRunner, runner.get());
    // Prefer libjxl color management → sRGB UINT8 when available.
    JxlColorEncoding srgb{};
    srgb.color_space = JXL_COLOR_SPACE_RGB;
    srgb.white_point = JXL_WHITE_POINT_D65;
    srgb.primaries = JXL_PRIMARIES_SRGB;
    srgb.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
    srgb.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
    JxlDecoderSetPreferredColorProfile(dec.get(), &srgb);
    JxlDecoderSetInput(dec.get(), data, len);
    JxlDecoderCloseInput(dec.get());

    JxlBasicInfo info{};
    JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    std::vector<uint8_t> pixels;

    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
        if (status == JXL_DEC_ERROR) return -4;
        if (status == JXL_DEC_NEED_MORE_INPUT) return -5;
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) return -6;
            w = info.xsize;
            h = info.ysize;
            if (w == 0 || h == 0 || w > 16384 || h > 16384) return -7;
            JxlResizableParallelRunnerSetThreads(
                runner.get(), JxlResizableParallelRunnerSuggestThreads(w, h));
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t buffer_size = 0;
            if (JxlDecoderImageOutBufferSize(dec.get(), &format, &buffer_size) != JXL_DEC_SUCCESS) {
                return -8;
            }
            pixels.resize(buffer_size);
            if (JxlDecoderSetImageOutBuffer(dec.get(), &format, pixels.data(), buffer_size) !=
                JXL_DEC_SUCCESS) {
                return -9;
            }
        } else if (status == JXL_DEC_FULL_IMAGE || status == JXL_DEC_SUCCESS) {
            if (!pixels.empty()) break;
            if (status == JXL_DEC_SUCCESS) break;
        }
    }
    if (pixels.empty() || w == 0 || h == 0) return -10;

    if (max_edge > 0) {
        const unsigned long_edge = w > h ? w : h;
        if (long_edge > max_edge) {
            const float scale = static_cast<float>(max_edge) / static_cast<float>(long_edge);
            const unsigned nw = std::max(1u, static_cast<unsigned>(w * scale));
            const unsigned nh = std::max(1u, static_cast<unsigned>(h * scale));
            std::vector<uint8_t> scaled(static_cast<size_t>(nw) * nh * 4);
            for (unsigned y = 0; y < nh; y++) {
                const unsigned sy = y * h / nh;
                for (unsigned x = 0; x < nw; x++) {
                    const unsigned sx = x * w / nw;
                    const size_t si = (static_cast<size_t>(sy) * w + sx) * 4;
                    const size_t di = (static_cast<size_t>(y) * nw + x) * 4;
                    scaled[di + 0] = pixels[si + 0];
                    scaled[di + 1] = pixels[si + 1];
                    scaled[di + 2] = pixels[si + 2];
                    scaled[di + 3] = pixels[si + 3];
                }
            }
            pixels.swap(scaled);
            w = nw;
            h = nh;
        }
    }
    out_rgba.swap(pixels);
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeJxlContent(JNIEnv* env, jclass, jbyteArray jInput) {
    if (!jInput) return 0;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return 0;
    const int rc = probe_jxl_content(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxlSdrRgba8(JNIEnv* env, jclass, jbyteArray jInput,
                                                           jint maxEdge, jintArray jOutWh) {
    if (!jInput || !jOutWh || env->GetArrayLength(jOutWh) < 2) return nullptr;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return nullptr;
    std::vector<uint8_t> rgba;
    unsigned w = 0, h = 0;
    const int rc = decode_jxl_sdr_rgba8(reinterpret_cast<const uint8_t*>(bytes),
                                        static_cast<size_t>(len), rgba, w, h,
                                        maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    if (rc != 0 || rgba.empty()) return nullptr;
    jint wh[2] = {static_cast<jint>(w), static_cast<jint>(h)};
    env->SetIntArrayRegion(jOutWh, 0, 2, wh);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()),
                            reinterpret_cast<const jbyte*>(rgba.data()));
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdr(JNIEnv* env, jclass,
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
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg);
    if (rc != 0) {
        ALOGE("JXL decode failed rc=%d", rc);
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }

    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/**
 * Convert JXL → Ultra HDR with optional long-edge cap (0 = full res). Used for thumbs.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdrMaxEdge(
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
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg);
    if (rc != 0) {
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}
