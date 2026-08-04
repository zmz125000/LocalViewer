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

/**
 * Pack linear F16 RGBA (1.0 ≈ SDR / 203 nits) for direct Android Bitmap present
 * (skip Ultra HDR JPEG convert).
 *
 * Linear RGB is assumed in [cg] primaries.
 *
 * Color policy:
 * - HDR (or peak > 1.25): rematrix wide → BT.709/scRGB, RGBA_F16 linear.
 * - Advanced [advanced_color] + SDR Display P3: **keep P3**, gamma OETF → RGBA_8888
 *   (Kotlin tags DISPLAY_P3; needs window COLOR_MODE_WIDE_COLOR_GAMUT).
 * - Advanced + SDR BT.709: RGBA_F16 linear scRGB (high bit depth).
 * - Default SDR: rematrix wide → 709, RGBA_8888 sRGB OETF.
 *
 * [force_hdr]: true when transfer is PQ/HLG (or similar absolute HDR).
 * [advanced_color]: reader advanced-color toggle (WCG preserve + high bit depth).
 *
 * @param out_format 0 = RGBA_8888, 1 = RGBA_F16
 * @param out_is_hdr  0/1
 * @param out_boost   content headroom linear (for setDesiredHdrHeadroom)
 * @param out_gamut   **pixel** gamut after pack: 0=BT.709/scRGB, 1=Display P3, 2=BT.2100
 * @return 0 OK
 */
int pack_linear_f16_for_direct(const uint16_t* rgba, unsigned w, unsigned h, bool force_hdr,
                               uhdr_color_gamut_t cg, bool advanced_color,
                               std::vector<uint8_t>& out_pixels, int* out_format, int* out_is_hdr,
                               float* out_boost, int* out_gamut);
