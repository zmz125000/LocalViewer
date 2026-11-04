// Host regression/benchmark for the production AVIF gain-map path.
// Linux host build (dav1d development files and a JDK must be installed):
//   cmake -S benchmark/avif -B /tmp/avif-bench -DCMAKE_BUILD_TYPE=Release \
//     -DAVIF_SOURCE_DIR=/path/to/libavif -DUHDR_SOURCE_DIR=/path/to/libultrahdr
//   cmake --build /tmp/avif-bench -j
// Run:
//   avif_hdr_benchmark [--max-edge=768] samples/iphone18/*.avif
// Reports codec and HDR processing separately. Compares against libavif's PQ
// output, checks exact strip/full-frame RGB parity and parallel determinism, and rejects a >0.3% relative difference
// (with a 0.01 linear-light floor around black for PQ quantization).
#include "../../app/src/main/cpp/avif_hdr.cpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>

// Host reader capture has no export request.
bool export_pixels_preserve_scrgb() { return false; }

using Clock = std::chrono::steady_clock;

static double elapsed_ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void check_rgb_strips(const avifImage* image) {
    for (unsigned depth : {8u, 10u, 12u, 16u}) {
        for (bool floating : {false, true}) {
            if (floating && depth != 16) continue;
            for (bool premultiplied : {false, true}) {
                ScopedAvifRgb reference(image, 1), strips(image, 4);
                reference.rgb.depth = strips.rgb.depth = depth;
                reference.rgb.isFloat = strips.rgb.isFloat = floating;
                reference.rgb.alphaPremultiplied = strips.rgb.alphaPremultiplied = premultiplied;
                require(avifRGBImageAllocatePixels(&reference.rgb) == AVIF_RESULT_OK &&
                    avifRGBImageAllocatePixels(&strips.rgb) == AVIF_RESULT_OK, "RGB allocation failed");
                require(avifImageYUVToRGB(image, &reference.rgb) == AVIF_RESULT_OK &&
                    avif_yuv_to_rgb(image, &strips.rgb) == AVIF_RESULT_OK, "RGB conversion failed");
                require(reference.rgb.rowBytes == strips.rgb.rowBytes &&
                    std::memcmp(reference.rgb.pixels, strips.rgb.pixels,
                        size_t(reference.rgb.rowBytes) * image->height) == 0,
                    "strip conversion differs from full-frame libavif");
            }
        }
    }
}

static void check_rgb_fixture() {
    // Odd dimensions and non-opaque alpha exercise strip/chroma borders.
    for (unsigned depth : {8u, 10u, 12u}) {
        std::unique_ptr<avifImage, decltype(&avifImageDestroy)> image(
            avifImageCreate(1025, 1027, depth, AVIF_PIXEL_FORMAT_YUV420), avifImageDestroy);
        require(bool(image), "fixture allocation failed");
        image->matrixCoefficients = AVIF_MATRIX_COEFFICIENTS_BT709;
        require(avifImageAllocatePlanes(image.get(), AVIF_PLANES_ALL) == AVIF_RESULT_OK, "plane allocation failed");
        for (unsigned plane = 0; plane < 4; ++plane) {
            const bool alpha = plane == 3;
            auto* bytes = alpha ? image->alphaPlane : image->yuvPlanes[plane];
            const auto stride = alpha ? image->alphaRowBytes : image->yuvRowBytes[plane];
            const unsigned width = plane == 0 || alpha ? image->width : (image->width + 1) / 2;
            const unsigned height = plane == 0 || alpha ? image->height : (image->height + 1) / 2;
            for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
                const unsigned code = (x * 71 + y * 43 + plane * 17) % (1u << depth);
                if (depth == 8) bytes[size_t(y) * stride + x] = code;
                else reinterpret_cast<uint16_t*>(bytes + size_t(y) * stride)[x] = code;
            }
        }
        for (auto range : {AVIF_RANGE_FULL, AVIF_RANGE_LIMITED}) {
            image->yuvRange = range;
            check_rgb_strips(image.get());
        }
    }
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
        check_rgb_fixture();
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
            check_rgb_strips(image);
            check_rgb_strips(image->gainMap->image);
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
