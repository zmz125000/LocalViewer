#include "pdfium_wrapper.h"
#include <jni.h>
#include <android/bitmap.h>


extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeOpenFd(JNIEnv* env, jobject, jstring key, jint fd) {
    if (!key) return JNI_FALSE;
    const char* k = env->GetStringUTFChars(key, nullptr);
    bool ok = PdfiumWrapper::get().openFd(std::string(k), fd);
    env->ReleaseStringUTFChars(key, k);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeOpenCustom(JNIEnv* env, jobject, jstring key, jlong fileSize, jobject reader) {
    if (!key || !reader) return JNI_FALSE;
    JavaVM* jvm;
    if (env->GetJavaVM(&jvm) != JNI_OK) return JNI_FALSE;
    const char* k = env->GetStringUTFChars(key, nullptr);
    jobject ref = env->NewGlobalRef(reader);
    bool ok = PdfiumWrapper::get().openCustom(std::string(k), (size_t)fileSize, jvm, ref);
    if (!ok) env->DeleteGlobalRef(ref);
    env->ReleaseStringUTFChars(key, k);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeClose(JNIEnv* env, jobject, jstring key) {
    if (!key) return;
    const char* k = env->GetStringUTFChars(key, nullptr);
    PdfiumWrapper::get().close(std::string(k));
    env->ReleaseStringUTFChars(key, k);
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeIsOpen(JNIEnv* env, jobject, jstring key) {
    if (!key) return JNI_FALSE;
    const char* k = env->GetStringUTFChars(key, nullptr);
    bool ok = PdfiumWrapper::get().isOpen(std::string(k));
    env->ReleaseStringUTFChars(key, k);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativePageCount(JNIEnv* env, jobject, jstring key) {
    if (!key) return 0;
    const char* k = env->GetStringUTFChars(key, nullptr);
    int n = PdfiumWrapper::get().pageCount(std::string(k));
    env->ReleaseStringUTFChars(key, k);
    return n;
}

JNIEXPORT jintArray JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativePageSize(JNIEnv* env, jobject, jstring key, jint idx) {
    if (!key) return nullptr;
    const char* k = env->GetStringUTFChars(key, nullptr);
    double w = 0, h = 0;
    PdfiumWrapper::get().getPageSize(std::string(k), idx, &w, &h);
    env->ReleaseStringUTFChars(key, k);
    if (w <= 0 || h <= 0) return nullptr;
    jintArray result = env->NewIntArray(2);
    jint sz[2] = {(jint)w, (jint)h};
    env->SetIntArrayRegion(result, 0, 2, sz);
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeRenderBitmap(JNIEnv* env, jobject, jstring key, jint idx, jint w, jint h) {
    if (!key) return nullptr;
    const char* k = env->GetStringUTFChars(key, nullptr);
    int stride = 0;
    FPDF_BITMAP bmp = (FPDF_BITMAP)PdfiumWrapper::get().render(std::string(k), idx, w, h, &stride);
    env->ReleaseStringUTFChars(key, k);
    if (!bmp) return nullptr;
    unsigned char* src = PdfiumWrapper::get().bitmapBuffer(bmp);
    if (!src) { PdfiumWrapper::get().destroyBitmap(bmp); return nullptr; }
    jclass bmpCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBmp = env->GetStaticMethodID(bmpCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject cfg = env->GetStaticObjectField(cfgCls, argbField);
    jobject bitmap = env->CallStaticObjectMethod(bmpCls, createBmp, w, h, cfg);
    if (!bitmap) { PdfiumWrapper::get().destroyBitmap(bmp); return nullptr; }
    void* pixels = nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS && pixels) {
        jint* dst = (jint*)pixels;
        int count = w * h;
        for (int i = 0; i < count; i++) {
            int off = i * 4;
            jint r = src[off] & 0xFF;
            jint g = src[off + 1] & 0xFF;
            jint b = src[off + 2] & 0xFF;
            jint a = src[off + 3] & 0xFF;
            dst[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    PdfiumWrapper::get().destroyBitmap(bmp);
    env->DeleteLocalRef(bmpCls);
    env->DeleteLocalRef(cfgCls);
    env->DeleteLocalRef(cfg);
    return bitmap;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_Pdfium_nativeCloseAll(JNIEnv*, jobject) {
    PdfiumWrapper::get().closeAll();
}


}