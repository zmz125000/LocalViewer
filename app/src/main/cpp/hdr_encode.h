#pragma once

#include <cstdint>

/**
 * Shared Ultra HDR JPEG encode (google/libultrahdr).
 * Linear scRGB half-float RGBA, 1.0 ≈ SDR / 203 nits graphics white.
 * Content-matched hdr_capacity_max (peak boost), not default 10000/203.
 *
 * @return 0 on success.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path);
