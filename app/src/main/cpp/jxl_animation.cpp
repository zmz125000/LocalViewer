#include "jxl_rs.h"
#include "reader_animation.h"

namespace {
constexpr size_t kMaxEncoded = 128ull * 1024 * 1024;

const uint8_t* input(JNIEnv* env, jobject source, jlong& size) {
    if (!source) return nullptr;
    size = env->GetDirectBufferCapacity(source);
    return size > 0 && size <= kMaxEncoded ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(source)) : nullptr;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeJxlHasAnimation(JNIEnv* env, jclass, jobject source) try {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    if (!bytes) return false;
    JxlRsReader decoder(bytes, size);
    return decoder && decoder.info().animated;
} catch (...) { return false; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeAnimationOpen(JNIEnv* env, jclass, jobject source, jboolean avif) try {
    jlong size = 0;
    const auto* bytes = input(env, source, size);
    if (!bytes) return 0;
    auto animation = avif ? open_avif_animation(bytes, size) : open_jxl_animation(bytes, size);
    return reinterpret_cast<jlong>(animation.release());
} catch (...) { return 0; }

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeAnimationLoops(JNIEnv*, jclass, jlong handle) {
    return reinterpret_cast<ReaderAnimation*>(handle)->loops;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeAnimationNext(JNIEnv* env, jclass, jlong handle,
        jboolean restart, jboolean advanced, jintArray info, jfloatArray boost, jintArray duration) try {
    return reinterpret_cast<ReaderAnimation*>(handle)->next(env, restart, advanced, info, boost, duration);
} catch (...) { return nullptr; }

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_coil_AnimatedJxlDrawableKt_nativeAnimationClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ReaderAnimation*>(handle);
}
