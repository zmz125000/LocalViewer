#pragma once

#include <jni.h>
#include <cstdint>
#include <limits>
#include <sys/mman.h>
#include <sys/stat.h>

/** Borrow a regular descriptor without changing its position or owning its lifetime. */
class ThumbnailMappedInput {
public:
    const uint8_t* data = nullptr;
    size_t size = 0;

    explicit ThumbnailMappedInput(int fd) {
        struct stat st{};
        if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
            static_cast<uint64_t>(st.st_size) > std::numeric_limits<size_t>::max()) return;
        const size_t length = static_cast<size_t>(st.st_size);
        void* mapped = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped == MAP_FAILED) return;
        data = static_cast<const uint8_t*>(mapped);
        size = length;
    }

    ~ThumbnailMappedInput() { reset(); }
    void reset() {
        if (data) munmap(const_cast<uint8_t*>(data), size);
        data = nullptr;
        size = 0;
    }
    ThumbnailMappedInput(const ThumbnailMappedInput&) = delete;
    ThumbnailMappedInput& operator=(const ThumbnailMappedInput&) = delete;
};

/** Release output-path JNI resources even if a codec allocation throws. */
class ThumbnailOutputPath {
    JNIEnv* env_;
    jstring value_;
    const char* path_;
public:
    ThumbnailOutputPath(JNIEnv* env, jstring value)
        : env_(env), value_(value), path_(env->GetStringUTFChars(value, nullptr)) {}
    ~ThumbnailOutputPath() { if (path_) env_->ReleaseStringUTFChars(value_, path_); }
    const char* get() const { return path_; }
    ThumbnailOutputPath(const ThumbnailOutputPath&) = delete;
    ThumbnailOutputPath& operator=(const ThumbnailOutputPath&) = delete;
};

class ThumbnailJniBytes {
    JNIEnv* env_;
    jbyteArray array_;
    jbyte* bytes_;
public:
    ThumbnailJniBytes(JNIEnv* env, jbyteArray array)
        : env_(env), array_(array), bytes_(env->GetByteArrayElements(array, nullptr)) {}
    ~ThumbnailJniBytes() { reset(); }
    const uint8_t* data() const { return reinterpret_cast<const uint8_t*>(bytes_); }
    void reset() {
        if (bytes_) env_->ReleaseByteArrayElements(array_, bytes_, JNI_ABORT);
        bytes_ = nullptr;
    }
    ThumbnailJniBytes(const ThumbnailJniBytes&) = delete;
    ThumbnailJniBytes& operator=(const ThumbnailJniBytes&) = delete;
};
