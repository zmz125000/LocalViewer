#include "reader_pixels.h"

#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace {
struct ReaderPixels {
    std::vector<uint8_t> bytes;
    std::vector<uint16_t> halves;
    ReaderPixels* previous = nullptr;
    void* data() { return halves.empty() ? static_cast<void*>(bytes.data()) : halves.data(); }
    size_t size() const { return halves.empty() ? bytes.size() : halves.size() * sizeof(uint16_t); }
};

// decodePacked is synchronous and never suspends or switches threads. Separate
// decoder threads each own their capture; the previous pointer supports nesting.
thread_local ReaderPixels* active = nullptr;

void throw_argument(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
}

bool retain_reader_pixels(std::vector<uint8_t>& pixels) {
    if (!active) return false;
    active->bytes = std::move(pixels);
    active->halves.clear();
    return true;
}

bool retain_reader_pixels(std::vector<uint16_t>& pixels) {
    if (!active) return false;
    active->halves = std::move(pixels);
    active->bytes.clear();
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_beginNativeReaderPixels(JNIEnv* env, jclass) {
    auto* pixels = new (std::nothrow) ReaderPixels;
    if (!pixels) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Native reader pixels");
        return 0;
    }
    pixels->previous = active;
    active = pixels;
    return reinterpret_cast<jlong>(pixels);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_endNativeReaderPixels(JNIEnv* env, jclass, jlong handle) {
    auto* pixels = reinterpret_cast<ReaderPixels*>(handle);
    if (!pixels || active != pixels) {
        throw_argument(env, "Native reader capture must end on its decoding thread");
        return nullptr;
    }
    active = pixels->previous;
    if (!pixels->size()) return nullptr; // Platform/preview fallback used its existing storage.
    if (pixels->size() > static_cast<size_t>(std::numeric_limits<jint>::max())) {
        throw_argument(env, "Native reader frame exceeds ByteBuffer capacity");
        return nullptr;
    }
    return env->NewDirectByteBuffer(pixels->data(), static_cast<jlong>(pixels->size()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_releaseNativeReaderPixels(JNIEnv*, jclass, jlong handle) {
    auto* pixels = reinterpret_cast<ReaderPixels*>(handle);
    if (active == pixels && pixels) active = pixels->previous; // Decode threw before end.
    delete pixels;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_copyNativeReaderPixelsToAHB(
        JNIEnv* env, jclass, jobject source, jobject destination) {
    auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    const jlong length = env->GetDirectBufferCapacity(source);
    auto* buffer = AHardwareBuffer_fromHardwareBuffer(env, destination);
    if (!bytes || !buffer) {
        throw_argument(env, "Expected direct pixels and a HardwareBuffer");
        return;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    const uint64_t row = static_cast<uint64_t>(desc.width) * 8;
    if (desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT || desc.layers != 1 ||
        desc.stride < desc.width || length < 0 || row * desc.height > static_cast<uint64_t>(length)) {
        throw_argument(env, "Invalid RGBA_F16 buffer dimensions/format");
        return;
    }
    void* target = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, nullptr, &target) != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "HardwareBuffer lock failed");
        return;
    }
    const size_t stride = static_cast<size_t>(desc.stride) * 8;
    if (desc.stride == desc.width) {
        std::memcpy(target, bytes, static_cast<size_t>(row) * desc.height);
    } else {
        for (uint32_t y = 0; y < desc.height; ++y) {
            std::memcpy(static_cast<uint8_t*>(target) + y * stride, bytes + y * row, static_cast<size_t>(row));
        }
    }
    if (AHardwareBuffer_unlock(buffer, nullptr) != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "HardwareBuffer unlock failed");
    }
}
