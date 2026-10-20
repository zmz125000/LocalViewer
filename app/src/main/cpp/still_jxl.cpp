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

// The caller owns straight-alpha linear half samples. Normalize HDR to its
// declared luminance before encoding; the decoder recovers 1.0 = 203 nits.
int encode_jxl_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t cg,
                   bool hdr, const char* path) {
    const size_t pixels = static_cast<size_t>(w) * h;
    if (!rgba || !path || !pixels) return -60;
    float peak = 1.f;
    if (hdr) {
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
    const float intensity = hdr ? peak * kSdrWhiteNits : kSdrWhiteNits;
    // One table also bounds invalid input and avoids per-channel floating-point
    // conversion in the image loop. Alpha is already straight and stays unchanged.
    std::vector<uint16_t> table(65536);
    for (size_t code = 0; code < table.size(); ++code) {
        float v = half_to_float(static_cast<uint16_t>(code));
        if (!std::isfinite(v) || v < 0.f) v = 0.f;
        table[code] = float_to_half(std::min(v, peak) / peak);
    }
    hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
        for (size_t i = begin; i < end; ++i) {
            for (unsigned c = 0; c < 3; ++c) rgba[4 * i + c] = table[rgba[4 * i + c]];
        }
    });
    auto encoder = JxlEncoderMake(nullptr);
    const unsigned threads = std::min(4u, hdr_pixel_jobs(pixels));
    auto runner = JxlThreadParallelRunnerMake(nullptr, threads);
    if (!encoder || !runner || JxlEncoderSetParallelRunner(encoder.get(), JxlThreadParallelRunner, runner.get()) != JXL_ENC_SUCCESS) return -61;
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = w;
    info.ysize = h;
    info.bits_per_sample = 16;
    info.exponent_bits_per_sample = 5;
    info.num_color_channels = 3;
    info.num_extra_channels = 1;
    info.alpha_bits = 16;
    info.alpha_exponent_bits = 5;
    info.alpha_premultiplied = JXL_FALSE;
    info.uses_original_profile = JXL_TRUE;
    info.intensity_target = intensity;
    JxlColorEncoding color{};
    color.color_space = JXL_COLOR_SPACE_RGB;
    color.white_point = JXL_WHITE_POINT_D65;
    color.primaries = cg == UHDR_CG_BT_2100 ? JXL_PRIMARIES_2100 : cg == UHDR_CG_DISPLAY_P3 ? JXL_PRIMARIES_P3 : JXL_PRIMARIES_SRGB;
    color.transfer_function = JXL_TRANSFER_FUNCTION_LINEAR;
    color.rendering_intent = JXL_RENDERING_INTENT_RELATIVE;
    if (JxlEncoderSetBasicInfo(encoder.get(), &info) != JXL_ENC_SUCCESS ||
        JxlEncoderSetColorEncoding(encoder.get(), &color) != JXL_ENC_SUCCESS) return -62;
    auto* frame = JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    // libjxl 0.12: tier 2 avoids the weighted predictor and uses smaller
    // modular groups for faster decoding. Lossless F16 samples stay intact;
    // the trade-off is compression density, not HDR/color precision.
    if (!frame || JxlEncoderSetFrameLossless(frame, JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, 3) != JXL_ENC_SUCCESS ||
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED, 2) != JXL_ENC_SUCCESS) return -63;
    const JxlPixelFormat format{4, JXL_TYPE_FLOAT16, JXL_NATIVE_ENDIAN, 0};
    if (JxlEncoderAddImageFrame(frame, &format, rgba, pixels * 8) != JXL_ENC_SUCCESS) return -64;
    JxlEncoderCloseInput(encoder.get());
    std::unique_ptr<FILE, JxlFileCloser> file(fopen(path, "wb"));
    if (!file) return -65;
    // Stream encoded bytes directly to disk; no second complete compressed file.
    std::array<uint8_t, 65536> output{};
    for (;;) {
        uint8_t* next = output.data();
        size_t available = output.size();
        const auto status = JxlEncoderProcessOutput(encoder.get(), &next, &available);
        const size_t count = output.size() - available;
        if (count && fwrite(output.data(), 1, count, file.get()) != count) return -66;
        if (status == JXL_ENC_SUCCESS) break;
        if (status != JXL_ENC_NEED_MORE_OUTPUT) return -67;
    }
    return fflush(file.get()) == 0 ? 0 : -66;
}
