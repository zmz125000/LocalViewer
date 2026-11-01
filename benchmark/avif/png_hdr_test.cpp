// Small host regression for the production PNG transfer conversion (no Android runtime).
#include "../../app/src/main/cpp/avif_hdr.cpp"
#include <cstdio>
#include <stdexcept>

static void near(float actual, float expected, const char* message) {
    if (std::abs(actual - expected) > std::max(.002f, expected * .002f)) throw std::runtime_error(message);
}

int main() try {
    const auto white = static_cast<uint16_t>(std::lround(linear_to_pq_code(1.f) * 65535.f));
    const uint16_t pq[] = {0, white, 65535, 32768};
    auto pixels = png_hdr_linear_rgba(pq, 1, 16);
    near(half_to_float(pixels[0]), 0, "PQ black");
    near(half_to_float(pixels[1]), 1, "PQ 203-nit reference white");
    near(half_to_float(pixels[2]), 10000.f / 203.f, "PQ retains HDR headroom");
    near(half_to_float(pixels[3]), .5f, "Alpha is linear and straight");
    const uint16_t hlg[] = {0, 32768, 65535, 65535};
    pixels = png_hdr_linear_rgba(hlg, 1, 18);
    near(half_to_float(pixels[0]), 0, "HLG black");
    near(half_to_float(pixels[1]), 1, "HLG midpoint");
    near(half_to_float(pixels[2]), 12, "HLG headroom matches the reader's AVIF path");
    near(half_to_float(pixels[3]), 1, "Opaque alpha");
    std::puts("PNG PQ/HLG transfer and alpha tests passed");
} catch (const std::exception& error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
}
