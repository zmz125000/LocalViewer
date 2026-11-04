// Compare the Android gain-map handoff with libavif at partial/full headroom.
#include "../../app/src/main/cpp/avif_hdr.cpp"
#include <stdexcept>
bool export_pixels_preserve_scrgb() { return false; }
static void check(bool value, const char* text) { if (!value) throw std::runtime_error(text); }
static void check_alpha8() {
    std::unique_ptr<avifImage, decltype(&avifImageDestroy)> image(avifImageCreate(7, 5, 8, AVIF_PIXEL_FORMAT_YUV400), avifImageDestroy);
    image->yuvRange = AVIF_RANGE_FULL;
    check(avifImageAllocatePlanes(image.get(), AVIF_PLANES_YUV) == AVIF_RESULT_OK, "gray allocation");
    for (unsigned y=0; y<5; ++y) for (unsigned x=0; x<7; ++x) image->yuvPlanes[0][y*image->yuvRowBytes[0]+x] = x*31+y*7;
    const auto stride = avif_gainmap_alpha8_stride(image.get());
    check(stride == 8, "ALPHA_8 row alignment");
    std::vector<uint8_t> packed(stride*5, 0);
    copy_avif_gainmap_alpha8(image.get(), packed.data(), stride);
    for (unsigned y=0; y<5; ++y) for (unsigned x=0; x<7; ++x)
        check(packed[y*stride+x] == x*31+y*7, "ALPHA_8 gain codes changed");
    image->yuvRange = AVIF_RANGE_LIMITED;
    check(!avif_gainmap_alpha8_stride(image.get()), "limited-range map must use RGB conversion");
}
static void compare_android(avifImage* image) {
    float meta[17]{};
    check(android_gainmap_metadata(image->gainMap, meta), "gain metadata handoff");
    check(image->gainMap->useBaseColorSpace, "test expects base color space math");
    ScopedAvifRgb base(image, 1);
    check(base.convert(image), "base RGB");
    std::unique_ptr<avifImage, decltype(&avifImageDestroy)> map(avifImageCreateEmpty(), avifImageDestroy);
    const avifCropRect rect{0, 0, image->gainMap->image->width, image->gainMap->image->height};
    check(avifImageSetViewRect(map.get(), image->gainMap->image, &rect) == AVIF_RESULT_OK, "map view");
    avifDiagnostics diag{};
    check(avifImageScale(map.get(), image->width, image->height, &diag) == AVIF_RESULT_OK, "scale gain map");
    ScopedAvifRgb gain(map.get(), 1); check(gain.convert(map.get()), "gain RGB");
    for (float display : {1.f, 2.f, 4.f, meta[16]}) {
        avifRGBImage tone{}; tone.format = AVIF_RGB_FORMAT_RGBA; tone.depth = 16;
        check(avifImageApplyGainMap(image, image->gainMap, std::log2(display), image->colorPrimaries,
            AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084, &tone, nullptr, &diag) == AVIF_RESULT_OK, "libavif reconstruction");
        const double weight = std::clamp(std::log(display/meta[15])/std::log(meta[16]/meta[15]), 0.f, 1.f);
        double worst = 0.;
        for (unsigned y=0; y<image->height; ++y) for (unsigned x=0; x<image->width; ++x) for (unsigned c=0; c<3; ++c) {
            auto sample = [&](const avifRGBImage& rgb) { const auto* row=rgb.pixels+size_t(y)*rgb.rowBytes; return rgb.depth>8 ? reinterpret_cast<const uint16_t*>(row)[x*4+c]/double((1u<<rgb.depth)-1) : row[x*4+c]/255.; };
            const double b = srgb_eotf(sample(base.rgb));
            const double g = std::pow(sample(gain.rgb), meta[6+c]);
            const double logGain = (1.-g)*std::log(meta[c])+g*std::log(meta[3+c]);
            const double expected = (b+meta[9+c])*std::exp(logGain*weight)-meta[12+c];
            const double actual = pq_eotf(reinterpret_cast<const uint16_t*>(tone.pixels+size_t(y)*tone.rowBytes)[x*4+c]/65535.f)/kSdrWhiteNits;
            worst = std::max(worst, std::abs(expected-actual)/std::max(.01, std::abs(expected)));
        }
        avifRGBImageFreePixels(&tone);
        std::printf("display=%.3f weight=%.4f relative error=%.6f\n", display, weight, worst);
        check(worst < .003, "Android metadata brightness differs from libavif");
    }
}
int main(int argc, char** argv) try {
    check_alpha8();
    check(argc == 2, "pass a gain-map AVIF sample");
    std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)> decoder(avifDecoderCreate(), avifDecoderDestroy);
    decoder->imageContentToDecode = AVIF_IMAGE_CONTENT_ALL;
    check(avifDecoderSetIOFile(decoder.get(), argv[1]) == AVIF_RESULT_OK && avifDecoderParse(decoder.get()) == AVIF_RESULT_OK &&
        avifDecoderNextImage(decoder.get()) == AVIF_RESULT_OK, "sample decode");
    auto* image = decoder->image;
    check(image->gainMap && scale_avif_to_max_edge(image, 256), "sample gain map");
    compare_android(image);
    image->gainMap->gainMapGamma[0] = {2,1};
    compare_android(image);
    image->gainMap->gainMapGamma[0].d = 0;
    float invalid[17]{};check(!android_gainmap_metadata(image->gainMap, invalid), "invalid gamma accepted");
    std::puts("Android gain-map handoff checks passed");
} catch (const std::exception& error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
