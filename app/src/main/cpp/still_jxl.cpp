#include "hdr_encode.h"
#include "hdr_parallel.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <memory>
#include <thread>
#include <jxl/encode_cxx.h>
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
                             JxlTransferFunction transfer, float intensity) {
    const size_t pixels = static_cast<size_t>(w) * h;
    if (!rgba || !path || !pixels) return -60;
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, hdr_pixel_jobs(pixels));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner || JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS) return -61;
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = w;
    info.ysize = h;
    info.bits_per_sample = type == JXL_TYPE_UINT8 ? 8 : 16;
    info.exponent_bits_per_sample = type == JXL_TYPE_FLOAT16 ? 5 : 0;
    info.num_color_channels = 3;
    info.num_extra_channels = 1;
    info.alpha_bits = info.bits_per_sample;
    info.alpha_exponent_bits = info.exponent_bits_per_sample;
    info.alpha_premultiplied = JXL_FALSE;
    const float distance = quality.jxl_distance();
    const bool compression = distance > 0.f;
    info.uses_original_profile = compression ? JXL_FALSE : JXL_TRUE;
    info.intensity_target = intensity;
    JxlColorEncoding color{};
    color.color_space = JXL_COLOR_SPACE_RGB;
    color.white_point = JXL_WHITE_POINT_D65;
    color.primaries = cg == UHDR_CG_BT_2100 ? JXL_PRIMARIES_2100 : cg == UHDR_CG_DISPLAY_P3 ? JXL_PRIMARIES_P3 : JXL_PRIMARIES_SRGB;
    color.transfer_function = transfer;
    color.rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
    if (JxlEncoderSetBasicInfo(encoder.get(), &info) != JXL_ENC_SUCCESS ||
        JxlEncoderSetColorEncoding(encoder.get(), &color) != JXL_ENC_SUCCESS) return -62;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    // Lossless keeps original-profile / fast-decode coding. High/mid/low use
    // perceptual XYB at distances 0.5/1.0/1.5, preserving primaries, transfer
    // and HDR intensity target. Alpha remains lossless in every preset.
    if (!frame || JxlEncoderSetFrameLossless(frame, compression ? JXL_FALSE : JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderSetFrameDistance(frame, distance) != JXL_ENC_SUCCESS ||
        JxlEncoderSetExtraChannelDistance(frame, 0, 0.f) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, compression ? 5 : 3) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED, compression ? 0 : 2) != JXL_ENC_SUCCESS) return -63;
    const JxlPixelFormat format{4, type, JXL_NATIVE_ENDIAN, 0};
    if (JxlEncoderAddImageFrame(frame, &format, rgba, pixels * (type == JXL_TYPE_UINT8 ? 4 : 8)) != JXL_ENC_SUCCESS) return -64;
    JxlEncoderCloseInput(encoder.get());
    return write_jxl_output(encoder.get(), path);
}

// The caller owns straight-alpha linear half samples. Normalize HDR to its
// declared luminance for linear JXL; PQ retains the absolute 10000-nit reference.
int encode_jxl_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t cg,
                   bool hdr, const char* path, StillEncodeQuality quality, bool pq) {
    const size_t pixels = static_cast<size_t>(w) * h;
    if (!rgba || !path || !pixels) return -60;
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
        JNIEnv* env, jclass, jbyteArray input, jstring output) {
    if (!input || !output) return -60;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0) return -60;
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner ||
        JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS ||
        JxlEncoderStoreJPEGMetadata(encoder.get(), JXL_TRUE) != JXL_ENC_SUCCESS) return -61;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    if (!frame || JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, 5) != JXL_ENC_SUCCESS) return -63;
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
