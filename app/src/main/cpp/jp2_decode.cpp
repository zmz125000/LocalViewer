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

    DecodeJob job;
    job.data = data;
    job.n = static_cast<int>(n);
    job.maxEdge = maxEdge;
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8u * 1024u * 1024u);
    const int started = pthread_create(&thread, &attr, decodeJob, &job);
    pthread_attr_destroy(&attr);
    if (started != 0) return nullptr;
    pthread_join(thread, nullptr);
    session.releaseInput();
    session.image = job.image;
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
        // Config.ARGB_8888 is Skia kN32. On little-endian the bytes are B,G,R,A.
        auto* row = reinterpret_cast<uint32_t*>(dst + static_cast<size_t>(y) * info.stride);
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
            row[x] = (static_cast<uint32_t>(a) << 24) |
                     (static_cast<uint32_t>(r) << 16) |
                     (static_cast<uint32_t>(g) << 8) |
                     static_cast<uint32_t>(b);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}
