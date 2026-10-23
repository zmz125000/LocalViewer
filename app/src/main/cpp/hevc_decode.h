#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

struct HevcPlanes {
    int width = 0, height = 0, depth = 0, chroma_width = 0, chroma_height = 0;
    int full_range = 0, matrix = 2;
    // Unshifted integer samples, planar Y/Cb/Cr. Depth is the decoded precision.
    std::vector<uint16_t> samples;
};

bool decode_hevc_annex_b(const uint8_t* csd, size_t csd_size,
                        const uint8_t* picture, size_t picture_size, HevcPlanes& out);
