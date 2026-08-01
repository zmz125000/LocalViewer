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
#include <memory>
#include <string>
#include <vector>

#include "ultrahdr_api.h"

// jxrlib (Microsoft / brion jpegxr packaging)
extern "C" {
#include "JXRGlue.h"
}

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

// Decode JXR to packed RGBA half-float linear scRGB (A=1).
bool decode_jxr_to_rgba_f16(const char* path, std::vector<uint16_t>& out_rgba, unsigned& w,
                            unsigned& h) {
    PKImageDecode* decoder = nullptr;
    ERR err = PKCodecFactory_CreateDecoderFromFile(path, &decoder);
    if (Failed(err) || !decoder) {
        ALOGE("CreateDecoderFromFile failed: %d for %s", err, path);
        return false;
    }

    struct DecoderGuard {
        PKImageDecode* d;
        ~DecoderGuard() {
            if (d) PKImageDecode_Release(&d);
        }
    } guard{decoder};

    PKPixelFormatGUID guid{};
    err = decoder->GetPixelFormat(decoder, &guid);
    if (Failed(err)) {
        ALOGE("GetPixelFormat failed: %d", err);
        return false;
    }
    JxrPix pix = classify_guid(guid);
    if (pix == JxrPix::Unsupported) {
        ALOGE("Unsupported JXR pixel format");
        return false;
    }

    I32 width = 0, height = 0;
    err = decoder->GetSize(decoder, &width, &height);
    if (Failed(err) || width <= 0 || height <= 0) {
        ALOGE("GetSize failed: %d", err);
        return false;
    }
    w = static_cast<unsigned>(width);
    h = static_cast<unsigned>(height);

    const bool rgb_half = guid_eq(guid, GUID_PKPixelFormat64bppRGBHalf);
    const bool rgb_float = guid_eq(guid, GUID_PKPixelFormat128bppRGBFloat);
    int bpp = bpp_of(pix);
    if (rgb_half) bpp = 6;
    if (rgb_float) bpp = 12;

    const size_t stride = static_cast<size_t>(w) * static_cast<size_t>(bpp);
    std::vector<uint8_t> raw(stride * h);

    PKRect rect{};
    rect.X = 0;
    rect.Y = 0;
    rect.Width = width;
    rect.Height = height;
    err = decoder->Copy(decoder, &rect, raw.data(), static_cast<U32>(stride));
    if (Failed(err)) {
        ALOGE("Copy failed: %d", err);
        return false;
    }

    out_rgba.resize(static_cast<size_t>(w) * h * 4);
    if (pix == JxrPix::RgbaF16) {
        if (rgb_half) {
            for (unsigned i = 0; i < w * h; i++) {
                const uint16_t* src = reinterpret_cast<const uint16_t*>(raw.data() + i * 6);
                out_rgba[i * 4 + 0] = src[0];
                out_rgba[i * 4 + 1] = src[1];
                out_rgba[i * 4 + 2] = src[2];
                out_rgba[i * 4 + 3] = float_to_half(1.0f);
            }
        } else {
            memcpy(out_rgba.data(), raw.data(), out_rgba.size() * sizeof(uint16_t));
        }
        return true;
    }
    if (pix == JxrPix::RgbaF32) {
        if (rgb_float) {
            const float* src = reinterpret_cast<const float*>(raw.data());
            for (unsigned i = 0; i < w * h; i++) {
                out_rgba[i * 4 + 0] = float_to_half(src[i * 3 + 0]);
                out_rgba[i * 4 + 1] = float_to_half(src[i * 3 + 1]);
                out_rgba[i * 4 + 2] = float_to_half(src[i * 3 + 2]);
                out_rgba[i * 4 + 3] = float_to_half(1.0f);
            }
        } else {
            const float* src = reinterpret_cast<const float*>(raw.data());
            for (unsigned i = 0; i < w * h; i++) {
                out_rgba[i * 4 + 0] = float_to_half(src[i * 4 + 0]);
                out_rgba[i * 4 + 1] = float_to_half(src[i * 4 + 1]);
                out_rgba[i * 4 + 2] = float_to_half(src[i * 4 + 2]);
                out_rgba[i * 4 + 3] = float_to_half(1.0f);
            }
        }
        return true;
    }
    if (pix == JxrPix::Rgb101010) {
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
        return true;
    }
    return false;
}

bool write_file(const char* path, const void* data, size_t size) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

int encode_linear_rgba_f16_to_uhdr(unsigned w, unsigned h, const uint16_t* rgba, const char* out_path) {
    uhdr_codec_private_t* enc = uhdr_create_encoder();
    if (!enc) {
        ALOGE("uhdr_create_encoder failed");
        return -1;
    }

    uhdr_raw_image_t img{};
    img.fmt = UHDR_IMG_FMT_64bppRGBAHalfFloat;
    img.cg = UHDR_CG_BT_709;  // scRGB primaries ≈ BT.709
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
    err = uhdr_enc_set_quality(enc, 90, UHDR_BASE_IMG);
    if (err.error_code != UHDR_CODEC_OK) {
        ALOGE("uhdr_enc_set_quality base: %s", err.has_detail ? err.detail : "error");
    }
    err = uhdr_enc_set_quality(enc, 85, UHDR_GAIN_MAP_IMG);
    err = uhdr_enc_set_preset(enc, UHDR_USAGE_BEST_QUALITY);
    err = uhdr_enc_set_output_format(enc, UHDR_CODEC_JPG);
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
    ALOGI("Wrote Ultra HDR %ux%u → %s (%zu bytes)", w, h, out_path, stream->data_sz);
    uhdr_release_encoder(enc);
    return 0;
}

}  // namespace

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
        rc = encode_linear_rgba_f16_to_uhdr(w, h, rgba.data(), out_path);
    } else {
        rc = -21;
    }

    env->ReleaseStringUTFChars(jInput, in_path);
    env->ReleaseStringUTFChars(jOutput, out_path);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_encodeLinearRgbaF16ToUltraHdr(
        JNIEnv* env, jclass, jint width, jint height, jbyteArray jRgba, jstring jOutput) {
    if (width <= 0 || height <= 0 || !jRgba || !jOutput) return -10;
    const size_t need = static_cast<size_t>(width) * static_cast<size_t>(height) * 8;
    jsize len = env->GetArrayLength(jRgba);
    if (static_cast<size_t>(len) < need) return -12;
    jbyte* bytes = env->GetByteArrayElements(jRgba, nullptr);
    if (!bytes) return -13;
    const char* out_path = env->GetStringUTFChars(jOutput, nullptr);
    if (!out_path) {
        env->ReleaseByteArrayElements(jRgba, bytes, JNI_ABORT);
        return -14;
    }
    int rc = encode_linear_rgba_f16_to_uhdr(static_cast<unsigned>(width), static_cast<unsigned>(height),
                                            reinterpret_cast<const uint16_t*>(bytes), out_path);
    env->ReleaseStringUTFChars(jOutput, out_path);
    env->ReleaseByteArrayElements(jRgba, bytes, JNI_ABORT);
    return rc;
}
