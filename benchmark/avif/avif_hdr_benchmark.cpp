// Host regression/benchmark for the production AVIF gain-map path.
// Linux host build (dav1d development files and a JDK must be installed):
//   cmake -S benchmark/avif -B /tmp/avif-bench -DCMAKE_BUILD_TYPE=Release \
//     -DAVIF_SOURCE_DIR=/path/to/libavif -DUHDR_SOURCE_DIR=/path/to/libultrahdr
//   cmake --build /tmp/avif-bench -j
// Run:
//   avif_hdr_benchmark [--max-edge=768] samples/iphone18/*.avif
// Reports codec and HDR processing separately. Compares against libavif's PQ
// output, checks parallel determinism, and rejects a >0.3% relative difference
// (with a 0.01 linear-light floor around black for PQ quantization).
#include "../../app/src/main/cpp/avif_hdr.cpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>

using Clock = std::chrono::steady_clock;

static double elapsed_ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void check_fallbacks(avifImage* image) {
    std::vector<uint16_t> unused;
    auto transfer = image->transferCharacteristics;
    image->transferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_LINEAR;
    require(!apply_iso_gain_map_fast(image, unused, 4), "unsupported transfer accepted");
    image->transferCharacteristics = transfer;
    image->gainMap->useBaseColorSpace = AVIF_TRUE;
    require(!apply_iso_gain_map_fast(image, unused, 4), "unsupported gain color space accepted");
    image->gainMap->useBaseColorSpace = AVIF_FALSE;
    auto gamma = image->gainMap->gainMapGamma[0];
    image->gainMap->gainMapGamma[0].d = 0;
    require(!apply_iso_gain_map_fast(image, unused, 4), "invalid gamma accepted");
    image->gainMap->gainMapGamma[0] = gamma;
}

static void compare(avifImage* image, const char* name, double decode_ms) {
    avifRGBImage tone{};
    tone.format = AVIF_RGB_FORMAT_RGBA;
    tone.depth = 16;
    avifDiagnostics diag{};
    auto start = Clock::now();
    const auto rc = avifImageApplyGainMap(
        image, image->gainMap, unsigned_frac(image->gainMap->alternateHdrHeadroom),
        AVIF_COLOR_PRIMARIES_SMPTE432, AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084,
        &tone, nullptr, &diag);
    require(rc == AVIF_RESULT_OK, diag.error);
    std::vector<uint16_t> reference(size_t(image->width) * image->height * 4);
    for (unsigned y = 0; y < image->height; ++y) {
        const auto* p = reinterpret_cast<const uint16_t*>(tone.pixels + size_t(y) * tone.rowBytes);
        for (unsigned x = 0; x < image->width; ++x) {
            for (int c = 0; c < 4; ++c) {
                const float v = c == 3 ? p[4 * x + c] / 65535.f :
                    std::min(kMaxLinear, pq_eotf(p[4 * x + c] / 65535.f) / kSdrWhiteNits);
                reference[(size_t(y) * image->width + x) * 4 + c] = float_to_half(v);
            }
        }
    }
    const double reference_ms = elapsed_ms(start);
    avifRGBImageFreePixels(&tone);

    std::vector<uint16_t> serial, parallel;
    start = Clock::now();
    require(apply_iso_gain_map_fast(image, serial, 1), "fast path rejected sample");
    const double serial_ms = elapsed_ms(start);
    start = Clock::now();
    require(apply_iso_gain_map_fast(image, parallel, 4), "parallel path rejected sample");
    const double parallel_ms = elapsed_ms(start);
    require(serial == parallel, "single-thread and parallel pixels differ");
    require(serial.size() == reference.size(), "output dimensions differ");
    double max_error = 0;
    for (size_t i = 0; i < serial.size(); ++i) {
        const float v = half_to_float(serial[i]), r = half_to_float(reference[i]);
        const double error = std::abs(v - r) / std::max(0.01f, std::abs(r));
        max_error = std::max(error, max_error);
    }
    std::printf("%s %ux%u codec=%.1f hdr_old=%.1f hdr_1thread=%.1f hdr_4threads=%.1f ms max_error=%.6f\n",
                name, image->width, image->height, decode_ms, reference_ms, serial_ms, parallel_ms, max_error);
    std::fflush(stdout);
    require(max_error < 0.003, "pixel error exceeds 0.3%");
}

int main(int argc, char** argv) {
    try {
        unsigned max_edge = 0;
        int first = 1;
        if (argc > 1 && std::string(argv[1]).find("--max-edge=") == 0) {
            max_edge = std::stoul(argv[1] + 11);
            ++first;
        }
        require(first < argc, "pass at least one gain-map AVIF sample");
        for (int a = first; a < argc; ++a) {
            std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)> decoder(avifDecoderCreate(), avifDecoderDestroy);
            require(bool(decoder), "decoder allocation failed");
            decoder->imageContentToDecode = AVIF_IMAGE_CONTENT_ALL;
            decoder->maxThreads = 4;
            auto start = Clock::now();
            require(avifDecoderSetIOFile(decoder.get(), argv[a]) == AVIF_RESULT_OK, "cannot open sample");
            require(avifDecoderParse(decoder.get()) == AVIF_RESULT_OK, "cannot parse sample");
            require(avifDecoderNextImage(decoder.get()) == AVIF_RESULT_OK, "cannot decode sample");
            const double decode_ms = elapsed_ms(start);
            auto* image = decoder->image;
            require(scale_avif_to_max_edge(image, max_edge), "cannot scale sample");
            compare(image, argv[a], decode_ms);
            check_fallbacks(image);
            // Exercise non-unit gain gamma and non-opaque alpha on a small frame.
            require(scale_avif_to_max_edge(image, 256), "cannot scale alpha/gamma fixture");
            image->gainMap->gainMapGamma[0] = {2, 1};
            require(avifImageAllocatePlanes(image, AVIF_PLANES_A) == AVIF_RESULT_OK, "cannot allocate alpha");
            for (unsigned y = 0; y < image->height; ++y) {
                auto* row = reinterpret_cast<uint16_t*>(image->alphaPlane + size_t(y) * image->alphaRowBytes);
                for (unsigned x = 0; x < image->width; ++x) row[x] = x % (1u << image->depth);
            }
            compare(image, "alpha/gamma", 0);
        }
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
