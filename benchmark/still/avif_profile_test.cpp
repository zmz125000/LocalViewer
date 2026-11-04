// Exercise the actual source writer, including serialization of the ICC fallback.
#include "../../app/src/main/cpp/source_export.cpp"
#include <stdexcept>

static void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

int main() {
    const char* path = "/tmp/localviewer-avif-profile-test.avif";
    for (int cp : {1, 9, 12}) for (int tc : {8, 13}) for (bool sixteen : {false, true}) {
        StillEncodeQuality quality(0);
        const uint8_t pixels[] = {31, 103, 224, 255, 203, 67, 11, 255};
        const uint16_t pixels16[] = {8000, 26500, 57500, 65535, 52200, 17300, 2800, 65535};
        auto image = from_rgb(2, 1, sixteen ? static_cast<const void*>(pixels16) : pixels,
                              sixteen, sixteen ? 12 : 8, cp, tc, quality);
        check(bool(image), "create RGB fixture");
        std::vector<uint8_t> before;
        check(rgb_pixels(image.get(), sixteen ? 16 : 8, before), "read source pixels");
        check(write_avif_source(image.get(), path, quality) == 1, "direct AVIF export");
        Decoder decoder(avifDecoderCreate(), avifDecoderDestroy);
        check(decoder && avifDecoderSetIOFile(decoder.get(), path) == AVIF_RESULT_OK &&
              avifDecoderParse(decoder.get()) == AVIF_RESULT_OK &&
              avifDecoderNextImage(decoder.get()) == AVIF_RESULT_OK, "read AVIF");
        auto expected = ultrahdr::IccHelper::writeIccProfile(tc == 8 ? UHDR_CT_LINEAR : UHDR_CT_SRGB, gamut(cp));
        const auto* profile = static_cast<const uint8_t*>(expected->getData()) + ultrahdr::kICCIdentifierSize;
        const size_t size = expected->getLength() - ultrahdr::kICCIdentifierSize;
        check(decoder->image->icc.size == size && !std::memcmp(decoder->image->icc.data, profile, size), "matching ICC fallback");
        check(decoder->image->colorPrimaries == cp && decoder->image->transferCharacteristics == tc, "CICP preserved");
        std::vector<uint8_t> after;
        check(rgb_pixels(decoder->image, sixteen ? 16 : 8, after) && before == after, "pixel codes preserved");
    }
    auto image = from_rgb(1, 1, "\x11\x22\x33\xff", false, 8, 9, 13, StillEncodeQuality(0));
    auto custom = ultrahdr::IccHelper::writeIccProfile(UHDR_CT_SRGB, UHDR_CG_DISPLAY_P3);
    const auto* profile = static_cast<const uint8_t*>(custom->getData()) + ultrahdr::kICCIdentifierSize;
    const size_t size = custom->getLength() - ultrahdr::kICCIdentifierSize;
    check(avifImageSetProfileICC(image.get(), profile, size) == AVIF_RESULT_OK, "set custom profile");
    check(ensure_avif_sdr_profile(image.get()) && image->icc.size == size && !std::memcmp(image->icc.data, profile, size), "preserve existing ICC verbatim");
    avifRWDataFree(&image->icc);
    for (int tc : {16, 18, 2}) {
        image->transferCharacteristics = tc;
        check(ensure_avif_sdr_profile(image.get()) && !image->icc.size, "no SDR profile for HDR or unknown transfer");
    }
    image->transferCharacteristics = 13;
    image->colorPrimaries = 2;
    check(ensure_avif_sdr_profile(image.get()) && !image->icc.size, "no guessed gamut");
    image->colorPrimaries = 9;
    image->yuvFormat = AVIF_PIXEL_FORMAT_YUV400;
    check(ensure_avif_sdr_profile(image.get()) && !image->icc.size, "no RGB profile on gray");
    image->yuvFormat = AVIF_PIXEL_FORMAT_YUV444;
    image->gainMap = avifGainMapCreate();
    check(image->gainMap && ensure_avif_sdr_profile(image.get()) && !image->icc.size, "gain-map base stays CICP-only");
    std::remove(path);
    std::puts("All AVIF ICC fallback checks passed");
}
