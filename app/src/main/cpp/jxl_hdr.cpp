#include "working_pixels.h"
// JPEG XL decodes to straight-alpha linear RGB in the same working space
// as the other HDR codecs. The decoder's output profile describes its samples.
#include <android/log.h>
#include <jni.h>
#include "thumbnail_mapped_input.h"
#include "still_exif_input.h"
#include "decoded_exif.h"
#include "decoded_image_info.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include <jxl/cms.h>
#include <jxl/color_encoding.h>
#include "jxl_rs.h"

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

bool jxl_profile_to_linear(std::vector<float>& rgba, size_t npx,
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
                         skcms_PixelFormat_RGBA_ffff,
                         src.data_color_space == skcms_Signature_CMYK ? skcms_AlphaFormat_Opaque : skcms_AlphaFormat_Unpremul,
                         &dst, npx)) {
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
bool jxl_hdr_profile_to_linear(std::vector<float>& rgba, size_t pixels,
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

// The generated grayscale ICC has no HDR CICP tag. Decode its declared transfer
// directly, keeping the same intensity normalization as the RGB HDR CMS.
void gray_hdr_to_linear(std::vector<float>& rgba, size_t pixels, bool pq, float intensity) {
    const float gamma = intensity >= 295.f && intensity <= 305.f ? 1.f :
        1.2f * std::pow(1.111f, std::log2(intensity / 1000.f));
    hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
        for (size_t i = begin; i < end; ++i) {
            const float code = std::clamp(rgba[i * 4], 0.f, 1.f);
            float linear;
            if (pq) {
                const double p = std::pow(code, 32.0 / 2523.0);
                linear = float(std::pow(std::max(0.0, p - 3424.0 / 4096.0) /
                    (2413.0 / 128.0 - (2392.0 / 128.0) * p), 16384.0 / 2610.0) * kMaxNits / intensity);
            } else {
                const float scene = code <= .5f ? code * code / 3.f :
                    (std::exp((code - .55991073f) / .17883277f) + .28466892f) / 12.f;
                linear = std::pow(scene, gamma);
            }
            rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = linear;
        }
    });
}

/** Pick a known linear destination. The gamut also tags the resulting samples. */
uhdr_color_gamut_t linear_gamut(const JxlRsColor& color, bool structured, bool hdr) {
    if (hdr || (structured && color.primaries == JXL_PRIMARIES_2100)) return UHDR_CG_BT_2100;
    if (structured && color.primaries == JXL_PRIMARIES_P3) return UHDR_CG_DISPLAY_P3;
    if (structured && color.primaries == JXL_PRIMARIES_SRGB) return UHDR_CG_BT_709;
    return UHDR_CG_BT_2100;
}

// A structured linear profile already in our chosen working gamut is an
// identity transform. Going through quantized ICC matrices adds color error
// and a complete CMS pass, especially for our lossless linear F16 exports.
bool is_linear_working_encoding(const JxlRsColor& src, uhdr_color_gamut_t cg) {
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
template<class Sample>
int decode_jxl_to_linear(const uint8_t* data, size_t len, std::vector<Sample>& out_rgba,
                             unsigned& w, unsigned& h, uhdr_color_gamut_t* out_cg,
                             bool* out_force_hdr = nullptr, int* out_transfer_cicp = nullptr,
                             bool composite_alpha_on_black = true, unsigned thumbnail_edge = 0,
                             bool export_bt2020 = false, std::vector<uint8_t>* out_exif = nullptr, int* out_source_depth = nullptr, DecodedImageInfo* source = nullptr) {
    const bool preserve_extended = export_pixels_preserve_scrgb();
    if (out_source_depth) *out_source_depth = 0;
    if (out_exif) out_exif->clear();
    if (out_cg) *out_cg = UHDR_CG_BT_709;
    if (out_force_hdr) *out_force_hdr = false;
    if (out_transfer_cicp) *out_transfer_cicp = 0;
    if (!data || len == 0) return -1;

    JxlRsReader decoder(data, len);
    if (!decoder) return -2;
    const auto& info = decoder.info();
    w = info.xsize;
    h = info.ysize;
    if (!w || !h || w > 16384 || h > 16384 ||
        (std::is_same_v<Sample, float> && uint64_t(w) * h > 80000000)) return -8;
    if (out_source_depth) *out_source_depth = static_cast<int>(info.bits_per_sample);

    JxlRsColor src_encoding{};
    const bool have_src = decoder.color(src_encoding, true);
    const bool is_pq = have_src && src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_PQ;
    const bool is_hlg = have_src && src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_HLG;
    if (source) {
        source->width = jint(w);
        source->height = jint(h);
        source->depth = jint(info.bits_per_sample);
        if (have_src) {
            source->primaries = src_encoding.white_point != JXL_WHITE_POINT_D65 ?
                (src_encoding.primaries == JXL_PRIMARIES_P3 && src_encoding.white_point == JXL_WHITE_POINT_DCI ? 11 : 0) :
                src_encoding.primaries == JXL_PRIMARIES_SRGB ? 1 :
                src_encoding.primaries == JXL_PRIMARIES_2100 ? 9 :
                src_encoding.primaries == JXL_PRIMARIES_P3 ? 12 : 0;
            source->transfer = src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_GAMMA ?
                0 : src_encoding.transfer_function;
        }
    }

    auto cg = linear_gamut(src_encoding, have_src, is_pq || is_hlg);
    if (out_cg) *out_cg = cg;
    if (!decoder.set_format(JxlRsFormat::f32, info.has_black)) return -9;
    JxlRsColor output_color{};
    const bool structured_output = decoder.color(output_color);
    const bool already_linear = structured_output && is_linear_working_encoding(output_color, cg);
    const auto icc = already_linear ? std::vector<uint8_t>{} : decoder.icc();
    const bool cmyk = icc.size() >= 20 && !memcmp(icc.data() + 16, "CMYK", 4);
    if (cmyk && !info.has_black) return -13;

    const size_t pixels_n = static_cast<size_t>(w) * h;
    std::vector<float> pixels(pixels_n * 4);
    std::vector<float> black(info.has_black ? pixels_n : 0);
    double duration = 0;
    if (decoder.next(pixels.data(), pixels.size() * sizeof(float), duration,
                     black.data(), black.size() * sizeof(float)) != 1) return -5;
    if (out_exif) *out_exif = JxlRsReader::exif(data, len);

    // CMYK uses an extra plane for inverse K. Keep alpha separate while the CMS sees the four inks.
    std::vector<float> alpha;
    if (cmyk) {
        if (info.has_alpha) alpha.resize(pixels_n);
        for (size_t i = 0; i < pixels_n; ++i) {
            if (!alpha.empty()) alpha[i] = pixels[i * 4 + 3];
            pixels[i * 4 + 3] = black[i];
        }
    }
    std::vector<float>().swap(black);
    const bool hdr_cms = structured_output &&
        (output_color.transfer_function == JXL_TRANSFER_FUNCTION_PQ || output_color.transfer_function == JXL_TRANSFER_FUNCTION_HLG);
    const float cms_intensity = std::clamp(
        std::isfinite(info.intensity_target) && info.intensity_target > 0.f ? info.intensity_target : 1000.f,
        is_pq ? 1.f : kSdrWhiteNits, kMaxNits);
    if (hdr_cms) {
        if (output_color.color_space == JXL_COLOR_SPACE_GRAY) {
            gray_hdr_to_linear(pixels, pixels_n, is_pq, cms_intensity);
        } else if (!jxl_hdr_profile_to_linear(pixels, pixels_n, icc, cg, 3, cms_intensity)) {
            return -13;
        }
    } else if (!already_linear && !jxl_profile_to_linear(pixels, pixels_n, icc, cg)) {
        return -13;
    }
    if (!alpha.empty()) {
        for (size_t i = 0; i < pixels_n; ++i) pixels[i * 4 + 3] = alpha[i];
    }
    std::vector<float>().swap(alpha);

    // The usual 255-nit intensity target is SDR.
    const float intensity = info.intensity_target;
    const bool intensity_hdr = std::isfinite(intensity) && intensity >= 400.f;
    const bool scrgb = info.exponent_bits_per_sample > 0 && have_src && intensity == 80.f &&
        src_encoding.color_space == JXL_COLOR_SPACE_RGB && src_encoding.white_point == JXL_WHITE_POINT_D65 &&
        src_encoding.primaries == JXL_PRIMARIES_SRGB && src_encoding.transfer_function == JXL_TRANSFER_FUNCTION_LINEAR;
    if (source) source->scrgb = scrgb;

    // Floating-point linear sRGB can carry WCG through signed RGB, just like
    // scRGB JXR. Convert before converted_sample clips negative channels. JXL's
    // own intensity_target remains authoritative; only explicitly tagged scRGB uses 80 nits.
    // For display, recover WCG from signed scRGB before the F16 pack bounds RGB.
    const bool to_bt2020 = (export_bt2020 || (scrgb && !export_pixels_capture())) &&
        info.exponent_bits_per_sample > 0 &&
        cg == UHDR_CG_BT_709 && have_src && is_linear_working_encoding(src_encoding, cg);
    if (to_bt2020) {
        cg = UHDR_CG_BT_2100;
        if (out_cg) *out_cg = cg;
    }

    const bool declared_hdr = is_pq || is_hlg || intensity_hdr || scrgb;
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
        // Restore the absolute brightness from the CMS intensity normalization.
        float peak_nits = hdr_cms ? cms_intensity : intensity;
        if (!std::isfinite(peak_nits) || peak_nits < 1.f) peak_nits = 1000.f;
        // PQ content peaks can be below SDR white; undo its CMS normalization
        // exactly without raising the decoded brightness.
        if (is_pq) scale = peak_nits / kSdrWhiteNits;
        else if constexpr (std::is_same_v<Sample, float>) scale = scrgb ? 80.f / kSdrWhiteNits : std::max(peak_nits / kSdrWhiteNits, 1.f);
        else scale = scrgb ? 80.f / kSdrWhiteNits : std::clamp(peak_nits / kSdrWhiteNits, 1.f, kMaxLinear);
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

        auto clamp_hf = [&](float v) {
            return sanitize_working<Sample>(v, preserve_extended);
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
                const double top = double(y)*h/nh, bottom = double(y+1)*h/nh;
                const unsigned sy0 = unsigned(top), sy1 = std::min(h, unsigned(std::ceil(bottom)));
                for (unsigned x = 0; x < nw; ++x) {
                    const double left = double(x)*w/nw, right = double(x+1)*w/nw;
                    const unsigned sx0 = unsigned(left), sx1 = std::min(w, unsigned(std::ceil(right)));
                    double sum[4]{};
                    for (unsigned sy = sy0; sy < sy1; ++sy) {
                        for (unsigned sx = sx0; sx < sx1; ++sx) {
                            float sample[4];
                            converted_sample(static_cast<size_t>(sy) * w + sx, sample);
                            const double weight = (std::min(bottom, double(sy+1))-std::max(top, double(sy))) *
                                (std::min(right, double(sx+1))-std::max(left, double(sx)));
                            for (unsigned c = 0; c < 3; ++c) sum[c] += sample[c]*sample[3]*weight;
                            sum[3] += sample[3]*weight;
                        }
                    }
                    const double count = (right-left)*(bottom-top);
                    const size_t offset = (static_cast<size_t>(y) * nw + x) * 4;
                    for (unsigned c = 0; c < 3; ++c) out_rgba[offset+c] = store_working<Sample>(sum[3] > 0 ? float(sum[c]/sum[3]) : 0.f);
                    out_rgba[offset+3] = store_working<Sample>(float(sum[3]/count));
                }
            }
        });
        w = nw;
        h = nh;
    } else {
        if constexpr (!std::is_same_v<Sample, float>) out_rgba.resize(pixels_n * 4);
        hdr_parallel_pixels(pixels_n, hdr_pixel_jobs(pixels_n), [&](size_t begin, size_t end, unsigned) {
            for (size_t i = begin; i < end; ++i) {
                float sample[4];
                converted_sample(i, sample);
                for (unsigned c = 0; c < 4; ++c) {
                    if constexpr (std::is_same_v<Sample, float>) pixels[i*4+c] = sample[c];
                    else out_rgba[i*4+c] = store_working<Sample>(sample[c]);
                }
            }
        });
        if constexpr (std::is_same_v<Sample, float>) out_rgba = std::move(pixels);
    }

    ALOGI("JXL CMS %ux%u cg=%d pq=%d hlg=%d original=%d intensity=%.1f scale=%.3f hdr=%d "
          "composite=%d",
          w, h, (int)cg, is_pq ? 1 : 0, is_hlg ? 1 : 0, decoder.outputs_original_profile() ? 1 : 0, intensity, scale,
          declared_hdr ? 1 : 0, composite_alpha_on_black ? 1 : 0);
    return 0;
}

}  // namespace

#if defined(EHVIEWER_STILL_ENCODERS)
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
    int rc = decode_jxl_to_linear(reinterpret_cast<const uint8_t*>(bytes),
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
    int rc = decode_jxl_to_linear(reinterpret_cast<const uint8_t*>(bytes),
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
    int rc = decode_jxl_to_linear(reinterpret_cast<const uint8_t*>(bytes),
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
    int rc = decode_jxl_to_linear(input.data,
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
#endif  // EHVIEWER_STILL_ENCODERS

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
                                                                jfloatArray jOutBoost, jboolean exportBt2020, jobjectArray jOutExif) {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return nullptr;

    std::vector<uint8_t> exif;
    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool force_hdr = false;
    int transfer_cicp = 0;
    int source_depth = 0;
    DecodedImageInfo source;
    jbyteArray result = nullptr;
    // Resize linear float samples in parallel before allocating display F16.
    // Exports retain the existing full-resolution F16 + explicit resize path.
    int rc = decode_jxl_to_linear(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr,
                                      &transfer_cicp, /*composite_alpha_on_black=*/false,
                                      !export_pixels_capture() && maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u,
                                      exportBt2020 == JNI_TRUE && export_pixels_capture(), jOutExif ? &exif : nullptr,
                                      &source_depth, &source);
    if (rc == 0) {
        publish_decoded_exif(env, jOutExif, exif, true);
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer_cicp, jOutInfo,
                                            jOutBoost);
        if (result) source.publish(env, jOutInfo);
        if (result && env->GetArrayLength(jOutInfo) >= 7) {
            const jint depth = source_depth;
            env->SetIntArrayRegion(jOutInfo, 6, 1, &depth);
        }
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
                                                                jfloatArray jOutBoost, jboolean exportBt2020, jobjectArray jOutExif) try {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return nullptr;

    if (export_pixels_capture() && maxEdge <= 0) {
        std::vector<float> rgba;
        std::vector<uint8_t> exif;
        unsigned w = 0, h = 0;
        uhdr_color_gamut_t cg = UHDR_CG_BT_709;
        bool hdr = false;
        int transfer = 0, depth = 0;
        if (decode_jxl_to_linear(bytes, size_t(len), rgba, w, h, &cg, &hdr, &transfer,
            false, 0, exportBt2020 == JNI_TRUE, jOutExif ? &exif : nullptr, &depth)) return nullptr;
        publish_decoded_exif(env, jOutExif, exif, true);
        auto result = retain_export_f32(env, rgba, w, h, cg, hdr, transfer, jOutInfo, jOutBoost);
        if (result && env->GetArrayLength(jOutInfo) >= 7) env->SetIntArrayRegion(jOutInfo, 6, 1, &depth);
        return result;
    }

    std::vector<uint8_t> exif;
    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool force_hdr = false;
    int transfer_cicp = 0;
    int source_depth = 0;
    DecodedImageInfo source;
    jbyteArray result = nullptr;
    // Resize linear float samples in parallel before allocating display F16.
    // Exports retain the existing full-resolution F16 + explicit resize path.
    int rc = decode_jxl_to_linear(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len), rgba, w, h, &cg, &force_hdr,
                                      &transfer_cicp, /*composite_alpha_on_black=*/false,
                                      !export_pixels_capture() && maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u,
                                      exportBt2020 == JNI_TRUE && export_pixels_capture(), jOutExif ? &exif : nullptr,
                                      &source_depth, &source);
    if (rc == 0) {
        publish_decoded_exif(env, jOutExif, exif, true);
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer_cicp, jOutInfo,
                                            jOutBoost);
        if (result) source.publish(env, jOutInfo);
        if (result && env->GetArrayLength(jOutInfo) >= 7) {
            const jint depth = source_depth;
            env->SetIntArrayRegion(jOutInfo, 6, 1, &depth);
        }
    } else {
        ALOGE("JXL direct decode failed rc=%d", rc);
    }
    return result;
} catch (...) {
    return nullptr;
}

static jbyteArray jxl_exif_bytes(JNIEnv* env, const std::vector<uint8_t>& exif) {
    if (exif.empty()) return nullptr;
    const auto size = static_cast<jsize>(exif.size());
    auto result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(exif.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractJxlExif(JNIEnv* env, jclass, jobject input) try {
    if (!input) return nullptr;
    const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    const jlong size = env->GetDirectBufferCapacity(input);
    if (!data || size <= 0) return nullptr;
    return jxl_exif_bytes(env, JxlRsReader::exif(data, static_cast<size_t>(size)));
} catch (...) { return nullptr; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractJxlExifInput(JNIEnv* env, jclass, jobject input) try {
    if (!input) return nullptr;
    StillExifInput reader(env, input);
    std::vector<uint8_t> chunk;
    struct Input { StillExifInput& reader; std::vector<uint8_t>& chunk; } source{reader, chunk};
    return jxl_exif_bytes(env, JxlRsReader::exif(&source, [](void* context, uint64_t offset, uint8_t* bytes, size_t length) -> size_t {
        auto& input = *static_cast<Input*>(context);
        if (!input.reader.read(offset, length, input.chunk) || input.chunk.size() > length) return 0;
        if (!input.chunk.empty()) std::memcpy(bytes, input.chunk.data(), input.chunk.size());
        return input.chunk.size();
    }));
} catch (...) { return nullptr; }
