#ifndef PDFIUM_WRAPPER_H
#define PDFIUM_WRAPPER_H

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <unordered_map>
#include <mutex>

typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_BITMAP;
typedef unsigned long FPDF_DWORD;
typedef int FPDF_BOOL;

typedef void (*Fn_InitLibrary)();
typedef void (*Fn_DestroyLibrary)();
typedef FPDF_DOCUMENT (*Fn_LoadDocument)(const char*, const char*);
typedef FPDF_DOCUMENT (*Fn_LoadMemDocument)(const void*, size_t, const char*);
typedef FPDF_DOCUMENT (*Fn_LoadCustomDocument)(void*, const char*);
typedef void (*Fn_CloseDocument)(FPDF_DOCUMENT);
typedef int (*Fn_GetPageCount)(FPDF_DOCUMENT);
typedef FPDF_PAGE (*Fn_LoadPage)(FPDF_DOCUMENT, int);
typedef void (*Fn_ClosePage)(FPDF_PAGE);
typedef double (*Fn_GetPageWidth)(FPDF_PAGE);
typedef double (*Fn_GetPageHeight)(FPDF_PAGE);
typedef FPDF_BITMAP (*Fn_BitmapCreate)(int, int, int);
typedef void (*Fn_BitmapFillRect)(FPDF_BITMAP, int, int, int, int, FPDF_DWORD);
typedef void (*Fn_RenderPageBitmap)(FPDF_BITMAP, FPDF_PAGE, int, int, int, int, int, int);
typedef void (*Fn_BitmapDestroy)(FPDF_BITMAP);
typedef unsigned char* (*Fn_BitmapGetBuffer)(FPDF_BITMAP);
typedef int (*Fn_BitmapGetStride)(FPDF_BITMAP);

struct FPDF_FILEACCESS {
    unsigned long m_FileLen;
    FPDF_BOOL (*m_GetBlock)(void*, unsigned long, unsigned char*, unsigned long);
    void* m_Param;
};

struct PdfReadCallback {
    JavaVM* jvm = nullptr;
    jobject ref = nullptr;
    jmethodID readBlock = nullptr;
};

struct PdfDocHandle {
    FPDF_DOCUMENT doc = nullptr;
    FPDF_PAGE page = nullptr;
    int pageIdx = -1;
    int fd = -1;
    void* mapped = nullptr;
    size_t mappedLen = 0;
    FPDF_FILEACCESS* fileAccess = nullptr;
    PdfReadCallback* callback = nullptr;
};

class PdfiumWrapper {
public:
    static PdfiumWrapper& get();
    bool load();
    bool openFd(const std::string& key, int fd);
    bool openCustom(const std::string& key, size_t fileSize, JavaVM* jvm, jobject reader);
    void close(const std::string& key);
    bool isOpen(const std::string& key);
    int pageCount(const std::string& key);
    bool getPageSize(const std::string& key, int idx, double* w, double* h);
    void* render(const std::string& key, int idx, int w, int h, int* stride);
    unsigned char* bitmapBuffer(FPDF_BITMAP bmp);
    void destroyBitmap(FPDF_BITMAP bmp);
    void closeAll();
private:
    PdfiumWrapper() = default;
    std::unordered_map<std::string, PdfDocHandle> docs;
    std::mutex mtx;
    bool inited = false;
    void* dlHandle = nullptr;
    Fn_InitLibrary fnInit = nullptr;
    Fn_DestroyLibrary fnDestroy = nullptr;
    Fn_LoadDocument fnLoadDoc = nullptr;
    Fn_LoadMemDocument fnLoadMem = nullptr;
    Fn_LoadCustomDocument fnLoadCustom = nullptr;
    Fn_CloseDocument fnCloseDoc = nullptr;
    Fn_GetPageCount fnPageCount = nullptr;
    Fn_LoadPage fnLoadPage = nullptr;
    Fn_ClosePage fnClosePage = nullptr;
    Fn_GetPageWidth fnPageW = nullptr;
    Fn_GetPageHeight fnPageH = nullptr;
    Fn_BitmapCreate fnBmpCreate = nullptr;
    Fn_BitmapFillRect fnBmpFill = nullptr;
    Fn_RenderPageBitmap fnRender = nullptr;
    Fn_BitmapDestroy fnBmpDestroy = nullptr;
    Fn_BitmapGetBuffer fnBmpBuf = nullptr;
    Fn_BitmapGetStride fnBmpStride = nullptr;
    void evictIfNeeded();
    void cleanupHandle(PdfDocHandle& h);
};

#endif