#include "pdfium_wrapper.h"
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <cstring>

PdfiumWrapper& PdfiumWrapper::get() {
    static PdfiumWrapper inst;
    return inst;
}

bool PdfiumWrapper::load() {
    if (inited) return true;
    dlHandle = dlopen("libpdfium.so", RTLD_NOW);
    if (!dlHandle) return false;
    fnInit = (Fn_InitLibrary)dlsym(dlHandle, "FPDF_InitLibrary");
    fnDestroy = (Fn_DestroyLibrary)dlsym(dlHandle, "FPDF_DestroyLibrary");
    fnLoadDoc = (Fn_LoadDocument)dlsym(dlHandle, "FPDF_LoadDocument");
    fnLoadMem = (Fn_LoadMemDocument)dlsym(dlHandle, "FPDF_LoadMemDocument");
    fnLoadCustom = (Fn_LoadCustomDocument)dlsym(dlHandle, "FPDF_LoadCustomDocument");
    fnCloseDoc = (Fn_CloseDocument)dlsym(dlHandle, "FPDF_CloseDocument");
    fnPageCount = (Fn_GetPageCount)dlsym(dlHandle, "FPDF_GetPageCount");
    fnLoadPage = (Fn_LoadPage)dlsym(dlHandle, "FPDF_LoadPage");
    fnClosePage = (Fn_ClosePage)dlsym(dlHandle, "FPDF_ClosePage");
    fnPageW = (Fn_GetPageWidth)dlsym(dlHandle, "FPDF_GetPageWidth");
    fnPageH = (Fn_GetPageHeight)dlsym(dlHandle, "FPDF_GetPageHeight");
    fnBmpCreate = (Fn_BitmapCreate)dlsym(dlHandle, "FPDFBitmap_Create");
    fnBmpFill = (Fn_BitmapFillRect)dlsym(dlHandle, "FPDFBitmap_FillRect");
    fnRender = (Fn_RenderPageBitmap)dlsym(dlHandle, "FPDF_RenderPageBitmap");
    fnBmpDestroy = (Fn_BitmapDestroy)dlsym(dlHandle, "FPDFBitmap_Destroy");
    fnBmpBuf = (Fn_BitmapGetBuffer)dlsym(dlHandle, "FPDFBitmap_GetBuffer");
    fnBmpStride = (Fn_BitmapGetStride)dlsym(dlHandle, "FPDFBitmap_GetStride");
    if (!fnInit || !fnLoadMem || !fnRender || !fnLoadCustom) return false;
    fnInit();
    inited = true;
    return true;
}

void PdfiumWrapper::cleanupHandle(PdfDocHandle& h) {
    if (h.page) fnClosePage(h.page);
    if (h.doc) fnCloseDoc(h.doc);
    if (h.mapped) munmap(h.mapped, h.mappedLen);
    if (h.fd >= 0) close(h.fd);
    if (h.fileAccess) {
        if (h.callback) {
            JNIEnv* env;
            if (h.callback->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
                env->DeleteGlobalRef(h.callback->ref);
            delete h.callback;
        }
        delete h.fileAccess;
    }
}

void PdfiumWrapper::evictIfNeeded() {
    if (docs.size() < 5) return;
    auto oldest = docs.begin();
    cleanupHandle(oldest->second);
    docs.erase(oldest);
}

bool PdfiumWrapper::openFd(const std::string& key, int fd) {
    std::lock_guard<std::mutex> lock(mtx);
    if (docs.find(key) != docs.end()) return true;
    if (!load()) return false;
    off_t sz = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (sz <= 0) return false;
    void* mapped = mmap(nullptr, sz, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapped == MAP_FAILED) return false;
    FPDF_DOCUMENT doc = fnLoadMem(mapped, sz, nullptr);
    if (!doc) { munmap(mapped, sz); return false; }
    evictIfNeeded();
    PdfDocHandle h;
    h.doc = doc;
    h.fd = fd;
    h.mapped = mapped;
    h.mappedLen = (size_t)sz;
    docs[key] = h;
    return true;
}

static int readBlockCb(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    auto* cb = static_cast<PdfReadCallback*>(param);
    if (!cb || !cb->jvm || !cb->ref || !cb->readBlock) return 0;
    JNIEnv* env;
    bool attached = false;
    jint rc = cb->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        rc = cb->jvm->AttachCurrentThread(&env, nullptr);
        if (rc != JNI_OK) return 0;
        attached = true;
    } else if (rc != JNI_OK) return 0;
    jbyteArray result = static_cast<jbyteArray>(
        env->CallObjectMethod(cb->ref, cb->readBlock, (jlong)pos, (jint)size));
    int ok = 0;
    if (result && !env->ExceptionCheck()) {
        jsize len = env->GetArrayLength(result);
        if (len > 0) env->GetByteArrayRegion(result, 0, len, reinterpret_cast<jbyte*>(buf));
        env->DeleteLocalRef(result);
        if (len == (jsize)size) ok = 1;
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) cb->jvm->DetachCurrentThread();
    return ok;
}

bool PdfiumWrapper::openCustom(const std::string& key, size_t fileSize, JavaVM* jvm, jobject reader) {
    std::lock_guard<std::mutex> lock(mtx);
    if (docs.find(key) != docs.end()) return true;
    if (!load()) return false;
    JNIEnv* env;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return false;
    jclass cls = env->GetObjectClass(reader);
    jmethodID readBlock = env->GetMethodID(cls, "readBlock", "(JI)[B");
    env->DeleteLocalRef(cls);
    if (!readBlock) return false;
    auto* cb = new PdfReadCallback{jvm, reader, readBlock};
    auto* fa = new FPDF_FILEACCESS{(unsigned long)fileSize, readBlockCb, cb};
    FPDF_DOCUMENT doc = fnLoadCustom(fa, nullptr);
    if (!doc) { delete fa; delete cb; return false; }
    evictIfNeeded();
    PdfDocHandle h;
    h.doc = doc;
    h.fileAccess = fa;
    h.callback = cb;
    docs[key] = h;
    return true;
}

void PdfiumWrapper::close(const std::string& key) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = docs.find(key);
    if (it != docs.end()) {
        cleanupHandle(it->second);
        docs.erase(it);
    }
}

bool PdfiumWrapper::isOpen(const std::string& key) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = docs.find(key);
    return it != docs.end() && it->second.doc != nullptr;
}

int PdfiumWrapper::pageCount(const std::string& key) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = docs.find(key);
    if (it == docs.end() || !it->second.doc) return 0;
    return fnPageCount(it->second.doc);
}

bool PdfiumWrapper::getPageSize(const std::string& key, int idx, double* w, double* h) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = docs.find(key);
    if (it == docs.end() || !it->second.doc) return false;
    if (!load()) return false;
    FPDF_PAGE p = fnLoadPage(it->second.doc, idx);
    if (!p) return false;
    *w = fnPageW(p);
    *h = fnPageH(p);
    fnClosePage(p);
    return true;
}

void* PdfiumWrapper::render(const std::string& key, int idx, int w, int h, int* stride) {
    std::lock_guard<std::mutex> lock(mtx);
    auto it = docs.find(key);
    if (it == docs.end() || !it->second.doc) return nullptr;
    if (!load()) return nullptr;
    if (w <= 0 || h <= 0) return nullptr;
    if (it->second.page && it->second.pageIdx != idx) {
        fnClosePage(it->second.page);
        it->second.page = nullptr;
        it->second.pageIdx = -1;
    }
    if (!it->second.page) {
        it->second.page = fnLoadPage(it->second.doc, idx);
        it->second.pageIdx = idx;
    }
    if (!it->second.page) return nullptr;
    FPDF_BITMAP bmp = fnBmpCreate(w, h, 1);
    if (!bmp) return nullptr;
    fnBmpFill(bmp, 0, 0, w, h, 0xFFFFFFFF);
    fnRender(bmp, it->second.page, 0, 0, w, h, 0, 0x02 | 0x20);
    int s = fnBmpStride(bmp);
    if (s <= 0 || (size_t)s * (size_t)h == 0) { fnBmpDestroy(bmp); return nullptr; }
    if (stride) *stride = s;
    return (void*)bmp;
}

unsigned char* PdfiumWrapper::bitmapBuffer(FPDF_BITMAP bmp) {
    if (!bmp || !load()) return nullptr;
    return fnBmpBuf(bmp);
}

void PdfiumWrapper::destroyBitmap(FPDF_BITMAP bmp) {
    if (bmp && load()) fnBmpDestroy(bmp);
}

void PdfiumWrapper::closeAll() {
    std::lock_guard<std::mutex> lock(mtx);
    for (auto& kv : docs) cleanupHandle(kv.second);
    docs.clear();
    if (dlHandle) { dlclose(dlHandle); dlHandle = nullptr; }
}