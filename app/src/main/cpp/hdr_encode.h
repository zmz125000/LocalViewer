#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include <jni.h>

#include "ultrahdr_api.h"

/**
 * Shared Ultra HDR / baseline JPEG encode (google/libultrahdr + libjpeg-turbo).
 * Linear half-float RGBA in the declared gamut, 1.0 ≈ SDR / 203 nits graphics white.
 *
 * Capacity / content boost (Ultra HDR only):
 * - [fixed_peak_nits] ≤ 0: p99.99 MaxCLL-style pixel scan (full pages).
 * - [fixed_peak_nits] > 0: skip scan; use this MaxCLL in nits.
 *   Thumbs: [kSdrWhiteNits] when known SDR, [kHdrThumbNits] when known HDR — no scan.
 *
 * Pure SDR ([force_hdr] false and content peak ≤ 1.0): **baseline JPEG** (no gain map),
 * so tools report ratio 1.0 / no Ultra HDR metadata. libultrahdr would otherwise
 * epsilon-bump max boost to ~1.07 when min==max.
 *
 * Baseline SDR color:
 *   - Display P3: keep primaries + embed P3 ICC (WCG-capable convert for Coil).
 *   - BT.2100 pure SDR: rematrix → 709 (no PQ/HLG in baseline).
 *   - BT.709: sRGB OETF.
 *
 * [cg] must match the primaries of [rgba] for Ultra HDR encode:
 *   UHDR_CG_BT_709     — BT.709 / scRGB-like
 *   UHDR_CG_DISPLAY_P3 — Display P3
 *   UHDR_CG_BT_2100    — BT.2020 primaries (PQ/HLG HDR stills)
 *
 * @return 0 on success.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path,
                                   uhdr_color_gamut_t cg = UHDR_CG_BT_709,
                                   float fixed_peak_nits = 0.f, bool force_hdr = false);

/** Downscale packed RGBA F16 so long edge ≤ max_edge (box filter). max_edge 0 = no-op. */
void scale_rgba_f16_max_edge(std::vector<uint16_t>& rgba, unsigned& w, unsigned& h,
                             unsigned max_edge);

// ── Shared scRGB / Ultra HDR constants (libultrahdr kSdrWhiteNits / kPqMaxNits) ──
constexpr float kSdrWhiteNits = 203.0f;
constexpr float kMaxNits = 10000.0f;
/** Nominal max LINEAR half value: 10000/203 ≈ 49.26 (ultrahdr_api.h). */
constexpr float kMaxLinear = kMaxNits / kSdrWhiteNits;
/** Fixed MaxCLL for known-HDR thumbs (no peak scan). */
constexpr float kHdrThumbNits = 1000.0f;

/** Thumb fixed peak: 203 for known SDR, 1000 for known HDR — never scan. */
inline float thumb_fixed_peak_nits(bool force_hdr) {
    return force_hdr ? kHdrThumbNits : kSdrWhiteNits;
}

// ── IEC 61966-2-1 sRGB transfer (shared by pack + JXL/AVIF decode) ───────────

/** Encoded sRGB [0,1] → linear light [0,1]. */
inline float srgb_eotf(float s) {
    if (!std::isfinite(s) || s <= 0.f) return 0.f;
    if (s >= 1.f) return 1.f;
    if (s <= 0.04045f) return s / 12.92f;
    return std::pow((s + 0.055f) / 1.055f, 2.4f);
}

/** Linear light [0,1+] → encoded sRGB [0,1] (clamps above 1). */
inline float srgb_oetf(float l) {
    if (!std::isfinite(l) || l <= 0.f) return 0.f;
    if (l >= 1.f) return 1.f;
    if (l <= 0.0031308f) return l * 12.92f;
    return 1.055f * std::pow(l, 1.f / 2.4f) - 0.055f;
}

inline uint8_t linear_to_srgb_u8(float l) {
    const float s = srgb_oetf(l);
    return static_cast<uint8_t>(s * 255.f + 0.5f);
}

// ── Linear RGB gamut rematrix (D65, no CAT) ─────────────────────────────────
//
// Built as:  M = XYZ_to_linear_sRGB × source_to_XYZ
// using CSS Color Module Level 4 sample matrices (same primaries as BT.709 for
// the sRGB/709 end, Display P3, and Rec.2020 / BT.2100):
//   https://www.w3.org/TR/css-color-4/#color-conversion-code
// Both ends share D65, so no Bradford chromatic adaptation is applied.
// Row-major: [R' G' B']^T = M × [R G B]^T in **linear** light.

/** Linear Display P3 → linear BT.709 / sRGB. */
inline void linear_p3_to_bt709(float& r, float& g, float& b) {
    const float nr = 1.2249401763f * r + -0.2249401763f * g + 0.0000000000f * b;
    const float ng = -0.0420569547f * r + 1.0420569547f * g + 0.0000000000f * b;
    const float nb = -0.0196375546f * r + -0.0786360456f * g + 1.0982736001f * b;
    r = nr;
    g = ng;
    b = nb;
}

/** Linear BT.2020 / BT.2100 primaries → linear BT.709 / sRGB. */
inline void linear_bt2020_to_bt709(float& r, float& g, float& b) {
    const float nr = 1.6604910021f * r + -0.5876411388f * g + -0.0728498633f * b;
    const float ng = -0.1245504745f * r + 1.1328998971f * g + -0.0083494226f * b;
    const float nb = -0.0181507634f * r + -0.1005788980f * g + 1.1187296614f * b;
    r = nr;
    g = ng;
    b = nb;
}

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
