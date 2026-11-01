#include "hdr_encode.h"
#include "working_pixels.h"
#include "hdr_parallel.h"
#include "thumbnail_mapped_input.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>
#include <jxl/encode_cxx.h>
#include <avif/avif.h>
#include <jxl/thread_parallel_runner_cxx.h>

namespace {
struct JxlFileCloser {
    void operator()(FILE* file) const { fclose(file); }
};
}

static int write_jxl_output(JxlEncoder* encoder, const char* path) {
    std::unique_ptr<FILE, JxlFileCloser> file(fopen(path, "wb"));
    if (!file) return -65;
    // Stream encoded bytes directly to disk; no second complete compressed file.
    std::array<uint8_t, 65536> output{};
    for (;;) {
        uint8_t* next = output.data();
        size_t available = output.size();
        const auto status = JxlEncoderProcessOutput(encoder, &next, &available);
        const size_t count = output.size() - available;
        if (count && fwrite(output.data(), 1, count, file.get()) != count) return -66;
        if (status == JXL_ENC_SUCCESS) break;
        if (status != JXL_ENC_NEED_MORE_OUTPUT) return -67;
    }
    return fflush(file.get()) == 0 ? 0 : -66;
}

static int encode_jxl_pixels(unsigned w, unsigned h, const void* rgba, uhdr_color_gamut_t cg,
                             const char* path, StillEncodeQuality quality, JxlDataType type,
                             JxlTransferFunction transfer, float intensity, const StillSourceColor* source = nullptr) {
    const size_t pixels = static_cast<size_t>(w) * h;
    if (!rgba || !path || !pixels) return -60;
    const unsigned input_channels = source ? source->channels : 4;
    const bool alpha = input_channels == 2 || input_channels == 4;
    const bool gray = input_channels <= 2 || (source && source->icc_size >= 20 && !std::memcmp(source->icc + 16, "GRAY", 4));
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, hdr_pixel_jobs(pixels));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner || JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS) return -61;
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = w;
    info.ysize = h;
    info.bits_per_sample = source ? source->bits : type == JXL_TYPE_UINT8 ? 8 : type == JXL_TYPE_FLOAT ? 32 : 16;
    if (source) info.orientation = static_cast<JxlOrientation>(source->orientation);
    info.exponent_bits_per_sample = source ? source->exponent_bits : type == JXL_TYPE_FLOAT ? 8 : type == JXL_TYPE_FLOAT16 ? 5 : 0;
    info.num_color_channels = gray ? 1 : 3;
    info.num_extra_channels = alpha ? 1 : 0;
    info.alpha_bits = alpha ? info.bits_per_sample : 0;
    info.alpha_exponent_bits = alpha ? info.exponent_bits_per_sample : 0;
    info.alpha_premultiplied = JXL_FALSE;
    const float distance = quality.jxl_distance();
    const bool compression = distance > 0.f;
    info.uses_original_profile = compression ? JXL_FALSE : JXL_TRUE;
    info.intensity_target = intensity;
    JxlColorEncoding color{};
    color.color_space = gray ? JXL_COLOR_SPACE_GRAY : JXL_COLOR_SPACE_RGB;
    color.white_point = JXL_WHITE_POINT_D65;
    color.primaries = cg == UHDR_CG_BT_2100 ? JXL_PRIMARIES_2100 : cg == UHDR_CG_DISPLAY_P3 ? JXL_PRIMARIES_P3 : JXL_PRIMARIES_SRGB;
    color.transfer_function = transfer;
    if (source) {
        if (source->primaries == 12 || source->primaries == 11) {
            color.primaries = JXL_PRIMARIES_P3;
            color.white_point = source->primaries == 11 ? JXL_WHITE_POINT_DCI : JXL_WHITE_POINT_D65;
        } else if (source->primaries == 1 || source->primaries == 9) {
            color.primaries = static_cast<JxlPrimaries>(source->primaries);
        } else if (source->primaries == 0 || source->primaries == 2) {
            // Unspecified CICP is rendered as sRGB; JXL has no "unknown" encoding.
            color.primaries = JXL_PRIMARIES_SRGB;
        } else if (!source->icc_size) {
            float xy[8];avifColorPrimariesGetValues(static_cast<avifColorPrimaries>(source->primaries), xy);
            color.primaries = JXL_PRIMARIES_CUSTOM;color.white_point = JXL_WHITE_POINT_CUSTOM;
            color.primaries_red_xy[0]=xy[0];color.primaries_red_xy[1]=xy[1];
            color.primaries_green_xy[0]=xy[2];color.primaries_green_xy[1]=xy[3];
            color.primaries_blue_xy[0]=xy[4];color.primaries_blue_xy[1]=xy[5];
            color.white_point_xy[0]=xy[6];color.white_point_xy[1]=xy[7];
        }
        color.transfer_function = static_cast<JxlTransferFunction>(source->transfer);
        if (source->transfer == 4 || source->transfer == 5) {
            color.transfer_function = JXL_TRANSFER_FUNCTION_GAMMA;
            color.gamma = source->transfer == 4 ? 1.0/2.2 : 1.0/2.8;
        } else if (source->transfer == 6 || source->transfer == 14 || source->transfer == 15) {
            color.transfer_function = JXL_TRANSFER_FUNCTION_709;
        } else if (source->transfer == 0 || source->transfer == 2) {
            color.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
        }
    }
    color.rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
    if (JxlEncoderSetBasicInfo(encoder.get(), &info) != JXL_ENC_SUCCESS ||
        (source && source->icc_size ?
            JxlEncoderSetICCProfile(encoder.get(), source->icc, source->icc_size) :
            JxlEncoderSetColorEncoding(encoder.get(), &color)) != JXL_ENC_SUCCESS) return -62;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    // Lossless keeps original-profile / fast-decode coding. High/mid/low use
    // perceptual XYB at distances 0.5/1.0/1.5, preserving primaries, transfer
    // and HDR intensity target. Alpha remains lossless in every preset.
    if (!frame || JxlEncoderSetFrameLossless(frame, compression ? JXL_FALSE : JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderSetFrameDistance(frame, distance) != JXL_ENC_SUCCESS ||
        (alpha && JxlEncoderSetExtraChannelDistance(frame, 0, 0.f) != JXL_ENC_SUCCESS) ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, compression ? 5 : 3) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED, compression ? 0 : 2) != JXL_ENC_SUCCESS) return -63;
    const unsigned output_channels = (gray ? 1u : 3u) + (alpha ? 1u : 0u);
    const JxlPixelFormat format{output_channels, type, JXL_NATIVE_ENDIAN, 0};
    std::vector<uint8_t> gray_alpha;
    const size_t sample_bytes = type == JXL_TYPE_UINT8 ? 1 : type == JXL_TYPE_FLOAT ? 4 : 2;
    const void* input = rgba;
    if (gray && input_channels != output_channels) {
        gray_alpha.resize(pixels * sample_bytes * output_channels);
        for (size_t i=0;i<pixels;++i) {
            std::memcpy(gray_alpha.data()+i*sample_bytes*output_channels, static_cast<const uint8_t*>(rgba)+i*sample_bytes*input_channels, sample_bytes);
            if (alpha) std::memcpy(gray_alpha.data()+(i*output_channels+1)*sample_bytes,
                static_cast<const uint8_t*>(rgba)+(i*input_channels+input_channels-1)*sample_bytes, sample_bytes);
        }
        input = gray_alpha.data();
    }
    if (quality.exif_size) {
        if (JxlEncoderUseContainer(encoder.get(), JXL_TRUE) != JXL_ENC_SUCCESS ||
            JxlEncoderUseBoxes(encoder.get()) != JXL_ENC_SUCCESS) return -62;
        std::vector<uint8_t> box(4 + quality.exif_size, 0);
        std::copy(quality.exif, quality.exif + quality.exif_size, box.begin() + 4);
        if (JxlEncoderAddBox(encoder.get(), "Exif", box.data(), box.size(), JXL_FALSE) != JXL_ENC_SUCCESS) return -62;
        JxlEncoderCloseBoxes(encoder.get());
    }
    if (JxlEncoderAddImageFrame(frame, &format, input, pixels * sample_bytes * output_channels) != JXL_ENC_SUCCESS) return -64;
    JxlEncoderCloseInput(encoder.get());
    return write_jxl_output(encoder.get(), path);
}

// Match JXRConverter: retain signed/extended RGB and straight alpha. Working
// linear pixels use 203 nits per unit; scRGB uses 80 nits and BT.709 primaries.
// Mutate the owned export buffer, avoiding another full-resolution frame.
template<class Sample>
static int encode_jxl_scrgb(unsigned w, unsigned h, Sample* rgba, uhdr_color_gamut_t cg,
                             const char* path, StillEncodeQuality quality) {
    const size_t pixels = size_t(w) * h;
    hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
        for (size_t i = begin; i < end; ++i) {
            float r = read_working(rgba[4*i]), g = read_working(rgba[4*i+1]), b = read_working(rgba[4*i+2]);
            if (cg == UHDR_CG_BT_2100) linear_bt2020_to_bt709(r, g, b);
            else if (cg == UHDR_CG_DISPLAY_P3) linear_p3_to_bt709(r, g, b);
            const float values[]{r, g, b};
            for (unsigned c = 0; c < 3; ++c) {
                float v = values[c] * (kSdrWhiteNits / 80.f);
                if (!std::isfinite(v)) v = 0.f;
                if constexpr (!std::is_same_v<Sample, float>) v = std::clamp(v, -65504.f, 65504.f);
                rgba[4*i+c] = store_working<Sample>(v);
            }
        }
    });
    StillSourceColor color;
    color.bits = sizeof(Sample) == 4 ? 32 : 16;
    color.exponent_bits = sizeof(Sample) == 4 ? 8 : 5;
    color.primaries = 1;
    color.transfer = 8;
    color.intensity_target = 80.f;
    return encode_jxl_source(w, h, rgba, true, color, path, quality);
}

// The caller owns straight-alpha linear half samples. Normalize HDR to its
// declared luminance for linear JXL; PQ retains the absolute 10000-nit reference.
int encode_jxl_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t cg,
                   bool hdr, const char* path, StillEncodeQuality quality, bool pq) {
    const size_t pixels = static_cast<size_t>(w) * h;
    if (!rgba || !path || !pixels) return -60;
    if (hdr && !pq && quality.jxl_scrgb) return encode_jxl_scrgb(w, h, rgba, cg, path, quality);
    float peak = pq ? kMaxLinear : 1.f;
    if (hdr && !pq) {
        std::array<float, 8> peaks{};
        const unsigned jobs = hdr_pixel_jobs(pixels);
        hdr_parallel_pixels(pixels, jobs, [&](size_t begin, size_t end, unsigned job) {
            float p = 1.f;
            for (size_t i = begin; i < end; ++i) {
                for (unsigned c = 0; c < 3; ++c) {
                    const float v = half_to_float(rgba[4 * i + c]);
                    if (std::isfinite(v)) p = std::max(p, v);
                }
            }
            peaks[job] = p;
        });
        peak = std::clamp(*std::max_element(peaks.begin(), peaks.end()), 400.f / kSdrWhiteNits, kMaxLinear);
    }
    const float intensity = pq ? kMaxNits : hdr ? peak * kSdrWhiteNits : kSdrWhiteNits;
    // One table also bounds invalid input and avoids per-channel floating-point
    // conversion in the image loop. Alpha stays straight; PQ converts its
    // storage to the same uint16 precision as the color channels.
    std::vector<uint16_t> table(65536);
    for (size_t code = 0; code < table.size(); ++code) {
        float v = half_to_float(static_cast<uint16_t>(code));
        if (!std::isfinite(v) || v < 0.f) v = 0.f;
        table[code] = pq ? static_cast<uint16_t>(std::lround(linear_to_pq_code(v) * 65535.f)) :
                           float_to_half(std::min(v, peak) / peak);
    }
    hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
        for (size_t i = begin; i < end; ++i) {
            for (unsigned c = 0; c < 3; ++c) rgba[4 * i + c] = table[rgba[4 * i + c]];
            if (pq) {
                float alpha = half_to_float(rgba[4 * i + 3]);
                if (!std::isfinite(alpha)) alpha = 0.f;
                rgba[4 * i + 3] = static_cast<uint16_t>(std::lround(std::clamp(alpha, 0.f, 1.f) * 65535.f));
            }
        }
    });
    return encode_jxl_pixels(w, h, rgba, cg, path, quality,
                             pq ? JXL_TYPE_UINT16 : JXL_TYPE_FLOAT16,
                             pq ? JXL_TRANSFER_FUNCTION_PQ : JXL_TRANSFER_FUNCTION_LINEAR, intensity);
}

int encode_jxl_u8(unsigned w, unsigned h, const uint8_t* rgba, uhdr_color_gamut_t cg,
                   const char* path, StillEncodeQuality quality) {
    return encode_jxl_pixels(w, h, rgba, cg, path, quality, JXL_TYPE_UINT8,
                             JXL_TRANSFER_FUNCTION_SRGB, kSdrWhiteNits);
}

// JPEG transcoding preserves compressed coefficients and reconstruction metadata.
// No RGB conversion or distance setting is involved.
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_transcodeJpegToJxl(
        JNIEnv* env, jclass, jbyteArray input, jstring output, jboolean keepExif) {
    if (!input || !output) return -60;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return -60;
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner ||
        JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS ||
        JxlEncoderStoreJPEGMetadata(encoder.get(), keepExif) != JXL_ENC_SUCCESS) return -61;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    if (!frame || JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_JPEG_KEEP_EXIF, keepExif == JNI_TRUE ? 1 : 0) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, 5) != JXL_ENC_SUCCESS) return -63;
    auto* jpeg = env->GetByteArrayElements(input, nullptr);
    if (!jpeg) return -60;
    // AddJPEGFrame copies the JPEG internally. Release the JNI input before
    // encoding output, avoiding a pinned Java array during compression.
    const auto added = JxlEncoderAddJPEGFrame(frame, reinterpret_cast<uint8_t*>(jpeg), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(input, jpeg, JNI_ABORT);
    if (added != JXL_ENC_SUCCESS) return -68;
    const char* path = env->GetStringUTFChars(output, nullptr);
    if (!path) return -65;
    JxlEncoderCloseInput(encoder.get());
    const int result = write_jxl_output(encoder.get(), path);
    env->ReleaseStringUTFChars(output, path);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_transcodeJpegBufferToJxl(
        JNIEnv* env, jclass, jobject input, jstring output, jboolean keepExif) try {
    if (!input || !output) return -60;
    const jlong length = env->GetDirectBufferCapacity(input);
    if (length <= 0) return -60;
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner ||
        JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS ||
        JxlEncoderStoreJPEGMetadata(encoder.get(), keepExif) != JXL_ENC_SUCCESS) return -61;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    if (!frame || JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_JPEG_KEEP_EXIF, keepExif == JNI_TRUE ? 1 : 0) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, 5) != JXL_ENC_SUCCESS) return -63;
    const auto* jpeg = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    if (!jpeg) return -60;
    // AddJPEGFrame owns the required reconstruction data; input is borrowed.
    const auto added = JxlEncoderAddJPEGFrame(frame, jpeg, static_cast<size_t>(length));
    if (added != JXL_ENC_SUCCESS) return -68;
    ThumbnailOutputPath outputPath(env, output);
    const char* path = outputPath.get();
    if (!path) return -65;
    JxlEncoderCloseInput(encoder.get());
    const int result = write_jxl_output(encoder.get(), path);
    return result;
} catch (...) {
    return -69;
}

int encode_jxl_source(unsigned w, unsigned h, const void* rgba, bool sixteen,
                      const StillSourceColor& color, const char* path, StillEncodeQuality quality) {
    return encode_jxl_pixels(w, h, rgba, UHDR_CG_BT_709, path, quality,
        color.exponent_bits ? (color.bits <= 16 ? JXL_TYPE_FLOAT16 : JXL_TYPE_FLOAT) : sixteen ? JXL_TYPE_UINT16 : JXL_TYPE_UINT8,
        static_cast<JxlTransferFunction>(color.transfer),
        color.intensity_target > 0.f ? color.intensity_target : color.transfer == 16 ? 10000.f : color.transfer == 18 ? 1000.f : kSdrWhiteNits, &color);
}

int encode_jxl_f32(unsigned w,unsigned h,float* rgba,uhdr_color_gamut_t cg,
                   bool hdr,const char* path,StillEncodeQuality quality,bool pq) {
    const size_t pixels=static_cast<size_t>(w)*h;
    if(!rgba||!path||!pixels)return -60;
    if (hdr && !pq && quality.jxl_scrgb) return encode_jxl_scrgb(w, h, rgba, cg, path, quality);
    float peak=pq?kMaxLinear:1.f;
    if(hdr&&!pq) {
        float found=1.f;
        const unsigned jobs=hdr_pixel_jobs(pixels);
        std::array<float,8> peaks{};
        hdr_parallel_pixels(pixels,jobs,[&](size_t begin,size_t end,unsigned job){
            float local=1.f;
            for(size_t i=begin;i<end;++i)for(unsigned c=0;c<3;++c)
                if(std::isfinite(rgba[4*i+c]))local=std::max(local,rgba[4*i+c]);
            peaks[job]=local;
        });
        found=*std::max_element(peaks.begin(),peaks.end());
        peak=std::max(found,400.f/kSdrWhiteNits);
    }
    // Export exclusively owns rgba until the synchronous encode finishes.
    hdr_parallel_pixels(pixels,hdr_pixel_jobs(pixels),[&](size_t begin,size_t end,unsigned){
        for(size_t i=begin;i<end;++i) {
            for(unsigned c=0;c<3;++c) {
                float v=rgba[4*i+c];if(!std::isfinite(v))v=0.f;
                if(pq||!hdr)v=std::clamp(v,0.f,peak);
                rgba[4*i+c]=pq?linear_to_pq_code(v):v/peak;
            }
            float alpha=rgba[4*i+3];if(!std::isfinite(alpha))alpha=0.f;
            rgba[4*i+3]=std::clamp(alpha,0.f,1.f);
        }
    });
    const float intensity=pq?kMaxNits:hdr?peak*kSdrWhiteNits:kSdrWhiteNits;
    if (pq) {
        // Float32 processing does not require a float codestream for bounded PQ codes.
        std::vector<uint16_t> packed(pixels * 4);
        hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
            for (size_t i = begin * 4; i < end * 4; ++i)
                packed[i] = static_cast<uint16_t>(std::lround(std::clamp(rgba[i], 0.f, 1.f) * 65535.f));
        });
        return encode_jxl_pixels(w, h, packed.data(), cg, path, quality, JXL_TYPE_UINT16,
            JXL_TRANSFER_FUNCTION_PQ, intensity);
    }
    return encode_jxl_pixels(w,h,rgba,cg,path,quality,JXL_TYPE_FLOAT,
        JXL_TRANSFER_FUNCTION_LINEAR,intensity);
}
