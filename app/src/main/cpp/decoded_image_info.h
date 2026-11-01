#pragma once
#include <jni.h>

// Original source tags captured before scaling or conversion to a working gamut.
// Optional outInfo tail: [8] width, [9] height, [10] CICP primaries, [11] transfer.
struct DecodedImageInfo {
    jint width = 0, height = 0, primaries = 0, transfer = 0, depth = 0;
    void publish(JNIEnv* env, jintArray info) const {
        if (env->GetArrayLength(info) >= 7) env->SetIntArrayRegion(info, 6, 1, &depth);
        if (env->GetArrayLength(info) >= 12) {
            const jint tags[]{width, height, primaries, transfer};
            env->SetIntArrayRegion(info, 8, 4, tags);
        }
    }
};
