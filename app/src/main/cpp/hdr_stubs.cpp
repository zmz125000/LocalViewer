/*
 * Stub JNI for HDR convert suite when EHVIEWER_HDR_CODECS is off
 * (armeabi-v7a / 32-bit). Real implementations live in hdr_convert.cpp,
 * avif_hdr.cpp, jxl_hdr.cpp and are only linked for arm64-v8a + x86_64.
 *
 * Return codes: non-zero so Kotlin treats convert as failed without
 * UnsatisfiedLinkError.
 */
#include <jni.h>

namespace {

constexpr jint kUnsupportedAbi = -100;

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrToUltraHdr(JNIEnv*, jclass, jstring, jstring) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                   jstring) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                          jbyteArray, jstring,
                                                                          jint) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                    jstring) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                           jbyteArray, jstring,
                                                                           jint) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeAvifHdrKind(JNIEnv*, jclass, jbyteArray) {
    return 0;  // not AVIF / unsupported on this ABI
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                   jstring) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                          jbyteArray, jstring,
                                                                          jint) {
    return kUnsupportedAbi;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxrBytesToDirect(JNIEnv*, jclass, jbyteArray, jint,
                                                                jboolean, jintArray, jfloatArray) {
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeJxlBytesToDirect(JNIEnv*, jclass, jbyteArray, jint,
                                                                jboolean, jintArray, jfloatArray) {
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeAvifBytesToDirect(JNIEnv*, jclass, jbyteArray, jint,
                                                                 jboolean, jintArray, jfloatArray) {
    return nullptr;
}
