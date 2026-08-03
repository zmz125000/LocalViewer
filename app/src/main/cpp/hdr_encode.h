#pragma once

#include <cstdint>
#include <vector>

#include "ultrahdr_api.h"

/**
 * Shared Ultra HDR JPEG encode (google/libultrahdr).
 * Linear half-float RGBA in the declared gamut, 1.0 ≈ SDR / 203 nits graphics white.
 *
 * Capacity / content boost:
 * - [fixed_peak_nits] ≤ 0: p99.99 MaxCLL-style pixel scan (full pages).
 * - [fixed_peak_nits] > 0: skip scan; use this MaxCLL in nits (thumbs: 1000).
 *
 * [cg] must match the primaries of [rgba] (no silent rematrix inside encode):
 *   UHDR_CG_BT_709     — BT.709 / scRGB-like
 *   UHDR_CG_DISPLAY_P3 — Display P3
 *   UHDR_CG_BT_2100    — BT.2020 primaries (PQ/HLG HDR stills)
 *
 * @return 0 on success.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path,
                                   uhdr_color_gamut_t cg = UHDR_CG_BT_709,
                                   float fixed_peak_nits = 0.f);

/** Downscale packed RGBA F16 so long edge ≤ max_edge (box filter). max_edge 0 = no-op. */
void scale_rgba_f16_max_edge(std::vector<uint16_t>& rgba, unsigned& w, unsigned& h,
                             unsigned max_edge);
