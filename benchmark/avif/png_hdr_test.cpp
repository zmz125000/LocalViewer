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
    auto pixels = png_hdr_linear_rgba(pq, 1, 16, UHDR_CG_BT_2100);
    near(half_to_float(pixels[0]), 0, "PQ black");
    near(half_to_float(pixels[1]), 1, "PQ 203-nit reference white");
    near(half_to_float(pixels[2]), 10000.f / 203.f, "PQ retains HDR headroom");
    near(half_to_float(pixels[3]), .5f, "Alpha is linear and straight");
    const uint16_t hlg[] = {
        0, 0, 0, 65535,
        65535, 65535, 65535, 65535,
        0, 32768, 65535, 32768,
    };
    const uhdr_color_gamut_t gamuts[] = {UHDR_CG_BT_709, UHDR_CG_DISPLAY_P3, UHDR_CG_BT_2100};
    // Independent reference luminance coefficients for 709, P3-D65, and 2020.
    const float green_y[] = {0.7152f, 0.6917385f, 0.6780f};
    const float blue_y[] = {0.0722f, 0.0792869f, 0.0593f};
    for (unsigned gamut = 0; gamut < 3; ++gamut) {
        pixels = png_hdr_linear_rgba(hlg, 3, 18, gamuts[gamut]);
        for (unsigned channel = 0; channel < 3; ++channel) {
            near(half_to_float(pixels[channel]), 0, "HLG black");
            near(half_to_float(pixels[4 + channel]), 1000.f / 203.f, "HLG 1000-nit neutral white");
        }
        near(half_to_float(pixels[7]), 1, "Opaque alpha");
        // At code 0.5 scene green is 1/12, while full-scale scene blue is 1.
        // The OOTF scales all RGB by Y^0.2, using the source gamut's luminance.
        const float scale = std::pow(green_y[gamut] / 12.f + blue_y[gamut], .2f) * (1000.f / 203.f);
        near(half_to_float(pixels[8]), 0, "HLG colored sample red");
        near(half_to_float(pixels[9]), scale / 12.f, "HLG colored sample green uses source luminance");
        near(half_to_float(pixels[10]), scale, "HLG colored sample blue uses source luminance");
        near(half_to_float(pixels[11]), .5f, "HLG alpha stays linear and RGB stays straight");
    }
    std::puts("PNG PQ/HLG transfer and alpha tests passed");
} catch (const std::exception& error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
}
