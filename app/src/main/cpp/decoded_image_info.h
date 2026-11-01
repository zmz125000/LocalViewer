#pragma once
#include <jni.h>

// Original source tags captured before scaling or conversion to a working gamut.
// Optional outInfo tail: [8] width, [9] height, [10] CICP primaries, [11] transfer, [12] scRGB.
struct DecodedImageInfo {
    jint width = 0, height = 0, primaries = 0, transfer = 0, depth = 0;
    bool scrgb = false;
    void publish(JNIEnv* env, jintArray info) const {
        if (env->GetArrayLength(info) >= 7) env->SetIntArrayRegion(info, 6, 1, &depth);
        if (env->GetArrayLength(info) >= 13) {
            const jint flag = scrgb ? 1 : 0;
            env->SetIntArrayRegion(info, 12, 1, &flag);
        }
        if (env->GetArrayLength(info) >= 12) {
            const jint tags[]{width, height, primaries, transfer};
            env->SetIntArrayRegion(info, 8, 4, tags);
        }
    }
};
