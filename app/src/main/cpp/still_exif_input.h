#pragma once

#include <jni.h>
#include <cstdint>
#include <vector>

// Bounded JNI reads from an existing heap buffer or forward-only SAF stream.
// No whole-file Java array, direct-buffer copy, temporary file, or GC pinning.
class StillExifInput {
    JNIEnv* env_;
    jobject input_;
    jmethodID read_;
public:
    StillExifInput(JNIEnv* env, jobject input) : env_(env), input_(input), read_(nullptr) {
        if (!input) return;
        auto cls = env->GetObjectClass(input);
        if (cls) {
            read_ = env->GetMethodID(cls, "readAt", "(JI)[B");
            env->DeleteLocalRef(cls);
        }
    }
    bool read(uint64_t offset, size_t size, std::vector<uint8_t>& out) {
        if (!read_ || offset > INT64_MAX || size > 1024 * 1024) return false;
        auto bytes = static_cast<jbyteArray>(env_->CallObjectMethod(input_, read_, static_cast<jlong>(offset), static_cast<jint>(size)));
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            if (bytes) env_->DeleteLocalRef(bytes);
            return false;
        }
        if (!bytes) return false;
        const jsize count = env_->GetArrayLength(bytes);
        out.resize(static_cast<size_t>(count));
        if (count) env_->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte*>(out.data()));
        env_->DeleteLocalRef(bytes);
        return !env_->ExceptionCheck();
    }
};
