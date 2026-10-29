/*
 * AVIF still decode via libavif → Ultra HDR JPEG (libultrahdr).
 *
 * - ISO 21496-1 gain-map AVIF (`tmap`): common BT.709/sRGB → Display P3 maps use
 *   parallel lookup-based linear output. Other maps use libavif's PQ output
 *   converted back to linear (1.0 = 203 nits); its linear output clamps at 1.
 * - Absolute PQ/HLG AVIF: decode with libavif, linearize in **source primaries**,
 *   encode content-matched Ultra HDR JPEG tagged with matching libultrahdr gamut
 *   (BT.2100 / Display P3 / BT.709). Do not force BT.709 clip when the format can
 *   carry the source gamut.
 *
 * JNI: com.hippo.ehviewer.jni.HdrConvertKt.convertAvifBytesToUltraHdr
 */
#include <android/log.h>
#include <jni.h>
#include "thumbnail_mapped_input.h"
#include "still_exif_input.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "avif/avif.h"
#include "hdr_encode.h"
#include "hdr_parallel.h"
#include "linear_color_profile.h"
#include "ultrahdr_api.h"

#define LOG_TAG "AvifHdr"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Parse container metadata only, independently of platform AV1/profile support.
static jbyteArray avif_exif_result(JNIEnv* env, const avifDecoder* decoder) {
    const auto& exif = decoder->image->exif;
    if (!exif.data || !exif.size || exif.size > 1024 * 1024) return nullptr;
    size_t offset = 0;
    if (avifGetExifTiffHeaderOffset(exif.data, exif.size, &offset) != AVIF_RESULT_OK || offset >= exif.size) return nullptr;
    const size_t count = exif.size - offset;
    auto result = env->NewByteArray(static_cast<jsize>(count));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(exif.data + offset));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractAvifExif(JNIEnv* env, jclass, jobject input) try {
    if (!input) return nullptr;
    const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    const jlong size = env->GetDirectBufferCapacity(input);
    if (!data || size <= 0) return nullptr;
    std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)> decoder(avifDecoderCreate(), avifDecoderDestroy);
    if (!decoder) return nullptr;
    decoder->ignoreExif = AVIF_FALSE;
    decoder->ignoreXMP = AVIF_TRUE;
    if (avifDecoderSetIOMemory(decoder.get(), data, static_cast<size_t>(size)) != AVIF_RESULT_OK ||
        avifDecoderParse(decoder.get()) != AVIF_RESULT_OK || !decoder->image) return nullptr;
    return avif_exif_result(env, decoder.get());
} catch (...) { return nullptr; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractAvifExifInput(JNIEnv* env, jclass, jobject input) try {
    if (!input) return nullptr;
    struct Reader {
        StillExifInput input;
        std::vector<uint8_t> bytes;
        Reader(JNIEnv* env, jobject source) : input(env, source) {}
    } reader(env, input);
    // Declared before decoder so its storage outlives avifDecoderDestroy.
    avifIO io{};
    io.sizeHint = INT64_MAX;
    io.data = &reader;
    io.read = [](avifIO* source, uint32_t flags, uint64_t offset, size_t size, avifROData* out) -> avifResult {
        auto* reader = static_cast<Reader*>(source->data);
        if (flags || !reader->input.read(offset, size, reader->bytes)) return AVIF_RESULT_IO_ERROR;
        out->data = reader->bytes.data();
        out->size = reader->bytes.size();
        return AVIF_RESULT_OK;
    };
    std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)> decoder(avifDecoderCreate(), avifDecoderDestroy);
    if (!decoder) return nullptr;
    decoder->ignoreExif = AVIF_FALSE;
    decoder->ignoreXMP = AVIF_TRUE;
    avifDecoderSetIO(decoder.get(), &io); // destroy=nullptr: io is borrowed stack storage.
    if (avifDecoderParse(decoder.get()) != AVIF_RESULT_OK || !decoder->image) return nullptr;
    return avif_exif_result(env, decoder.get());
} catch (...) { return nullptr; }

namespace {

// SMPTE ST 2084 PQ EOTF → linear relative luminance [0, 10000] nits-ish (absolute).
float pq_eotf(float n) {
    if (n <= 0.f) return 0.f;
    if (n >= 1.f) n = 1.f;
    const float m1 = 0.1593017578125f;
    const float m2 = 78.84375f;
    const float c1 = 0.8359375f;
    const float c2 = 18.8515625f;
    const float c3 = 18.6875f;
    const float np = std::pow(n, 1.f / m2);
    float num = np - c1;
    if (num < 0.f) num = 0.f;
    const float den = c2 - c3 * np;
    if (den <= 1e-6f) return 10000.f;
    return 10000.f * std::pow(num / den, 1.f / m1);
}

// HLG inverse OETF (BT.2100) → relative scene-linear [0, 12] roughly, then scale.
float hlg_inv_oetf(float x) {
    if (x <= 0.f) return 0.f;
    if (x >= 1.f) x = 1.f;
    const float a = 0.17883277f;
    const float b = 0.28466892f;
    const float c = 0.55991073f;
    if (x <= 0.5f) {
        return (x * x) / 3.f;
    }
    return (std::exp((x - c) / a) + b) / 12.f;
}

// RGB conversion produces integer 16-bit codes. Evaluate each transfer once
// per possible code, rather than millions of pow/exp calls for every PQ page.
std::vector<float> make_avif_linear_lut(avifTransferCharacteristics tc) {
    std::vector<float> lut(65536);
    for (unsigned i = 0; i < lut.size(); ++i) {
        const float v = i / 65535.f;
        if (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084) lut[i] = pq_eotf(v) / kSdrWhiteNits;
        else if (tc == AVIF_TRANSFER_CHARACTERISTICS_HLG) lut[i] = hlg_inv_oetf(v) * 12.f;
        else if (tc == AVIF_TRANSFER_CHARACTERISTICS_LINEAR) lut[i] = v;
        else if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT709 || tc == AVIF_TRANSFER_CHARACTERISTICS_BT601 ||
                 tc == AVIF_TRANSFER_CHARACTERISTICS_BT2020_10BIT || tc == AVIF_TRANSFER_CHARACTERISTICS_BT2020_12BIT) {
            lut[i] = v < 4.5f * 0.018053968510807f ? v / 4.5f :
                std::pow((v + 0.09929682680944f) / 1.09929682680944f, 1.f / 0.45f);
        }
        else if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT470M) lut[i] = std::pow(v, 2.2f);
        else if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT470BG) lut[i] = std::pow(v, 2.8f);
        else lut[i] = srgb_eotf(v);
    }
    return lut;
}

const std::vector<float>& avif_linear_lut(avifTransferCharacteristics tc) {
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084);
        return lut;
    }
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_HLG) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_HLG);
        return lut;
    }
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_LINEAR) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_LINEAR);
        return lut;
    }
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT709 || tc == AVIF_TRANSFER_CHARACTERISTICS_BT601 ||
        tc == AVIF_TRANSFER_CHARACTERISTICS_BT2020_10BIT || tc == AVIF_TRANSFER_CHARACTERISTICS_BT2020_12BIT) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_BT709);
        return lut;
    }
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT470M) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_BT470M);
        return lut;
    }
    if (tc == AVIF_TRANSFER_CHARACTERISTICS_BT470BG) {
        static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_BT470BG);
        return lut;
    }
    static const auto lut = make_avif_linear_lut(AVIF_TRANSFER_CHARACTERISTICS_SRGB);
    return lut;
}

/**
 * Map AVIF CICP primaries (+ transfer) → libultrahdr gamut tag.
 * Values and tags must stay consistent: if we keep BT.2020 RGB, tag BT_2100.
 */
uhdr_color_gamut_t map_avif_primaries_to_uhdr_cg(avifColorPrimaries primaries,
                                                   bool is_pq_or_hlg) {
    switch (primaries) {
        case AVIF_COLOR_PRIMARIES_BT2020:
            return UHDR_CG_BT_2100;
        case AVIF_COLOR_PRIMARIES_SMPTE432:  // Display P3 (D65)
            return UHDR_CG_DISPLAY_P3;
        case AVIF_COLOR_PRIMARIES_SMPTE431:  // DCI-P3 — closest Ultra HDR tag is Display P3
            return UHDR_CG_DISPLAY_P3;
        case AVIF_COLOR_PRIMARIES_BT709:
            return UHDR_CG_BT_709;
        default:
            // PQ/HLG with unspecified/unknown primaries: industry default is BT.2020.
            if (is_pq_or_hlg) return UHDR_CG_BT_2100;
            return UHDR_CG_BT_709;
    }
}

static float unsigned_frac(avifUnsignedFraction f) {
    if (f.d == 0) return 0.f;
    return static_cast<float>(f.n) / static_cast<float>(f.d);
}

/** Two reader pages decode at once, so each decode takes half the cores. */
int avif_decode_threads() {
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 4;
    unsigned per = hw / 2;
    if (per < 2) per = hw;
    if (per < 1) per = 1;
    if (per > 8) per = 8;
    return static_cast<int>(per);
}

/**
 * Shrink YUV to the reader/thumb long edge before gain-map apply and YUV→RGB.
 * dav1d has already reconstructed the full frame. [max_edge] 0 keeps it.
 * @return false when the image was left unusable.
 */
bool scale_avif_to_max_edge(avifImage* image, unsigned max_edge) {
    if (image == nullptr || max_edge == 0 || image->width == 0 || image->height == 0) return true;
    const unsigned long_edge = image->width > image->height ? image->width : image->height;
    if (long_edge <= max_edge) return true;
    const double scale = static_cast<double>(max_edge) / static_cast<double>(long_edge);
    uint32_t dw = static_cast<uint32_t>(std::lround(static_cast<double>(image->width) * scale));
    uint32_t dh = static_cast<uint32_t>(std::lround(static_cast<double>(image->height) * scale));
    if (dw > image->width) dw = image->width;
    if (dh > image->height) dh = image->height;
    if (dw < 2) dw = 2;
    if (dh < 2) dh = 2;
    if ((dw & 1u) != 0u && dw > 2) dw -= 1u;
    if ((dh & 1u) != 0u && dh > 2) dh -= 1u;
    if (dw == image->width && dh == image->height) return true;
    avifDiagnostics diag;
    avifDiagnosticsClearError(&diag);
    const unsigned src_w = image->width;
    const unsigned src_h = image->height;
    const avifResult scaled = avifImageScale(image, dw, dh, &diag);
    if (scaled != AVIF_RESULT_OK) {
        ALOGE("avifImageScale %ux%u -> %ux%u failed: %s", src_w, src_h, dw, dh,
              diag.error[0] ? diag.error : avifResultToString(scaled));
        return false;
    }
    return true;
}

// Own RGB buffers even when conversion fails. The fast path leaves the decoder's
// YUV planes untouched so unsupported inputs can still use libavif's tone mapper.
struct ScopedAvifRgb {
    avifRGBImage rgb{};
    explicit ScopedAvifRgb(const avifImage* image, int threads) {
        avifRGBImageSetDefaults(&rgb, image);
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.maxThreads = threads;
    }
    ~ScopedAvifRgb() { avifRGBImageFreePixels(&rgb); }
    bool convert(const avifImage* image) {
        return avifRGBImageAllocatePixels(&rgb) == AVIF_RESULT_OK &&
               avifImageYUVToRGB(image, &rgb) == AVIF_RESULT_OK;
    }
};

template <typename Work>
void avif_parallel_rows(unsigned width, unsigned height, int max_threads, Work work) {
    // Avoid thread startup for thumbnails. Include the calling thread in the budget.
    const unsigned jobs = std::min<unsigned>(std::max(1, max_threads),
        std::min<size_t>(height, std::max<size_t>(1, static_cast<size_t>(width) * height / 65536)));
    std::vector<std::thread> workers;
    workers.reserve(jobs - 1);
    for (unsigned job = 1; job < jobs; ++job) {
        workers.emplace_back(work, height * job / jobs, height * (job + 1) / jobs);
    }
    work(0, height / jobs);
    for (auto& worker : workers) worker.join();
}

/**
 * Fast ISO gain-map case used by the iphone18 samples: 10/12-bit sRGB BT.709
 * base, gain math and output in Display P3, full alternate headroom. Keep the
 * same YUV→RGB quantization as libavif, then use exact tables for every possible
 * integer base/gain code. This removes per-pixel pow/exp and the PQ round trip.
 * Other color spaces and ICC profiles use the general path.
 */
bool apply_iso_gain_map_fast(const avifImage* image, std::vector<uint16_t>& out_rgba,
                             int threads) {
    const avifGainMap* gm = image->gainMap;
    if (!gm || !gm->image || image->icc.size || gm->altICC.size ||
        image->transferCharacteristics != AVIF_TRANSFER_CHARACTERISTICS_SRGB ||
        image->colorPrimaries != AVIF_COLOR_PRIMARIES_BT709 ||
        gm->altColorPrimaries != AVIF_COLOR_PRIMARIES_SMPTE432 || gm->useBaseColorSpace != AVIF_FALSE ||
        image->depth <= 8 || image->depth > 12 || gm->image->depth <= 8 || gm->image->depth > 12 ||
        !gm->baseHdrHeadroom.d || !gm->alternateHdrHeadroom.d ||
        unsigned_frac(gm->alternateHdrHeadroom) <= unsigned_frac(gm->baseHdrHeadroom)) return false;

    for (int c = 0; c < 3; ++c) {
        if (!gm->gainMapMin[c].d || !gm->gainMapMax[c].d || !gm->gainMapGamma[c].d ||
            !gm->gainMapGamma[c].n || !gm->baseOffset[c].d || !gm->alternateOffset[c].d ||
            static_cast<int64_t>(gm->gainMapMax[c].n) * gm->gainMapMin[c].d <
                static_cast<int64_t>(gm->gainMapMin[c].n) * gm->gainMapMax[c].d) return false;
    }

    // Match libavif's order: resize the YUV gain map before RGB conversion.
    std::unique_ptr<avifImage, decltype(&avifImageDestroy)> scaled(nullptr, avifImageDestroy);
    const avifImage* gain_image = gm->image;
    if (gain_image->width != image->width || gain_image->height != image->height) {
        scaled.reset(avifImageCreateEmpty());
        if (!scaled) return false;
        const avifCropRect rect{0, 0, gain_image->width, gain_image->height};
        avifDiagnostics diag{};
        if (avifImageSetViewRect(scaled.get(), gain_image, &rect) != AVIF_RESULT_OK ||
            avifImageScale(scaled.get(), image->width, image->height, &diag) != AVIF_RESULT_OK) return false;
        gain_image = scaled.get();
    }
    ScopedAvifRgb base(image, threads), gain(gain_image, threads);
    if (!base.convert(image) || !gain.convert(gain_image)) return false;

    const unsigned base_max = (1u << base.rgb.depth) - 1;
    const unsigned gain_max = (1u << gain.rgb.depth) - 1;
    std::vector<float> linear(base_max + 1);
    for (unsigned i = 0; i <= base_max; ++i) {
        const float v = i / float(base_max);
        // libavif's continuous sRGB curve (slightly different from rounded IEC constants).
        linear[i] = v < 12.92f * 0.0030412825601275209f ? v / 12.92f :
            std::pow((v + 0.0550107189475866f) / 1.0550107189475866f, 2.4f);
    }
    std::vector<float> boost[3];
    float base_offset[3], alt_offset[3];
    for (int c = 0; c < 3; ++c) {
        const float lo = float(gm->gainMapMin[c].n) / gm->gainMapMin[c].d;
        const float hi = float(gm->gainMapMax[c].n) / gm->gainMapMax[c].d;
        const float gamma_inv = 1.f / unsigned_frac(gm->gainMapGamma[c]);
        base_offset[c] = float(gm->baseOffset[c].n) / gm->baseOffset[c].d;
        alt_offset[c] = float(gm->alternateOffset[c].n) / gm->alternateOffset[c].d;
        boost[c].resize(gain_max + 1);
        for (unsigned i = 0; i <= gain_max; ++i) {
            const float v = std::pow(i / float(gain_max), gamma_inv);
            boost[c][i] = std::exp2((1.f - v) * lo + v * hi);
        }
    }

    out_rgba.resize(static_cast<size_t>(image->width) * image->height * 4);
    avif_parallel_rows(image->width, image->height, threads, [&](unsigned begin, unsigned end) {
        for (unsigned y = begin; y < end; ++y) {
            const auto* b = reinterpret_cast<const uint16_t*>(base.rgb.pixels + size_t(y) * base.rgb.rowBytes);
            const auto* g = reinterpret_cast<const uint16_t*>(gain.rgb.pixels + size_t(y) * gain.rgb.rowBytes);
            auto* dst = out_rgba.data() + size_t(y) * image->width * 4;
            for (unsigned x = 0; x < image->width; ++x, b += 4, g += 4, dst += 4) {
                const float r = linear[b[0]], green = linear[b[1]], blue = linear[b[2]];
                // BT.709 D65 → Display P3 D65, matching libavif's CICP matrix.
                const float p3[3] = {
                    0.822461936750f * r + 0.177538063250f * green,
                    0.033194235636f * r + 0.966805764364f * green,
                    0.017082632689f * r + 0.072397383873f * green + 0.910519983438f * blue,
                };
                for (int c = 0; c < 3; ++c) {
                    const float v = (p3[c] + base_offset[c]) * boost[c][g[c]] - alt_offset[c];
                    dst[c] = float_to_half(std::min(kMaxLinear, std::max(0.f, v)));
                }
                dst[3] = float_to_half(b[3] / float(base_max));
            }
        }
    });
    return true;
}

/**
 * ISO 21496-1 gain map → linear RGBA half.
 * [avifImageApplyGainMap] clamps a linear destination to 0..1, which drops the
 * headroom. PQ output keeps it; [pq_eotf] brings those codes back to 203-nit linear.
 * @return true when [out_rgba] holds the tone-mapped HDR image.
 */
bool apply_iso_gain_map(const avifImage* image, std::vector<uint16_t>& out_rgba, int* out_has_gainmap,
                        int* out_transfer, uhdr_color_gamut_t* out_cg) {
    if (image->gainMap == nullptr || image->gainMap->image == nullptr) return false;
    const float headroom = unsigned_frac(image->gainMap->alternateHdrHeadroom);
    if (!(headroom > 0.05f)) return false;

    avifColorPrimaries primaries = image->gainMap->altColorPrimaries;
    if (primaries == AVIF_COLOR_PRIMARIES_UNKNOWN || primaries == AVIF_COLOR_PRIMARIES_UNSPECIFIED) {
        primaries = image->colorPrimaries;
    }
    if (primaries == AVIF_COLOR_PRIMARIES_UNKNOWN || primaries == AVIF_COLOR_PRIMARIES_UNSPECIFIED) {
        primaries = AVIF_COLOR_PRIMARIES_BT2020;
    }

    if (apply_iso_gain_map_fast(image, out_rgba, avif_decode_threads())) {
        if (out_cg) *out_cg = UHDR_CG_DISPLAY_P3;
        if (out_transfer) *out_transfer = 16;
        if (out_has_gainmap) *out_has_gainmap = 1;
        ALOGI("ISO gain map fast %ux%u headroom=%.3f", image->width, image->height, headroom);
        return true;
    }

    avifRGBImage tone;
    memset(&tone, 0, sizeof(tone));
    tone.format = AVIF_RGB_FORMAT_RGBA;
    tone.depth = 16;
    tone.isFloat = AVIF_FALSE;
    avifDiagnostics diag;
    avifDiagnosticsClearError(&diag);
    const avifResult applied = avifImageApplyGainMap(
        image, image->gainMap, headroom, primaries, AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084, &tone,
        nullptr, &diag);
    if (applied != AVIF_RESULT_OK || tone.pixels == nullptr || tone.width == 0 || tone.height == 0) {
        ALOGE("ISO gain map apply failed: %s", diag.error[0] ? diag.error : avifResultToString(applied));
        avifRGBImageFreePixels(&tone);
        return false;
    }

    const unsigned tw = tone.width;
    const unsigned th = tone.height;
    const size_t pixels = static_cast<size_t>(tw) * static_cast<size_t>(th);
    out_rgba.resize(pixels * 4);
    const uint8_t* row_base = tone.pixels;
    for (unsigned y = 0; y < th; y++) {
        const uint16_t* row =
            reinterpret_cast<const uint16_t*>(row_base + static_cast<size_t>(y) * tone.rowBytes);
        for (unsigned x = 0; x < tw; x++) {
            const uint16_t* p = row + x * 4;
            const float rl = pq_eotf(p[0] / 65535.f) / kSdrWhiteNits;
            const float gl = pq_eotf(p[1] / 65535.f) / kSdrWhiteNits;
            const float bl = pq_eotf(p[2] / 65535.f) / kSdrWhiteNits;
            float an = p[3] / 65535.f;
            auto clamp_hf = [](float v) {
                if (!std::isfinite(v) || v < 0.f) return 0.f;
                if (v > kMaxLinear) return kMaxLinear;
                return v;
            };
            if (an < 0.f) an = 0.f;
            if (an > 1.f) an = 1.f;
            const size_t i = (static_cast<size_t>(y) * tw + x) * 4;
            out_rgba[i + 0] = float_to_half(clamp_hf(rl));
            out_rgba[i + 1] = float_to_half(clamp_hf(gl));
            out_rgba[i + 2] = float_to_half(clamp_hf(bl));
            out_rgba[i + 3] = float_to_half(an);
        }
    }
    avifRGBImageFreePixels(&tone);
    if (out_cg) *out_cg = map_avif_primaries_to_uhdr_cg(primaries, true);
    if (out_transfer) *out_transfer = 16;
    if (out_has_gainmap) *out_has_gainmap = 1;
    ALOGI("ISO gain map %ux%u headroom=%.3f primaries=%d", tw, th, headroom, (int)primaries);
    return true;
}

/**
 * Decode AVIF → linear RGBA half in source (or fallback BT.709) primaries.
 * 1.0 = SDR white / 203 nits for PQ.
 *
 * @param out_cg libultrahdr gamut matching out_rgba (required for accurate encode).
 * @return 0 OK, non-zero on failure. Sets out_has_gainmap if libavif reports a gain map.
 */
int decode_avif_to_linear_f16(const uint8_t* data, size_t len, std::vector<uint16_t>& out_rgba,
                              unsigned& w, unsigned& h, int* out_has_gainmap,
                              int* out_transfer /* 16=PQ, 18=HLG, else other */,
                              uhdr_color_gamut_t* out_cg, unsigned max_edge) {
    if (out_has_gainmap) *out_has_gainmap = 0;
    if (out_transfer) *out_transfer = 0;
    if (out_cg) *out_cg = UHDR_CG_BT_709;
    if (!data || len == 0) return -1;

    avifDecoder* dec = avifDecoderCreate();
    if (!dec) return -2;
    dec->ignoreExif = AVIF_TRUE;
    dec->ignoreXMP = AVIF_TRUE;
    // Default decode skips the gain-map item. ISO 21496-1 AVIF needs those pixels.
    dec->imageContentToDecode = AVIF_IMAGE_CONTENT_ALL;
    // dav1d API 7 uses this as the tile-thread pool (one still, so frame delay stays 1).
    dec->maxThreads = avif_decode_threads();
    avifResult r = avifDecoderSetIOMemory(dec, data, len);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderSetIOMemory: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -3;
    }
    r = avifDecoderParse(dec);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderParse: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -4;
    }
    r = avifDecoderNextImage(dec);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifDecoderNextImage: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -5;
    }

    avifImage* image = dec->image;
    if (!image || image->width == 0 || image->height == 0) {
        avifDecoderDestroy(dec);
        return -6;
    }
    if (image->width > 16384 || image->height > 16384) {
        avifDecoderDestroy(dec);
        return -7;
    }
    if (!scale_avif_to_max_edge(image, max_edge)) {
        avifDecoderDestroy(dec);
        return -11;
    }
    w = image->width;
    h = image->height;

    if (apply_iso_gain_map(image, out_rgba, out_has_gainmap, out_transfer, out_cg)) {
        avifDecoderDestroy(dec);
        return 0;
    }
    // An advertised HDR rendition must not silently become an SDR base when
    // reconstruction fails (libavif currently cannot apply ICC-based maps).
    if (image->gainMap && image->gainMap->image &&
        unsigned_frac(image->gainMap->alternateHdrHeadroom) > 0.05f) {
        ALOGE("Cannot reconstruct the AVIF HDR gain map");
        avifDecoderDestroy(dec);
        return -12;
    }

    const avifTransferCharacteristics tc = image->transferCharacteristics;
    if (out_transfer) *out_transfer = static_cast<int>(tc);
    const bool is_pq = (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084);
    const bool is_hlg = (tc == AVIF_TRANSFER_CHARACTERISTICS_HLG);
    const avifColorPrimaries primaries = image->colorPrimaries;
    const bool is_bt2020 = (primaries == AVIF_COLOR_PRIMARIES_BT2020);

    // Preserve source gamut when libultrahdr can tag it (accuracy-first).
    // Policy B (convert → BT.709) only if we ever tag 709 while source was BT.2020.
    uhdr_color_gamut_t cg = map_avif_primaries_to_uhdr_cg(primaries, is_pq || is_hlg);
    const bool need_bt2020_to_709 = (cg == UHDR_CG_BT_709 && is_bt2020);
    if (out_cg) *out_cg = cg;

    avifRGBImage rgb;
    memset(&rgb, 0, sizeof(rgb));
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 16;
    rgb.maxThreads = dec->maxThreads;
    rgb.chromaUpsampling = AVIF_CHROMA_UPSAMPLING_BEST_QUALITY;

    r = avifRGBImageAllocatePixels(&rgb);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifRGBImageAllocatePixels: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return -8;
    }
    r = avifImageYUVToRGB(image, &rgb);
    if (r != AVIF_RESULT_OK) {
        ALOGE("avifImageYUVToRGB: %s", avifResultToString(r));
        avifRGBImageFreePixels(&rgb);
        avifDecoderDestroy(dec);
        return -9;
    }

    const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
    out_rgba.resize(pixels * 4);
    const uint8_t* row_base = rgb.pixels;
    const uint32_t row_bytes = rgb.rowBytes;

    // libavif converts YUV to encoded RGB; it does not apply the image ICC.
    // For SDR the complete ICC (TRC, white point and primaries) takes priority.
    // PQ/HLG use CICP explicitly: HDR ICCs can contain an SDR fallback TRC.
    if (image->icc.size && !is_pq && !is_hlg) {
        skcms_ICCProfile source{}, destination{};
        bool ok = image->icc.size <= 16u * 1024u * 1024u &&
            skcms_Parse(image->icc.data, image->icc.size, &source) &&
            make_linear_bt2020_profile(&destination);
        for (unsigned y = 0; ok && y < h; ++y) {
            ok = skcms_Transform(row_base + static_cast<size_t>(y) * row_bytes,
                skcms_PixelFormat_RGBA_16161616LE, skcms_AlphaFormat_Unpremul, &source,
                out_rgba.data() + static_cast<size_t>(y) * w * 4,
                skcms_PixelFormat_RGBA_hhhh, skcms_AlphaFormat_Unpremul, &destination, w);
        }
        avifRGBImageFreePixels(&rgb);
        avifDecoderDestroy(dec);
        if (!ok) {
            ALOGE("AVIF ICC conversion failed");
            return -13;
        }
        if (out_cg) *out_cg = UHDR_CG_BT_2100;
        if (out_transfer) *out_transfer = 0;
        return 0;
    }

    const auto& linear = avif_linear_lut(tc);
    hdr_parallel_pixels(h, hdr_pixel_jobs(pixels, dec->maxThreads), [&](size_t begin, size_t end, unsigned) {
        for (unsigned y = begin; y < end; y++) {
            const uint16_t* row = reinterpret_cast<const uint16_t*>(row_base + static_cast<size_t>(y) * row_bytes);
            for (unsigned x = 0; x < w; x++) {
                const uint16_t* p = row + x * 4;
                // 16-bit full range normalized (transfer-encoded if PQ/HLG)
                float an = p[3] / 65535.f;
                float rl = linear[p[0]], gl = linear[p[1]], bl = linear[p[2]];

                if (is_hlg) {
                    // LUT values are 12 * scene RGB. BT.2100's reference
                    // 1000-nit display needs the luminance-dependent OOTF,
                    // gamma 1.2, before our 203-nit-relative working space.
                    const float yr = cg == UHDR_CG_DISPLAY_P3 ? 0.2289746f : cg == UHDR_CG_BT_2100 ? 0.2627f : 0.2126f;
                    const float yb = cg == UHDR_CG_DISPLAY_P3 ? 0.0792869f : cg == UHDR_CG_BT_2100 ? 0.0593f : 0.0722f;
                    const float scene_y = (yr * rl + (1.f - yr - yb) * gl + yb * bl) / 12.f;
                    const float scale = std::pow(std::max(scene_y, 0.f), 0.2f) * (1000.f / (12.f * kSdrWhiteNits));
                    rl *= scale;
                    gl *= scale;
                    bl *= scale;
                }

                // Policy B only: values must match the BT.709 tag. Preserve path never rematrixes.
                if (need_bt2020_to_709) {
                    linear_bt2020_to_bt709(rl, gl, bl);
                }

                // Clamp to Ultra HDR LINEAR nominal max (10000/203).
                auto clamp_hf = [](float v) {
                    if (!std::isfinite(v) || v < 0.f) return 0.f;
                    if (v > kMaxLinear) return kMaxLinear;
                    return v;
                };
                rl = clamp_hf(rl);
                gl = clamp_hf(gl);
                bl = clamp_hf(bl);
                if (an < 0.f) an = 0.f;
                if (an > 1.f) an = 1.f;

                const size_t i = static_cast<size_t>(y) * w + x;
                out_rgba[i * 4 + 0] = float_to_half(rl);
                out_rgba[i * 4 + 1] = float_to_half(gl);
                out_rgba[i * 4 + 2] = float_to_half(bl);
                out_rgba[i * 4 + 3] = float_to_half(an);
            }
        }
    });

    // DCI-P3 has a different white point from Display P3. Other CICP gamuts
    // likewise need a real transform instead of assigning a nearby gamut tag.
    if (primaries != AVIF_COLOR_PRIMARIES_BT709 &&
        primaries != AVIF_COLOR_PRIMARIES_BT2020 &&
        primaries != AVIF_COLOR_PRIMARIES_SMPTE432 &&
        primaries != AVIF_COLOR_PRIMARIES_UNKNOWN &&
        primaries != AVIF_COLOR_PRIMARIES_UNSPECIFIED) {
        float xy[8];
        avifColorPrimariesGetValues(primaries, xy);
        skcms_ICCProfile source{}, destination{};
        skcms_Init(&source);
        skcms_SetTransferFunction(&source, skcms_Identity_TransferFunction());
        skcms_Matrix3x3 matrix{};
        bool ok = skcms_PrimariesToXYZD50(xy[0], xy[1], xy[2], xy[3], xy[4], xy[5], xy[6], xy[7], &matrix);
        skcms_SetXYZD50(&source, &matrix);
        ok = ok && make_linear_bt2020_profile(&destination) &&
            skcms_Transform(out_rgba.data(), skcms_PixelFormat_RGBA_hhhh, skcms_AlphaFormat_Unpremul,
                &source, out_rgba.data(), skcms_PixelFormat_RGBA_hhhh, skcms_AlphaFormat_Unpremul,
                &destination, pixels);
        if (!ok) {
            avifRGBImageFreePixels(&rgb);
            avifDecoderDestroy(dec);
            return -14;
        }
        cg = UHDR_CG_BT_2100;
        if (out_cg) *out_cg = cg;
    }

    ALOGI("AVIF %ux%u tc=%d primaries=%d pq=%d hlg=%d cg=%d rematrix709=%d gainmap=%d", w, h,
          (int)tc, (int)primaries, is_pq ? 1 : 0, is_hlg ? 1 : 0, (int)cg,
          need_bt2020_to_709 ? 1 : 0, out_has_gainmap ? *out_has_gainmap : 0);

    avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(dec);
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdr(JNIEnv* env, jclass,
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
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg, 0);
    if (rc != 0) {
        ALOGE("AVIF decode failed rc=%d", rc);
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    // Gain-map AVIF should use platform path; if caller still converts, we encode base HDR.
    if (has_gm) {
        ALOGI("AVIF has embedded gain map — encoding linearized base to Ultra HDR JPEG");
    }

    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg, 0.f, force_hdr);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/** Full-resolution convert with borrowed mapped/direct input. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBufferToUltraHdr(JNIEnv* env, jclass,
                                                                    jobject jInput,
                                                                    jstring jOutput) try {
    if (!jInput || !jOutput) return -10;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return -12;

    ThumbnailOutputPath outputPath(env, jOutput);
    const char* out_path = outputPath.get();
    if (!out_path) {
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg, 0);
    if (rc != 0) {
        ALOGE("AVIF decode failed rc=%d", rc);
        return -20 + rc;
    }
    // Gain-map AVIF should use platform path; if caller still converts, we encode base HDR.
    if (has_gm) {
        ALOGI("AVIF has embedded gain map — encoding linearized base to Ultra HDR JPEG");
    }

    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg, 0.f, force_hdr);
    return rc;
} catch (...) {
    return -30;
}

/** AVIF → Ultra HDR with optional long-edge cap (0 = full res). Used for thumbs. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdrMaxEdge(
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
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), rgba, w, h, &has_gm, &transfer,
                                       &cg, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u);
    if (rc != 0) {
        env->ReleaseStringUTFChars(jOutput, out_path);
        env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    // Thumbs: known TF only — 203 nits SDR / 1000 nits HDR; no peak scan.
    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg,
                                       thumb_fixed_peak_nits(force_hdr), force_hdr);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return rc;
}

/** File-backed thumbnail input avoids Java arrays and JNI compressed copies. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifFdToUltraHdrMaxEdge(
        JNIEnv* env, jclass, jint fd, jstring jOutput, jint maxEdge) try {
    if (!jOutput || maxEdge <= 0) return -10;
    ThumbnailMappedInput input(fd);
    if (!input.data) return -101;  // Non-regular/unmappable provider: stage to a file.
    ThumbnailOutputPath outputPath(env, jOutput);
    const char* out_path = outputPath.get();
    if (!out_path) {
        return -14;
    }

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int rc = decode_avif_to_linear_f16(input.data,
                                       input.size, rgba, w, h, &has_gm, &transfer,
                                       &cg, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u);
    input.reset();
    if (rc != 0) {
        return -20 + rc;
    }
    if (maxEdge > 0) {
        scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
    }
    // Thumbs: known TF only — 203 nits SDR / 1000 nits HDR; no peak scan.
    const bool force_hdr = (transfer == 16 /*PQ*/ || transfer == 18 /*HLG*/);
    rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path, cg,
                                       thumb_fixed_peak_nits(force_hdr), force_hdr);
    return rc;
} catch (...) {
    // In particular, do not let std::bad_alloc terminate the process at JNI.
    return -30;
}

/**
 * PQ/HLG AVIF → direct display pixels (skip UHDR JPEG).
 * outInfo int[≥6]: w, h, format, isHdr, gamut, transferCICP
 * outBoost float[1]: contentHdrBoost
 * advancedColor: WCG preserve + high bit depth
 */
// Reader-only handoff: Android applies the ISO gain map on the GPU instead of
// allocating and reconstructing a full-resolution linear HDR frame on the CPU.
// Export and thumbnail paths continue to use the complete reconstruction above.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeAvifGainmap(
        JNIEnv* env, jclass, jobject jInput, jbyteArray jBytes, jint maxEdge,
        jboolean alternatePrimaries, jintArray jInfo, jfloatArray jMetadata) try {
    if (!jInfo || !jMetadata || env->GetArrayLength(jInfo) < 7 ||
        env->GetArrayLength(jMetadata) < 17) return nullptr;
    std::unique_ptr<ThumbnailJniBytes> array;
    const uint8_t* data = nullptr;
    size_t length = 0;
    if (jInput) {
        const jlong capacity = env->GetDirectBufferCapacity(jInput);
        if (capacity <= 0) return nullptr;
        data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
        length = static_cast<size_t>(capacity);
    } else if (jBytes) {
        const jsize size = env->GetArrayLength(jBytes);
        if (size <= 0) return nullptr;
        array = std::make_unique<ThumbnailJniBytes>(env, jBytes);
        data = array->data();
        length = static_cast<size_t>(size);
    }
    if (!data || !length) return nullptr;

    std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)> dec(avifDecoderCreate(), avifDecoderDestroy);
    if (!dec) return nullptr;
    dec->ignoreExif = AVIF_TRUE;
    dec->ignoreXMP = AVIF_TRUE;
    dec->imageContentToDecode = AVIF_IMAGE_CONTENT_ALL;
    dec->maxThreads = avif_decode_threads();
    dec->imageSizeLimit = 80000000;
    if (avifDecoderSetIOMemory(dec.get(), data, length) != AVIF_RESULT_OK ||
        avifDecoderParse(dec.get()) != AVIF_RESULT_OK) return nullptr;

    avifImage* image = dec->image;
    const avifGainMap* gm = image ? image->gainMap : nullptr;
    const auto known_primaries = [](avifColorPrimaries p) {
        return p == AVIF_COLOR_PRIMARIES_BT709 || p == AVIF_COLOR_PRIMARIES_SMPTE432 || p == AVIF_COLOR_PRIMARIES_BT2020;
    };
    // Restrict the handoff to opaque SDR bases with directly representable
    // color metadata. ICC, HDR bases, 12-bit planes, and transformed items retain
    // the general F16 path. Normalized F16 retains at least 10-bit code precision.
    if (!gm || !gm->image || image->icc.size || gm->altICC.size || dec->alphaPresent || dec->imageCount != 1 ||
        image->transformFlags || gm->image->transformFlags ||
        image->depth > 10 || gm->image->depth > 10 ||
        !known_primaries(image->colorPrimaries) ||
        (image->transferCharacteristics != AVIF_TRANSFER_CHARACTERISTICS_SRGB &&
         image->transferCharacteristics != AVIF_TRANSFER_CHARACTERISTICS_BT709) ||
        !gm->baseHdrHeadroom.d || gm->baseHdrHeadroom.n != 0 || !gm->alternateHdrHeadroom.d) return nullptr;
    const avifColorPrimaries math_primaries =
        gm->useBaseColorSpace || gm->altColorPrimaries == AVIF_COLOR_PRIMARIES_UNSPECIFIED ?
        image->colorPrimaries : gm->altColorPrimaries;
    if (!known_primaries(math_primaries) ||
        (math_primaries != image->colorPrimaries && alternatePrimaries != JNI_TRUE)) return nullptr;

    jfloat metadata[17]{};
    for (int c = 0; c < 3; ++c) {
        if (!gm->gainMapMin[c].d || !gm->gainMapMax[c].d || !gm->gainMapGamma[c].d ||
            !gm->gainMapGamma[c].n || !gm->baseOffset[c].d || !gm->alternateOffset[c].d) return nullptr;
        metadata[c] = std::exp2(float(gm->gainMapMin[c].n) / gm->gainMapMin[c].d);
        metadata[3 + c] = std::exp2(float(gm->gainMapMax[c].n) / gm->gainMapMax[c].d);
        // ISO stores encoding gamma; Android expects the decoding exponent.
        metadata[6 + c] = float(gm->gainMapGamma[c].d) / gm->gainMapGamma[c].n;
        metadata[9 + c] = float(gm->baseOffset[c].n) / gm->baseOffset[c].d;
        metadata[12 + c] = float(gm->alternateOffset[c].n) / gm->alternateOffset[c].d;
        if (!(metadata[c] > 0.f) || metadata[3 + c] < metadata[c] ||
            metadata[9 + c] < 0.f || metadata[12 + c] < 0.f) return nullptr;
    }
    metadata[15] = 1.f;
    metadata[16] = std::exp2(unsigned_frac(gm->alternateHdrHeadroom));
    for (float value : metadata) if (!std::isfinite(value)) return nullptr;
    if (!(metadata[16] > 1.f)) return nullptr;

    // Eligibility is checked before reconstructing any AV1 pixels, so fallback
    // costs a metadata parse rather than a second full image decode.
    if (avifDecoderNextImage(dec.get()) != AVIF_RESULT_OK ||
        !image->width || !image->height || image->width > 16384 || image->height > 16384 ||
        !gm->image->width || !gm->image->height || gm->image->width > 16384 || gm->image->height > 16384 ||
        !scale_avif_to_max_edge(image, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0)) return nullptr;

    // Keep the gain map at its encoded resolution. Android samples it when
    // rendering the base; there is no full-size gain-map expansion or HDR pack.
    const auto convert = [&](const avifImage* input) {
        using Pixels = std::unique_ptr<void, decltype(&free)>;
        const uint64_t count = uint64_t(input->width) * input->height;
        if (!count || count > 80000000) return Pixels(nullptr, free);
        Pixels pixels(malloc(static_cast<size_t>(count) * 8), free);
        if (!pixels) return pixels;
        avifRGBImage rgb{};
        avifRGBImageSetDefaults(&rgb, input);
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.depth = 16;
        rgb.isFloat = AVIF_TRUE;
        rgb.alphaPremultiplied = AVIF_TRUE;
        rgb.maxThreads = dec->maxThreads;
        rgb.rowBytes = input->width * 8;
        rgb.pixels = static_cast<uint8_t*>(pixels.get());
        if (avifImageYUVToRGB(input, &rgb) != AVIF_RESULT_OK) pixels.reset();
        return pixels;
    };
    auto base = convert(image);
    if (!base) return nullptr;
    auto gain = convert(gm->image);
    if (!gain) return nullptr;
    const jint info[]{static_cast<jint>(image->width), static_cast<jint>(image->height),
        static_cast<jint>(gm->image->width), static_cast<jint>(gm->image->height),
        image->colorPrimaries, image->transferCharacteristics, math_primaries};
    env->SetIntArrayRegion(jInfo, 0, 7, info);
    env->SetFloatArrayRegion(jMetadata, 0, 17, metadata);
    jclass bufferClass = env->FindClass("java/nio/ByteBuffer");
    if (!bufferClass || env->ExceptionCheck()) return nullptr;
    jobjectArray result = env->NewObjectArray(2, bufferClass, nullptr);
    if (!result) return nullptr;
    jobject b = env->NewDirectByteBuffer(base.get(), jlong(image->width) * image->height * 8);
    jobject g = env->NewDirectByteBuffer(gain.get(), jlong(gm->image->width) * gm->image->height * 8);
    if (!b || !g || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(result, 0, b);
    env->SetObjectArrayElement(result, 1, g);
    if (env->ExceptionCheck()) return nullptr;
    base.release();
    gain.release();
    ALOGI("ISO gain map GPU handoff base=%ux%u gain=%ux%u gamut=%d math=%d",
          image->width, image->height, gm->image->width, gm->image->height, image->colorPrimaries, math_primaries);
    return result;
} catch (...) {
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeAvifBytesToDirect(JNIEnv* env, jclass,
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
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    jbyteArray result = nullptr;
    int rc = decode_avif_to_linear_f16(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len), rgba, w, h, &has_gm,
        &transfer, &cg, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u);
    if (rc == 0) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        const bool force_hdr = (transfer == 16 || transfer == 18);
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer, jOutInfo,
                                            jOutBoost);
    } else {
        ALOGE("AVIF direct decode failed rc=%d", rc);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}

/** Direct/mapped compressed input; the caller owns its lifetime. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeAvifBufferToDirect(JNIEnv* env, jclass,
                                                                 jobject jInput, jint maxEdge,
                                                                 jboolean advancedColor,
                                                                 jintArray jOutInfo,
                                                                 jfloatArray jOutBoost) try {
    if (!jInput || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return nullptr;

    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    int has_gm = 0;
    int transfer = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    jbyteArray result = nullptr;
    int rc = decode_avif_to_linear_f16(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len), rgba, w, h, &has_gm,
        &transfer, &cg, maxEdge > 0 ? static_cast<unsigned>(maxEdge) : 0u);
    if (rc == 0) {
        if (maxEdge > 0) {
            scale_rgba_f16_max_edge(rgba, w, h, static_cast<unsigned>(maxEdge));
        }
        const bool force_hdr = (transfer == 16 || transfer == 18);
        result = pack_direct_to_jbyte_array(env, rgba, w, h, force_hdr, cg,
                                            advancedColor == JNI_TRUE, transfer, jOutInfo,
                                            jOutBoost);
    } else {
        ALOGE("AVIF direct decode failed rc=%d", rc);
    }
    return result;
} catch (...) {
    return nullptr;
}

/**
 * Probe: 0 = not AVIF / error, 1 = gain-map AVIF, 2 = absolute PQ/HLG, 3 = other AVIF.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeAvifHdrKind(JNIEnv* env, jclass, jbyteArray jInput) {
    if (!jInput) return 0;
    const jsize len = env->GetArrayLength(jInput);
    if (len <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(jInput, nullptr);
    if (!bytes) return 0;

    avifDecoder* dec = avifDecoderCreate();
    jint result = 0;
    if (dec) {
        avifResult r =
            avifDecoderSetIOMemory(dec, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
        if (r == AVIF_RESULT_OK) {
            r = avifDecoderParse(dec);
            if (r == AVIF_RESULT_OK && dec->image) {
                result = 3;
                if (dec->image->gainMap != nullptr) {
                    result = 1;
                } else {
                    const auto tc = dec->image->transferCharacteristics;
                    if (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084 ||
                        tc == AVIF_TRANSFER_CHARACTERISTICS_HLG) {
                        result = 2;
                    }
                }
            }
        }
        avifDecoderDestroy(dec);
    }
    env->ReleaseByteArrayElements(jInput, bytes, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeAvifBufferHdrKind(JNIEnv* env, jclass, jobject jInput) {
    if (!jInput) return 0;
    const jlong len = env->GetDirectBufferCapacity(jInput);
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jInput));
    if (!bytes || len <= 0) return 0;

    avifDecoder* dec = avifDecoderCreate();
    jint result = 0;
    if (dec) {
        avifResult r =
            avifDecoderSetIOMemory(dec, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
        if (r == AVIF_RESULT_OK) {
            r = avifDecoderParse(dec);
            if (r == AVIF_RESULT_OK && dec->image) {
                result = 3;
                if (dec->image->gainMap != nullptr) {
                    result = 1;
                } else {
                    const auto tc = dec->image->transferCharacteristics;
                    if (tc == AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084 ||
                        tc == AVIF_TRANSFER_CHARACTERISTICS_HLG) {
                        result = 2;
                    }
                }
            }
        }
        avifDecoderDestroy(dec);
    }
    return result;
}
