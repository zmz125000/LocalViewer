/*
 * JPEG XR / HDR raster → Ultra HDR JPEG via libultrahdr.
 * JNI: com.hippo.ehviewer.jni.HdrConvertKt
 */
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include "hdr_encode.h"
#include "ultrahdr_api.h"

// jxrlib (Microsoft / brion jpegxr packaging)
extern "C" {
#include "JXRGlue.h"
}
// JXRGlue.h defines min/max as macros (Windows-style) — breaks std::min/max.
#ifdef min
#undef min
#endif
#ifdef max
#undef max
#endif

#define LOG_TAG "HdrConvert"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// IEEE754 half from float (round-to-nearest-even, simple portable).
uint16_t float_to_half(float f) {
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
        // Inf/NaN → max finite or Inf
        if (mant) return static_cast<uint16_t>(sign | 0x7e00u);
        return static_cast<uint16_t>(sign | 0x7c00u);
    }
    uint32_t half = sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) half++;  // round
    return static_cast<uint16_t>(half);
}

float half_to_float(uint16_t h) {
    const uint32_t sign = (static_cast<uint32_t>(h) & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t mant = h & 0x3ffu;
    uint32_t out;
    if (exp == 0) {
        if (mant == 0) {
            out = sign;
        } else {
            // subnormal
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
 * Content peak of max(R,G,B) in linear scRGB (1.0 ≈ SDR / 203 nits).
 *
 * Uses the **99.99th percentile** brightest max-component (not raw single-pixel max),
 * matching jxr_to_png / "On the Calculation and Usage of HDR Static Content Metadata"
 * (MaxCLL percentile). JXR half-float fireflies (peak 48 → ~9750 nits) otherwise
 * inflate hdr_capacity_max and collapse Android gain-map weight to SDR.
 *
 * Returns linear boost (peak_nits / 203), clamped to a camera-like range.
 */
// jxrlib JXRGlue.h defines min/max macros — avoid std::max/min after that include.
static inline float fmax3(float a, float b, float c) {
    float m = a > b ? a : b;
    return m > c ? m : c;
}

/** Percentile for content MaxCLL (0.9999 = top 0.01% of pixels). */
static constexpr double kContentPeakPercentile = 0.9999;

float scan_scrgb_peak(const uint16_t* rgba, size_t pixel_count) {
    // 1.0 = SDR white (203 nits). Do **not** floor above 1.0 — that made pure SDR
    // Ultra HDR files report hdr/sdr ratio 1.05 instead of 1.0.
    if (!rgba || pixel_count == 0) return 1.0f;

    // Histogram of max(R,G,B) as absolute nits (1.0 linear → 203 nits), 1-nit bins 0..10000.
    // Same spirit as jxr_to_png MAXCLL_PERCENTILE (there: BT.2100 linear 1.0 = 10000 nits).
    constexpr int kNitBins = 10001;  // 0..10000 inclusive
    std::vector<uint32_t> nit_counts(static_cast<size_t>(kNitBins), 0);
    int raw_max_nits = 0;

    for (size_t i = 0; i < pixel_count; i++) {
        const float r = half_to_float(rgba[i * 4 + 0]);
        const float g = half_to_float(rgba[i * 4 + 1]);
        const float b = half_to_float(rgba[i * 4 + 2]);
        float m = fmax3(r, g, b);
        if (!std::isfinite(m) || m <= 0.f) continue;
        // Absolute nits for histogram (same scale we feed libultrahdr).
        float nits_f = m * 203.0f;
        if (nits_f > 10000.0f) nits_f = 10000.0f;
        int nits = static_cast<int>(std::lround(nits_f));
        if (nits < 0) nits = 0;
        if (nits >= kNitBins) nits = kNitBins - 1;
        nit_counts[static_cast<size_t>(nits)]++;
        if (nits > raw_max_nits) raw_max_nits = nits;
    }

    // Walk from brightest bin until we have covered the top (1 - percentile) of pixels.
    const uint64_t count_target = static_cast<uint64_t>(
        std::llround((1.0 - kContentPeakPercentile) * static_cast<double>(pixel_count)));
    const uint64_t need = count_target < 1 ? 1 : count_target;
    uint64_t count = 0;
    int maxcll_nits = raw_max_nits;
    for (int idx = raw_max_nits; idx >= 0; idx--) {
        count += nit_counts[static_cast<size_t>(idx)];
        if (count >= need) {
            maxcll_nits = idx;
            break;
        }
    }

    // Linear content boost for Ultra HDR metadata (1.0 = no headroom / pure SDR).
    // libultrahdr allows max_boost == min_boost (1.0).
    float peak = static_cast<float>(maxcll_nits) / 203.0f;
    if (peak < 1.0f) peak = 1.0f;
    if (peak > 64.0f) peak = 64.0f;
    return peak;
}

bool guid_eq(const PKPixelFormatGUID& a, const PKPixelFormatGUID& b) {
    return memcmp(&a, &b, sizeof(PKPixelFormatGUID)) == 0;
}

enum class JxrPix {
    RgbaF32,
    RgbaF16,
    Rgb101010,
    Unsupported,
};

JxrPix classify_guid(const PKPixelFormatGUID& g) {
    if (guid_eq(g, GUID_PKPixelFormat128bppRGBAFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat128bppPRGBAFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat128bppRGBFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBAHalf)) return JxrPix::RgbaF16;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBHalf)) return JxrPix::RgbaF16;
    if (guid_eq(g, GUID_PKPixelFormat32bppRGB101010)) return JxrPix::Rgb101010;
    return JxrPix::Unsupported;
}

int bpp_of(JxrPix p) {
    switch (p) {
        case JxrPix::RgbaF32:
            return 16;
        case JxrPix::RgbaF16:
            return 8;
        case JxrPix::Rgb101010:
            return 4;
        default:
            return 0;
    }
}

/**
 * Configure decoder for full-frame decode in the given (native) pixel format.
 * Ported from branch `hdr` jxr_hdr.c — never force half→float via jxrlib Convert
 * (that path produces vertical stripe garbage).
 */
bool setup_full_frame(PKImageDecode* dec, PKPixelFormatGUID* fmt) {
    PKPixelInfo pi{};
    pi.pGUIDPixFmt = fmt;
    if (PixelFormatLookup(&pi, LOOKUP_FORWARD) != WMP_errSuccess) return false;

    if (!!(pi.grBit & PK_pixfmtHasAlpha)) {
        dec->WMP.wmiSCP.uAlphaMode = 2; /* image + alpha */
    } else {
        dec->WMP.wmiSCP.uAlphaMode = 0;
    }

    dec->WMP.wmiI.cfColorFormat = pi.cfColorFormat;
    dec->WMP.wmiI.bdBitDepth = pi.bdBitDepth;
    dec->WMP.wmiI.cBitsPerUnit = pi.cbitUnit;
    dec->WMP.wmiI.bRGB = !(pi.grBit & PK_pixfmtBGR);

    dec->WMP.wmiI.cThumbnailWidth = dec->WMP.wmiI.cWidth;
    dec->WMP.wmiI.cThumbnailHeight = dec->WMP.wmiI.cHeight;
    dec->WMP.wmiI.bSkipFlexbits = FALSE;
    dec->WMP.wmiI.cROILeftX = 0;
    dec->WMP.wmiI.cROITopY = 0;
    dec->WMP.wmiI.cROIWidth = dec->WMP.wmiI.cThumbnailWidth;
    dec->WMP.wmiI.cROIHeight = dec->WMP.wmiI.cThumbnailHeight;
    dec->WMP.wmiI.oOrientation = O_NONE;
    dec->WMP.wmiI.cPostProcStrength = 0;
    return true;
}

/**
 * Decode JXR from an in-memory buffer (no temp file).
 * Same approach as branch `hdr` HdrJxr: CreateStreamFromMemory + native pixel format.
 * SAF/local paths: Kotlin reads via Okio → bytes → this (skips copy-to-temp.jxr).
 */
bool decode_jxr_from_memory(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                            unsigned& w, unsigned& h) {
    if (!data || len == 0) return false;

    PKFactory* factory = nullptr;
    PKCodecFactory* codecFactory = nullptr;
    struct WMPStream* stream = nullptr;
    PKImageDecode* decoder = nullptr;
    bool ok = false;

    auto release_all = [&]() {
        if (decoder) {
            decoder->Release(&decoder);
            decoder = nullptr;
        }
        if (stream) {
            stream->Close(&stream);
            stream = nullptr;
        }
        if (codecFactory) {
            codecFactory->Release(&codecFactory);
            codecFactory = nullptr;
        }
        if (factory) {
            factory->Release(&factory);
            factory = nullptr;
        }
    };

    ERR err = PKCreateFactory(&factory, PK_SDK_VERSION);
    if (Failed(err) || !factory) {
        ALOGE("PKCreateFactory failed: %d", err);
        return false;
    }
    err = PKCreateCodecFactory(&codecFactory, WMP_SDK_VERSION);
    if (Failed(err) || !codecFactory) {
        ALOGE("PKCreateCodecFactory failed: %d", err);
        release_all();
        return false;
    }
    err = factory->CreateStreamFromMemory(&stream, const_cast<uint8_t*>(data), len);
    if (Failed(err) || !stream) {
        ALOGE("CreateStreamFromMemory failed: %d", err);
        release_all();
        return false;
    }
    err = PKImageDecode_Create_WMP(&decoder);
    if (Failed(err) || !decoder) {
        ALOGE("PKImageDecode_Create_WMP failed: %d", err);
        release_all();
        return false;
    }
    err = decoder->Initialize(decoder, stream);
    if (Failed(err)) {
        ALOGE("decoder Initialize failed: %d", err);
        release_all();
        return false;
    }
    /* Stream owned by decoder after Initialize (hdr branch). */
    decoder->fStreamOwner = 1;
    stream = nullptr;

    I32 width = 0, height = 0;
    err = decoder->GetSize(decoder, &width, &height);
    if (Failed(err) || width <= 0 || height <= 0 || width > 16384 || height > 16384) {
        ALOGE("GetSize failed/out of range: %d %dx%d", err, (int)width, (int)height);
        release_all();
        return false;
    }

    PKPixelFormatGUID guid{};
    err = decoder->GetPixelFormat(decoder, &guid);
    if (Failed(err)) {
        ALOGE("GetPixelFormat failed: %d", err);
        release_all();
        return false;
    }
    JxrPix pix = classify_guid(guid);
    if (pix == JxrPix::Unsupported) {
        ALOGE("Unsupported JXR pixel format");
        release_all();
        return false;
    }

    /* Decode in native format — no jxrlib format conversion. */
    if (!setup_full_frame(decoder, &guid)) {
        ALOGE("setup_full_frame failed");
        release_all();
        return false;
    }

    w = static_cast<unsigned>(decoder->WMP.wmiI.cROIWidth);
    h = static_cast<unsigned>(decoder->WMP.wmiI.cROIHeight);
    if (w == 0 || h == 0) {
        w = static_cast<unsigned>(width);
        h = static_cast<unsigned>(height);
    }

    const bool rgb_half = guid_eq(guid, GUID_PKPixelFormat64bppRGBHalf) ||
        guid_eq(guid, GUID_PKPixelFormat48bppRGBHalf);
    const bool rgba_half = guid_eq(guid, GUID_PKPixelFormat64bppRGBAHalf);
    const bool rgb_float = guid_eq(guid, GUID_PKPixelFormat128bppRGBFloat) ||
        guid_eq(guid, GUID_PKPixelFormat96bppRGBFloat);
    const bool rgba_float = guid_eq(guid, GUID_PKPixelFormat128bppRGBAFloat) ||
        guid_eq(guid, GUID_PKPixelFormat128bppPRGBAFloat);

    PKPixelInfo pi{};
    pi.pGUIDPixFmt = &guid;
    if (PixelFormatLookup(&pi, LOOKUP_FORWARD) != WMP_errSuccess || pi.cbitUnit == 0 ||
        (pi.cbitUnit % 8) != 0) {
        ALOGE("bad cbitUnit");
        release_all();
        return false;
    }
    const U32 bytes_per_pixel = pi.cbitUnit / 8u;
    const size_t stride = static_cast<size_t>(w) * static_cast<size_t>(bytes_per_pixel);
    const size_t buf_size = stride * static_cast<size_t>(h);
    std::vector<uint8_t> raw(buf_size);

    PKRect rect{};
    rect.X = 0;
    rect.Y = 0;
    rect.Width = static_cast<I32>(w);
    rect.Height = static_cast<I32>(h);
    err = decoder->Copy(decoder, &rect, raw.data(), static_cast<U32>(stride));
    if (Failed(err)) {
        ALOGE("Copy failed: %d", err);
        release_all();
        return false;
    }

    out_rgba.resize(static_cast<size_t>(w) * h * 4);
    if (rgba_half || rgb_half) {
        const size_t src_bpp = bytes_per_pixel;
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint16_t* p =
                    reinterpret_cast<const uint16_t*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                out_rgba[i * 4 + 0] = p[0];
                out_rgba[i * 4 + 1] = p[1];
                out_rgba[i * 4 + 2] = p[2];
                out_rgba[i * 4 + 3] = rgba_half ? p[3] : float_to_half(1.0f);
            }
        }
        ok = true;
    } else if (rgba_float || rgb_float) {
        const size_t src_bpp = bytes_per_pixel;
        const bool has_a = rgba_float;
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const float* p =
                    reinterpret_cast<const float*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                out_rgba[i * 4 + 0] = float_to_half(p[0]);
                out_rgba[i * 4 + 1] = float_to_half(p[1]);
                out_rgba[i * 4 + 2] = float_to_half(p[2]);
                out_rgba[i * 4 + 3] = float_to_half(has_a ? p[3] : 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgb101010) {
        for (unsigned i = 0; i < w * h; i++) {
            uint32_t p;
            memcpy(&p, raw.data() + i * 4, 4);
            float r = static_cast<float>((p >> 0) & 0x3ff) / 1023.0f;
            float g = static_cast<float>((p >> 10) & 0x3ff) / 1023.0f;
            float b = static_cast<float>((p >> 20) & 0x3ff) / 1023.0f;
            out_rgba[i * 4 + 0] = float_to_half(r);
            out_rgba[i * 4 + 1] = float_to_half(g);
            out_rgba[i * 4 + 2] = float_to_half(b);
            out_rgba[i * 4 + 3] = float_to_half(1.0f);
        }
        ok = true;
    } else {
        ALOGE("Unhandled JXR expand path");
    }

    release_all();
    return ok;
}

// Path helper: load whole file into memory then decode (no jxrlib file I/O).
bool decode_jxr_to_rgba_f16(const char* path, std::vector<uint16_t>& out_rgba, unsigned& w,
                            unsigned& h) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) {
        ALOGE("open JXR failed: %s", path);
        return false;
    }
    const auto sz = in.tellg();
    if (sz <= 0) return false;
    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> buf(static_cast<size_t>(sz));
    if (!in.read(reinterpret_cast<char*>(buf.data()), sz)) {
        ALOGE("read JXR failed: %s", path);
        return false;
    }
    return decode_jxr_from_memory(buf.data(), buf.size(), out_rgba, w, h);
}

}  // namespace

static bool write_file(const char* path, const void* data, size_t size) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

/**
 * Encode LINEAR half-float RGB (declared gamut) → Ultra HDR JPEG with **content-matched** capacity.
 *
 * Default libultrahdr LINEAR peak is 10000 nits → hdr_capacity_max ≈ 10000/203 ≈ 49.
 * On a phone with display boost ≈ 4, Android applies:
 *   weight = log(display) / log(capacity) ≈ log(4)/log(49) ≈ 0.25  → looks SDR.
 *
 * Match camera Ultra HDR: capacity ≈ content peak so weight ≈ 1 on phones.
 * Encode metadata is content-only — never bake panel/display boost into the file.
 *
 * [fixed_peak_nits] > 0: skip full-frame peak scan (thumbs use fixed MaxCLL e.g. 1000).
 * [fixed_peak_nits] ≤ 0: p99.99 scan of max(R,G,B) (full pages).
 *
 * [cg] must match [rgba] primaries. libultrahdr embeds a matching ICC on the base JPEG
 * (BT.709 / Display P3 / BT.2100). Do not rematrix here.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path, uhdr_color_gamut_t cg,
                                   float fixed_peak_nits) {
    uhdr_codec_private_t* enc = uhdr_create_encoder();
    if (!enc) {
        ALOGE("uhdr_create_encoder failed");
        return -1;
    }

    // Only the three gamuts libultrahdr can tag/ICC-embed.
    if (cg != UHDR_CG_BT_709 && cg != UHDR_CG_DISPLAY_P3 && cg != UHDR_CG_BT_2100) {
        ALOGI("Unknown gamut %d → BT.709 tag (caller must have converted RGB if needed)", (int)cg);
        cg = UHDR_CG_BT_709;
    }

    float content_peak;
    float peak_nits;
    if (fixed_peak_nits > 0.f) {
        // Thumbs / cheap path: fixed MaxCLL, no pixel histogram.
        peak_nits = fixed_peak_nits;
        if (peak_nits < 203.0f) peak_nits = 203.0f;
        if (peak_nits > 10000.0f) peak_nits = 10000.0f;
        content_peak = peak_nits / 203.0f;
        if (content_peak < 1.0f) content_peak = 1.0f;
    } else {
        const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
        // Full page: 99.99th-percentile max(R,G,B) (jxr_to_png / MaxCLL paper).
        content_peak = scan_scrgb_peak(rgba, pixels);
        peak_nits = 203.0f * content_peak;
        if (peak_nits < 203.0f) peak_nits = 203.0f;
        if (peak_nits > 10000.0f) peak_nits = 10000.0f;
    }

    uhdr_raw_image_t img{};
    img.fmt = UHDR_IMG_FMT_64bppRGBAHalfFloat;
    img.cg = cg;
    img.ct = UHDR_CT_LINEAR;
    img.range = UHDR_CR_FULL_RANGE;
    img.w = w;
    img.h = h;
    img.planes[UHDR_PLANE_PACKED] = const_cast<uint16_t*>(rgba);
    img.stride[UHDR_PLANE_PACKED] = w;

    uhdr_error_info_t err = uhdr_enc_set_raw_image(enc, &img, UHDR_HDR_IMG);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_raw_image: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -2;
    }

    // Quality: base + multi-channel gain map (closer to camera Ultra HDR).
    err = uhdr_enc_set_quality(enc, 92, UHDR_BASE_IMG);
    err = uhdr_enc_set_quality(enc, 95, UHDR_GAIN_MAP_IMG);
    err = uhdr_enc_set_using_multi_channel_gainmap(enc, 1);
    err = uhdr_enc_set_gainmap_gamma(enc, 1.0f);
    err = uhdr_enc_set_preset(enc, UHDR_USAGE_BEST_QUALITY);
    err = uhdr_enc_set_output_format(enc, UHDR_CODEC_JPG);

    // Content boost (linear): min=1 (SDR base), max=content peak.
    err = uhdr_enc_set_min_max_content_boost(enc, 1.0f, content_peak);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_min_max_content_boost: %s", err.has_detail ? err.detail : "error");
    }
    // Sets hdr_capacity_max ≈ peak_nits / 203 ≈ content_peak (not 49).
    err = uhdr_enc_set_target_display_peak_brightness(enc, peak_nits);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_target_display_peak_brightness: %s",
              err.has_detail ? err.detail : "error");
    }

    ALOGI("Ultra HDR encode content_peak=%.3f peak_nits=%.1f fixed=%d cg=%d %ux%u", content_peak,
          peak_nits, fixed_peak_nits > 0.f ? 1 : 0, (int)cg, w, h);

    err = uhdr_encode(enc);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_encode: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -3;
    }
    uhdr_compressed_image_t* stream = uhdr_get_encoded_stream(enc);
    if (!stream || !stream->data || stream->data_sz == 0) {
        ALOGE("uhdr_get_encoded_stream empty");
        uhdr_release_encoder(enc);
        return -4;
    }
    if (!write_file(out_path, stream->data, stream->data_sz)) {
        ALOGE("write Ultra HDR failed: %s", out_path);
        uhdr_release_encoder(enc);
        return -5;
    }
    ALOGI("Wrote Ultra HDR %ux%u peak=%.2f cg=%d → %s (%zu bytes)", w, h, content_peak, (int)cg,
          out_path, stream->data_sz);
    uhdr_release_encoder(enc);
    return 0;
}

namespace {

/** Linear RGB in Display P3 → BT.709 (D65). Matrices from IEC 61966-2-1 / CSS Color 4. */
inline void p3_to_bt709(float& r, float& g, float& b) {
    const float nr = 1.22494018f * r + -0.224940176f * g + -0.000000001f * b;
    const float ng = -0.042056955f * r + 1.04205695f * g + 0.000000001f * b;
    const float nb = -0.019637555f * r + -0.078636046f * g + 1.09827360f * b;
    r = nr;
    g = ng;
    b = nb;
}

/** Linear RGB BT.2020 → BT.709 (D65). */
inline void bt2020_to_bt709(float& r, float& g, float& b) {
    const float nr = 1.660491f * r + -0.587641f * g + -0.072850f * b;
    const float ng = -0.124550f * r + 1.132900f * g + -0.008349f * b;
    const float nb = -0.018151f * r + -0.100579f * g + 1.118730f * b;
    r = nr;
    g = ng;
    b = nb;
}

inline float clamp_nonneg(float v) {
    if (!std::isfinite(v) || v < 0.f) return 0.f;
    if (v > 64.f) return 64.f;
    return v;
}

}  // namespace

int pack_linear_f16_for_direct(std::vector<uint16_t>& rgba, unsigned w, unsigned h, bool force_hdr,
                               uhdr_color_gamut_t cg, bool advanced_color, int transfer_cicp,
                               std::vector<uint8_t>& out_pixels, int* out_format, int* out_is_hdr,
                               float* out_boost, int* out_gamut, int* out_transfer) {
    if (w == 0 || h == 0) return -1;
    const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
    if (rgba.size() < pixels * 4) return -1;

    float peak = 0.f;
    for (size_t i = 0; i < pixels; i++) {
        const float r = half_to_float(rgba[i * 4 + 0]);
        const float g = half_to_float(rgba[i * 4 + 1]);
        const float b = half_to_float(rgba[i * 4 + 2]);
        float m = fmax3(r, g, b);
        if (std::isfinite(m) && m > peak) peak = m;
    }
    if (peak < 1.f) peak = 1.f;
    if (peak > 64.f) peak = 64.f;

    const bool is_hdr = force_hdr || peak > 1.25f;
    // Advanced + SDR Display P3: keep source primaries for true WCG.
    const bool preserve_p3 = advanced_color && !is_hdr && cg == UHDR_CG_DISPLAY_P3;
    // Advanced + HDR BT.2100: keep BT.2020 linear for correct WCG+HDR tagging.
    const bool preserve_bt2100 = advanced_color && is_hdr && cg == UHDR_CG_BT_2100;
    const bool need_rematrix = (cg == UHDR_CG_DISPLAY_P3 || cg == UHDR_CG_BT_2100) &&
        !preserve_p3 && !preserve_bt2100;

    // Rematrix in-place — never hold a second full-frame F16 buffer.
    if (need_rematrix) {
        for (size_t i = 0; i < pixels; i++) {
            float r = half_to_float(rgba[i * 4 + 0]);
            float g = half_to_float(rgba[i * 4 + 1]);
            float b = half_to_float(rgba[i * 4 + 2]);
            float a = half_to_float(rgba[i * 4 + 3]);
            if (cg == UHDR_CG_DISPLAY_P3) {
                p3_to_bt709(r, g, b);
            } else {
                bt2020_to_bt709(r, g, b);
            }
            r = clamp_nonneg(r);
            g = clamp_nonneg(g);
            b = clamp_nonneg(b);
            if (!std::isfinite(a) || a < 0.f) a = 0.f;
            if (a > 1.f) a = 1.f;
            rgba[i * 4 + 0] = float_to_half(r);
            rgba[i * 4 + 1] = float_to_half(g);
            rgba[i * 4 + 2] = float_to_half(b);
            rgba[i * 4 + 3] = float_to_half(a);
        }
        ALOGI("direct pack rematrix cg=%d → BT.709 %ux%u (in-place)", (int)cg, w, h);
    }

    // Pixel gamut after pack (for Bitmap ColorSpace).
    int pixel_gamut = 0;
    if (preserve_p3) {
        pixel_gamut = 1;
    } else if (preserve_bt2100) {
        pixel_gamut = 2;
    }
    if (out_gamut) *out_gamut = pixel_gamut;
    if (out_transfer) {
        *out_transfer = preserve_bt2100 ? transfer_cicp : 0;
    }

    // High bit depth F16: HDR always; advanced SDR unless preserving P3 as 8888+DISPLAY_P3.
    const bool use_f16 = is_hdr || (advanced_color && !preserve_p3);
    if (out_is_hdr) *out_is_hdr = is_hdr ? 1 : 0;
    if (out_boost) *out_boost = is_hdr ? peak : 1.f;
    if (out_format) *out_format = use_f16 ? 1 : 0;

    if (use_f16) {
        // Leave packed F16 in [rgba] — no second 66 MiB memcpy buffer.
        out_pixels.clear();
        ALOGI("direct pack F16 %ux%u peak=%.3f hdr=%d advanced=%d gamut=%d tf=%d", w, h, peak,
              is_hdr ? 1 : 0, advanced_color ? 1 : 0, pixel_gamut, transfer_cicp);
        return 0;
    }

    // SDR 8888: sRGB-like OETF (also used for Display P3 tagging — same transfer curve).
    auto linear_to_srgb_u8 = [](float l) -> uint8_t {
        if (!std::isfinite(l) || l <= 0.f) return 0;
        if (l >= 1.f) return 255;
        float s;
        if (l <= 0.0031308f) {
            s = l * 12.92f;
        } else {
            s = 1.055f * std::pow(l, 1.f / 2.4f) - 0.055f;
        }
        if (s < 0.f) s = 0.f;
        if (s > 1.f) s = 1.f;
        return static_cast<uint8_t>(s * 255.f + 0.5f);
    };

    out_pixels.resize(pixels * 4);
    for (size_t i = 0; i < pixels; i++) {
        const float r = half_to_float(rgba[i * 4 + 0]);
        const float g = half_to_float(rgba[i * 4 + 1]);
        const float b = half_to_float(rgba[i * 4 + 2]);
        float a = half_to_float(rgba[i * 4 + 3]);
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        out_pixels[i * 4 + 0] = linear_to_srgb_u8(r);
        out_pixels[i * 4 + 1] = linear_to_srgb_u8(g);
        out_pixels[i * 4 + 2] = linear_to_srgb_u8(b);
        out_pixels[i * 4 + 3] = static_cast<uint8_t>(a * 255.f + 0.5f);
    }
    // Drop F16 staging before caller allocates the Java byte[] / Bitmap.
    rgba.clear();
    rgba.shrink_to_fit();
    ALOGI("direct pack SDR 8888 %ux%u peak=%.3f advanced=%d pixel_gamut=%d preserve_p3=%d", w, h,
          peak, advanced_color ? 1 : 0, pixel_gamut, preserve_p3 ? 1 : 0);
    return 0;
}

jbyteArray pack_direct_to_jbyte_array(JNIEnv* env, std::vector<uint16_t>& rgba, unsigned w,
                                      unsigned h, bool force_hdr, uhdr_color_gamut_t cg,
                                      bool advanced_color, int transfer_cicp, jintArray jOutInfo,
                                      jfloatArray jOutBoost) {
    if (!env || !jOutInfo || !jOutBoost) return nullptr;
    std::vector<uint8_t> sdr;
    int format = 0, is_hdr = 0, gamut = 0, tf = 0;
    float boost = 1.f;
    if (pack_linear_f16_for_direct(rgba, w, h, force_hdr, cg, advanced_color, transfer_cicp, sdr,
                                   &format, &is_hdr, &boost, &gamut, &tf) != 0) {
        return nullptr;
    }
    jint info[6] = {static_cast<jint>(w), static_cast<jint>(h), format, is_hdr, gamut, tf};
    env->SetIntArrayRegion(jOutInfo, 0, 6, info);
    env->SetFloatArrayRegion(jOutBoost, 0, 1, &boost);

    jbyteArray result = nullptr;
    if (format == 1) {
        // F16 still lives in rgba — copy once to Java, then free native immediately.
        const size_t nbytes = rgba.size() * sizeof(uint16_t);
        if (nbytes == 0 || nbytes > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            return nullptr;
        }
        result = env->NewByteArray(static_cast<jsize>(nbytes));
        if (result) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(nbytes),
                                    reinterpret_cast<const jbyte*>(rgba.data()));
        }
        rgba.clear();
        rgba.shrink_to_fit();
    } else {
        // pack already freed rgba; only sdr remains.
        if (sdr.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            return nullptr;
        }
        result = env->NewByteArray(static_cast<jsize>(sdr.size()));
        if (result) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(sdr.size()),
                                    reinterpret_cast<const jbyte*>(sdr.data()));
        }
    }
    return result;
}

void scale_rgba_f16_max_edge(std::vector<uint16_t>& rgba, unsigned& w, unsigned& h,
                             unsigned max_edge) {
    if (max_edge == 0 || w == 0 || h == 0) return;
    const unsigned long_edge = w > h ? w : h;
    if (long_edge <= max_edge) return;

    const float scale = static_cast<float>(max_edge) / static_cast<float>(long_edge);
    const unsigned nw = std::max(1u, static_cast<unsigned>(w * scale));
    const unsigned nh = std::max(1u, static_cast<unsigned>(h * scale));
    std::vector<uint16_t> out(static_cast<size_t>(nw) * nh * 4);

    auto h2f = [](uint16_t hv) -> float {
        const uint32_t sign = (static_cast<uint32_t>(hv) & 0x8000u) << 16;
        uint32_t exp = (hv >> 10) & 0x1fu;
        uint32_t mant = hv & 0x3ffu;
        uint32_t o;
        if (exp == 0) {
            if (mant == 0) {
                o = sign;
            } else {
                exp = 1;
                while ((mant & 0x400u) == 0) {
                    mant <<= 1;
                    exp--;
                }
                mant &= 0x3ffu;
                o = sign | ((exp + (127 - 15)) << 23) | (mant << 13);
            }
        } else if (exp == 31) {
            o = sign | 0x7f800000u | (mant << 13);
        } else {
            o = sign | ((exp + (127 - 15)) << 23) | (mant << 13);
        }
        union {
            uint32_t u;
            float f;
        } v{o};
        return v.f;
    };
    auto f2h = [](float f) -> uint16_t {
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
    };

    for (unsigned y = 0; y < nh; y++) {
        const unsigned sy0 = y * h / nh;
        const unsigned sy1 = std::min(h, (y + 1) * h / nh);
        for (unsigned x = 0; x < nw; x++) {
            const unsigned sx0 = x * w / nw;
            const unsigned sx1 = std::min(w, (x + 1) * w / nw);
            float acc[4] = {0, 0, 0, 0};
            unsigned cnt = 0;
            for (unsigned sy = sy0; sy < sy1; sy++) {
                for (unsigned sx = sx0; sx < sx1; sx++) {
                    const size_t i = (static_cast<size_t>(sy) * w + sx) * 4;
                    acc[0] += h2f(rgba[i + 0]);
                    acc[1] += h2f(rgba[i + 1]);
                    acc[2] += h2f(rgba[i + 2]);
                    acc[3] += h2f(rgba[i + 3]);
                    cnt++;
                }
            }
            if (cnt == 0) cnt = 1;
            const size_t o = (static_cast<size_t>(y) * nw + x) * 4;
            out[o + 0] = f2h(acc[0] / cnt);
            out[o + 1] = f2h(acc[1] / cnt);
            out[o + 2] = f2h(acc[2] / cnt);
            out[o + 3] = f2h(acc[3] / cnt);
        }
    }
    rgba.swap(out);
    w = nw;
    h = nh;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrToUltraHdr(JNIEnv* env, jclass,
                                                              jstring jInput, jstring jOutput) {
    if (!jInput || !jOutput) return -10;
    const char* in_path = env->GetStringUTFChars(jInput, nullptr);
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!in_path || !out_path) {
        if (in_path) env->ReleaseStringUTFChars(jInput, in_path);
        if (out_path) env->ReleaseStringUTFChars(jOutput, out_path);
        return -11;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int rc = -20;
    if (decode_jxr_to_rgba_f16(in_path, rgba, w, h)) {
        // HD Photo / scRGB-like: BT.709 primaries, linear extended range.
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, UHDR_CG_BT_709);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jInput, in_path);
    env->ReleaseStringUTFChars(jOutput, out_path);
    return rc;
}

/**
 * SAF/local Okio path path: Kotlin already holds JXR bytes — no temp .jxr on disk.
 * Mirrors branch `hdr` HdrJxr.decode(bytes) → convert pipeline.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdr(JNIEnv* env, jclass,
                                                                   jbyteArray jInput,
                                                                   jstring jOutput) {
    if (!jInput || !jOutput) return -10;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return -12;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return -13;
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) {
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int rc = -20;
    if (decode_jxr_from_memory(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len),
                               rgba, w, h)) {
        // HD Photo / scRGB-like: BT.709 primaries, linear extended range.
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, UHDR_CG_BT_709, 0.f);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/** JXR → Ultra HDR with optional long-edge cap (0 = full res). Used for thumbs. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdrMaxEdge(
        JNIEnv* env, jclass, jbyteArray jInput, jstring jOutput, jint maxEdge) {
    if (!jInput || !jOutput) return -10;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return -12;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return -13;
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) {
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int rc = -20;
    if (decode_jxr_from_memory(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len),
                               rgba, w, h)) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        // Thumbs: fixed MaxCLL 1000 nits — skip full-frame p99.99 peak scan.
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, UHDR_CG_BT_709, 1000.f);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/**
 * JXR → direct display pixels (skip UHDR JPEG).
 * outInfo int[≥6]: w, h, format, isHdr, gamut, transferCICP
 * outBoost float[1]: contentHdrBoost
 * advancedColor: WCG preserve + high bit depth
 * @return pixel bytes or null
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxrBytesToDirect(JNIEnv* env, jclass,
                                                                jbyteArray jInput, jint maxEdge,
                                                                jboolean advancedColor,
                                                                jintArray jOutInfo,
                                                                jfloatArray jOutBoost) {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return nullptr;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    jbyteArray result = nullptr;
    if (decode_jxr_from_memory(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len),
                               rgba, w, h)) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        // Peak (and optional force) decides 8888 vs F16; float JXR often peaks > 1.25.
        // JXR path has no reliable CICP here — treat as BT.709 scRGB-like.
        result = pack_direct_to_jbyte_array(env, rgba, w, h, /*force_hdr=*/false, UHDR_CG_BT_709,
                                            advancedColor == JNI_TRUE, /*transfer_cicp=*/0, jOutInfo,
                                            jOutBoost);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}
