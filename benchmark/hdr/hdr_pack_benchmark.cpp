#include "hdr_encode.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <stdexcept>

static void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

struct Packed {
    std::vector<uint16_t> rgba;
    std::vector<uint8_t> sdr;
    int format = 0, hdr = 0, gamut = 0, transfer = 0;
    float boost = 0;
};

static Packed pack(const std::vector<uint16_t>& rgba, unsigned w, unsigned h,
                   uhdr_color_gamut_t cg, bool advanced, bool force_hdr, unsigned threads) {
    Packed out;
    out.rgba = rgba;
    require(pack_linear_f16_for_direct(out.rgba, w, h, force_hdr, cg, advanced, 16,
        out.sdr, &out.format, &out.hdr, &out.boost, &out.gamut, &out.transfer, threads) == 0,
        "pack failed");
    return out;
}

static void parity(const std::vector<uint16_t>& input, unsigned w, unsigned h,
                   uhdr_color_gamut_t cg, bool advanced, bool force_hdr) {
    const auto serial = pack(input, w, h, cg, advanced, force_hdr, 1);
    const auto parallel = pack(input, w, h, cg, advanced, force_hdr, 4);
    require(serial.rgba == parallel.rgba && serial.sdr == parallel.sdr, "parallel pixel mismatch");
    require(serial.format == parallel.format && serial.hdr == parallel.hdr &&
        serial.boost == parallel.boost && serial.gamut == parallel.gamut &&
        serial.transfer == parallel.transfer, "parallel metadata mismatch");
}

static void test_half_codes() {
    std::vector<uint16_t> input(65536 * 4);
    for (unsigned i = 0; i < 65536; ++i) {
        input[4 * i] = input[4 * i + 1] = input[4 * i + 2] = i;
        input[4 * i + 3] = 0x3c00;
    }
    const auto out = pack(input, 256, 256, UHDR_CG_BT_709, true, true, 1);
    for (unsigned i = 0; i < 65536; ++i) {
        float v = half_to_float(i);
        if (!std::isfinite(v) || v < 0) v = 0;
        v = std::min(v, kMaxLinear);
        require(out.rgba[4 * i] == float_to_half(v), "opaque F16 clamp changed");
    }

    // Verify encoded-alpha multiplication after sRGB OETF, including arbitrary
    // invalid half codes. Transparent RGB must not accidentally select HDR.
    for (unsigned i = 0; i < 65536; ++i) input[4 * i + 3] = 0;
    const auto transparent = pack(input, 256, 256, UHDR_CG_BT_709, false, false, 1);
    require(transparent.format == 0 && transparent.hdr == 0, "transparent RGB forced HDR");
    require(std::all_of(transparent.sdr.begin(), transparent.sdr.end(), [](uint8_t v) { return v == 0; }),
            "transparent output is not black");
    for (unsigned i = 0; i < 65536; ++i) {
        input[4 * i] = input[4 * i + 1] = input[4 * i + 2] = float_to_half((i % 1024) / 1023.f);
        input[4 * i + 3] = i;
    }
    const auto sdr = pack(input, 256, 256, UHDR_CG_BT_709, false, false, 1);
    for (unsigned i = 0; i < 65536; ++i) {
        float a = half_to_float(input[4 * i + 3]);
        if (!std::isfinite(a) || a < 0) a = 0;
        a = std::min(1.f, a);
        const uint8_t expected = static_cast<uint8_t>(srgb_oetf(half_to_float(input[4 * i])) * a * 255.f + 0.5f);
        require(sdr.sdr[4 * i] == expected, "SDR transfer/alpha changed");
        require(sdr.sdr[4 * i + 3] == static_cast<uint8_t>(a * 255.f + 0.5f), "SDR alpha changed");
    }
}

static void benchmark(unsigned w, unsigned h, bool advanced, bool transparent, bool hdr) {
    std::vector<uint16_t> input(size_t(w) * h * 4);
    for (size_t i = 0; i < input.size() / 4; ++i) {
        input[4 * i] = float_to_half((i % 1024) / (hdr ? 128.f : 1023.f));
        input[4 * i + 1] = float_to_half((i % 641) / 641.f);
        input[4 * i + 2] = float_to_half((i % 313) / 313.f);
        input[4 * i + 3] = float_to_half(transparent ? (i % 257) / 256.f : 1.f);
    }
    for (auto cg : {UHDR_CG_BT_709, UHDR_CG_DISPLAY_P3, UHDR_CG_BT_2100}) {
        parity(input, w, h, cg, advanced, hdr);
    }
    for (unsigned threads : {1u, 4u}) {
        auto out = input;
        std::vector<uint8_t> sdr;
        int format = 0;
        const auto start = std::chrono::steady_clock::now();
        require(pack_linear_f16_for_direct(out, w, h, hdr, UHDR_CG_BT_709, advanced, 16,
            sdr, &format, nullptr, nullptr, nullptr, nullptr, threads) == 0, "benchmark pack failed");
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        std::printf("%ux%u format=%s alpha=%s threads=%u %.1f ms\n", w, h,
            format == 1 ? "F16" : "8888", transparent ? "mixed" : "opaque", threads, ms);
    }
}

int main() {
    try {
        test_half_codes();
        benchmark(4096, 3072, true, false, true);
        benchmark(4096, 3072, true, true, true);
        benchmark(4096, 3072, false, false, false);
        benchmark(4096, 3072, false, true, false);
        std::puts("HDR pack regression checks passed");
    } catch (const std::exception& error) {
        std::fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
