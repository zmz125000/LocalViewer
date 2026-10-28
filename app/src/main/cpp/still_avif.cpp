#include "hdr_encode.h"
#include "hdr_parallel.h"
#include "ultrahdr/icc.h"

#include <android/log.h>
#include <avif/avif.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <memory>
#include <numeric>
#include <vector>

namespace {
using Image = std::unique_ptr<avifImage, decltype(&avifImageDestroy)>;
using Encoder = std::unique_ptr<avifEncoder, decltype(&avifEncoderDestroy)>;
struct EncodedBytes {
    avifRWData data = AVIF_DATA_EMPTY;
    ~EncodedBytes() { avifRWDataFree(&data); }
};

avifColorPrimaries primaries(uhdr_color_gamut_t gamut) {
    return gamut == UHDR_CG_BT_2100 ? AVIF_COLOR_PRIMARIES_BT2020 :
           gamut == UHDR_CG_DISPLAY_P3 ? AVIF_COLOR_PRIMARIES_SMPTE432 : AVIF_COLOR_PRIMARIES_BT709;
}

bool set_sdr_profile(avifImage* image, uhdr_color_gamut_t gamut) {
    // CICP alone cannot describe BT.2020 + sRGB to every platform decoder.
    // Embed the profile of the *encoded* pixels, not the original source ICC:
    // the source TRC has already been converted to sRGB by this encoder.
    auto profile = ultrahdr::IccHelper::writeIccProfile(UHDR_CT_SRGB, gamut);
    if (!profile || profile->getLength() <= ultrahdr::kICCIdentifierSize) return false;
    // IccHelper returns a JPEG APP2 payload. AVIF needs the bare ICC profile.
    return avifImageSetProfileICC(image,
        static_cast<const uint8_t*>(profile->getData()) + ultrahdr::kICCIdentifierSize,
        profile->getLength() - ultrahdr::kICCIdentifierSize) == AVIF_RESULT_OK;
}

Image make_image(unsigned w, unsigned h, unsigned depth, uhdr_color_gamut_t gamut, bool pq, StillEncodeQuality quality) {
    Image image(avifImageCreate(w, h, depth, AVIF_PIXEL_FORMAT_YUV444), avifImageDestroy);
    if (image) {
        image->colorPrimaries = primaries(gamut);
        image->transferCharacteristics = pq ? AVIF_TRANSFER_CHARACTERISTICS_PQ : AVIF_TRANSFER_CHARACTERISTICS_SRGB;
        // Identity + full range preserves the quantized RGB codes in lossless mode.
        image->matrixCoefficients = quality.avif_lossless() ? AVIF_MATRIX_COEFFICIENTS_IDENTITY :
            gamut == UHDR_CG_BT_2100 ? AVIF_MATRIX_COEFFICIENTS_BT2020_NCL : AVIF_MATRIX_COEFFICIENTS_BT709;
        image->yuvRange = AVIF_RANGE_FULL;
        image->alphaPremultiplied = AVIF_FALSE;
    }
    return image;
}

int write_avif(avifImage* image, const char* path, StillEncodeQuality quality) {
    Encoder encoder(avifEncoderCreate(), avifEncoderDestroy);
    if (!encoder) return -71;

    encoder->codecChoice = AVIF_CODEC_CHOICE_AOM;
    encoder->quality = quality.avif();
    encoder->qualityAlpha = AVIF_QUALITY_LOSSLESS;
    encoder->qualityGainMap = encoder->quality;
    encoder->speed = 6;
    encoder->maxThreads = static_cast<int>(std::min(4u, hdr_pixel_jobs(static_cast<size_t>(image->width) * image->height)));
    encoder->autoTiling = AVIF_TRUE;
    EncodedBytes output;
    const avifResult result = avifEncoderWrite(encoder.get(), image, &output.data);
    if (result != AVIF_RESULT_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "AvifExport", "%s: %s", avifResultToString(result), encoder->diag.error);
        return -72;
    }
    FILE* file = fopen(path, "wb");
    if (!file) return -73;
    const bool written = fwrite(output.data.data, 1, output.data.size, file) == output.data.size;
    const bool closed = fclose(file) == 0;
    return written && closed ? 0 : -74;
}

avifRGBImage rgb_view(const avifImage* image, uint8_t* pixels, unsigned depth, unsigned bytes_per_channel) {
    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = depth;
    rgb.pixels = pixels;
    rgb.rowBytes = image->width * 4 * bytes_per_channel;
    rgb.alphaPremultiplied = AVIF_FALSE;
    return rgb;
}
} // namespace

int encode_avif_u8(unsigned w, unsigned h, const uint8_t* rgba, uhdr_color_gamut_t gamut,
                   const char* path, StillEncodeQuality quality) {
    if (!rgba || !path || !w || !h) return -70;
    auto image = make_image(w, h, 8, gamut, false, quality);
    if (!image) return -71;
    if (!set_sdr_profile(image.get(), gamut)) return -77;
    auto rgb = rgb_view(image.get(), const_cast<uint8_t*>(rgba), 8, 1);
    if (avifImageRGBToYUV(image.get(), &rgb) != AVIF_RESULT_OK) return -75;
    return write_avif(image.get(), path, quality);
}

int encode_avif_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t gamut,
                    int mode, const char* path, StillEncodeQuality quality, const uint8_t* sdr_base,
                    bool scrgb_sdr) {
    if (!rgba || !path || !w || !h || mode < 0 || mode > 2) return -70;
    const size_t pixels = static_cast<size_t>(w) * h;
    const bool pq = mode == 1;
    const bool gain_map = mode == 2;
    const unsigned depth = quality.avif_lossless() ? 12 : 10;
    const unsigned maximum = (1u << depth) - 1;
    auto image = make_image(w, h, depth, gamut, pq, quality);
    if (!image) return -71;
    // PQ and ISO gain maps use their CICP/HDR metadata contracts. A base ICC
    // would also make libavif's ISO gain-map reconstruction unsupported.
    if (!pq && !gain_map && !set_sdr_profile(image.get(), gamut)) return -77;

    // Table transforms avoid per-pixel pow calls. Sanitize linear HDR for both
    // gain-map computation and luminance metadata; this buffer belongs to export.
    std::vector<uint16_t> codes(65536);
    std::vector<uint16_t> sanitized(65536);
    std::vector<uint16_t> hdr_codes(gain_map ? 65536 : 0);
    for (size_t i = 0; i < codes.size(); ++i) {
        float v = half_to_float(static_cast<uint16_t>(i));
        if (!std::isfinite(v)) v = 0.f;
        v = std::clamp(v, 0.f, kMaxLinear);
        sanitized[i] = float_to_half(v);
        const float hdr_code = pq || gain_map ? linear_to_pq_code(v) : 0.f;
        const float code = pq ? hdr_code : srgb_oetf(v * (scrgb_sdr ? kSdrWhiteNits / 80.f : 1.f));
        codes[i] = static_cast<uint16_t>(std::lround(code * maximum));
        if (gain_map) hdr_codes[i] = static_cast<uint16_t>(std::lround(hdr_code * 65535.f));
    }
    std::vector<uint16_t> encoded(pixels * 4);
    std::array<float, 8> peaks{};
    std::array<double, 8> averages{};
    const unsigned jobs = hdr_pixel_jobs(pixels);
    hdr_parallel_pixels(pixels, jobs, [&](size_t begin, size_t end, unsigned job) {
        float peak = 0.f;
        double sum = 0.0;
        for (size_t i = begin; i < end; ++i) {
            float luminance[3];
            for (unsigned c = 0; c < 3; ++c) {
                const auto code = rgba[i * 4 + c];
                encoded[i * 4 + c] = gain_map && sdr_base ?
                    static_cast<uint16_t>((sdr_base[i * 4 + c] * maximum + 127u) / 255u) : codes[code];
                rgba[i * 4 + c] = sanitized[code];
                luminance[c] = half_to_float(rgba[i * 4 + c]);
                peak = std::max(peak, luminance[c]);
            }
            // Per-pixel RGB average follows libavif's CLLI convention.
            sum += (luminance[0] + luminance[1] + luminance[2]) / 3.0;
            float alpha = half_to_float(rgba[i * 4 + 3]);
            if (!std::isfinite(alpha)) alpha = 0.f;
            alpha = std::clamp(alpha, 0.f, 1.f);
            rgba[i * 4 + 3] = float_to_half(alpha);
            encoded[i * 4 + 3] = static_cast<uint16_t>(std::lround(alpha * maximum));
        }
        peaks[job] = peak;
        averages[job] = sum;
    });
    avifContentLightLevelInformationBox clli{};
    clli.maxCLL = static_cast<uint16_t>(std::lround(*std::max_element(peaks.begin(), peaks.end()) * kSdrWhiteNits));
    clli.maxPALL = static_cast<uint16_t>(std::lround(std::accumulate(averages.begin(), averages.end(), 0.0) / pixels * kSdrWhiteNits));
    if (pq) image->clli = clli;
    auto base_rgb = rgb_view(image.get(), reinterpret_cast<uint8_t*>(encoded.data()), depth, 2);
    if (avifImageRGBToYUV(image.get(), &base_rgb) != AVIF_RESULT_OK) return -75;

    if (gain_map) {
        image->gainMap = avifGainMapCreate();
        if (!image->gainMap) return -71;
        // Equal-sized map avoids libavif's optional libyuv scaling dependency.
        image->gainMap->image = avifImageCreate(w, h, depth, AVIF_PIXEL_FORMAT_YUV444);
        if (!image->gainMap->image) return -71;
        image->gainMap->image->matrixCoefficients = quality.avif_lossless() ?
            AVIF_MATRIX_COEFFICIENTS_IDENTITY : AVIF_MATRIX_COEFFICIENTS_BT709;
        image->gainMap->image->yuvRange = AVIF_RANGE_FULL;
        // libavif clamps the LINEAR transfer to [0,1]. Pass 16-bit PQ instead,
        // so gain-map math retains HDR values above SDR white (203 nits).
        hdr_parallel_pixels(pixels, jobs, [&](size_t begin, size_t end, unsigned) {
            for (size_t i = begin; i < end; ++i) {
                for (unsigned c = 0; c < 3; ++c) rgba[i * 4 + c] = hdr_codes[rgba[i * 4 + c]];
                rgba[i * 4 + 3] = static_cast<uint16_t>(std::lround(half_to_float(rgba[i * 4 + 3]) * 65535.f));
            }
        });
        auto hdr_rgb = rgb_view(image.get(), reinterpret_cast<uint8_t*>(rgba), 16, 2);
        avifDiagnostics diagnostics{};
        if (avifRGBImageComputeGainMap(&base_rgb, image->colorPrimaries, AVIF_TRANSFER_CHARACTERISTICS_SRGB,
                                      &hdr_rgb, image->colorPrimaries, AVIF_TRANSFER_CHARACTERISTICS_PQ,
                                      image->gainMap, &diagnostics) != AVIF_RESULT_OK) {
            __android_log_print(ANDROID_LOG_ERROR, "AvifExport", "Gain map: %s", diagnostics.error);
            return -76;
        }
        // The helper computes ISO metadata in linear light from absolute PQ.
        // Describe the reconstructed HDR rendition with the same signaling.
        auto* map = image->gainMap;
        map->altColorPrimaries = image->colorPrimaries;
        map->altTransferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_PQ;
        map->altMatrixCoefficients = image->matrixCoefficients;
        map->altYUVRange = AVIF_RANGE_FULL;
        map->altDepth = depth;
        map->altPlaneCount = 3;
        map->altCLLI = clli;
    }
    // YUV and gain-map planes now own all pixels needed by the encoder.
    std::vector<uint16_t>().swap(encoded);
    return write_avif(image.get(), path, quality);
}
