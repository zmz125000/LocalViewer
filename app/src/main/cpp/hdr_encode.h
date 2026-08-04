#pragma once

#include <cstdint>
#include <vector>

#include <jni.h>

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
 * Linear RGB is assumed in [cg] primaries. [rgba] is mutated in-place when a
 * rematrix is required (no second full-frame buffer).
 *
 * Color policy:
 * - Default HDR: rematrix wide → BT.709/scRGB, RGBA_F16 linear.
 * - Advanced + HDR BT.2100: **keep BT.2020 primaries**, F16 linear (Kotlin tags
 *   linear-BT2020 / BT2020_PQ|HLG metadata via [out_transfer]).
 * - Advanced + SDR Display P3: **keep P3**, gamma OETF → RGBA_8888 (DISPLAY_P3).
 * - Advanced + SDR BT.709: RGBA_F16 linear scRGB (high bit depth).
 * - Default SDR: rematrix wide → 709, RGBA_8888 sRGB OETF.
 *
 * Memory contract (critical on 256 MiB Java heaps):
 * - **F16** (`*out_format == 1`): packed half-floats stay in [rgba];
 *   [out_pixels] is cleared. Caller copies [rgba] → Java then frees native.
 * - **8888** (`*out_format == 0`): [out_pixels] holds bytes; [rgba] is cleared
 *   and shrink_to_fit'd before return so peak is one full frame, not two.
 *
 * [force_hdr]: true when transfer is PQ/HLG (or similar absolute HDR).
 * [advanced_color]: reader advanced-color toggle (WCG preserve + high bit depth).
 * [transfer_cicp]: 16=PQ, 18=HLG, 0=other (passed through to [out_transfer]).
 *
 * @param out_format 0 = RGBA_8888, 1 = RGBA_F16
 * @param out_is_hdr  0/1
 * @param out_boost   content headroom linear (for setDesiredHdrHeadroom)
 * @param out_gamut   **pixel** gamut after pack: 0=BT.709/scRGB, 1=Display P3, 2=BT.2100
 * @param out_transfer CICP transfer of source (16/18/0) when pixels stay BT.2100
 * @return 0 OK
 */
int pack_linear_f16_for_direct(std::vector<uint16_t>& rgba, unsigned w, unsigned h, bool force_hdr,
                               uhdr_color_gamut_t cg, bool advanced_color, int transfer_cicp,
                               std::vector<uint8_t>& out_pixels, int* out_format, int* out_is_hdr,
                               float* out_boost, int* out_gamut, int* out_transfer);

/**
 * Pack [rgba] then copy into a Java byte[] for Bitmap.createBitmap.
 * Frees native staging before/after the Java allocation so peak is one full
 * frame of pixels on the native side, not two.
 *
 * Writes [jOutInfo] (len≥6) and [jOutBoost] (len≥1). Returns null on failure.
 */
jbyteArray pack_direct_to_jbyte_array(JNIEnv* env, std::vector<uint16_t>& rgba, unsigned w,
                                      unsigned h, bool force_hdr, uhdr_color_gamut_t cg,
                                      bool advanced_color, int transfer_cicp, jintArray jOutInfo,
                                      jfloatArray jOutBoost);
