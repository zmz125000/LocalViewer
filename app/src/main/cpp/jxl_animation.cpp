#include "jxl_rs.h"
#include "skcms.h"

#include <android/bitmap.h>
#include <jni.h>
#include <jxl/color_encoding.h>
#include <optional>

namespace {
constexpr size_t kMaxEncoded = 128ull * 1024 * 1024;
constexpr size_t kMaxPixels = 16ull * 1024 * 1024;

bool can_animate(const JxlRsReader& decoder) {
    if (!decoder) return false;
    const auto& info = decoder.info();
    return info.animated && info.xsize && info.ysize && uint64_t(info.xsize) * info.ysize <= kMaxPixels;
}

struct Animation {
    std::vector<uint8_t> encoded;
    std::optional<JxlRsReader> decoder;
    std::vector<uint8_t> icc, black, alpha;
    skcms_ICCProfile profile{};
    bool transform = false;
    bool ended = false;

    bool reset() {
        decoder.emplace(encoded.data(), encoded.size());
        if (!can_animate(*decoder)) return false;
        const auto& info = decoder->info();
        if (!decoder->set_format(JxlRsFormat::u8, info.has_black)) return false;
        JxlRsColor color{};
        transform = !decoder->color(color) || color.color_space != JXL_COLOR_SPACE_RGB ||
            color.primaries != JXL_PRIMARIES_SRGB || color.white_point != JXL_WHITE_POINT_D65 ||
            color.transfer_function != JXL_TRANSFER_FUNCTION_SRGB;
        icc = transform ? decoder->icc() : std::vector<uint8_t>{};
        if (transform && !skcms_Parse(icc.data(), icc.size(), &profile)) return false;
        const bool cmyk = transform && profile.data_color_space == skcms_Signature_CMYK;
        if (cmyk && !info.has_black) return false;
        const size_t count = size_t(info.xsize) * info.ysize;
        black.resize(info.has_black ? count : 0);
        alpha.resize(cmyk && info.has_alpha ? count : 0);
        ended = false;
        return true;
    }

    bool convert_to_srgb(uint8_t* pixels, size_t count) {
        if (!transform) return true;
        const bool cmyk = profile.data_color_space == skcms_Signature_CMYK;
        if (cmyk) {
            for (size_t i = 0; i < count; ++i) {
                if (!alpha.empty()) alpha[i] = pixels[i * 4 + 3];
                pixels[i * 4 + 3] = black[i];
            }
        }
        if (!skcms_Transform(pixels, skcms_PixelFormat_RGBA_8888, skcms_AlphaFormat_Unpremul, &profile,
                             pixels, skcms_PixelFormat_RGBA_8888,
                             cmyk ? skcms_AlphaFormat_Opaque : skcms_AlphaFormat_Unpremul,
                             skcms_sRGB_profile(), count)) return false;
        for (size_t i = 0; i < alpha.size(); ++i) pixels[i * 4 + 3] = alpha[i];
        return true;
    }
};

const uint8_t* input(JNIEnv* env, jobject source, jlong& size) {
    if (!source) return nullptr;
    size = env->GetDirectBufferCapacity(source);
    return size > 0 && size <= kMaxEncoded ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(source)) : nullptr;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlHasAnimation(JNIEnv* env, jclass, jobject source) {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    return bytes && can_animate(JxlRsReader(bytes, size));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlCreate(JNIEnv* env, jclass, jobject source) try {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    if (!bytes) return 0;
    auto animation = std::make_unique<Animation>();
    animation->encoded.assign(bytes, bytes + size);
    return animation->reset() ? reinterpret_cast<jlong>(animation.release()) : 0;
} catch (...) { return 0; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlInfo(JNIEnv*, jclass, jlong handle) {
    const auto* animation = reinterpret_cast<Animation*>(handle);
    if (!animation || !*animation->decoder) return 0;
    const auto& info = animation->decoder->info();
    return (static_cast<jlong>(info.xsize) << 40) |
           (static_cast<jlong>(info.ysize) << 16) | std::min(info.loops, 0xffffu);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlNext(JNIEnv* env, jclass, jlong handle, jobject bitmap, jboolean restart) try {
    auto* animation = reinterpret_cast<Animation*>(handle);
    if (!animation || (restart && !animation->reset())) return -1;
    if (animation->ended) return 0;
    auto& decoder = *animation->decoder;
    const auto& info = decoder.info();
    AndroidBitmapInfo bitmap_info{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || bitmap_info.width != info.xsize ||
        bitmap_info.height != info.ysize || bitmap_info.stride != info.xsize * 4) return -1;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    const size_t count = size_t(info.xsize) * info.ysize;
    double duration_ms = 0;
    int status = decoder.next(pixels, count * 4, duration_ms, animation->black.data(), animation->black.size());
    if (status == 1 && !animation->convert_to_srgb(static_cast<uint8_t*>(pixels), count)) status = -1;
    AndroidBitmap_unlockPixels(env, bitmap);
    if (status != 1) {
        animation->ended = true;
        return status;
    }
    return duration_ms > 0 ? static_cast<jint>(std::clamp(duration_ms, 20.0, 60000.0)) : 100;
} catch (...) { return -1; }

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Animation*>(handle);
}
