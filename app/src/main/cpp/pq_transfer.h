#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

namespace pq_transfer {

// Linear inputs and outputs use 1.0 = 10000 nits. Callers choose their own
// clipping and black conventions.
inline double encode_exact(double linear) {
    const double p = std::pow(linear, .1593017578125);
    return std::pow((.8359375 + 18.8515625 * p) / (1. + 18.6875 * p), 78.84375);
}

inline double decode_exact(double code) {
    const double p = std::pow(code, 1. / 78.84375);
    return std::pow(std::max(p - .8359375, 0.) / std::max(18.8515625 - 18.6875 * p, 1e-12),
                    1. / .1593017578125);
}

inline double encode(double linear) {
    if (!(linear > 0.)) {
        static const double black = encode_exact(0.);
        return black;
    }
    if (linear >= 1. || linear < 0x1p-30) return encode_exact(linear);

    // Interpolate on 512 float mantissa steps per octave, down to 2^-30.
    constexpr unsigned shift = 23 - 9;
    static_assert(sizeof(float) == sizeof(uint32_t) && std::numeric_limits<float>::is_iec559);
    constexpr uint32_t first = (127u - 30u) << 9;
    constexpr uint32_t last = 127u << 9;
    constexpr unsigned mask = (1u << shift) - 1;
    static const auto table = [] {
        std::array<double, last - first + 1> values{};
        for (uint32_t key = first; key <= last; ++key) {
            const uint32_t bits = key << shift;
            float sample;
            std::memcpy(&sample, &bits, sizeof(sample));
            values[key - first] = encode_exact(sample);
        }
        return values;
    }();

    const float sample = float(linear);
    uint32_t bits;
    std::memcpy(&bits, &sample, sizeof(bits));
    const uint32_t index = (bits >> shift) - first;
    if (index == last - first) return 1.;
    const double fraction = (bits & mask) * (1. / (1u << shift));
    return table[index] + (table[index + 1] - table[index]) * fraction;
}

inline double decode(double code) {
    constexpr unsigned steps = 8192;
    if (!(code > 0.)) return 0.;
    if (code >= 1. || code < 1. / steps) return decode_exact(code);
    static const auto table = [] {
        std::array<double, steps + 1> values{};
        for (unsigned i = 0; i <= steps; ++i) values[i] = decode_exact(double(i) / steps);
        return values;
    }();
    const double position = code * steps;
    const auto index = unsigned(position);
    return table[index] + (table[index + 1] - table[index]) * (position - index);
}

} // namespace pq_transfer
