/*
 * JPEG XL → one linear RGBA_F16 working contract via libjxl's CMS.
 *
 * libjxl's decoder CMS owns XYB conversion. Original-profile/modular images use
 * an explicit skcms pass because libjxl 0.11.1 leaves those float samples encoded.
 * Neither path applies a second manual EOTF.
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

#include <jxl/cms.h>
#include <jxl/color_encoding.h>
#include <jxl/decode.h>
#include <jxl/decode_cxx.h>
#include <jxl/resizable_parallel_runner_cxx.h>

#include "hdr_encode.h"
#include "skcms.h"
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

void set_linear_rgb(JxlColorEncoding* enc, JxlPrimaries primaries) {
    *enc = {};
    enc->color_space = JXL_COLOR_SPACE_RGB;
    enc->white_point = JXL_WHITE_POINT_D65;
    enc->primaries = primaries;
    enc->transfer_function = JXL_TRANSFER_FUNCTION_LINEAR;
    enc->rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
}

/** Build the linear RGB profile matching the gamut handed to the shared encoder/packer. */
bool make_linear_output_profile(uhdr_color_gamut_t cg, skcms_ICCProfile* profile) {
    if (!profile) return false;
    skcms_Init(profile);
    skcms_SetTransferFunction(profile, skcms_Identity_TransferFunction());

    skcms_Matrix3x3 to_xyz_d50{};
    bool have_matrix = false;
    if (cg == UHDR_CG_BT_709) {
        const skcms_ICCProfile* srgb = skcms_sRGB_profile();
        if (srgb && srgb->has_toXYZD50) {
            to_xyz_d50 = srgb->toXYZD50;
            have_matrix = true;
        }
    } else if (cg == UHDR_CG_DISPLAY_P3) {
        have_matrix = skcms_PrimariesToXYZD50(0.680f, 0.320f, 0.265f, 0.690f, 0.150f,
                                               0.060f, 0.3127f, 0.3290f, &to_xyz_d50);
    } else if (cg == UHDR_CG_BT_2100) {
        have_matrix = skcms_PrimariesToXYZD50(0.708f, 0.292f, 0.170f, 0.797f, 0.131f,
                                               0.046f, 0.3127f, 0.3290f, &to_xyz_d50);
    }
    if (!have_matrix) return false;
    skcms_SetXYZD50(profile, &to_xyz_d50);
    return skcms_MakeUsableAsDestination(profile);
}

/**
 * libjxl 0.11.1 does not run its requested output CMS stage for modular / lossless
 * codestreams (`uses_original_profile=true`): their color transform is kNone, so
 * float output remains in the original encoded transfer function. Run the same
 * source ICC to linear-working-gamut conversion explicitly for those frames.
 */
bool transform_original_profile_to_linear(std::vector<float>& rgba, size_t npx,
                                          const std::vector<uint8_t>& source_icc,
                                          uhdr_color_gamut_t cg) {
    if (npx == 0 || rgba.size() < npx * 4 || source_icc.empty()) return false;

    skcms_ICCProfile src{};
    if (!skcms_Parse(source_icc.data(), source_icc.size(), &src)) {
        ALOGE("JXL original ICC parse failed (%zu B)", source_icc.size());
        return false;
    }
    skcms_ICCProfile dst{};
    if (!make_linear_output_profile(cg, &dst)) {
        ALOGE("JXL linear destination profile failed cg=%d", (int)cg);
        return false;
    }
    if (!skcms_Transform(rgba.data(), skcms_PixelFormat_RGBA_ffff,
                         skcms_AlphaFormat_Unpremul, &src, rgba.data(),
                         skcms_PixelFormat_RGBA_ffff, skcms_AlphaFormat_Unpremul, &dst, npx)) {
        ALOGE("JXL original-profile CMS transform failed cg=%d", (int)cg);
        return false;
    }
    ALOGI("JXL original-profile CMS applied (%zu B) cg=%d", source_icc.size(), (int)cg);
    return true;
}

/** Pick a known linear destination; [out_cg] always names the resulting samples. */
void choose_linear_output(const JxlColorEncoding* src, bool have_src, bool is_pq, bool is_hlg,
                          JxlColorEncoding* out, uhdr_color_gamut_t* out_cg) {
    if (is_pq || is_hlg || (have_src && src->primaries == JXL_PRIMARIES_2100)) {
        set_linear_rgb(out, JXL_PRIMARIES_2100);
        *out_cg = UHDR_CG_BT_2100;
    } else if (have_src && src->primaries == JXL_PRIMARIES_P3) {
        set_linear_rgb(out, JXL_PRIMARIES_P3);
        *out_cg = UHDR_CG_DISPLAY_P3;
    } else if (have_src && src->primaries == JXL_PRIMARIES_SRGB) {
        set_linear_rgb(out, JXL_PRIMARIES_SRGB);
        *out_cg = UHDR_CG_BT_709;
    } else {
        // ICC-only/custom content gets a wide linear intermediate. The CMS does
        // the conversion, so tagging it BT.2020 is accurate rather than a hint.
        set_linear_rgb(out, JXL_PRIMARIES_2100);
        *out_cg = UHDR_CG_BT_2100;
    }
}

/**
 * Decode JXL → straight-alpha linear RGBA half (1.0 ≈ 203-nit SDR white).
 * [composite_alpha_on_black] is true only for JPEG/UHDR destinations.
 * @return 0 OK
 */
int decode_jxl_to_linear_f16(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                             unsigned& w, unsigned& h, uhdr_color_gamut_t* out_cg,
                             bool* out_force_hdr = nullptr, int* out_transfer_cicp = nullptr,
                             bool composite_alpha_on_black = true) {
    if (out_cg) *out_cg = UHDR_CG_BT_709;
    if (out_force_hdr) *out_force_hdr = false;
    if (out_transfer_cicp) *out_transfer_cicp = 0;
    if (!data || len == 0) return -1;

    auto runner = JxlResizableParallelRunnerMake(nullptr);
    auto dec = JxlDecoderMake(nullptr);
    if (!dec) return -2;

    const JxlCmsInterface* cms = JxlGetDefaultCms();
    if (!cms) {
        ALOGE("libjxl default CMS unavailable");
        return -3;
    }
    if (JxlDecoderSetCms(dec.get(), *cms) != JXL_DEC_SUCCESS) {
        ALOGE("libjxl default CMS setup failed");
        return -3;
    }
    (void)JxlDecoderSetUnpremultiplyAlpha(dec.get(), JXL_TRUE);

    if (JxlDecoderSubscribeEvents(dec.get(),
                                  JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) !=
        JXL_DEC_SUCCESS) {
        return -4;
    }
    JxlDecoderSetParallelRunner(dec.get(), JxlResizableParallelRunner, runner.get());

    JxlDecoderSetInput(dec.get(), data, len);
    JxlDecoderCloseInput(dec.get());

    JxlBasicInfo info{};
    JxlColorEncoding src_encoding{};
    bool have_src = false;
    bool is_pq = false;
    bool is_hlg = false;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    std::vector<uint8_t> source_icc;
    std::vector<float> pixels;
    JxlPixelFormat format = {4, JXL_TYPE_FLOAT, JXL_NATIVE_ENDIAN, 0};

    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec.get());
        if (status == JXL_DEC_ERROR) {
            ALOGE("JxlDecoderProcessInput error");
            return -5;
        }
        if (status == JXL_DEC_NEED_MORE_INPUT) {
            ALOGE("JXL truncated");
            return -6;
        }
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(dec.get(), &info) != JXL_DEC_SUCCESS) return -7;
            w = info.xsize;
            h = info.ysize;
            if (w == 0 || h == 0 || w > 16384 || h > 16384) return -8;
            JxlResizableParallelRunnerSetThreads(
                runner.get(), JxlResizableParallelRunnerSuggestThreads(w, h));
        } else if (status == JXL_DEC_COLOR_ENCODING) {
            have_src = JxlDecoderGetColorAsEncodedProfile(
                           dec.get(), JXL_COLOR_PROFILE_TARGET_ORIGINAL, &src_encoding) ==
                JXL_DEC_SUCCESS;
            if (have_src) {
                is_pq = src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_PQ;
                is_hlg = src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_HLG;
            }
            JxlColorEncoding linear_output{};
            choose_linear_output(have_src ? &src_encoding : nullptr, have_src, is_pq, is_hlg,
                                 &linear_output, &cg);

            // TARGET_DATA incorrectly remains the original profile for modular images in
            // libjxl 0.11.1, so retain TARGET_ORIGINAL for the explicit transform below.
            if (info.uses_original_profile) {
                size_t icc_size = 0;
                if (JxlDecoderGetICCProfileSize(dec.get(), JXL_COLOR_PROFILE_TARGET_ORIGINAL,
                                                &icc_size) == JXL_DEC_SUCCESS &&
                    icc_size > 0 && icc_size <= 16u * 1024u * 1024u) {
                    source_icc.resize(icc_size);
                    if (JxlDecoderGetColorAsICCProfile(
                            dec.get(), JXL_COLOR_PROFILE_TARGET_ORIGINAL, source_icc.data(),
                            source_icc.size()) != JXL_DEC_SUCCESS) {
                        source_icc.clear();
                    }
                }
            }
            if (!info.uses_original_profile) {
                if (JxlDecoderSetOutputColorProfile(dec.get(), &linear_output, nullptr, 0) !=
                    JXL_DEC_SUCCESS) {
                    ALOGE("libjxl cannot convert source profile to linear output");
                    return -9;
                }
            }
            if (out_cg) *out_cg = cg;
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t buffer_size = 0;
            if (JxlDecoderImageOutBufferSize(dec.get(), &format, &buffer_size) != JXL_DEC_SUCCESS) {
                return -10;
            }
            pixels.resize(buffer_size / sizeof(float));
            if (JxlDecoderSetImageOutBuffer(dec.get(), &format, pixels.data(), buffer_size) !=
                JXL_DEC_SUCCESS) {
                return -11;
            }
        } else if (status == JXL_DEC_FULL_IMAGE) {
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        }
    }

    if (pixels.empty() || w == 0 || h == 0) return -12;

    const size_t pixels_n = static_cast<size_t>(w) * static_cast<size_t>(h);
    const bool original_profile = info.uses_original_profile == JXL_TRUE;
    if (original_profile &&
        !transform_original_profile_to_linear(pixels, pixels_n, source_icc, cg)) {
        // Continuing would reinterpret encoded sRGB/P3/etc. as linear and produce the
        // washed-out, desaturated regression this path is intended to prevent.
        return -13;
    }

    // BasicInfo.intensity_target: libjxl default for SDR is **255** nits — NOT HDR.
    const float intensity = info.intensity_target;
    const bool intensity_hdr = std::isfinite(intensity) && intensity >= 400.f;
    const bool declared_hdr = is_pq || is_hlg || intensity_hdr;
    if (out_force_hdr) *out_force_hdr = declared_hdr;
    if (out_transfer_cicp) {
        if (is_pq) {
            *out_transfer_cicp = 16;  // SMPTE ST 2084 PQ (CICP)
        } else if (is_hlg) {
            *out_transfer_cicp = 18;  // ARIB STD-B67 HLG
        } else {
            *out_transfer_cicp = 0;
        }
    }

    out_rgba.resize(pixels_n * 4);

    // CMS output is normalized linear RGB. Preserve absolute HDR headroom using
    // the codestream intensity target; SDR's common 255-nit metadata stays 1.0.
    float scale = 1.f;
    if (declared_hdr && original_profile && is_pq) {
        // skcms PQ linear output is relative to 10,000 nits, not intensity_target.
        scale = kMaxLinear;
    } else if (declared_hdr && !(original_profile && is_hlg)) {
        float peak_nits = intensity;
        if (!std::isfinite(peak_nits) || peak_nits < 1.f) peak_nits = 1000.f;
        scale = peak_nits / kSdrWhiteNits;
        if (scale < 1.f) scale = 1.f;
        if (scale > kMaxLinear) scale = kMaxLinear;
    }

    for (size_t i = 0; i < pixels_n; i++) {
        float r = pixels[i * 4 + 0];
        float g = pixels[i * 4 + 1];
        float b = pixels[i * 4 + 2];
        float a = pixels[i * 4 + 3];

        float rl = r * scale;
        float gl = g * scale;
        float bl = b * scale;
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        if (composite_alpha_on_black && a < 1.f) {
            rl *= a;
            gl *= a;
            bl *= a;
            a = 1.f;
        }

        auto clamp_hf = [](float v) {
            if (!std::isfinite(v) || v < 0.f) return 0.f;
            if (v > kMaxLinear) return kMaxLinear;
            return v;
        };
        rl = clamp_hf(rl);
        gl = clamp_hf(gl);
        bl = clamp_hf(bl);
        out_rgba[i * 4 + 0] = float_to_half(rl);
        out_rgba[i * 4 + 1] = float_to_half(gl);
        out_rgba[i * 4 + 2] = float_to_half(bl);
        out_rgba[i * 4 + 3] = float_to_half(a);
    }

    ALOGI("JXL CMS %ux%u cg=%d pq=%d hlg=%d original=%d intensity=%.1f scale=%.3f hdr=%d "
          "composite=%d",
          w, h, (int)cg, is_pq ? 1 : 0, is_hlg ? 1 : 0, original_profile ? 1 : 0, intensity, scale,
          declared_hdr ? 1 : 0, composite_alpha_on_black ? 1 : 0);
    return 0;
}

}  // namespace

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
    bool force_hdr = false;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr);
    if (rc != 0) {
        ALOGE("JXL decode failed rc=%d", rc);
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }

    // Full page: scan peak (0). Pure SDR → baseline JPEG; PQ/HLG → Ultra HDR.
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg, 0.f, force_hdr);
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
    bool force_hdr = false;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr);
    if (rc != 0) {
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    // Thumbs: known TF only — 203 nits SDR / 1000 nits HDR; no peak scan.
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg,
                                       thumb_fixed_peak_nits(force_hdr), force_hdr);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/**
 * JXL → direct display pixels (skip UHDR JPEG).
 * outInfo int[≥6]: w, h, format, isHdr, gamut, transferCICP (16=PQ, 18=HLG, 0=other)
 * outBoost float[1]: contentHdrBoost
 * advancedColor: WCG preserve + high bit depth
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxlBytesToDirect(JNIEnv* env, jclass,
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
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool force_hdr = false;
    int transfer_cicp = 0;
    jbyteArray result = nullptr;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr,
                                      &transfer_cicp, /*composite_alpha_on_black=*/false);
    if (rc == 0) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer_cicp, jOutInfo,
                                            jOutBoost);
    } else {
        ALOGE("JXL direct decode failed rc=%d", rc);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}
