#include <jni.h>

// Preserve the JNI ABI on slim and on ABIs without the native still encoders.
namespace {
constexpr jint kUnsupportedEncoder = -100;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrToUltraHdr(JNIEnv*, jclass, jstring, jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                   jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                          jbyteArray, jstring,
                                                                          jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                    jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                           jbyteArray, jstring,
                                                                           jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                   jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBytesToUltraHdrMaxEdge(JNIEnv*, jclass,
                                                                          jbyteArray, jstring,
                                                                          jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000BytesToUltraHdr(JNIEnv*, jclass, jbyteArray,
                                                                        jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000BytesToUltraHdrMaxEdge(
        JNIEnv*, jclass, jbyteArray, jstring, jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_encodeStillExport(JNIEnv*, jclass, jbyteArray, jint, jint,
                                                           jboolean, jint, jint, jboolean, jstring, jint, jint, jfloat, jint, jint, jboolean, jbyteArray, jint, jboolean, jboolean, jfloatArray) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_encodeNativeStillExport(JNIEnv*, jclass, jobject, jint, jint,
                                                             jint, jint, jint, jboolean, jstring, jint, jint, jfloat, jint, jint, jboolean, jbyteArray, jint, jboolean, jboolean, jfloatArray) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_transcodeJpegToJxl(JNIEnv*, jclass, jbyteArray, jstring, jboolean) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrFdToUltraHdrMaxEdge(
        JNIEnv*, jclass, jint, jstring, jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlFdToUltraHdrMaxEdge(
        JNIEnv*, jclass, jint, jstring, jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifFdToUltraHdrMaxEdge(
        JNIEnv*, jclass, jint, jstring, jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000FdToUltraHdrMaxEdge(
        JNIEnv*, jclass, jint, jstring, jint) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxrBufferToUltraHdr(JNIEnv*, jclass, jobject, jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJxlBufferToUltraHdr(JNIEnv*, jclass, jobject, jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertAvifBufferToUltraHdr(JNIEnv*, jclass, jobject, jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertJpeg2000BufferToUltraHdr(JNIEnv*, jclass, jobject, jstring) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_transcodeJpegBufferToJxl(JNIEnv*, jclass, jobject, jstring, jboolean) {
    return kUnsupportedEncoder;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertSourceEncodedStill(
        JNIEnv*, jclass, jobject, jint, jint, jstring, jint, jfloat, jint, jint,
        jboolean, jboolean, jintArray, jboolean) {
    return 0; // No source-encoded result.
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_encodeSourceRgb(
        JNIEnv*, jclass, jobject, jint, jint, jint, jint, jint, jbyteArray, jint, jint,
        jstring, jint, jfloat, jint, jint, jboolean, jbyteArray, jobject, jint, jint,
        jboolean, jfloatArray, jintArray) {
    return kUnsupportedEncoder;
}
