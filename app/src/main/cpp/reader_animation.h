#pragma once

#include <jni.h>
#include <memory>
#include <cstdint>

class ReaderAnimation {
public:
    virtual ~ReaderAnimation() = default;
    // 0 is end of animation; positive values are frame durations in milliseconds.
    virtual jbyteArray next(JNIEnv*, bool restart, bool advanced, jintArray info,
                            jfloatArray boost, jintArray duration) = 0;
    int loops = 0;  // zero means infinite
};

std::unique_ptr<ReaderAnimation> open_jxl_animation(const uint8_t*, size_t);
std::unique_ptr<ReaderAnimation> open_avif_animation(const uint8_t*, size_t);
