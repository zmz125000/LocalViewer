/*
 * JPEG 2000 (JP2 / raw J2K) → ARGB_8888 Bitmap.
 * Android ImageDecoder cannot open JP2/J2K.
 */
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <openjpeg.h>

#include <pthread.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "Jp2Decode"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxEdge = 20000;
constexpr int64_t kMaxPixels = 64LL * 1000 * 1000;

struct MemSrc {
    const uint8_t* data;
    OPJ_SIZE_T size;
    OPJ_SIZE_T offset;
};

struct Session {
    JNIEnv* env = nullptr;
    jbyteArray input = nullptr;
    jbyte* bytes = nullptr;
    opj_stream_t* stream = nullptr;
    opj_codec_t* codec = nullptr;
    opj_image_t* image = nullptr;

    ~Session() {
        if (bytes && env && input) {
            env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
        }
        if (image) opj_image_destroy(image);
        if (codec) opj_destroy_codec(codec);
        if (stream) opj_stream_destroy(stream);
    }

    void releaseInput() {
        if (bytes && env && input) {
            env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
            bytes = nullptr;
        }
    }
};

void silent_callback(const char*, void*) {}

OPJ_SIZE_T mem_read(void* buf, OPJ_SIZE_T n, void* user) {
    auto* s = static_cast<MemSrc*>(user);
    if (s->offset >= s->size) return static_cast<OPJ_SIZE_T>(-1);
    const OPJ_SIZE_T remain = s->size - s->offset;
    if (n > remain) n = remain;
    memcpy(buf, s->data + s->offset, n);
    s->offset += n;
    return n;
}

OPJ_OFF_T mem_skip(OPJ_OFF_T n, void* user) {
    auto* s = static_cast<MemSrc*>(user);
    auto next = static_cast<OPJ_OFF_T>(s->offset) + n;
    if (next < 0) next = 0;
    if (next > static_cast<OPJ_OFF_T>(s->size)) next = static_cast<OPJ_OFF_T>(s->size);
    const OPJ_OFF_T skipped = next - static_cast<OPJ_OFF_T>(s->offset);
    s->offset = static_cast<OPJ_SIZE_T>(next);
    return skipped;
}

OPJ_BOOL mem_seek(OPJ_OFF_T n, void* user) {
    auto* s = static_cast<MemSrc*>(user);
    if (n < 0 || n > static_cast<OPJ_OFF_T>(s->size)) return OPJ_FALSE;
    s->offset = static_cast<OPJ_SIZE_T>(n);
    return OPJ_TRUE;
}

bool is_jp2(const uint8_t* p, int n) {
    return n >= 12 && p[4] == 'j' && p[5] == 'P' && p[6] == ' ' && p[7] == ' ';
}

bool is_j2k(const uint8_t* p, int n) {
    // SOC + SIZ. SIZ is required immediately after SOC.
    return n >= 4 && p[0] == 0xff && p[1] == 0x4f && p[2] == 0xff && p[3] == 0x51;
}

int sample8(const opj_image_comp_t& c, int x, int y, int outW, int outH) {
    if (!c.data || c.w <= 0 || c.h <= 0 || c.prec <= 0 || c.prec > 32 || outW <= 0 || outH <= 0) {
        return 0;
    }
    const int sx = outW == static_cast<int>(c.w)
        ? std::min(static_cast<int>(c.w) - 1, std::max(0, x))
        : std::min(static_cast<int>(c.w) - 1,
                   static_cast<int>((static_cast<int64_t>(x) * c.w) / outW));
    const int sy = outH == static_cast<int>(c.h)
        ? std::min(static_cast<int>(c.h) - 1, std::max(0, y))
        : std::min(static_cast<int>(c.h) - 1,
                   static_cast<int>((static_cast<int64_t>(y) * c.h) / outH));
    int v = c.data[static_cast<size_t>(sy) * static_cast<size_t>(c.w) + static_cast<size_t>(sx)];
    if (c.sgnd && c.prec < 31) v += 1 << (c.prec - 1);
    if (c.prec > 8) v >>= (c.prec - 8);
    else if (c.prec < 8) v <<= (8 - c.prec);
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}

int clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}

void ycbcr_to_rgb(int y, int cb, int cr, int& r, int& g, int& b) {
    const int cbb = cb - 128;
    const int crr = cr - 128;
    r = clamp8(y + ((1436 * crr) >> 10));
    g = clamp8(y - ((352 * cbb + 731 * crr) >> 10));
    b = clamp8(y + ((1815 * cbb) >> 10));
}

struct DecodeJob {
    const uint8_t* data = nullptr;
    int n = 0;
    int maxEdge = 0;
    opj_image_t* image = nullptr;
};

void* decodeJob(void* arg) {
    auto* job = static_cast<DecodeJob*>(arg);
    const OPJ_CODEC_FORMAT fmt = is_jp2(job->data, job->n) ? OPJ_CODEC_JP2
        : is_j2k(job->data, job->n) ? OPJ_CODEC_J2K
                                    : OPJ_CODEC_UNKNOWN;
    if (fmt == OPJ_CODEC_UNKNOWN) return nullptr;

    // Tier-1 decode recurses deeply. opj_dparameters_t is also ~8 KiB.
    // Coil's Default dispatcher stack hits the guard page (SEGV_ACCERR,
    // reported as executing non-executable memory).
    MemSrc src{job->data, static_cast<OPJ_SIZE_T>(job->n), 0};
    opj_stream_t* stream = opj_stream_default_create(OPJ_TRUE);
    if (!stream) return nullptr;
    opj_stream_set_read_function(stream, mem_read);
    opj_stream_set_skip_function(stream, mem_skip);
    opj_stream_set_seek_function(stream, mem_seek);
    opj_stream_set_user_data(stream, &src, nullptr);
    opj_stream_set_user_data_length(stream, src.size);

    auto* params = static_cast<opj_dparameters_t*>(calloc(1, sizeof(opj_dparameters_t)));
    if (!params) {
        opj_stream_destroy(stream);
        return nullptr;
    }
    opj_set_default_decoder_parameters(params);
    opj_codec_t* codec = opj_create_decompress(fmt);
    if (!codec || !opj_setup_decoder(codec, params)) {
        free(params);
        if (codec) opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        return nullptr;
    }
    free(params);
    opj_set_info_handler(codec, silent_callback, nullptr);
    opj_set_warning_handler(codec, silent_callback, nullptr);
    opj_set_error_handler(codec, silent_callback, nullptr);
    opj_codec_set_threads(codec, 1);

    opj_image_t* image = nullptr;
    if (!opj_read_header(stream, codec, &image) || !image) {
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        if (image) opj_image_destroy(image);
        return nullptr;
    }
    const int fullW = static_cast<int>(image->x1 - image->x0);
    const int fullH = static_cast<int>(image->y1 - image->y0);
    if (fullW <= 0 || fullH <= 0 || fullW > kMaxEdge || fullH > kMaxEdge ||
        static_cast<int64_t>(fullW) * fullH > kMaxPixels || image->numcomps < 1) {
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        opj_image_destroy(image);
        return nullptr;
    }
    if (job->maxEdge > 0) {
        int reduce = 0;
        while (reduce < 8) {
            const int lw = std::max(1, fullW >> reduce);
            const int lh = std::max(1, fullH >> reduce);
            if (lw <= job->maxEdge && lh <= job->maxEdge) break;
            ++reduce;
        }
        while (reduce > 0 &&
               !opj_set_decoded_resolution_factor(codec, static_cast<OPJ_UINT32>(reduce))) {
            --reduce;
        }
    }
    if (!opj_decode(codec, stream, image) || !opj_end_decompress(codec, stream)) {
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        opj_image_destroy(image);
        return nullptr;
    }
    opj_destroy_codec(codec);
    opj_stream_destroy(stream);
    job->image = image;
    return nullptr;
}

opj_image_t* decode_on_worker(const uint8_t* data, int n, int maxEdge) {
    DecodeJob job;
    job.data = data;
    job.n = n;
    job.maxEdge = maxEdge;
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    // Tier-1 decode recurses past Coil's dispatcher stack.
    pthread_attr_setstacksize(&attr, 8u * 1024u * 1024u);
    const int started = pthread_create(&thread, &attr, decodeJob, &job);
    pthread_attr_destroy(&attr);
    if (started != 0) return nullptr;
    pthread_join(thread, nullptr);
    return job.image;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_Jpeg2000Kt_decodeJpeg2000Bitmap(
        JNIEnv* env, jclass, jbyteArray input, jint maxEdge) {
    if (!input) return nullptr;
    const jsize n = env->GetArrayLength(input);
    if (n < 8) return nullptr;

    Session session;
    session.env = env;
    session.input = input;
    session.bytes = env->GetByteArrayElements(input, nullptr);
    if (!session.bytes) return nullptr;
    const auto* data = reinterpret_cast<const uint8_t*>(session.bytes);

    session.image = decode_on_worker(data, static_cast<int>(n), maxEdge);
    session.releaseInput();
    if (!session.image || session.image->numcomps < 1) return nullptr;

    // x1/y1 stay at the reference grid. A resolution factor only shrinks comps[].w/h.
    const int w = static_cast<int>(session.image->comps[0].w);
    const int h = static_cast<int>(session.image->comps[0].h);
    if (w <= 0 || h <= 0 || w > kMaxEdge || h > kMaxEdge) return nullptr;

    jclass bmpCls = env->FindClass("android/graphics/Bitmap");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bmpCls || !cfgCls) return nullptr;
    jfieldID argbField = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject cfg = env->GetStaticObjectField(cfgCls, argbField);
    jmethodID create = env->GetStaticMethodID(
            bmpCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bmpCls, create, w, h, cfg);
    if (!bitmap || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    // Straight alpha. The default flag is premultiplied, which would darken translucency.
    jmethodID setPremul = env->GetMethodID(bmpCls, "setPremultiplied", "(Z)V");
    if (setPremul) env->CallVoidMethod(bitmap, setPremul, JNI_FALSE);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.stride < static_cast<uint32_t>(w) * 4) {
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return nullptr;
    }

    auto* dst = static_cast<uint8_t*>(pixels);
    opj_image_t* image = session.image;
    const int nc = static_cast<int>(image->numcomps);
    const bool cmyk = image->color_space == OPJ_CLRSPC_CMYK;
    const bool ycc = image->color_space == OPJ_CLRSPC_SYCC;
    for (int y = 0; y < h; ++y) {
        // ANDROID_BITMAP_FORMAT_RGBA_8888: byte order is R, G, B, A.
        auto* row = dst + static_cast<size_t>(y) * info.stride;
        for (int x = 0; x < w; ++x) {
            int r, g, b, a = 255;
            if (nc == 1) {
                r = g = b = sample8(image->comps[0], x, y, w, h);
            } else if (nc == 2) {
                r = g = b = sample8(image->comps[0], x, y, w, h);
                a = sample8(image->comps[1], x, y, w, h);
            } else if (cmyk) {
                const float c = sample8(image->comps[0], x, y, w, h) / 255.f;
                const float m = sample8(image->comps[1], x, y, w, h) / 255.f;
                const float ye = sample8(image->comps[2], x, y, w, h) / 255.f;
                const float k = nc > 3 ? sample8(image->comps[3], x, y, w, h) / 255.f : 0.f;
                r = static_cast<int>((1.f - c) * (1.f - k) * 255.f);
                g = static_cast<int>((1.f - m) * (1.f - k) * 255.f);
                b = static_cast<int>((1.f - ye) * (1.f - k) * 255.f);
            } else if (ycc) {
                ycbcr_to_rgb(
                        sample8(image->comps[0], x, y, w, h),
                        sample8(image->comps[1], x, y, w, h),
                        sample8(image->comps[2], x, y, w, h),
                        r, g, b);
                if (nc >= 4) a = sample8(image->comps[3], x, y, w, h);
            } else {
                r = sample8(image->comps[0], x, y, w, h);
                g = sample8(image->comps[1], x, y, w, h);
                b = sample8(image->comps[2], x, y, w, h);
                if (nc >= 4) a = sample8(image->comps[3], x, y, w, h);
            }
            uint8_t* px = row + static_cast<size_t>(x) * 4;
            px[0] = static_cast<uint8_t>(r);
            px[1] = static_cast<uint8_t>(g);
            px[2] = static_cast<uint8_t>(b);
            px[3] = static_cast<uint8_t>(a);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

#if defined(EHVIEWER_HDR_CODECS)
// Same contract as jxl_hdr.cpp: linear straight-alpha half in a tagged gamut,
// then the shared packer / Ultra HDR encoder. Enumerated sRGB uses the sRGB
// EOTF. An embedded ICC is converted with skcms; unknown primaries land in
// linear BT.2020, matching JXL's ICC-only path.

#include <cmath>
#include <string>
#include <vector>

#include "hdr_encode.h"
#include "skcms.h"

namespace {

float unit_sample(const opj_image_comp_t& c, int x, int y, int outW, int outH) {
    if (!c.data || c.w <= 0 || c.h <= 0 || c.prec <= 0 || c.prec > 16 || outW <= 0 || outH <= 0) {
        return 0.f;
    }
    const int sx = outW == static_cast<int>(c.w)
        ? std::min(static_cast<int>(c.w) - 1, std::max(0, x))
        : std::min(static_cast<int>(c.w) - 1,
                   static_cast<int>((static_cast<int64_t>(x) * c.w) / outW));
    const int sy = outH == static_cast<int>(c.h)
        ? std::min(static_cast<int>(c.h) - 1, std::max(0, y))
        : std::min(static_cast<int>(c.h) - 1,
                   static_cast<int>((static_cast<int64_t>(y) * c.h) / outH));
    int v = c.data[static_cast<size_t>(sy) * static_cast<size_t>(c.w) + static_cast<size_t>(sx)];
    if (c.sgnd && c.prec < 16) v += 1 << (c.prec - 1);
    const int maxCode = (1 << c.prec) - 1;
    if (maxCode <= 0) return 0.f;
    float u = static_cast<float>(v) / static_cast<float>(maxCode);
    if (u < 0.f) return 0.f;
    if (u > 1.f) return 1.f;
    return u;
}

bool matrix_near(const skcms_Matrix3x3& a, const skcms_Matrix3x3& b) {
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            if (std::fabs(a.vals[i][j] - b.vals[i][j]) > 0.02f) return false;
        }
    }
    return true;
}

bool make_linear_profile(uhdr_color_gamut_t cg, skcms_ICCProfile* profile) {
    skcms_Init(profile);
    skcms_SetTransferFunction(profile, skcms_Identity_TransferFunction());
    skcms_Matrix3x3 to_xyz{};
    bool ok = false;
    if (cg == UHDR_CG_BT_709) {
        const skcms_ICCProfile* srgb = skcms_sRGB_profile();
        if (srgb && srgb->has_toXYZD50) {
            to_xyz = srgb->toXYZD50;
            ok = true;
        }
    } else if (cg == UHDR_CG_DISPLAY_P3) {
        ok = skcms_PrimariesToXYZD50(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f,
                                     0.3127f, 0.3290f, &to_xyz);
    } else if (cg == UHDR_CG_BT_2100) {
        ok = skcms_PrimariesToXYZD50(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f,
                                     0.3127f, 0.3290f, &to_xyz);
    }
    if (!ok) return false;
    skcms_SetXYZD50(profile, &to_xyz);
    return skcms_MakeUsableAsDestination(profile);
}

uhdr_color_gamut_t gamut_from_icc(const skcms_ICCProfile& src) {
    if (!src.has_toXYZD50) return UHDR_CG_BT_2100;
    const skcms_ICCProfile* srgb = skcms_sRGB_profile();
    if (srgb && srgb->has_toXYZD50 && matrix_near(src.toXYZD50, srgb->toXYZD50)) {
        return UHDR_CG_BT_709;
    }
    skcms_ICCProfile p3{};
    skcms_ICCProfile bt2020{};
    if (make_linear_profile(UHDR_CG_DISPLAY_P3, &p3) && matrix_near(src.toXYZD50, p3.toXYZD50)) {
        return UHDR_CG_DISPLAY_P3;
    }
    if (make_linear_profile(UHDR_CG_BT_2100, &bt2020) && matrix_near(src.toXYZD50, bt2020.toXYZD50)) {
        return UHDR_CG_BT_2100;
    }
    // ICC-only / custom primaries: convert into linear BT.2020 and tag that, like JXL.
    return UHDR_CG_BT_2100;
}

int transfer_from_icc(const uint8_t* icc, size_t n) {
    if (!icc || n < 4) return 0;
    const char* s = reinterpret_cast<const char*>(icc);
    const std::string text(s, s + n);
    if (text.find("2084") != std::string::npos || text.find("PQ") != std::string::npos) return 16;
    if (text.find("HLG") != std::string::npos || text.find("B67") != std::string::npos) return 18;
    return 0;
}

bool image_to_linear_f16(opj_image_t* image, std::vector<uint16_t>& out, unsigned& w, unsigned& h,
                         uhdr_color_gamut_t* out_cg, bool* out_hdr, int* out_tf,
                         bool composite_alpha) {
    w = image->comps[0].w;
    h = image->comps[0].h;
    if (w == 0 || h == 0) return false;
    const size_t npx = static_cast<size_t>(w) * static_cast<size_t>(h);
    std::vector<float> rgba(npx * 4, 0.f);
    const int iw = static_cast<int>(w);
    const int ih = static_cast<int>(h);
    const int nc = static_cast<int>(image->numcomps);
    const bool cmyk = image->color_space == OPJ_CLRSPC_CMYK;
    const bool ycc = image->color_space == OPJ_CLRSPC_SYCC;
    const bool have_icc = image->icc_profile_buf && image->icc_profile_len > 0;

    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            const size_t i = (static_cast<size_t>(y) * static_cast<size_t>(iw) + static_cast<size_t>(x)) * 4;
            float a = 1.f;
            if (!have_icc && nc == 1) {
                const float g = unit_sample(image->comps[0], x, y, iw, ih);
                rgba[i] = rgba[i + 1] = rgba[i + 2] = g;
            } else if (!have_icc && nc == 2) {
                const float g = unit_sample(image->comps[0], x, y, iw, ih);
                rgba[i] = rgba[i + 1] = rgba[i + 2] = g;
                a = unit_sample(image->comps[1], x, y, iw, ih);
            } else if (!have_icc && cmyk) {
                const float c = unit_sample(image->comps[0], x, y, iw, ih);
                const float m = unit_sample(image->comps[1], x, y, iw, ih);
                const float ye = unit_sample(image->comps[2], x, y, iw, ih);
                const float k = nc > 3 ? unit_sample(image->comps[3], x, y, iw, ih) : 0.f;
                rgba[i] = (1.f - c) * (1.f - k);
                rgba[i + 1] = (1.f - m) * (1.f - k);
                rgba[i + 2] = (1.f - ye) * (1.f - k);
            } else if (!have_icc && ycc) {
                int r, g, b;
                ycbcr_to_rgb(
                        static_cast<int>(unit_sample(image->comps[0], x, y, iw, ih) * 255.f),
                        static_cast<int>(unit_sample(image->comps[1], x, y, iw, ih) * 255.f),
                        static_cast<int>(unit_sample(image->comps[2], x, y, iw, ih) * 255.f),
                        r, g, b);
                rgba[i] = r / 255.f;
                rgba[i + 1] = g / 255.f;
                rgba[i + 2] = b / 255.f;
                if (nc >= 4) a = unit_sample(image->comps[3], x, y, iw, ih);
            } else {
                rgba[i] = unit_sample(image->comps[0], x, y, iw, ih);
                if (nc > 1) rgba[i + 1] = unit_sample(image->comps[1], x, y, iw, ih);
                if (nc > 2) rgba[i + 2] = unit_sample(image->comps[2], x, y, iw, ih);
                if (!cmyk && nc >= 4) a = unit_sample(image->comps[3], x, y, iw, ih);
                else if (cmyk && nc > 3) rgba[i + 3] = unit_sample(image->comps[3], x, y, iw, ih);
            }
            if (!have_icc || !cmyk) rgba[i + 3] = a;
        }
    }

    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    int tf = 0;
    bool hdr = false;
    if (have_icc) {
        skcms_ICCProfile src{};
        if (!skcms_Parse(image->icc_profile_buf, image->icc_profile_len, &src)) return false;
        cg = gamut_from_icc(src);
        tf = transfer_from_icc(image->icc_profile_buf, image->icc_profile_len);
        skcms_ICCProfile dst{};
        if (!make_linear_profile(cg, &dst)) return false;
        if (!skcms_Transform(rgba.data(), skcms_PixelFormat_RGBA_ffff, skcms_AlphaFormat_Unpremul,
                             &src, rgba.data(), skcms_PixelFormat_RGBA_ffff,
                             skcms_AlphaFormat_Unpremul, &dst, npx)) {
            return false;
        }
        hdr = tf == 16 || tf == 18;
    } else {
        for (size_t i = 0; i < npx; ++i) {
            rgba[i * 4 + 0] = srgb_eotf(rgba[i * 4 + 0]);
            rgba[i * 4 + 1] = srgb_eotf(rgba[i * 4 + 1]);
            rgba[i * 4 + 2] = srgb_eotf(rgba[i * 4 + 2]);
        }
    }

    float scale = 1.f;
    if (hdr && tf == 16) scale = kMaxLinear;
    out.resize(npx * 4);
    float peak = 0.f;
    for (size_t i = 0; i < npx; ++i) {
        float r = rgba[i * 4 + 0] * scale;
        float g = rgba[i * 4 + 1] * scale;
        float b = rgba[i * 4 + 2] * scale;
        float a = rgba[i * 4 + 3];
        if (!std::isfinite(a) || a < 0.f) a = 0.f;
        if (a > 1.f) a = 1.f;
        if (composite_alpha && a < 1.f) {
            r *= a;
            g *= a;
            b *= a;
            a = 1.f;
        }
        auto clamp_hf = [](float v) {
            if (!std::isfinite(v) || v < 0.f) return 0.f;
            if (v > kMaxLinear) return kMaxLinear;
            return v;
        };
        r = clamp_hf(r);
        g = clamp_hf(g);
        b = clamp_hf(b);
        peak = std::max(peak, std::max(r, std::max(g, b)));
        out[i * 4 + 0] = float_to_half(r);
        out[i * 4 + 1] = float_to_half(g);
        out[i * 4 + 2] = float_to_half(b);
        out[i * 4 + 3] = float_to_half(a);
    }
    if (!hdr && peak > 1.05f) hdr = true;
    if (out_cg) *out_cg = cg;
    if (out_hdr) *out_hdr = hdr;
    if (out_tf) *out_tf = tf;
    return true;
}

bool decode_linear(const uint8_t* data, int n, int maxEdge, std::vector<uint16_t>& rgba,
                   unsigned& w, unsigned& h, uhdr_color_gamut_t& cg, bool& hdr, int& tf,
                   bool composite_alpha) {
    opj_image_t* image = decode_on_worker(data, n, maxEdge);
    if (!image) return false;
    const bool ok = image_to_linear_f16(image, rgba, w, h, &cg, &hdr, &tf, composite_alpha);
    opj_image_destroy(image);
    return ok && !rgba.empty();
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJpeg2000BytesToDirect(
        JNIEnv* env, jclass, jbyteArray input, jint maxEdge, jboolean advancedColor,
        jintArray jOutInfo, jfloatArray jOutBoost) {
    if (!input || !jOutInfo || !jOutBoost) return nullptr;
    if (env->GetArrayLength(jOutInfo) < 6 || env->GetArrayLength(jOutBoost) < 1) return nullptr;
    const jsize n = env->GetArrayLength(input);
    if (n < 8) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return nullptr;
    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool hdr = false;
    int tf = 0;
    const bool ok = decode_linear(reinterpret_cast<const uint8_t*>(bytes), n, maxEdge, rgba, w, h,
                                  cg, hdr, tf, false);
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!ok) return nullptr;
    return pack_direct_to_jbyte_array(env, rgba, w, h, hdr, cg, advancedColor == JNI_TRUE, tf,
                                      jOutInfo, jOutBoost);
}

static jint encode_jpeg2000(JNIEnv* env, jbyteArray input, jstring output, jint maxEdge) {
    if (!input || !output) return -10;
    const jsize n = env->GetArrayLength(input);
    if (n < 8) return -12;
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return -13;
    const char* path = env->GetStringUTFChars(output, nullptr);
    if (!path) {
        env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
        return -14;
    }
    std::vector<uint16_t> rgba;
    unsigned w = 0, h = 0;
    uhdr_color_gamut_t cg = UHDR_CG_BT_709;
    bool hdr = false;
    int tf = 0;
    const bool ok = decode_linear(reinterpret_cast<const uint8_t*>(bytes), n, maxEdge, rgba, w, h,
                                  cg, hdr, tf, true);
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    if (!ok) {
        env->ReleaseStringUTFChars(output, path);
        return -20;
    }
    (void)tf;
    const float peak = maxEdge > 0 ? thumb_fixed_peak_nits(hdr) : 0.f;
    const int rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), path, cg, peak, hdr);
    env->ReleaseStringUTFChars(output, path);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000BytesToUltraHdr(
        JNIEnv* env, jclass, jbyteArray input, jstring output) {
    return encode_jpeg2000(env, input, output, 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000BytesToUltraHdrMaxEdge(
        JNIEnv* env, jclass, jbyteArray input, jstring output, jint maxEdge) {
    return encode_jpeg2000(env, input, output, maxEdge);
}

#endif
