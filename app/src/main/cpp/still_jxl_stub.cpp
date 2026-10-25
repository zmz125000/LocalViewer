#include "hdr_encode.h"

// Keep the export ABI without pulling the JPEG XL encoder into the APK.
// The real implementation remains in still_jxl.cpp for opt-in builds.
int encode_jxl_f16(unsigned, unsigned, uint16_t*, uhdr_color_gamut_t, bool, const char*, StillEncodeQuality, bool) {
    return -100; // Unsupported in this build; never write a partial output.
}

int encode_jxl_u8(unsigned, unsigned, const uint8_t*, uhdr_color_gamut_t, const char*, StillEncodeQuality) {
    return -100;
}
