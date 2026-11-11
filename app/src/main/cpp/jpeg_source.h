#pragma once
#include "hdr_encode.h"
#include <vector>

// Encoded integer samples. In CMYK, the fourth channel is inverse K, never alpha.
struct JpegSourceImage {
    unsigned width = 0, height = 0, channels = 4;
    bool cmyk = false, gray = false;
    std::vector<uint8_t> pixels, icc, exif;
};
std::vector<uint8_t> source_jpeg_icc(const uint8_t*, size_t);
JpegSourceImage decode_source_jpeg(const uint8_t*, size_t, bool native_color = false);
void source_jpeg_to_rgb(JpegSourceImage&);
bool source_jpeg_is_cmyk(const uint8_t*, size_t);
int encode_cmyk_jpeg(unsigned, unsigned, const uint8_t*, const StillSourceColor&, const char*, StillEncodeQuality);
