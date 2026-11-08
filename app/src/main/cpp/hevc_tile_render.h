#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

// Coordinates describe the unrotated primary image; HEIF transforms and alpha
// remain with the caller. Plane samples are unpacked uint16 codes at source depth.
struct HevcTileRender {
    int width, height, chroma_width, chroma_height, depth;
    int source_width, source_height, output_width, output_height;
    int x, y, copy_width, copy_height;
    bool full_range;
    int matrix, primaries, gamut;
    bool encoded, hlg, preserve_encoded_color;
    bool source_bt2020 = false;
};

// Visit only the final source sample for each output pixel. This is equivalent
// to the old source-to-destination overwrite loop, including across tile edges.
// It avoids repeating YUV/color/half conversion for pixels discarded by resizing.
template<class Store>
float render_hevc_tile(const uint16_t* y_plane, const uint16_t* cb_plane, const uint16_t* cr_plane,
                      const float* lut, const HevcTileRender& p, Store store) {
    const float maximum = float((1u << p.depth) - 1), mid = float(1u << (p.depth - 1));
    const float scale = float(1u << (p.depth - 8));
    const int x_begin = int(int64_t(p.x) * p.output_width / p.source_width);
    const int x_end = int(int64_t(p.x + p.copy_width) * p.output_width / p.source_width);
    const int y_begin = int(int64_t(p.y) * p.output_height / p.source_height);
    const int y_end = int(int64_t(p.y + p.copy_height) * p.output_height / p.source_height);
    const int chroma_x_shift = p.chroma_width == p.width ? 0 : 1;
    const int chroma_y_shift = p.chroma_height == p.height ? 0 : 1;
    auto linear = [&](float code) {
        if (!lut) return std::clamp(code, 0.f, 1.f);
        if (code <= 0.f) return 0.f;
        if (code >= 1.f) return lut[65536];
        const float at = code * 65536.f;
        const int index = int(at);
        return lut[index] + (lut[index + 1] - lut[index]) * (at - index);
    };
    float peak = 1.f;
    for (int oy = y_begin; oy < y_end; ++oy) {
        const int sy = int((int64_t(oy + 1) * p.source_height - 1) / p.output_height) - p.y;
        for (int ox = x_begin; ox < x_end; ++ox) {
            const int sx = int((int64_t(ox + 1) * p.source_width - 1) / p.output_width) - p.x;
            const int cx = std::min(sx >> chroma_x_shift, p.chroma_width - 1);
            const int cy = std::min(sy >> chroma_y_shift, p.chroma_height - 1);
            const float y = y_plane[sy * p.width + sx], cb = cb_plane[cy * p.chroma_width + cx], cr = cr_plane[cy * p.chroma_width + cx];
            const float yf = p.full_range ? y / maximum : (y - 16.f * scale) / (219.f * scale);
            const float cbf = (cb - mid) / (p.full_range ? maximum : 224.f * scale);
            const float crf = (cr - mid) / (p.full_range ? maximum : 224.f * scale);
            float r, g, b;
            if (p.matrix == 0) { r = cr / maximum; g = y / maximum; b = cb / maximum; }
            else if (p.matrix == 1) {
                r = yf + 1.5748f * crf; g = yf - .1873f * cbf - .4681f * crf; b = yf + 1.8556f * cbf;
            } else if (p.matrix == 9 || p.primaries == 9) {
                r = yf + 1.4746f * crf; g = yf - .16455f * cbf - .57135f * crf; b = yf + 1.8814f * cbf;
            } else {
                r = yf + 1.402f * crf; g = yf - .344136f * cbf - .714136f * crf; b = yf + 1.772f * cbf;
            }
            r = linear(r); g = linear(g); b = linear(b);
            if (!p.encoded && p.hlg) {
                const float yr = p.primaries == 9 ? .2627f : p.primaries == 12 ? .2289746f : .2126f;
                const float yb = p.primaries == 9 ? .0593f : p.primaries == 12 ? .0792869f : .0722f;
                const float scene_y = (yr * r + (1.f - yr - yb) * g + yb * b) / 12.f;
                const float gain = std::pow(std::max(0.f, scene_y), .2f) * (1000.f / (12.f * 203.f));
                r *= gain; g *= gain; b *= gain;
            }
            if (!p.encoded && !p.preserve_encoded_color) {
                const float sr = r, sg = g, sb = b;
                if (p.gamut != 0) { r = std::max(0.f,r); g = std::max(0.f,g); b = std::max(0.f,b); }
                else if (p.source_bt2020) {
                    r = 1.6604910021f*sr - .5876411388f*sg - .0728498633f*sb;
                    g = -.1245504745f*sr + 1.1328998971f*sg - .0083494226f*sb;
                    b = -.0181507634f*sr - .1005788980f*sg + 1.1187296614f*sb;
                } else if (p.primaries == 12) {
                    r = 1.2249401763f*sr - .2249401763f*sg;
                    g = -.0420569547f*sr + 1.0420569547f*sg;
                    b = -.0196375546f*sr - .0786360456f*sg + 1.0982736001f*sb;
                }
            }
            peak = std::max(peak, std::max(r, std::max(g,b)));
            store((size_t(oy) * p.output_width + ox) * 4, r, g, b);
        }
    }
    return peak;
}
