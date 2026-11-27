#pragma once
// SPDX-License-Identifier: GPL-3.0-only
// CPU adaptation of MPC Video Renderer's HDR-to-SDR Metadata spline (adipose).
// https://github.com/zmz125000/VideoRenderer/blob/70811bc/Shaders/convert/hdr_tone_mapping_spline.hlsl

#include "sdr_tone_map.h"

// Linear RGB, with 1.0 = 203 nits. Ignore invisible pixels for the content peak.
template<class Pixels> inline float mpcvr_content_peak(const Pixels& image) {
    float peak = 0.f;
    for (size_t i = 0; i < image.size(); i += 4) {
        if (!std::isfinite(image[i + 3]) || image[i + 3] <= 0.f) continue;
        for (unsigned c = 0; c < 3; ++c) {
            const float value = image[i + c];
            if (std::isfinite(value)) peak = std::max(peak, value);
        }
    }
    return peak;
}

template<class Pixels> inline void tone_map_mpcvr_in_place(Pixels& image, int gamut, float peak) {
    using skiv::Matrix;
    using skiv::RGB;
    using skiv::transform;
    constexpr double sdr_white = 203.0;
    constexpr double flat_at = 4000.0;
    constexpr Matrix rgb_to_lms = {{{.412109375, .523925781, .063964844},
        {.166748047, .720458984, .112792969}, {.024169922, .075439453, .900390625}}};
    constexpr Matrix lms_to_rgb = {{{3.436606694, -2.506452119, .069845424},
        {-.791329556, 1.983600452, -.192270896}, {-.025949900, -.098913715, 1.124863614}}};
    const auto& to_xyz = gamut == 2 ? skiv::rgb2020_xyz : gamut == 1 ? skiv::rgbp3_xyz : skiv::rgb709_xyz;
    const auto input_to_bt2020 = skiv::multiply(skiv::inverse(skiv::rgb2020_xyz), to_xyz);
    const auto bt2020_to_display = skiv::multiply(skiv::inverse(to_xyz), skiv::rgb2020_xyz);
    const auto to_display = [&](const RGB& rgb) {
        return gamut == 2 ? rgb : transform(bt2020_to_display, rgb);
    };
    const auto pq_nits = [](double nits) { return skiv::pq(nits / 80.0); };
    const double source_peak = std::clamp(double(peak) * sdr_white, sdr_white + 1.0, 10000.0);
    const double white = pq_nits(sdr_white);
    const double knee = std::max(1.5 * white - 0.5, 0.0);
    const double width = pq_nits(std::max(source_peak, flat_at)) - knee;
    const double inverse_width = 1.0 / width;
    double endpoint = white;
    if (source_peak < flat_at) {
        const double t = (pq_nits(source_peak) - knee) * inverse_width;
        const double blend = t * t * (3.0 - 2.0 * t);
        endpoint = (white - (1.0 - blend) * knee - width * t * (1.0 - t) * (1.0 - t)) / blend;
    }
    const double knee_nits = 80.0 * skiv::inverse_pq(knee);
    const auto spline = [&](double nits) {
        if (nits <= knee_nits) return nits;
        const double encoded = pq_nits(nits);
        const double t = std::clamp((encoded - knee) * inverse_width, 0.0, 1.0);
        const double blend = t * t * (3.0 - 2.0 * t);
        const double mapped = knee + (endpoint - knee) * blend + width * t * (1.0 - t) * (1.0 - t);
        return 80.0 * skiv::inverse_pq(std::min(mapped, white));
    };
    const auto max_channel = [](const RGB& rgb) { return std::max({rgb[0], rgb[1], rgb[2]}); };
    const auto& source = image;
    const size_t pixels = image.size() / 4;
    hdr_parallel_pixels(pixels, hdr_pixel_jobs(pixels), [&](size_t begin, size_t end, unsigned) {
        for (size_t pixel = begin; pixel < end; ++pixel) {
            const size_t i = pixel * 4;
            if (!std::isfinite(source[i + 3]) || source[i + 3] <= 0.f) {
                image[i] = image[i + 1] = image[i + 2] = 0.f;
                continue;
            }
            RGB rgb{};
            for (unsigned c = 0; c < 3; ++c) {
                const float value = source[i + c];
                rgb[c] = std::isfinite(value) ? value * sdr_white : 0.0;
            }
            auto bt2020 = gamut == 2 ? rgb : transform(input_to_bt2020, rgb);
            for (double& channel : bt2020) channel = std::clamp(channel, 0.0, 10000.0);
            const double y = .2627 * bt2020[0] + .6780 * bt2020[1] + .0593 * bt2020[2];
            RGB mapped = bt2020;
            if (y > 1e-6) {
                const auto [low, high] = std::minmax_element(bt2020.begin(), bt2020.end());
                const double saturation = std::clamp(1.0 - pq_nits(*low) / pq_nits(*high), 0.0, 1.0);
                const double ratio = std::max(*high / y, 1.0);
                const double level = ratio > 1.0 && saturation > 0.0 ? y * std::pow(ratio, saturation) : y;
                const double scale = spline(level) / level;
                RGB scaled = bt2020;
                for (double& channel : scaled) channel *= scale;

                auto lms = transform(rgb_to_lms, bt2020);
                for (double& channel : lms) channel = spline(std::max(channel, 0.0));
                const auto channels = transform(lms_to_rgb, lms);
                const double overshoot = std::max(max_channel(to_display(scaled)) / sdr_white, 1.0);
                const double mix = overshoot > 1.0 ? 1.0 - (1.0 - .2003) * std::pow(overshoot, -2.73) : .2003;
                for (unsigned c = 0; c < 3; ++c) {
                    mapped[c] = scaled[c] + (std::max(channels[c], 0.0) - scaled[c]) * mix;
                }
            }
            const auto display = to_display(mapped);
            const double normalization = std::max(sdr_white, max_channel(display));
            for (unsigned c = 0; c < 3; ++c) {
                image[i + c] = float(std::clamp(display[c] / normalization, 0.0, 1.0));
            }
        }
    });
}

inline std::vector<float> tone_map_mpcvr_base(std::vector<float> image, int gamut, float peak) {
    tone_map_mpcvr_in_place(image, gamut, peak);
    return image;
}
