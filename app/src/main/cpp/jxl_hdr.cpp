/*
 * JPEG XL → one linear RGBA_F16 working contract via libjxl's CMS.
 *
 * libjxl's CMS owns XYB and HDR transfer conversion. Original-profile/modular
 * frames retain source samples: use the HDR CMS explicitly, skcms for SDR ICC,
 * or an identity path for a matching linear profile. Never apply a second EOTF.
 *
 * JNI: com.hippo.ehviewer.jni.HdrConvertKt.convertJxlBytesToUltraHdr
 */
#include <android/log.h>
#include <jni.h>
#include "thumbnail_mapped_input.h"

#include <algorithm>
#include <atomic>
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
#include "hdr_parallel.h"
#include "reader_pixels.h"
#include "skcms.h"
#include "ultrahdr_api.h"
#include "ultrahdr/icc.h"

#ifdef ALOGE
#undef ALOGE
#endif
#ifdef ALOGI
#undef ALOGI
#endif
#define LOG_TAG "JxlHdr"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

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

/**
 * libjxl's HDR ICC may contain an SDR display fallback. Its CMS recognizes CICP
 * and applies PQ/HLG without that fallback, unlike a plain skcms ICC transform.
 * Process small RGB batches in place so this needs no second complete frame.
 */
bool transform_original_hdr_to_linear(std::vector<float>& rgba, size_t pixels,
                                      const std::vector<uint8_t>& source_icc,
                                      uhdr_color_gamut_t cg, size_t channels, float intensity) {
    auto destination = ultrahdr::IccHelper::writeIccProfile(UHDR_CT_LINEAR, cg);
    const auto* cms = JxlGetDefaultCms();
    if (!destination || destination->getLength() <= ultrahdr::kICCIdentifierSize || !cms || source_icc.empty()) return false;
    JxlColorProfile input{}, output{};
    input.icc = {source_icc.data(), source_icc.size()};
    input.num_channels = channels;
    output.icc = {static_cast<const uint8_t*>(destination->getData()) + ultrahdr::kICCIdentifierSize,
                  destination->getLength() - ultrahdr::kICCIdentifierSize};
    output.num_channels = 3;
    constexpr size_t batch = 4096;
    const unsigned jobs = hdr_pixel_jobs(pixels);
    void* transform = cms->init(cms->init_data, jobs, batch, &input, &output, intensity);
    if (!transform) return false;
    std::unique_ptr<void, jpegxl_cms_destroy_func> owner(transform, cms->destroy);
    std::atomic<bool> success{true};
    hdr_parallel_pixels(pixels, jobs, [&](size_t begin, size_t end, unsigned job) {
        float* src = cms->get_src_buf(transform, job);
        float* dst = cms->get_dst_buf(transform, job);
        for (size_t first = begin; first < end; first += batch) {
            const size_t count = std::min(batch, end - first);
            for (size_t i = 0; i < count; ++i) {
                for (size_t c = 0; c < channels; ++c) src[i * channels + c] = rgba[(first + i) * 4 + c];
            }
            if (!cms->run(transform, job, src, dst, count)) { success = false; break; }
            for (size_t i = 0; i < count; ++i) {
                for (size_t c = 0; c < 3; ++c) rgba[(first + i) * 4 + c] = dst[i * 3 + c];
            }
        }
    });
    return success;
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

// A structured linear profile already in our chosen working gamut is an
// identity transform. Going through quantized ICC matrices adds color error
// and a complete CMS pass, especially for our lossless linear F16 exports.
bool is_linear_working_encoding(const JxlColorEncoding& src, uhdr_color_gamut_t cg) {
    if (src.color_space != JXL_COLOR_SPACE_RGB || src.white_point != JXL_WHITE_POINT_D65 ||
        src.transfer_function != JXL_TRANSFER_FUNCTION_LINEAR) return false;
    return (src.primaries == JXL_PRIMARIES_SRGB && cg == UHDR_CG_BT_709) ||
        (src.primaries == JXL_PRIMARIES_P3 && cg == UHDR_CG_DISPLAY_P3) ||
        (src.primaries == JXL_PRIMARIES_2100 && cg == UHDR_CG_BT_2100);
}

/**
 * Decode JXL → straight-alpha linear RGBA half (1.0 ≈ 203-nit SDR white).
 * [composite_alpha_on_black] is true only for JPEG/UHDR destinations.
 * @return 0 OK
 */
int decode_jxl_to_linear_f16(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                             unsigned& w, unsigned& h, uhdr_color_gamut_t* out_cg,
                             bool* out_force_hdr = nullptr, int* out_transfer_cicp = nullptr,
                             bool composite_alpha_on_black = true, unsigned thumbnail_edge = 0,
                             bool export_bt2020 = false) {
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

    const int events = JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE |
        (thumbnail_edge > 0 ? JXL_DEC_FRAME_PROGRESSION : 0);
    if (JxlDecoderSubscribeEvents(dec.get(), events) !=
        JXL_DEC_SUCCESS) {
        return -4;
    }
    if (thumbnail_edge > 0 &&
        JxlDecoderSetProgressiveDetail(dec.get(), kLastPasses) != JXL_DEC_SUCCESS) return -4;
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
                runner.get(), std::min<size_t>(JxlResizableParallelRunnerSuggestThreads(w, h), hdr_pixel_jobs(static_cast<size_t>(w) * h)));
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
            if (info.uses_original_profile && !(have_src && is_linear_working_encoding(src_encoding, cg))) {
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
        } else if (status == JXL_DEC_FRAME_PROGRESSION && thumbnail_edge > 0) {
            const size_t ratio = JxlDecoderGetIntendedDownsamplingRatio(dec.get());
            // Finish the first progressive rendition with enough source detail.
            // Files without suitable steps simply continue to the complete frame.
            if (ratio > 0 && (std::max(w, h) + ratio - 1) / ratio >= thumbnail_edge &&
                JxlDecoderFlushImage(dec.get()) == JXL_DEC_SUCCESS) break;
        } else if (status == JXL_DEC_FULL_IMAGE) {
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        }
    }

    if (pixels.empty() || w == 0 || h == 0) return -12;

    const size_t pixels_n = static_cast<size_t>(w) * static_cast<size_t>(h);
    const bool original_profile = info.uses_original_profile == JXL_TRUE;
    const bool hdr_cms = original_profile && (is_pq || is_hlg);
    const float cms_intensity = std::clamp(std::isfinite(info.intensity_target) && info.intensity_target > 0.f ? info.intensity_target : 1000.f, kSdrWhiteNits, kMaxNits);
    if (hdr_cms) {
        const size_t channels = have_src && src_encoding.color_space == JXL_COLOR_SPACE_GRAY ? 1 : 3;
        if (!transform_original_hdr_to_linear(pixels, pixels_n, source_icc, cg, channels, cms_intensity)) return -13;
    } else if (original_profile && !(have_src && is_linear_working_encoding(src_encoding, cg)) &&
               !transform_original_profile_to_linear(pixels, pixels_n, source_icc, cg)) {
        return -13;
    }

    // Floating-point linear sRGB can carry WCG through signed RGB, just like
    // scRGB JXR. Convert before converted_sample clips negative channels. JXL's
    // own intensity_target remains authoritative; do not apply JXR's 80-nit scale.
    const bool to_bt2020 = export_bt2020 && info.exponent_bits_per_sample > 0 &&
        cg == UHDR_CG_BT_709 && have_src && is_linear_working_encoding(src_encoding, cg);
    if (to_bt2020) {
        cg = UHDR_CG_BT_2100;
        if (out_cg) *out_cg = cg;
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

    // CMS output is normalized linear RGB. Preserve absolute HDR headroom using
    // the codestream intensity target; SDR's common 255-nit metadata stays 1.0.
    float scale = 1.f;
    if (declared_hdr) {
        // Both the decoder CMS and the explicit original-profile HDR CMS return
        // linear values relative to intensity_target, not 203-nit SDR white.
        float peak_nits = hdr_cms ? cms_intensity : intensity;
        if (!std::isfinite(peak_nits) || peak_nits < 1.f) peak_nits = 1000.f;
        scale = std::clamp(peak_nits / kSdrWhiteNits, 1.f, kMaxLinear);
    }

    const auto converted_sample = [&](size_t i, float* sample) {
        float r = pixels[i * 4 + 0];
        float g = pixels[i * 4 + 1];
        float b = pixels[i * 4 + 2];
        float a = pixels[i * 4 + 3];

        float rl = r * scale;
        float gl = g * scale;
        float bl = b * scale;
        if (to_bt2020) {
            if (!std::isfinite(rl)) rl = 0.f;
            if (!std::isfinite(gl)) gl = 0.f;
            if (!std::isfinite(bl)) bl = 0.f;
            linear_bt709_to_bt2020(rl, gl, bl);
        }
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
        sample[0] = rl;
        sample[1] = gl;
        sample[2] = bl;
        sample[3] = a;
    };
    if (thumbnail_edge > 0 && std::max(w, h) > thumbnail_edge) {
        // Box-filter CMS-converted linear floats straight into the thumbnail.
        // Avoid a full-resolution F16 allocation, conversion and second read.
        // Alpha/compositing runs before averaging, as in the full-frame path.
        const unsigned edge = std::max(w, h);
        const unsigned nw = std::max(1u, static_cast<unsigned>(static_cast<uint64_t>(w) * thumbnail_edge / edge));
        const unsigned nh = std::max(1u, static_cast<unsigned>(static_cast<uint64_t>(h) * thumbnail_edge / edge));
        out_rgba.resize(static_cast<size_t>(nw) * nh * 4);
        hdr_parallel_pixels(nh, hdr_pixel_jobs(pixels_n), [&](size_t begin, size_t end, unsigned) {
            for (unsigned y = begin; y < end; ++y) {
                const unsigned sy0 = y * h / nh, sy1 = (y + 1) * h / nh;
                for (unsigned x = 0; x < nw; ++x) {
                    const unsigned sx0 = x * w / nw, sx1 = (x + 1) * w / nw;
                    float sum[4]{};
                    for (unsigned sy = sy0; sy < sy1; ++sy) {
                        for (unsigned sx = sx0; sx < sx1; ++sx) {
                            float sample[4];
                            converted_sample(static_cast<size_t>(sy) * w + sx, sample);
                            for (unsigned c = 0; c < 4; ++c) sum[c] += sample[c];
                        }
                    }
                    const float count = static_cast<float>((sy1 - sy0) * (sx1 - sx0));
                    const size_t offset = (static_cast<size_t>(y) * nw + x) * 4;
                    for (unsigned c = 0; c < 4; ++c) out_rgba[offset + c] = float_to_half(sum[c] / count);
                }
            }
        });
        w = nw;
        h = nh;
    } else {
        out_rgba.resize(pixels_n * 4);
        hdr_parallel_pixels(pixels_n, hdr_pixel_jobs(pixels_n), [&](size_t begin, size_t end, unsigned) {
            for (size_t i = begin; i < end; ++i) {
                float sample[4];
                converted_sample(i, sample);
                for (unsigned c = 0; c < 4; ++c) out_rgba[i * 4 + c] = float_to_half(sample[c]);
            }
        });
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

/** Full-resolution convert with borrowed mapped/direct input. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBufferToUltraHdr(JNIEnv* env, jclass,
                                                                   jobject jInput,
                                                                   jstring jOutput) try {
    if (!jInput || !jOutput) return -10;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return -12;

    ThumbnailOutputPath outputPath(env, jOutput);
    const char* out_path = outputPath.get();
    if (!out_path) {
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
        return -20 + rc;
    }

    // Full page: scan peak (0). Pure SDR → baseline JPEG; PQ/HLG → Ultra HDR.
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg, 0.f, force_hdr);
    return rc;
} catch (...) {
    return -30;
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
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr, nullptr,
                                      true, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0);
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

/** File-backed thumbnail input avoids Java arrays and JNI compressed copies. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlFdToUltraHdrMaxEdge(
        JNIEnv* env, jclass, jint fd, jstring jOutput, jint maxEdge) try {
    if (!jOutput || maxEdge <= 0) return -10;
    ThumbnailMappedInput input(fd);
    if (!input.data) return -101;  // Non-regular/unmappable provider: stage to a file.
    ThumbnailOutputPath outputPath(env, jOutput);
    const char* out_path = outputPath.get();
    if (!out_path) {
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool force_hdr = false;
    int rc = decode_jxl_to_linear_f16(input.data,
                                      input.size, rgba, w, h, &cg, &force_hdr, nullptr,
                                      true, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0);
    input.reset();
    if (rc != 0) {
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    // Thumbs: known TF only — 203 nits SDR / 1000 nits HDR; no peak scan.
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg,
                                       thumb_fixed_peak_nits(force_hdr), force_hdr);
    return rc;
} catch (...) {
    // In particular, do not let std::bad_alloc terminate the process at JNI.
    return -30;
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
                                                                jfloatArray jOutBoost, jboolean exportBt2020) {
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
                                      &transfer_cicp, /*composite_alpha_on_black=*/false, 0,
                                      exportBt2020 == JNI_TRUE && export_pixels_capture());
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

/** Direct/mapped compressed input; the caller owns its lifetime. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxlBufferToDirect(JNIEnv* env, jclass,
                                                                jobject jInput, jint maxEdge,
                                                                jboolean advancedColor,
                                                                jintArray jOutInfo,
                                                                jfloatArray jOutBoost, jboolean exportBt2020) try {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return nullptr;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool force_hdr = false;
    int transfer_cicp = 0;
    jbyteArray result = nullptr;
    int rc = decode_jxl_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr,
                                      &transfer_cicp, /*composite_alpha_on_black=*/false, 0,
                                      exportBt2020 == JNI_TRUE && export_pixels_capture());
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
    return result;
} catch (...) {
    return nullptr;
}

// Extract only the EXIF box (including Brotli-compressed boxes), not image pixels.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractJxlExif(JNIEnv* env, jclass, jobject input) try {
    if (!input) return nullptr;
    const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    const jlong size = env->GetDirectBufferCapacity(input);
    if (!data || size <= 0) return nullptr;
    auto decoder = JxlDecoderMake(nullptr);
    if (!decoder || JxlDecoderSubscribeEvents(decoder.get(), JXL_DEC_BOX | JXL_DEC_BOX_COMPLETE) != JXL_DEC_SUCCESS ||
        JxlDecoderSetDecompressBoxes(decoder.get(), JXL_TRUE) != JXL_DEC_SUCCESS) return nullptr;
    JxlDecoderSetInput(decoder.get(), data, static_cast<size_t>(size));
    JxlDecoderCloseInput(decoder.get());
    std::vector<uint8_t> box;
    bool collecting = false;
    for (;;) {
        const auto status = JxlDecoderProcessInput(decoder.get());
        if (status == JXL_DEC_BOX) {
            JxlBoxType type;
            if (JxlDecoderGetBoxType(decoder.get(), type, JXL_TRUE) != JXL_DEC_SUCCESS) return nullptr;
            if (memcmp(type, "Exif", 4) == 0) {
                box.resize(1024 * 1024);
                if (JxlDecoderSetBoxBuffer(decoder.get(), box.data(), box.size()) != JXL_DEC_SUCCESS) return nullptr;
                collecting = true;
            }
        } else if (status == JXL_DEC_BOX_COMPLETE && collecting) {
            const size_t count = box.size() - JxlDecoderReleaseBoxBuffer(decoder.get());
            if (!count) return nullptr;
            auto result = env->NewByteArray(static_cast<jsize>(count));
            if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(box.data()));
            return result;
        } else if (status == JXL_DEC_SUCCESS || status == JXL_DEC_ERROR ||
                   status == JXL_DEC_NEED_MORE_INPUT || status == JXL_DEC_BOX_NEED_MORE_OUTPUT) {
            return nullptr;
        }
    }
} catch (...) { return nullptr; }
