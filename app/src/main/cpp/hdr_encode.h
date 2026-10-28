#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include <jni.h>

#include "ultrahdr_api.h"

/** One captured export quality selection; existing native callers default to High. */
struct StillEncodeQuality {
    int preset;
    float custom_distance;
    int custom_quality;
    int custom_avif_depth;
    bool custom_downsize_gain_map;

    StillEncodeQuality(int p = 1, float distance = .5f, int quality = 90, int avif_depth = 10,
                       bool downsize_gain_map = false)
        : preset(p < 0 ? 0 : p > 4 ? 4 : p),
          custom_distance(std::isfinite(distance) ? std::fmin(3.f, std::fmax(0.f, distance)) : .5f),
          custom_quality(quality < 1 ? 1 : quality > 100 ? 100 : quality),
          custom_avif_depth(avif_depth == 12 ? 12 : 10),
          custom_downsize_gain_map(downsize_gain_map) {}

    int jpeg() const {
        constexpr int values[] = {95, 95, 90, 85};
        return preset == 4 ? custom_quality : values[preset];
    }
    int avif() const {
        constexpr int values[] = {100, 90, 85, 80};
        return preset == 4 ? custom_quality : values[preset];
    }
    float jxl_distance() const {
        constexpr float values[] = {0.f, .5f, 1.f, 1.5f};
        return preset == 4 ? custom_distance : values[preset];
    }
    bool avif_lossless() const { return avif() == 100; }
    unsigned avif_depth() const { return preset == 4 ? custom_avif_depth : (preset == 0 || preset == 1) ? 12 : 10; }
    unsigned jpeg_gain_map_scale() const { return gain_map_scale(); }
    unsigned avif_gain_map_scale() const { return gain_map_scale(); }

private:
    unsigned gain_map_scale() const {
        return preset == 4 ? (custom_downsize_gain_map ? 2 : 1) : (preset == 2 || preset == 3) ? 2 : 1;
    }
};

/** IEEE754 binary16 from float (round-to-nearest, portable). */
inline uint16_t float_to_half(float f) {
    union {
        float f;
        uint32_t u;
    } v{f};
    uint32_t x = v.u;
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffffu;
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        uint32_t t = 14 - exp;
        uint32_t m = mant >> t;
        if ((mant >> (t - 1)) & 1u) m++;
        return static_cast<uint16_t>(sign | m);
    }
    if (exp >= 31) {
        if (mant) return static_cast<uint16_t>(sign | 0x7e00u);
        return static_cast<uint16_t>(sign | 0x7c00u);
    }
    uint32_t half = sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) half++;
    return static_cast<uint16_t>(half);
}

inline float half_to_float(uint16_t h) {
    const uint32_t sign = (static_cast<uint32_t>(h) & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t mant = h & 0x3ffu;
    uint32_t out;
    if (exp == 0) {
        if (mant == 0) {
            out = sign;
        } else {
            exp = 1;
            while ((mant & 0x400u) == 0) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ffu;
            uint32_t e = (127 - 15 + exp) << 23;
            out = sign | e | (mant << 13);
        }
    } else if (exp == 31) {
        out = sign | 0x7f800000u | (mant << 13);
    } else {
        out = sign | ((exp + (127 - 15)) << 23) | (mant << 13);
    }
    union {
        uint32_t u;
        float f;
    } v{out};
    return v.f;
}

/**
 * Shared contract for native still decode, direct Bitmap pack, and JPEG/UHDR encode.
 *
 * Every decoder must hand off straight-alpha RGBA F16 in linear light. [cg] names
 * the actual RGB primaries of those samples; it is never a source-profile hint.
 * 1.0 is SDR reference white (203 nits), HDR is represented by values above 1.0,
 * and transfer conversion must already be complete before this API is called.
 * JPEG/UHDR callers flatten alpha once. Direct Bitmap pack preserves alpha and
 * premultiplies RGB exactly once, as required by Android's drawing pipeline.
 *
 * Capacity / content boost (Ultra HDR only):
 * - [fixed_peak_nits] ≤ 0: p99.99 MaxCLL-style pixel scan (full pages).
 * - [fixed_peak_nits] > 0: skip scan; use this MaxCLL in nits.
 *   Thumbs: [kSdrWhiteNits] when known SDR, [kHdrThumbNits] when known HDR — no scan.
 * - displayRatioForFullHdr is set via target_display_peak_brightness (trust this).
 * - ratioMax may stay ~49 under libultrahdr API-0 HDR-raw one-pass — do not assume
 *   set_min_max_content_boost fully content-matches the gain-map range.
 *
 * Pure SDR ([force_hdr] false and content peak ≤ 1.0): **baseline JPEG** (no gain map),
 * so tools report ratio 1.0 / no Ultra HDR metadata. libultrahdr would otherwise
 * epsilon-bump max boost to ~1.07 when min==max.
 *
 * Encode quality (full page, maxEdge=0 — no downscale):
 *   - JPEG quality: 95/95/90/85 for lossless/high/mid/low; default high.
 *   - Baseline and Ultra HDR base: 4:4:4 chroma (libjpeg-turbo).
 *   - Ultra HDR gain map follows the selected quality, UHDR_USAGE_BEST_QUALITY.
 * Thumbs (maxEdge>0) scale long edge first, then same encode; browse platform
 * thumbs use Bitmap JPEG Q85 @ 768px separately.
 *
 * Baseline SDR color:
 *   - Keep BT.709, Display P3, or BT.2020 primaries.
 *   - sRGB OETF plus a matching ICC profile.
 *   - Rematrix to BT.709 only when that profile cannot be built.
 *
 * [cg] must match the primaries of [rgba] for Ultra HDR encode:
 *   UHDR_CG_BT_709     — BT.709 / scRGB-like
 *   UHDR_CG_DISPLAY_P3 — Display P3
 *   UHDR_CG_BT_2100    — BT.2020 primaries (PQ/HLG HDR stills)
 * [sdr_base], when supplied, is opaque sRGB-transfer RGBA8 in the same primaries
 * and dimensions. RAW uses its camera-look render before HDR highlight expansion.
 * [quality]: 0/1/2/3 use JPEG Q95/Q95/Q90/Q85 (lossless/high/mid/low).
 * Exports use 4:4:4; Mid and Low halve gain-map dimensions.
 * The primary image dimensions, gamut and HDR capacity remain unchanged.
 * scrgb_sdr restores an untagged scRGB source's 80-nit reference only for the SDR base.
 *
 * @return 0 on success.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path,
                                   uhdr_color_gamut_t cg = UHDR_CG_BT_709,
                                   float fixed_peak_nits = 0.f, bool force_hdr = false,
                                   const uint8_t* sdr_base = nullptr, StillEncodeQuality quality = 1,
                                   bool scrgb_sdr = false);

/** Downscale packed RGBA F16 so long edge ≤ max_edge (box filter). max_edge 0 = no-op. */
void scale_rgba_f16_max_edge(std::vector<uint16_t>& rgba, unsigned& w, unsigned& h,
                             unsigned max_edge);

// ── Shared scRGB / Ultra HDR constants (libultrahdr kSdrWhiteNits / kPqMaxNits) ──
constexpr float kSdrWhiteNits = 203.0f;
constexpr float kMaxNits = 10000.0f;
/** Nominal max LINEAR half value: 10000/203 ≈ 49.26 (ultrahdr_api.h). */
constexpr float kMaxLinear = kMaxNits / kSdrWhiteNits;

/** ST 2084 OETF. Linear working RGB uses 1.0 = 203 nits, PQ uses 1.0 = 10000 nits. */
inline float linear_to_pq_code(float linear) {
    double luminance = static_cast<double>(linear) * kSdrWhiteNits / kMaxNits;
    if (!(luminance > 0.0)) return 0.f;
    luminance = std::fmin(luminance, 1.0);
    const double p = std::pow(luminance, 2610.0 / 16384.0);
    return static_cast<float>(std::pow((3424.0 / 4096.0 + (2413.0 / 128.0) * p) /
                                      (1.0 + (2392.0 / 128.0) * p), 2523.0 / 32.0));
}

/** Fixed MaxCLL for known-HDR thumbs (no peak scan). */
constexpr float kHdrThumbNits = 1000.0f;

/** Thumb fixed peak: 203 for known SDR, 1000 for known HDR. */
inline float thumb_fixed_peak_nits(bool force_hdr) {
    return force_hdr ? kHdrThumbNits : kSdrWhiteNits;
}

/**
 * Content peak of max(R,G,B) in linear F16 (1.0 ≈ [kSdrWhiteNits] nits).
 * 99.99th percentile MaxCLL-style (rejects fireflies). Used by full-page encode
 * and by JXL/JXR thumbs after resize.
 */
float scan_scrgb_peak(const uint16_t* rgba, size_t pixel_count);

/** Nits for thumb encode from scanned linear peak (clamped). */
inline float thumb_peak_nits_from_linear(float peak_linear) {
    float nits = kSdrWhiteNits * peak_linear;
    if (nits < kSdrWhiteNits) nits = kSdrWhiteNits;
    if (nits > kMaxNits) nits = kMaxNits;
    return nits;
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

/** Exact transfer values for every half code, shared by display and export. */
inline const std::vector<float>& half_srgb_table() {
    static const std::vector<float> values = [] {
        std::vector<float> table(65536);
        for (size_t i = 0; i < table.size(); ++i) table[i] = srgb_oetf(half_to_float(i));
        return table;
    }();
    return values;
}

inline const std::vector<uint8_t>& half_srgb_u8_table() {
    static const std::vector<uint8_t> values = [] {
        const auto& curve = half_srgb_table();
        std::vector<uint8_t> table(65536);
        for (size_t i = 0; i < table.size(); ++i) table[i] = static_cast<uint8_t>(curve[i] * 255.f + 0.5f);
        return table;
    }();
    return values;
}

inline uint8_t half_to_srgb_u8(uint16_t code) { return half_srgb_u8_table()[code]; }

// HDR working samples use 203-nit units. Untagged scRGB's SDR rendition uses
// its original 80-nit reference instead; never apply this scale to HDR samples.
inline const std::vector<uint8_t>& half_srgb_u8_table(bool scrgb_sdr) {
    if (!scrgb_sdr) return half_srgb_u8_table();
    static const std::vector<uint8_t> values = [] {
        std::vector<uint8_t> table(65536);
        for (size_t i = 0; i < table.size(); ++i) {
            table[i] = linear_to_srgb_u8(half_to_float(i) * (kSdrWhiteNits / 80.f));
        }
        return table;
    }();
    return values;
}

/** Linear sRGB/BT.709 D65 -> BT.2020 D65. Keep signed input until after this matrix. */
inline void linear_bt709_to_bt2020(float& r, float& g, float& b) {
    const float nr = 0.627403896f * r + 0.329283038f * g + 0.043313066f * b;
    const float ng = 0.069097289f * r + 0.919540395f * g + 0.011362316f * b;
    const float nb = 0.016391439f * r + 0.088013308f * g + 0.895595253f * b;
    r = nr;
    g = ng;
    b = nb;
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
 * Linear straight-alpha RGB is assumed in [cg] primaries. [rgba] is mutated
 * in-place for any rematrix and for Android premultiplication (no second frame).
 *
 * Color policy:
 * - Default HDR: rematrix wide → BT.709/scRGB, RGBA_F16 linear.
 * - Advanced + BT.2100: **keep BT.2020 primaries**, RGBA_F16 linear.
 * - Advanced + Display P3: **keep P3 primaries**, RGBA_F16 linear.
 * - Advanced + BT.709: RGBA_F16 linear scRGB.
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
                               float* out_boost, int* out_gamut, int* out_transfer,
                               unsigned max_threads = 0);

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

/** Straight-alpha linear F16 → JXL. PQ uses uint16 codes, linear uses F16. Mutates RGBA. */
int encode_jxl_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t cg,
                   bool hdr, const char* path, StillEncodeQuality quality = 1, bool pq = false,
                   bool scrgb_sdr = false);

/** Straight-alpha sRGB-curve RGBA8 → 8-bit SDR JXL. */
int encode_jxl_u8(unsigned w, unsigned h, const uint8_t* rgba, uhdr_color_gamut_t cg,
                  const char* path, StillEncodeQuality quality = 1);

/** AVIF: mode 0 SDR, 1 PQ, 2 ISO 21496-1 SDR base + HDR gain map.
 * F16 is linear, straight alpha, with 1.0 = 203 nits; sdr_base is optional RGBA8.
 * Deep sources use 12 bits for Lossless/High, 10 for Mid/Low, or the custom depth, with 4:4:4 sampling.
 * scrgb_sdr affects only SDR output/the gain-map base, never absolute PQ or CLLI.
 */
int encode_avif_f16(unsigned w, unsigned h, uint16_t* rgba, uhdr_color_gamut_t cg,
                    int mode, const char* path, StillEncodeQuality quality, const uint8_t* sdr_base = nullptr,
                    bool scrgb_sdr = false);
int encode_avif_u8(unsigned w, unsigned h, const uint8_t* rgba, uhdr_color_gamut_t cg,
                   const char* path, StillEncodeQuality quality);
