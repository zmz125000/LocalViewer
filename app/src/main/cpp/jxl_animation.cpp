#include <android/bitmap.h>
#include <jni.h>
#include <jxl/cms.h>
#include <jxl/color_encoding.h>
#include <jxl/decode.h>
#include <jxl/decode_cxx.h>
#include <jxl/encode.h>
#include <jxl/resizable_parallel_runner_cxx.h>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <vector>

namespace {
struct Animation {
    std::vector<uint8_t> encoded;
    JxlResizableParallelRunnerPtr runner;
    JxlDecoderPtr decoder;
    JxlBasicInfo info{};
    bool ended = false;

    bool reset() {
        decoder = JxlDecoderMake(nullptr);
        runner = JxlResizableParallelRunnerMake(nullptr);
        const auto* cms = JxlGetDefaultCms();
        if (!decoder || !runner || !cms ||
            JxlDecoderSetCms(decoder.get(), *cms) != JXL_DEC_SUCCESS ||
            JxlDecoderSubscribeEvents(decoder.get(), JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING |
                                                  JXL_DEC_FRAME | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS ||
            JxlDecoderSetParallelRunner(decoder.get(), JxlResizableParallelRunner, runner.get()) != JXL_DEC_SUCCESS ||
            JxlDecoderSetInput(decoder.get(), encoded.data(), encoded.size()) != JXL_DEC_SUCCESS) return false;
        JxlDecoderCloseInput(decoder.get());
        ended = false;
        return true;
    }
};

const uint8_t* input(JNIEnv* env, jobject source, jlong& size) {
    size = env->GetDirectBufferCapacity(source);
    return size > 0 ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(source)) : nullptr;
}

bool read_info(Animation& animation) {
    for (;;) {
        switch (JxlDecoderProcessInput(animation.decoder.get())) {
            case JXL_DEC_BASIC_INFO:
                if (JxlDecoderGetBasicInfo(animation.decoder.get(), &animation.info) != JXL_DEC_SUCCESS) return false;
                return animation.info.have_animation && animation.info.xsize > 0 && animation.info.ysize > 0 &&
                       static_cast<uint64_t>(animation.info.xsize) * animation.info.ysize <= 16ull * 1024 * 1024;
            case JXL_DEC_ERROR:
            case JXL_DEC_NEED_MORE_INPUT:
            case JXL_DEC_SUCCESS: return false;
            default: break;
        }
    }
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlHasAnimation(JNIEnv* env, jclass, jobject source) {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    if (!bytes) return JNI_FALSE;
    if (size > 128ll * 1024 * 1024) return JNI_FALSE;
    auto decoder = JxlDecoderMake(nullptr);
    if (!decoder || JxlDecoderSubscribeEvents(decoder.get(), JXL_DEC_BASIC_INFO) != JXL_DEC_SUCCESS ||
        JxlDecoderSetInput(decoder.get(), bytes, size) != JXL_DEC_SUCCESS) return JNI_FALSE;
    JxlDecoderCloseInput(decoder.get());
    for (;;) {
        const auto status = JxlDecoderProcessInput(decoder.get());
        if (status == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info{};
            return JxlDecoderGetBasicInfo(decoder.get(), &info) == JXL_DEC_SUCCESS && info.have_animation &&
                   info.xsize > 0 && info.ysize > 0 &&
                   static_cast<uint64_t>(info.xsize) * info.ysize <= 16ull * 1024 * 1024;
        }
        if (status == JXL_DEC_ERROR || status == JXL_DEC_SUCCESS || status == JXL_DEC_NEED_MORE_INPUT) return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlCreate(JNIEnv* env, jclass, jobject source) {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    if (!bytes || size > 128ll * 1024 * 1024) return 0;
    auto animation = std::make_unique<Animation>();
    animation->encoded.assign(bytes, bytes + size);
    if (!animation->reset() || !read_info(*animation)) return 0;
    JxlResizableParallelRunnerSetThreads(animation->runner.get(),
        std::min<size_t>(4, JxlResizableParallelRunnerSuggestThreads(animation->info.xsize, animation->info.ysize)));
    return reinterpret_cast<jlong>(animation.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlInfo(JNIEnv*, jclass, jlong handle) {
    auto* animation = reinterpret_cast<Animation*>(handle);
    if (!animation) return 0;
    return (static_cast<jlong>(animation->info.xsize) << 40) |
           (static_cast<jlong>(animation->info.ysize) << 16) |
           std::min<uint32_t>(animation->info.animation.num_loops, 0xffff);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlNext(JNIEnv* env, jclass, jlong handle, jobject bitmap, jboolean restart) {
    auto* animation = reinterpret_cast<Animation*>(handle);
    if (!animation) return -1;
    if (restart && !animation->reset()) return -1;
    if (animation->ended) return 0;
    AndroidBitmapInfo bitmap_info{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || bitmap_info.width != animation->info.xsize ||
        bitmap_info.height != animation->info.ysize || bitmap_info.stride != animation->info.xsize * 4) return -1;
    void* pixels = nullptr;
    uint32_t duration = 1;
    const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    for (;;) {
        const auto status = JxlDecoderProcessInput(animation->decoder.get());
        switch (status) {
            case JXL_DEC_COLOR_ENCODING: {
                JxlColorEncoding srgb{};
                JxlColorEncodingSetToSRGB(&srgb, JXL_FALSE);
                if (JxlDecoderSetOutputColorProfile(animation->decoder.get(), &srgb, nullptr, 0) != JXL_DEC_SUCCESS) goto error;
                break;
            }
            case JXL_DEC_FRAME: {
                JxlFrameHeader header{};
                if (JxlDecoderGetFrameHeader(animation->decoder.get(), &header) != JXL_DEC_SUCCESS) goto error;
                duration = header.duration;
                break;
            }
            case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
                size_t size = 0;
                if (JxlDecoderImageOutBufferSize(animation->decoder.get(), &format, &size) != JXL_DEC_SUCCESS ||
                    size != static_cast<size_t>(bitmap_info.stride) * bitmap_info.height ||
                    AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) goto error;
                if (JxlDecoderSetImageOutBuffer(animation->decoder.get(), &format, pixels, size) != JXL_DEC_SUCCESS) goto error;
                break;
            }
            case JXL_DEC_FULL_IMAGE: {
                if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
                pixels = nullptr;
                if (duration == 0) break; // Internal reference frame, not a displayed animation frame.
                const auto& ticks = animation->info.animation;
                if (!ticks.tps_numerator || !ticks.tps_denominator) return 100;
                const uint64_t ms = static_cast<uint64_t>(duration) * ticks.tps_denominator * 1000 / ticks.tps_numerator;
                return static_cast<jint>(std::clamp<uint64_t>(ms, 20, 60000));
            }
            case JXL_DEC_SUCCESS:
                animation->ended = true;
                return 0;
            case JXL_DEC_ERROR:
            case JXL_DEC_NEED_MORE_INPUT: goto error;
            default: break;
        }
    }
error:
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
    animation->ended = true;
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Animation*>(handle);
}
