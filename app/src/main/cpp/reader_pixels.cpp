#include "reader_pixels.h"

#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <android/hardware_buffer_jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdlib>
#include <limits>
#include <new>
#include <utility>

namespace {
struct ReaderPixels {
    std::vector<uint8_t> bytes;
    std::vector<uint16_t> halves;
    ReaderPixels* previous = nullptr;
    bool exporting = false;
    bool keep_sdr = true;
    void* owned = nullptr;
    size_t owned_size = 0;
    void drop_owned() { free(owned); owned = nullptr; owned_size = 0; }
    ~ReaderPixels() { drop_owned(); }
    void* data() { return owned ? owned : halves.empty() ? static_cast<void*>(bytes.data()) : halves.data(); }
    size_t size() const { return owned ? owned_size : halves.empty() ? bytes.size() : halves.size() * sizeof(uint16_t); }
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
    active->drop_owned();
    active->bytes = std::move(pixels);
    active->halves.clear();
    return true;
}

bool retain_reader_pixels(std::vector<uint16_t>& pixels) {
    if (!active) return false;
    active->drop_owned();
    active->halves = std::move(pixels);
    active->bytes.clear();
    return true;
}

bool export_pixels_capture() { return active && active->exporting; }
bool export_pixels_keep_sdr() { return !active || !active->exporting || active->keep_sdr; }

static jbyteArray capture_bitmap(JNIEnv* env, jobject bitmap) {
    if (!bitmap || !active) return nullptr;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const size_t bpp = info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 ? 8 :
                       info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ? 4 : 0;
    const uint64_t count = static_cast<uint64_t>(info.width) * info.height;
    const size_t row = static_cast<size_t>(info.width) * bpp;
    if (!bpp || !count || count > 80000000u || info.stride < row) return nullptr;
    try {
        std::vector<uint8_t> pixels(static_cast<size_t>(count) * bpp);
        void* source = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &source) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
        if (!source) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return nullptr;
        }
        for (unsigned y = 0; y < info.height; ++y) {
            memcpy(pixels.data() + y * row, static_cast<const uint8_t*>(source) + y * info.stride, row);
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        retain_reader_pixels(pixels);
        return env->NewByteArray(0);
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Native bitmap capture");
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_captureExportBitmap(JNIEnv* env, jclass, jobject bitmap) {
    return export_pixels_capture() ? capture_bitmap(env, bitmap) : nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_captureStillBitmap(JNIEnv* env, jclass, jobject bitmap) {
    return capture_bitmap(env, bitmap);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_beginNativeExportPixels(JNIEnv* env, jclass, jboolean keep_sdr) {
    auto* pixels = new (std::nothrow) ReaderPixels;
    if (!pixels) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Native export pixels");
        return 0;
    }
    pixels->previous = active;
    pixels->exporting = true;
    pixels->keep_sdr = keep_sdr == JNI_TRUE;
    active = pixels;
    return reinterpret_cast<jlong>(pixels);
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

// Borrow the descriptor; Kotlin keeps it open for this synchronous decode scope.
extern "C" JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_mapStillInput(JNIEnv* env, jclass, jint fd) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > std::numeric_limits<int32_t>::max()) return nullptr;
    const size_t size = static_cast<size_t>(st.st_size);
    void* data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (data == MAP_FAILED) return nullptr;
    jobject buffer = env->NewDirectByteBuffer(data, static_cast<jlong>(size));
    if (!buffer) munmap(data, size);
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_unmapStillInput(JNIEnv* env, jclass, jobject buffer) {
    void* data = env->GetDirectBufferAddress(buffer);
    const jlong size = env->GetDirectBufferCapacity(buffer);
    if (data && size > 0) munmap(data, static_cast<size_t>(size));
}

// Scratch buffers are explicitly released, independent of Java GC scheduling.
extern "C" JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_allocateStillBuffer(JNIEnv* env, jclass, jint size) {
    if (size <= 0) return nullptr;
    void* data = calloc(1, static_cast<size_t>(size));
    if (!data) return nullptr;
    jobject buffer = env->NewDirectByteBuffer(data, size);
    if (!buffer) free(data);
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_releaseStillBuffer(JNIEnv* env, jclass, jobject buffer) {
    free(env->GetDirectBufferAddress(buffer));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_captureStillPixels(JNIEnv* env, jclass, jobject buffer) {
    if (!active || !buffer) return nullptr;
    void* data = env->GetDirectBufferAddress(buffer);
    const jlong size = env->GetDirectBufferCapacity(buffer);
    if (!data || size <= 0) return nullptr;
    // Allocate the success token before adopting; failure leaves ownership with Kotlin.
    jbyteArray token = env->NewByteArray(0);
    if (!token) return nullptr;
    active->drop_owned();
    active->bytes.clear();
    active->halves.clear();
    active->owned = data;
    active->owned_size = static_cast<size_t>(size);
    return token;
}
