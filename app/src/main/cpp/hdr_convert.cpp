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
// Internal libultrahdr: build Display P3 / 709 ICC for baseline JPEG APP2.
#include "ultrahdr/icc.h"
// skcms (libjxl third_party) — honor JXR embedded color context (ICC).
#include "skcms.h"
// ultrahdrcommon.h may stub ALOG* to no-ops — restore Android logging for this TU.
#ifdef ALOGE
#undef ALOGE
#endif
#ifdef ALOGI
#undef ALOGI
#endif

// libjpeg-turbo (built as libultrahdr dep) — baseline SDR JPEG only.
extern "C" {
#include "jpeglib.h"
}
#include <csetjmp>
#include <cstdio>

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

// Full-page convert quality (JXL/AVIF/JXR → JPEG). 4K 10-bit sources need higher
// than libjpeg default (~75) and than our prior 92: chroma 4:2:0 + q92 showed clear
// blocking on 3840×2160 SDR stills. Thumbs stay on Kotlin Bitmap q=85 / MaxEdge path.
constexpr int kJpegQualityBaseline = 97;  // pure SDR baseline (no gain map)
constexpr int kJpegQualityUhdrBase = 97;  // Ultra HDR SDR intent JPEG
constexpr int kJpegQualityUhdrGainMap = 95;
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

// jxrlib JXRGlue.h defines min/max macros — avoid std::max/min after that include.
static inline float fmax3(float a, float b, float c) {
    float m = a > b ? a : b;
    return m > c ? m : c;
}

}  // namespace

// Shared with jxl_hdr thumbs (declared in hdr_encode.h). Uses local half→float.
float scan_scrgb_peak(const uint16_t* rgba, size_t pixel_count) {
    if (!rgba || pixel_count == 0) return 1.0f;

    auto half_f = [](uint16_t h) -> float {
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
                out = sign | ((127 - 15 + exp) << 23) | (mant << 13);
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
    };
    auto mx3 = [](float a, float b, float c) {
        float m = a > b ? a : b;
        return m > c ? m : c;
    };

    constexpr double kPct = 0.9999;
    constexpr int kNitBins = 10001;
    std::vector<uint32_t> nit_counts(static_cast<size_t>(kNitBins), 0);
    int raw_max_nits = 0;
    uint64_t valid = 0;

    for (size_t i = 0; i < pixel_count; i++) {
        const float r = half_f(rgba[i * 4 + 0]);
        const float g = half_f(rgba[i * 4 + 1]);
        const float b = half_f(rgba[i * 4 + 2]);
        float m = mx3(r, g, b);
        if (!std::isfinite(m) || m <= 0.f) continue;
        valid++;
        float nits_f = m * kSdrWhiteNits;
        if (nits_f > kMaxNits) nits_f = kMaxNits;
        int nits = static_cast<int>(std::lround(nits_f));
        if (nits < 0) nits = 0;
        if (nits >= kNitBins) nits = kNitBins - 1;
        nit_counts[static_cast<size_t>(nits)]++;
        if (nits > raw_max_nits) raw_max_nits = nits;
    }
    if (valid == 0) return 1.0f;

    const uint64_t count_target =
        static_cast<uint64_t>(std::llround((1.0 - kPct) * static_cast<double>(valid)));
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

    float peak = static_cast<float>(maxcll_nits) / kSdrWhiteNits;
    if (peak < 1.0f) peak = 1.0f;
    if (peak > kMaxLinear) peak = kMaxLinear;
    return peak;
}

namespace {

bool guid_eq(const PKPixelFormatGUID& a, const PKPixelFormatGUID& b) {
    return memcmp(&a, &b, sizeof(PKPixelFormatGUID)) == 0;
}

/**
 * JXR/WDP pixel families we expand to linear F16 RGBA.
 *
 * Microsoft default (no color context): integer → sRGB; half/float/fixed → scRGB.
 * With embedded color context, ICC takes priority (skcms after expand).
 *
 * HDR (linear scRGB, 1.0 ≈ paper white): half / float / 13.3 fixed-point.
 * SDR unsigned integer (sRGB-encoded by default): 8/16-bit RGB(A)/BGR(A)/gray + RGB101010.
 */
enum class JxrPix {
    RgbaF32,
    RgbaF32Prem,  // 128bppPRGBAFloat — must unpremultiply
    RgbaF16,
    Rgb101010,    // unsigned 10-bit; jxrlib R@20 G@10 B@0; default sRGB-encoded
    Fixed16,      // signed 13.3 fixed-point linear scRGB (HD Photo HDR)
    Bgr8,         // 24bppBGR / 32bppBGR (B,G,R[,X])
    Bgra8,        // 32bppBGRA
    Pbgra8,       // 32bppPBGRA (premultiplied)
    Rgb8,         // 24bppRGB
    Rgba8,        // 32bppRGBA / 32bppRGB (R,G,B[,A])
    Prgba8,       // 32bppPRGBA
    Rgb16,        // 48bppRGB (u16 gamma)
    Rgba16,       // 64bppRGBA / PRGBA (u16 gamma)
    Gray8,
    Gray16,
    Unsupported,
};

JxrPix classify_guid(const PKPixelFormatGUID& g) {
    // ── HDR linear (scRGB when untagged) ──────────────────────────────────
    if (guid_eq(g, GUID_PKPixelFormat128bppRGBAFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat128bppPRGBAFloat)) return JxrPix::RgbaF32Prem;
    if (guid_eq(g, GUID_PKPixelFormat128bppRGBFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat96bppRGBFloat)) return JxrPix::RgbaF32;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBAHalf)) return JxrPix::RgbaF16;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBHalf)) return JxrPix::RgbaF16;
    if (guid_eq(g, GUID_PKPixelFormat48bppRGBHalf)) return JxrPix::RgbaF16;
    if (guid_eq(g, GUID_PKPixelFormat48bppRGBFixedPoint)) return JxrPix::Fixed16;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBAFixedPoint)) return JxrPix::Fixed16;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBFixedPoint)) return JxrPix::Fixed16;
    // ── Unsigned integer (default sRGB-encoded; RGB101010 bit order per jxrlib PFC) ──
    if (guid_eq(g, GUID_PKPixelFormat32bppRGB101010)) return JxrPix::Rgb101010;
    if (guid_eq(g, GUID_PKPixelFormat24bppBGR)) return JxrPix::Bgr8;
    if (guid_eq(g, GUID_PKPixelFormat32bppBGR)) return JxrPix::Bgr8;
    if (guid_eq(g, GUID_PKPixelFormat32bppBGRA)) return JxrPix::Bgra8;
    if (guid_eq(g, GUID_PKPixelFormat32bppPBGRA)) return JxrPix::Pbgra8;
    if (guid_eq(g, GUID_PKPixelFormat24bppRGB)) return JxrPix::Rgb8;
    if (guid_eq(g, GUID_PKPixelFormat32bppRGB)) return JxrPix::Rgba8;
    if (guid_eq(g, GUID_PKPixelFormat32bppRGBA)) return JxrPix::Rgba8;
    if (guid_eq(g, GUID_PKPixelFormat32bppPRGBA)) return JxrPix::Prgba8;
    if (guid_eq(g, GUID_PKPixelFormat48bppRGB)) return JxrPix::Rgb16;
    if (guid_eq(g, GUID_PKPixelFormat64bppRGBA)) return JxrPix::Rgba16;
    if (guid_eq(g, GUID_PKPixelFormat64bppPRGBA)) return JxrPix::Rgba16;
    if (guid_eq(g, GUID_PKPixelFormat8bppGray)) return JxrPix::Gray8;
    if (guid_eq(g, GUID_PKPixelFormat16bppGray)) return JxrPix::Gray16;
    return JxrPix::Unsupported;
}

/**
 * Build linear sRGB destination for skcms (BT.709/sRGB primaries + identity TRC).
 * Output floats are linear light suitable for libultrahdr (1.0 ≈ paper white).
 */
void skcms_linear_srgb_profile(skcms_ICCProfile* dst) {
    skcms_Init(dst);
    skcms_SetTransferFunction(dst, skcms_Identity_TransferFunction());
    const skcms_ICCProfile* srgb = skcms_sRGB_profile();
    if (srgb && srgb->has_toXYZD50) {
        skcms_SetXYZD50(dst, &srgb->toXYZD50);
    }
}

/**
 * Apply embedded JXR color context with its **full** ICC (TRC + matrix).
 *
 * WIC: pixel format does not define color space; color context takes priority.
 * Callers must expand numeric samples as **encoded** in the ICC (no default
 * sRGB EOTF / scRGB linear assumption). Do not strip TRC based on float vs int.
 *
 * Output is linear sRGB F16 (1.0 ≈ paper white).
 */
bool apply_jxr_color_context(std::vector<uint16_t>& rgba, size_t npx, const uint8_t* icc,
                             size_t icc_len) {
    if (!icc || icc_len < 128 || npx == 0) return false;
    skcms_ICCProfile src{};
    if (!skcms_Parse(icc, icc_len, &src)) {
        ALOGI("JXR ICC present (%zu B) but skcms_Parse failed", icc_len);
        return false;
    }
    skcms_ICCProfile dst{};
    skcms_linear_srgb_profile(&dst);
    if (!skcms_MakeUsableAsDestination(&dst)) {
        ALOGI("JXR ICC: linear sRGB dest not usable");
        return false;
    }
    // skcms supports IEEE-754 half pixels directly. Transforming the existing
    // F16 frame in place avoids a second full-frame RGBA_F32 allocation (16 B/px),
    // which otherwise causes GC/native-memory pressure before Bitmap presentation.
    if (!skcms_Transform(rgba.data(), skcms_PixelFormat_RGBA_hhhh, skcms_AlphaFormat_Unpremul, &src,
                         rgba.data(), skcms_PixelFormat_RGBA_hhhh, skcms_AlphaFormat_Unpremul, &dst,
                         npx)) {
        ALOGI("JXR ICC skcms_Transform failed");
        return false;
    }
    for (size_t i = 0; i < npx; i++) {
        auto clamp01p = [](float v) {
            if (!std::isfinite(v) || v < 0.f) return 0.f;
            if (v > kMaxLinear) return kMaxLinear;
            return v;
        };
        rgba[i * 4 + 0] = float_to_half(clamp01p(half_to_float(rgba[i * 4 + 0])));
        rgba[i * 4 + 1] = float_to_half(clamp01p(half_to_float(rgba[i * 4 + 1])));
        rgba[i * 4 + 2] = float_to_half(clamp01p(half_to_float(rgba[i * 4 + 2])));
        float a = half_to_float(rgba[i * 4 + 3]);
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        rgba[i * 4 + 3] = float_to_half(a);
    }
    ALOGI("JXR ICC applied via skcms (%zu B, full TRC)", icc_len);
    return true;
}

/** Encoded [0,1] F16 samples → linear via sRGB EOTF (ICC transform fallback). */
void apply_srgb_eotf_in_place(std::vector<uint16_t>& rgba, size_t npx) {
    for (size_t i = 0; i < npx; i++) {
        const float r = srgb_eotf(half_to_float(rgba[i * 4 + 0]));
        const float g = srgb_eotf(half_to_float(rgba[i * 4 + 1]));
        const float b = srgb_eotf(half_to_float(rgba[i * 4 + 2]));
        rgba[i * 4 + 0] = float_to_half(r);
        rgba[i * 4 + 1] = float_to_half(g);
        rgba[i * 4 + 2] = float_to_half(b);
        // alpha unchanged
    }
}

/**
 * Straight-alpha → composite on black, force opaque.
 * JPEG / libultrahdr read only RGB and ignore A; transparent edges otherwise show
 * pre-composite colors. Matches JXL path.
 */
void composite_straight_alpha_on_black(std::vector<uint16_t>& rgba, size_t npx) {
    for (size_t i = 0; i < npx; i++) {
        float a = half_to_float(rgba[i * 4 + 3]);
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        if (a >= 1.f) {
            rgba[i * 4 + 3] = float_to_half(1.f);
            continue;
        }
        float r = half_to_float(rgba[i * 4 + 0]);
        float g = half_to_float(rgba[i * 4 + 1]);
        float b = half_to_float(rgba[i * 4 + 2]);
        if (!std::isfinite(r) || r < 0.f) r = 0.f;
        if (!std::isfinite(g) || g < 0.f) g = 0.f;
        if (!std::isfinite(b) || b < 0.f) b = 0.f;
        r *= a;
        g *= a;
        b *= a;
        if (r > kMaxLinear) r = kMaxLinear;
        if (g > kMaxLinear) g = kMaxLinear;
        if (b > kMaxLinear) b = kMaxLinear;
        rgba[i * 4 + 0] = float_to_half(r);
        rgba[i * 4 + 1] = float_to_half(g);
        rgba[i * 4 + 2] = float_to_half(b);
        rgba[i * 4 + 3] = float_to_half(1.f);
    }
}

/** Log GUID for Unsupported diagnostics (family last byte is often enough). */
void log_unsupported_guid(const PKPixelFormatGUID& g) {
    const auto* b = reinterpret_cast<const uint8_t*>(&g);
    ALOGE("Unsupported JXR pixel format GUID "
          "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x "
          "(last=0x%02x)",
          b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12],
          b[13], b[14], b[15], b[15]);
}

/** sRGB-encoded channel [0,1] → linear F16. */
inline uint16_t enc_to_lin_half(float enc) {
    return float_to_half(srgb_eotf(enc));
}

/** HD Photo 13.3 signed fixed-point: 1.0 linear = 0x2000 (8192). */
inline float fixed13_3_to_float(int16_t v) {
    return static_cast<float>(v) * (1.0f / 8192.0f);
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
 *
 * @param composite_alpha_on_black true for JPEG/UHDR (no alpha plane) — flatten
 *   straight alpha onto black. false for direct Bitmap display — keep A.
 */
bool decode_jxr_from_memory(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                            unsigned& w, unsigned& h, bool composite_alpha_on_black = true) {
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
        log_unsupported_guid(guid);
        release_all();
        return false;
    }

    // Embedded color context (ICC) takes priority over pixel-format defaults (WIC).
    std::vector<uint8_t> color_ctx;
    {
        U32 cb = 0;
        ERR cc = decoder->GetColorContext(decoder, nullptr, &cb);
        if (!Failed(cc) && cb > 0 && cb < (16u * 1024u * 1024u)) {
            color_ctx.resize(cb);
            cc = decoder->GetColorContext(decoder, color_ctx.data(), &cb);
            if (Failed(cc) || cb == 0) {
                color_ctx.clear();
            } else {
                color_ctx.resize(cb);
            }
        }
    }
    // WIC: valid color context wins over integer→sRGB / float→scRGB inference.
    // Any parseable ICC (including ≈sRGB) is applied with its full TRC — do not
    // assume float storage is linear when a context is present.
    bool have_valid_icc = false;
    if (!color_ctx.empty()) {
        skcms_ICCProfile probe{};
        have_valid_icc = skcms_Parse(color_ctx.data(), color_ctx.size(), &probe);
        if (!have_valid_icc) {
            ALOGI("JXR color context %zu B unparsable — Microsoft default inference",
                  color_ctx.size());
        }
    }
    // Expand numeric samples as encoded for skcms; without ICC use default TF.
    const bool expand_as_encoded = have_valid_icc;

    /* Decode in native format — no jxrlib format conversion (half→float Convert
     * produces vertical stripe garbage). Integer SDR expands below with sRGB EOTF. */
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
    const bool rgba_float_prem = guid_eq(guid, GUID_PKPixelFormat128bppPRGBAFloat);
    const bool fixed_has_a = guid_eq(guid, GUID_PKPixelFormat64bppRGBAFixedPoint);
    const bool rgba16_has_a = guid_eq(guid, GUID_PKPixelFormat64bppRGBA) ||
        guid_eq(guid, GUID_PKPixelFormat64bppPRGBA);
    const bool rgba16_prem = guid_eq(guid, GUID_PKPixelFormat64bppPRGBA);
    const bool rgba8_has_a = guid_eq(guid, GUID_PKPixelFormat32bppRGBA) ||
        guid_eq(guid, GUID_PKPixelFormat32bppPRGBA);

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
    const size_t src_bpp = bytes_per_pixel;
    const size_t npx = static_cast<size_t>(w) * h;

    auto store_lin = [&](size_t i, float r, float g, float b, float a) {
        out_rgba[i * 4 + 0] = float_to_half(r);
        out_rgba[i * 4 + 1] = float_to_half(g);
        out_rgba[i * 4 + 2] = float_to_half(b);
        out_rgba[i * 4 + 3] = float_to_half(a);
    };
    auto store_enc_rgb = [&](size_t i, float er, float eg, float eb, float ea) {
        if (expand_as_encoded) {
            // Keep encoded; skcms applies ICC TRC + matrix → linear sRGB.
            store_lin(i, er, eg, eb, ea);
        } else {
            out_rgba[i * 4 + 0] = enc_to_lin_half(er);
            out_rgba[i * 4 + 1] = enc_to_lin_half(eg);
            out_rgba[i * 4 + 2] = enc_to_lin_half(eb);
            out_rgba[i * 4 + 3] = float_to_half(ea);
        }
    };

    if (rgba_half || rgb_half) {
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
        const bool has_a = rgba_float;
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const float* p =
                    reinterpret_cast<const float*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                float r = p[0], g = p[1], b = p[2];
                float a = has_a ? p[3] : 1.0f;
                if (rgba_float_prem) {
                    if (a > 1e-6f) {
                        r /= a;
                        g /= a;
                        b /= a;
                    } else {
                        r = g = b = 0.f;
                    }
                }
                store_lin(i, r, g, b, a);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgb101010) {
        // jxrlib RGB101010_RGB48: R = (v>>20)&0x3FF, G = (v>>10)&0x3FF, B = v&0x3FF.
        // Unsigned integer → sRGB-encoded by Microsoft default (not linear scRGB).
        for (size_t i = 0; i < npx; i++) {
            uint32_t p;
            memcpy(&p, raw.data() + i * 4, 4);
            const float r = static_cast<float>((p >> 20) & 0x3ff) / 1023.0f;
            const float g = static_cast<float>((p >> 10) & 0x3ff) / 1023.0f;
            const float b = static_cast<float>((p >> 0) & 0x3ff) / 1023.0f;
            store_enc_rgb(i, r, g, b, 1.0f);
        }
        ok = true;
    } else if (pix == JxrPix::Fixed16) {
        // Linear scRGB 13.3 fixed-point (HD Photo HDR).
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const int16_t* p =
                    reinterpret_cast<const int16_t*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                store_lin(i, fixed13_3_to_float(p[0]), fixed13_3_to_float(p[1]),
                          fixed13_3_to_float(p[2]),
                          fixed_has_a ? fixed13_3_to_float(p[3]) : 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Bgr8) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint8_t* p = row + static_cast<size_t>(x) * src_bpp;
                const size_t i = static_cast<size_t>(y) * w + x;
                store_enc_rgb(i, p[2] / 255.f, p[1] / 255.f, p[0] / 255.f, 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Bgra8 || pix == JxrPix::Pbgra8) {
        const bool prem = (pix == JxrPix::Pbgra8);
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint8_t* p = row + static_cast<size_t>(x) * src_bpp;
                const size_t i = static_cast<size_t>(y) * w + x;
                float a = p[3] / 255.f;
                float b = p[0] / 255.f, g = p[1] / 255.f, r = p[2] / 255.f;
                if (prem && a > 1e-6f) {
                    r /= a;
                    g /= a;
                    b /= a;
                } else if (prem && a <= 1e-6f) {
                    r = g = b = 0.f;
                }
                store_enc_rgb(i, r, g, b, a);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgb8) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint8_t* p = row + static_cast<size_t>(x) * src_bpp;
                const size_t i = static_cast<size_t>(y) * w + x;
                store_enc_rgb(i, p[0] / 255.f, p[1] / 255.f, p[2] / 255.f, 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgba8 || pix == JxrPix::Prgba8) {
        const bool prem = (pix == JxrPix::Prgba8);
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint8_t* p = row + static_cast<size_t>(x) * src_bpp;
                const size_t i = static_cast<size_t>(y) * w + x;
                float r = p[0] / 255.f, g = p[1] / 255.f, b = p[2] / 255.f;
                float a = rgba8_has_a || prem ? p[3] / 255.f : 1.0f;
                if (prem && a > 1e-6f) {
                    r /= a;
                    g /= a;
                    b /= a;
                } else if (prem && a <= 1e-6f) {
                    r = g = b = 0.f;
                }
                store_enc_rgb(i, r, g, b, a);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgb16) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint16_t* p =
                    reinterpret_cast<const uint16_t*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                store_enc_rgb(i, p[0] / 65535.f, p[1] / 65535.f, p[2] / 65535.f, 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Rgba16) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint16_t* p =
                    reinterpret_cast<const uint16_t*>(row + static_cast<size_t>(x) * src_bpp);
                const size_t i = static_cast<size_t>(y) * w + x;
                float r = p[0] / 65535.f, g = p[1] / 65535.f, b = p[2] / 65535.f;
                float a = rgba16_has_a ? p[3] / 65535.f : 1.0f;
                if (rgba16_prem && a > 1e-6f) {
                    r /= a;
                    g /= a;
                    b /= a;
                } else if (rgba16_prem && a <= 1e-6f) {
                    r = g = b = 0.f;
                }
                store_enc_rgb(i, r, g, b, a);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Gray8) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const float yv = row[static_cast<size_t>(x) * src_bpp] / 255.f;
                store_enc_rgb(static_cast<size_t>(y) * w + x, yv, yv, yv, 1.0f);
            }
        }
        ok = true;
    } else if (pix == JxrPix::Gray16) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t* row = raw.data() + static_cast<size_t>(y) * stride;
            for (unsigned x = 0; x < w; x++) {
                const uint16_t* p =
                    reinterpret_cast<const uint16_t*>(row + static_cast<size_t>(x) * src_bpp);
                const float yv = p[0] / 65535.f;
                store_enc_rgb(static_cast<size_t>(y) * w + x, yv, yv, yv, 1.0f);
            }
        }
        ok = true;
    } else {
        ALOGE("Unhandled JXR expand path");
    }

    release_all();
    if (!ok) return false;

    // Honor embedded ICC after expand (color context > pixel-format inference).
    if (have_valid_icc) {
        // Samples are still encoded in the ICC; apply full TRC + matrix.
        if (!apply_jxr_color_context(out_rgba, npx, color_ctx.data(), color_ctx.size())) {
            // Transform failed while we skipped default TF — recover with sRGB EOTF
            // so encoded integers/floats are not left as fake linear.
            ALOGI("JXR ICC transform failed — fallback sRGB EOTF");
            apply_srgb_eotf_in_place(out_rgba, npx);
        }
    }
    // JPEG/UHDR have no alpha plane — flatten only when the caller will encode
    // to those formats. Direct display keeps straight alpha for transparency.
    if (composite_alpha_on_black) {
        composite_straight_alpha_on_black(out_rgba, npx);
    }
    return true;
}

// Path helper: load whole file into memory then decode (no jxrlib file I/O).
// Path convert is always UHDR/JPEG → composite alpha.
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
    return decode_jxr_from_memory(buf.data(), buf.size(), out_rgba, w, h,
                                  /*composite_alpha_on_black=*/true);
}

}  // namespace

static bool write_file(const char* path, const void* data, size_t size) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

namespace {

struct JpegErr {
    jpeg_error_mgr pub;
    jmp_buf jump;
};

void jpeg_err_exit(j_common_ptr cinfo) {
    auto* e = reinterpret_cast<JpegErr*>(cinfo->err);
    char buf[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, buf);
    ALOGE("libjpeg: %s", buf);
    longjmp(e->jump, 1);
}

/**
 * Pure SDR → baseline JPEG (no gain map). Avoids libultrahdr epsilon-bump of
 * max_content_boost when min==max (≈1.07), which made tools show ratio ≠ 1.0.
 *
 * Color policy (lib-direct **off** convert path):
 * - **Display P3**: keep P3 primaries + sRGB-curve OETF + embed Display P3 ICC
 *   (APP2) so Coil/ImageDecoder can present WCG without rematrix crush.
 * - **BT.2100** (rare pure-SDR): rematrix → BT.709, untagged sRGB JPEG
 *   (no PQ/HLG in baseline).
 * - **BT.709**: sRGB OETF, untagged (or optional 709 ICC not required).
 *
 * Quality [kJpegQualityBaseline], 4:4:4 chroma (no 4:2:0 color blur on 4K stills).
 */
int encode_baseline_sdr_jpeg(unsigned w, unsigned h, const uint16_t* rgba, const char* out_path,
                             uhdr_color_gamut_t cg) {
    if (!rgba || !out_path || w == 0 || h == 0) return -30;
    const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
    std::vector<uint8_t> rgb(pixels * 3);
    // Keep P3 for WCG-capable convert; only BT.2100 SDR is rematrixed to 709.
    const bool preserve_p3 = (cg == UHDR_CG_DISPLAY_P3);
    const bool rematrix_2020 = (cg == UHDR_CG_BT_2100);
    for (size_t i = 0; i < pixels; i++) {
        float r = half_to_float(rgba[i * 4 + 0]);
        float g = half_to_float(rgba[i * 4 + 1]);
        float b = half_to_float(rgba[i * 4 + 2]);
        if (rematrix_2020) {
            linear_bt2020_to_bt709(r, g, b);
        }
        // preserve_p3: leave linear P3 as-is; OETF matches Display P3 (same as sRGB curve).
        if (!std::isfinite(r) || r < 0.f) r = 0.f;
        if (!std::isfinite(g) || g < 0.f) g = 0.f;
        if (!std::isfinite(b) || b < 0.f) b = 0.f;
        rgb[i * 3 + 0] = linear_to_srgb_u8(r);
        rgb[i * 3 + 1] = linear_to_srgb_u8(g);
        rgb[i * 3 + 2] = linear_to_srgb_u8(b);
    }

    FILE* fp = fopen(out_path, "wb");
    if (!fp) {
        ALOGE("fopen baseline JPEG failed: %s", out_path);
        return -31;
    }

    // P3 ICC for ImageDecoder / Coil (includes ICC_PROFILE chunk header for APP2).
    std::shared_ptr<ultrahdr::DataStruct> icc;
    if (preserve_p3) {
        icc = ultrahdr::IccHelper::writeIccProfile(UHDR_CT_SRGB, UHDR_CG_DISPLAY_P3);
        if (!icc || !icc->getData() || icc->getLength() == 0) {
            ALOGE("P3 ICC build failed — falling back to rematrix 709 baseline");
            icc.reset();
            // Re-encode pixels with rematrix so untagged JPEG is not wrong-primaries.
            for (size_t i = 0; i < pixels; i++) {
                float r = half_to_float(rgba[i * 4 + 0]);
                float g = half_to_float(rgba[i * 4 + 1]);
                float b = half_to_float(rgba[i * 4 + 2]);
                linear_p3_to_bt709(r, g, b);
                if (!std::isfinite(r) || r < 0.f) r = 0.f;
                if (!std::isfinite(g) || g < 0.f) g = 0.f;
                if (!std::isfinite(b) || b < 0.f) b = 0.f;
                rgb[i * 3 + 0] = linear_to_srgb_u8(r);
                rgb[i * 3 + 1] = linear_to_srgb_u8(g);
                rgb[i * 3 + 2] = linear_to_srgb_u8(b);
            }
        }
    }

    jpeg_compress_struct cinfo{};
    JpegErr jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = jpeg_err_exit;
    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        fclose(fp);
        return -32;
    }
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, fp);
    cinfo.image_width = w;
    cinfo.image_height = h;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, kJpegQualityBaseline, TRUE);
    // jpeg_set_defaults → 4:2:0; force 4:4:4 so fine color edges (text, hair) stay sharp
    // after 10-bit JXL/AVIF → 8-bit JPEG. File size up ~15–25% vs 4:2:0 at same Q.
    cinfo.comp_info[0].h_samp_factor = 1;
    cinfo.comp_info[0].v_samp_factor = 1;
    cinfo.comp_info[1].h_samp_factor = 1;
    cinfo.comp_info[1].v_samp_factor = 1;
    cinfo.comp_info[2].h_samp_factor = 1;
    cinfo.comp_info[2].v_samp_factor = 1;
    jpeg_start_compress(&cinfo, TRUE);
    if (icc && icc->getData() && icc->getLength() > 0) {
        // Same as libultrahdr JpegEncoderHelper: APP2 + ICC_PROFILE payload.
        const auto len = static_cast<unsigned int>(icc->getLength());
        if (len <= 65533u) {
            jpeg_write_marker(&cinfo, JPEG_APP0 + 2, static_cast<const JOCTET*>(icc->getData()),
                              len);
        } else {
            ALOGE("P3 ICC too large for single APP2 (%u) — writing without ICC", len);
        }
    }
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = rgb.data() + static_cast<size_t>(cinfo.next_scanline) * w * 3;
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    fclose(fp);
    ALOGI("Wrote baseline SDR JPEG %ux%u cg=%d p3_icc=%d → %s", w, h, (int)cg,
          (icc && icc->getData() && icc->getLength() > 0) ? 1 : 0, out_path);
    return 0;
}

}  // namespace

/**
 * Encode LINEAR half-float RGB → Ultra HDR JPEG (HDR) or baseline JPEG (pure SDR).
 *
 * Capacity: uhdr_enc_set_target_display_peak_brightness sets displayRatioForFullHdr
 * (hdr_capacity_max) ≈ peak_nits/203. Prefer that field at display time — not ratioMax.
 *
 * Note (libultrahdr API-0, HDR raw only): set_min_max_content_boost may not fully
 * shrink gain-map ratioMax to content_peak; one-pass path can leave ratioMax near
 * 10000/203 ≈ 49. Keep target_display_peak_brightness for correct capacity.
 *
 * Pure SDR ([force_hdr] false and peak ≤ 1.0): baseline JPEG only — no gain map.
 *
 * [fixed_peak_nits] > 0: skip full-frame peak scan (thumbs: 203 SDR / 1000 HDR).
 * [fixed_peak_nits] ≤ 0: p99.99 scan of max(R,G,B) (full pages).
 *
 * [cg] must match [rgba] primaries for Ultra HDR. Baseline SDR keeps P3+ICC
 * (see encode_baseline_sdr_jpeg); BT.2100 pure-SDR rematrixes to 709.
 */
int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba,
                                   const char* out_path, uhdr_color_gamut_t cg,
                                   float fixed_peak_nits, bool force_hdr) {
    if (!rgba || !out_path || w == 0 || h == 0) return -1;

    // Only the three gamuts libultrahdr can tag/ICC-embed.
    if (cg != UHDR_CG_BT_709 && cg != UHDR_CG_DISPLAY_P3 && cg != UHDR_CG_BT_2100) {
        ALOGI("Unknown gamut %d → BT.709 tag (caller must have converted RGB if needed)", (int)cg);
        cg = UHDR_CG_BT_709;
    }

    float content_peak;
    float peak_nits;
    if (fixed_peak_nits > 0.f) {
        // Thumbs / known path: fixed MaxCLL, no pixel histogram.
        peak_nits = fixed_peak_nits;
        if (peak_nits < kSdrWhiteNits) peak_nits = kSdrWhiteNits;
        if (peak_nits > kMaxNits) peak_nits = kMaxNits;
        content_peak = peak_nits / kSdrWhiteNits;
        if (content_peak < 1.0f) content_peak = 1.0f;
    } else {
        const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
        content_peak = scan_scrgb_peak(rgba, pixels);
        peak_nits = kSdrWhiteNits * content_peak;
        if (peak_nits < kSdrWhiteNits) peak_nits = kSdrWhiteNits;
        if (peak_nits > kMaxNits) peak_nits = kMaxNits;
    }

    // Pure SDR → baseline JPEG (no Ultra HDR gain map / no fake ratio > 1).
    if (!force_hdr && content_peak <= 1.0f) {
        return encode_baseline_sdr_jpeg(w, h, rgba, out_path, cg);
    }

    uhdr_codec_private_t* enc = uhdr_create_encoder();
    if (!enc) {
        ALOGE("uhdr_create_encoder failed");
        return -1;
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

    // Quality: base + multi-channel gain map (libultrahdr default is 95/95).
    err = uhdr_enc_set_quality(enc, kJpegQualityUhdrBase, UHDR_BASE_IMG);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_quality base: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }
    err = uhdr_enc_set_quality(enc, kJpegQualityUhdrGainMap, UHDR_GAIN_MAP_IMG);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_quality gainmap: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }
    err = uhdr_enc_set_using_multi_channel_gainmap(enc, 1);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_using_multi_channel_gainmap: %s",
              err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }
    err = uhdr_enc_set_gainmap_gamma(enc, 1.0f);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_gainmap_gamma: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }
    err = uhdr_enc_set_preset(enc, UHDR_USAGE_BEST_QUALITY);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_preset: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }
    err = uhdr_enc_set_output_format(enc, UHDR_CODEC_JPG);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_output_format: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -6;
    }

    // Hint only — API-0 one-pass may still emit ratioMax ≈ 49; capacity is set below.
    err = uhdr_enc_set_min_max_content_boost(enc, 1.0f, content_peak);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_min_max_content_boost: %s", err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -7;
    }
    // Sets displayRatioForFullHdr / hdr_capacity_max ≈ peak_nits / 203.
    err = uhdr_enc_set_target_display_peak_brightness(enc, peak_nits);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_target_display_peak_brightness: %s",
              err.has_detail ? err.detail : "error");
        uhdr_release_encoder(enc);
        return -7;
    }

    ALOGI("Ultra HDR encode content_peak=%.3f peak_nits=%.1f fixed=%d force_hdr=%d cg=%d %ux%u",
          content_peak, peak_nits, fixed_peak_nits > 0.f ? 1 : 0, force_hdr ? 1 : 0, (int)cg, w, h);

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

inline float clamp_nonneg(float v) {
    if (!std::isfinite(v) || v < 0.f) return 0.f;
    if (v > kMaxLinear) return kMaxLinear;
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
        float a = half_to_float(rgba[i * 4 + 3]);
        if (!std::isfinite(a) || a <= 0.f) continue;
        if (a > 1.f) a = 1.f;
        // Buffers are straight-alpha. Invisible RGB must not force HDR mode;
        // evaluate the linear contribution that Android actually composites.
        float m = fmax3(r, g, b) * a;
        if (std::isfinite(m) && m > peak) peak = m;
    }
    if (peak < 1.f) peak = 1.f;
    if (peak > kMaxLinear) peak = kMaxLinear;

    const bool is_hdr = force_hdr || peak > 1.25f;
    // Advanced color preserves the decoded working gamut at every precision/DR.
    // Kotlin tags the Bitmap with the matching linear ColorSpace.
    const bool preserve_p3 = advanced_color && cg == UHDR_CG_DISPLAY_P3;
    const bool preserve_bt2100 = advanced_color && cg == UHDR_CG_BT_2100;
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
                linear_p3_to_bt709(r, g, b);
            } else {
                linear_bt2020_to_bt709(r, g, b);
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

    // HDR always needs extended F16. Advanced color keeps deep precision for all
    // gamuts, including SDR P3/BT.2020 (never silently quantize those paths to 8-bit).
    const bool use_f16 = is_hdr || advanced_color;
    if (out_is_hdr) *out_is_hdr = is_hdr ? 1 : 0;
    if (out_boost) *out_boost = is_hdr ? peak : 1.f;
    if (out_format) *out_format = use_f16 ? 1 : 0;

    if (use_f16) {
        // Android Canvas/View rendering requires premultiplied bitmap storage.
        // Decoder handoff is straight alpha, so associate it once at this final
        // presentation boundary, after gamut conversion and peak measurement.
        for (size_t i = 0; i < pixels; i++) {
            float r = half_to_float(rgba[i * 4 + 0]);
            float g = half_to_float(rgba[i * 4 + 1]);
            float b = half_to_float(rgba[i * 4 + 2]);
            float a = half_to_float(rgba[i * 4 + 3]);
            if (!std::isfinite(a) || a < 0.f) a = 0.f;
            if (a > 1.f) a = 1.f;
            rgba[i * 4 + 0] = float_to_half(clamp_nonneg(r) * a);
            rgba[i * 4 + 1] = float_to_half(clamp_nonneg(g) * a);
            rgba[i * 4 + 2] = float_to_half(clamp_nonneg(b) * a);
            rgba[i * 4 + 3] = float_to_half(a);
        }
        // Leave packed F16 in [rgba] — no second 66 MiB memcpy buffer.
        out_pixels.clear();
        ALOGI("direct pack F16 %ux%u peak=%.3f hdr=%d advanced=%d gamut=%d tf=%d", w, h, peak,
              is_hdr ? 1 : 0, advanced_color ? 1 : 0, pixel_gamut, transfer_cicp);
        return 0;
    }

    // SDR 8888: IEC 61966-2-1 sRGB OETF (also used for Display P3 tagging — same curve).
    out_pixels.resize(pixels * 4);
    for (size_t i = 0; i < pixels; i++) {
        const float r = half_to_float(rgba[i * 4 + 0]);
        const float g = half_to_float(rgba[i * 4 + 1]);
        const float b = half_to_float(rgba[i * 4 + 2]);
        float a = half_to_float(rgba[i * 4 + 3]);
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        // RGBA_8888 is premultiplied in its encoded color space.
        out_pixels[i * 4 + 0] = static_cast<uint8_t>(srgb_oetf(r) * a * 255.f + 0.5f);
        out_pixels[i * 4 + 1] = static_cast<uint8_t>(srgb_oetf(g) * a * 255.f + 0.5f);
        out_pixels[i * 4 + 2] = static_cast<uint8_t>(srgb_oetf(b) * a * 255.f + 0.5f);
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
    // Own a native copy and release the Java array pin *before* decode/encode so
    // GetByteArrayElements' optional copy is not live for the whole F16 peak window.
    std::vector<uint8_t> compressed(static_cast<size_t>(len));
    memcpy(compressed.data(), bytes, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    bytes = nullptr;

    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) return -14;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int rc = -20;
    if (decode_jxr_from_memory(compressed.data(), compressed.size(), rgba, w, h)) {
        // Compressed bitstream no longer needed during F16 encode.
        compressed.clear();
        compressed.shrink_to_fit();
        // HD Photo / scRGB-like: BT.709 primaries, linear extended range.
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, UHDR_CG_BT_709, 0.f);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jOutput, out_path);
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
    std::vector<uint8_t> compressed(static_cast<size_t>(len));
    memcpy(compressed.data(), bytes, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);

    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) return -14;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int rc = -20;
    if (decode_jxr_from_memory(compressed.data(), compressed.size(), rgba, w, h)) {
        compressed.clear();
        compressed.shrink_to_fit();
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        // JXR thumbs: HDR from scanned peak (not format); capacity = content peak nits.
        const float peak = scan_scrgb_peak(rgba.data(), static_cast<size_t>(w) * h);
        const bool force_hdr = peak > 1.25f;
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, UHDR_CG_BT_709,
                                           thumb_peak_nits_from_linear(peak), force_hdr);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jOutput, out_path);
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
    // Preserve transparency: do not composite onto black (UHDR convert paths do).
    if (decode_jxr_from_memory(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len),
                               rgba, w, h, /*composite_alpha_on_black=*/false)) {
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
