#pragma once

#include <jni.h>
#include <cstdint>
#include <vector>

// Empty payload explicitly means the decoder inspected metadata and found none.
// Only the small TIFF metadata crosses JNI, never compressed image pixels.
inline void publish_decoded_exif(JNIEnv* env, jobjectArray sink, const std::vector<uint8_t>& payload, bool jxl) {
    if (!sink || env->GetArrayLength(sink) < 1) return;
    size_t offset = 0;
    size_t count = payload.size();
    if (jxl && count) {
        if (count < 4) count = 0;
        else {
            const uint32_t tiff = (uint32_t(payload[0]) << 24) | (uint32_t(payload[1]) << 16) |
                (uint32_t(payload[2]) << 8) | uint32_t(payload[3]);
            if (tiff > count - 4) count = 0;
            else { offset = 4 + static_cast<size_t>(tiff); count -= offset; }
        }
    }
    if (count > 1024 * 1024) count = 0;
    auto bytes = env->NewByteArray(static_cast<jsize>(count));
    if (!bytes) return;
    if (count) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(payload.data() + offset));
    env->SetObjectArrayElement(sink, 0, bytes);
    env->DeleteLocalRef(bytes);
}
