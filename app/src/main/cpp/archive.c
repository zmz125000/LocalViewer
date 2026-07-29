/*
 * Copyright 2022-2024 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * EhViewer. If not, see <https://www.gnu.org/licenses/>.
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <sys/mman.h>

#include <jni.h>
#include <android/log.h>

#include <archive.h>
#include <archive_entry.h>

#define LOG_TAG "libarchive_wrapper"

#include "natsort/strnatcmp.h"
#include "ehviewer.h"

typedef struct {
    int using;
    int next_index;
    struct archive *arc;
    struct archive_entry *entry;
} archive_ctx;

typedef struct {
    const char *filename;
    int index;
    ssize_t size;
    void *addr;
} entry;

#define CTX_POOL_SIZE 20
#define MAX_PARALLEL_DECOMP 4
#define max(a, b) ((a) > (b) ? (a) : (b))

static pthread_mutex_t ctx_pool_mutex = PTHREAD_MUTEX_INITIALIZER;
static archive_ctx **ctx_pool = NULL;
static pthread_mutex_t buffer_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *decode_buffer[MAX_PARALLEL_DECOMP];
static bool need_encrypt = false;
static char *passwd = NULL;
static void *archiveAddr = MAP_FAILED;
static size_t archiveSize = 0;
static entry *entries = NULL;
static size_t entryCount = 0;
static ssize_t max_file_size = 0;

// --- Stream I/O (random-access remote / non-mmap) via Kotlin ArchiveStreamBridge ---
// Do NOT define JNI_OnLoad here — Rust libehviewer already exports it.
//
// Stream mode shares a single file position + read buffer. The mmap ctx pool
// (parallel skip/extract) must NOT run concurrently on that state — it corrupts
// ZIP headers ("Truncated ZIP file header") and can SIGSEGV. All stream extracts
// take stream_mutex and keep at most one live archive_ctx.
static JavaVM *g_vm = NULL;
static bool use_stream_io = false;
static jobject g_stream_bridge = NULL;
static jmethodID g_mid_read = NULL;
static jmethodID g_mid_seek = NULL;
static uint8_t *g_stream_buf = NULL;
static size_t g_stream_buf_cap = 0;
static pthread_mutex_t stream_mutex = PTHREAD_MUTEX_INITIALIZER;

static void archive_cache_vm(JNIEnv *env) {
    if (!g_vm && env) {
        (*env)->GetJavaVM(env, &g_vm);
    }
}

static JNIEnv *archive_get_env(void) {
    if (!g_vm) return NULL;
    JNIEnv *env = NULL;
    jint st = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (st == JNI_OK) return env;
    if (st == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == 0) return env;
    }
    return NULL;
}

static la_ssize_t stream_read_cb(struct archive *a, void *client_data, const void **buff) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    JNIEnv *env = archive_get_env();
    if (!env || !g_stream_bridge || !g_mid_read) return ARCHIVE_FATAL;
    // Larger chunks → fewer JNI/network round-trips (Kotlin side also readaheads 2 MiB).
    const jint chunk = 512 * 1024;
    jbyteArray arr = (*env)->CallObjectMethod(env, g_stream_bridge, g_mid_read, chunk);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return ARCHIVE_FATAL;
    }
    if (!arr) {
        *buff = NULL;
        return 0;
    }
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n <= 0) {
        (*env)->DeleteLocalRef(env, arr);
        *buff = NULL;
        return 0;
    }
    if ((size_t) n > g_stream_buf_cap) {
        free(g_stream_buf);
        g_stream_buf = (uint8_t *) malloc((size_t) n);
        g_stream_buf_cap = (size_t) n;
        if (!g_stream_buf) {
            (*env)->DeleteLocalRef(env, arr);
            g_stream_buf_cap = 0;
            return ARCHIVE_FATAL;
        }
    }
    (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *) g_stream_buf);
    (*env)->DeleteLocalRef(env, arr);
    *buff = g_stream_buf;
    return (la_ssize_t) n;
}

static la_int64_t stream_seek_cb(struct archive *a, void *client_data, la_int64_t offset, int whence) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    JNIEnv *env = archive_get_env();
    if (!env || !g_stream_bridge || !g_mid_seek) return ARCHIVE_FATAL;
    jlong pos = (*env)->CallLongMethod(env, g_stream_bridge, g_mid_seek, (jlong) offset, (jint) whence);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return ARCHIVE_FATAL;
    }
    return (la_int64_t) pos;
}

static int stream_open_cb(struct archive *a, void *client_data) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    return ARCHIVE_OK;
}

static int stream_close_cb(struct archive *a, void *client_data) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    return ARCHIVE_OK;
}

static void stream_bridge_clear(JNIEnv *env) {
    if (g_stream_bridge && env) {
        (*env)->DeleteGlobalRef(env, g_stream_bridge);
    }
    g_stream_bridge = NULL;
    g_mid_read = NULL;
    g_mid_seek = NULL;
    free(g_stream_buf);
    g_stream_buf = NULL;
    g_stream_buf_cap = 0;
    use_stream_io = false;
}

#define SUPPORT_EXT_COUNT 11

const char supportExt[SUPPORT_EXT_COUNT][5] = {
        "jpeg",
        "jpg",
        "png",
        "gif",
        "webp",
        "bmp",
        "ico",
        "wbmp",
        "heic",
        "heif",
        "avif"
};

/** basename after last / or \ */
static inline const char *archive_basename(const char *name) {
    const char *base = name;
    for (const char *p = name; *p; p++) {
        if (*p == '/' || *p == '\\') base = p + 1;
    }
    return base;
}

static inline char ascii_lower(char c) {
    return (c >= 'A' && c <= 'Z') ? (char) (c - 'A' + 'a') : c;
}

/**
 * Skip macOS resource-fork junk from Finder "Compress" zips:
 * - any path under __MACOSX/
 * - AppleDouble files named ._*
 * These often end in .jpg/.png but are not decodable images
 * → ImageDecoder "unimplemented" / "Input contained an error".
 */
static inline bool filename_is_macos_junk(const char *name) {
    if (!name || !*name) return true;
    // Scan path segments for __MACOSX (case-insensitive)
    const char *seg = name;
    for (const char *p = name;; p++) {
        if (*p == '/' || *p == '\\' || *p == '\0') {
            size_t len = (size_t) (p - seg);
            if (len == 8) {
                static const char mac[] = "__macosx";
                int match = 1;
                for (size_t i = 0; i < 8; i++) {
                    if (ascii_lower(seg[i]) != mac[i]) {
                        match = 0;
                        break;
                    }
                }
                if (match) return true;
            }
            if (*p == '\0') break;
            seg = p + 1;
        }
    }
    const char *base = archive_basename(name);
    // AppleDouble: ._filename.jpg
    if (base[0] == '.' && base[1] == '_') return true;
    if (!*base || strcmp(base, ".") == 0 || strcmp(base, "..") == 0) return true;
    return false;
}

static inline int filename_is_playable_file(const char *name) {
    if (!name || filename_is_macos_junk(name))
        return false;
    const char *dotptr = strrchr(name, '.');
    if (!dotptr || !dotptr[1])
        return false;
    dotptr++; // skip '.'
    char ext[8];
    size_t n = 0;
    for (; dotptr[n] && n < sizeof(ext) - 1; n++) {
        char c = ascii_lower(dotptr[n]);
        // Extension must be alnum only
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) break;
        ext[n] = c;
    }
    ext[n] = '\0';
    if (!n) return false;
    int i;
    for (i = 0; i < SUPPORT_EXT_COUNT; i++)
        if (strcmp(ext, supportExt[i]) == 0)
            return true;
    return false;
}

static inline bool archive_entry_is_file(struct archive_entry *entry) {
    return archive_entry_filetype(entry) == AE_IFREG;
}

static inline bool archive_entry_is_playable(struct archive_entry *entry) {
    return archive_entry_is_file(entry) &&
           filename_is_playable_file(archive_entry_pathname(entry));
}

static inline int compare_entries(const void *a, const void *b) {
    const char *fa = ((entry *) a)->filename;
    const char *fb = ((entry *) b)->filename;
    return strnatcmp(fa, fb);
}

#define ADDR_IN_FILE_MAPPING(addr) (addr >= archiveAddr && addr < archiveAddr + archiveSize)

static bool fill_entry_zero_copy(struct archive *arc, entry *entry) {
    void *buffer = NULL;
    size_t buffer_size = 0;
    la_int64_t output_ofs = 0;
    archive_read_data_block(arc, (const void **) &buffer, &buffer_size, &output_ofs);
    bool zero_copy = ADDR_IN_FILE_MAPPING(buffer) && !output_ofs && buffer_size == entry->size;
    entry->addr = zero_copy ? buffer : NULL;
    return zero_copy;
}

static void archive_map_entries_index(archive_ctx *ctx, bool sort) {
    int count = 0;
    // Stream I/O cannot zero-copy (no file mapping); skip data_block probes.
    bool zero_copy = !use_stream_io;
    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname(ctx->entry);
        if (archive_entry_is_file(ctx->entry) && filename_is_playable_file(name)) {
            entries[count].filename = strdup(name);
            entries[count].index = count;
            ssize_t size = archive_entry_size(ctx->entry);
            max_file_size = max(size, max_file_size);
            entries[count].size = size;
            entries[count].addr = NULL;
            // We don't expect zero copy if first content can't do zero copy
            if (zero_copy) zero_copy = fill_entry_zero_copy(ctx->arc, &entries[count]);
            count++;
        }
    }
    if (sort) qsort(entries, entryCount, sizeof(entry), compare_entries);
}

static void *acquire_decode_buffer() {
    void *addr = NULL;
    pthread_mutex_lock(&buffer_mutex);
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        addr = decode_buffer[i];
        if (addr) {
            decode_buffer[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&buffer_mutex);
    if (!addr) addr = malloc(max_file_size);
    return addr;
}

static void release_decode_buffer(void *buffer) {
    pthread_mutex_lock(&buffer_mutex);
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        void *addr = decode_buffer[i];
        if (!addr) {
            decode_buffer[i] = buffer;
            pthread_mutex_unlock(&buffer_mutex);
            return;
        }
    }
    pthread_mutex_unlock(&buffer_mutex);
    free(buffer);
}

static int archive_list_all_entries(archive_ctx *ctx) {
    int count = 0;
    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK)
        if (archive_entry_is_playable(ctx->entry))
            count++;
    return count;
}

static void archive_release_ctx(archive_ctx *ctx) {
    if (ctx) {
        archive_read_close(ctx->arc);
        archive_read_free(ctx->arc);
        free(ctx);
    }
}

static archive_ctx *archive_alloc_ctx() {
    archive_ctx *ctx = calloc(1, sizeof(archive_ctx));
    ctx->arc = archive_read_new();
    ctx->using = 1;
    archive_read_support_format_tar(ctx->arc);
    archive_read_support_format_7zip(ctx->arc);
    archive_read_support_format_rar5(ctx->arc);
    archive_read_support_format_zip(ctx->arc);
    archive_read_support_filter_gzip(ctx->arc);
    archive_read_support_filter_xz(ctx->arc);
    archive_read_set_option(ctx->arc, "zip", "ignorecrc32", "1");
    if (passwd)
        archive_read_add_passphrase(ctx->arc, passwd);
    int err;
    if (use_stream_io) {
        // Random-access callbacks for remote ZIP/TAR (no full mmap).
        archive_read_set_seek_callback(ctx->arc, stream_seek_cb);
        err = archive_read_open(ctx->arc, NULL, stream_open_cb, stream_read_cb, stream_close_cb);
    } else {
        err = archive_read_open_memory(ctx->arc, archiveAddr, archiveSize);
    }
    if (err < ARCHIVE_OK) {
        LOGE("%s%s", "Open archive failed: ", archive_error_string(ctx->arc));
        archive_read_free(ctx->arc);
        free(ctx);
        return NULL;
    }
    return ctx;
}

static int archive_skip_to_index(archive_ctx *ctx, int index) {
    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK) {
        if (!archive_entry_is_playable(ctx->entry))
            continue;
        if (ctx->next_index++ == index) {
            return ctx->next_index - 1;
        }
    }
    return ARCHIVE_FATAL;
}

/** Remove [ctx] from the pool (if present) and free it. */
static void archive_drop_ctx(archive_ctx *ctx) {
    if (!ctx) return;
    if (ctx_pool) {
        pthread_mutex_lock(&ctx_pool_mutex);
        for (int i = 0; i < CTX_POOL_SIZE; i++) {
            if (ctx_pool[i] == ctx) {
                ctx_pool[i] = NULL;
                break;
            }
        }
        pthread_mutex_unlock(&ctx_pool_mutex);
    }
    archive_release_ctx(ctx);
}

/**
 * Stream mode: single live ctx, shared position/buffer. Caller must hold stream_mutex.
 * Reuses the ctx when it can only skip forward; otherwise reopens from the start.
 */
static int archive_get_ctx_stream(archive_ctx **ctxptr, int idx) {
    archive_ctx *ctx = NULL;
    pthread_mutex_lock(&ctx_pool_mutex);
    if (ctx_pool && ctx_pool[0] && !ctx_pool[0]->using && ctx_pool[0]->next_index <= idx) {
        ctx = ctx_pool[0];
        ctx->using = 1;
    }
    pthread_mutex_unlock(&ctx_pool_mutex);

    if (!ctx) {
        // Drop any leftover stream contexts (at most one should exist).
        if (ctx_pool) {
            for (int i = 0; i < CTX_POOL_SIZE; i++) {
                archive_ctx *old = NULL;
                pthread_mutex_lock(&ctx_pool_mutex);
                old = ctx_pool[i];
                ctx_pool[i] = NULL;
                pthread_mutex_unlock(&ctx_pool_mutex);
                archive_release_ctx(old);
            }
        }
        ctx = archive_alloc_ctx();
        if (!ctx) return ARCHIVE_FATAL;
        pthread_mutex_lock(&ctx_pool_mutex);
        if (ctx_pool) ctx_pool[0] = ctx;
        pthread_mutex_unlock(&ctx_pool_mutex);
    }

    int ret = archive_skip_to_index(ctx, idx);
    if (ret != idx) {
        LOGE("Skip to index failed: %s", archive_error_string(ctx->arc));
        int err = archive_errno(ctx->arc);
        archive_drop_ctx(ctx);
        return err != 0 ? err : ARCHIVE_FATAL;
    }
    *ctxptr = ctx;
    return 0;
}

static int archive_get_ctx(archive_ctx **ctxptr, int idx) {
    if (use_stream_io) {
        // stream_mutex is held by extract entry points
        return archive_get_ctx_stream(ctxptr, idx);
    }

    int ret;
    archive_ctx *ctx = NULL;
    pthread_mutex_lock(&ctx_pool_mutex);
    for (int i = 0; i < CTX_POOL_SIZE; i++) {
        if (!ctx_pool[i])
            continue;
        if (ctx_pool[i]->using)
            continue;
        if (ctx_pool[i]->next_index > idx)
            continue;
        if (!ctx || ctx_pool[i]->next_index > ctx->next_index)
            ctx = ctx_pool[i];
        if (ctx->next_index == idx)
            break;
    }
    if (ctx)
        ctx->using = 1;
    pthread_mutex_unlock(&ctx_pool_mutex);

    if (!ctx) {
        archive_ctx *victimCtx = NULL;
        int victimIdx = 0;
        int replace = 1;
        ctx = archive_alloc_ctx();
        if (!ctx) return ARCHIVE_FATAL;
        pthread_mutex_lock(&ctx_pool_mutex);
        for (int i = 0; i < CTX_POOL_SIZE; i++) {
            if (!ctx_pool[i]) {
                ctx_pool[i] = ctx;
                replace = 0;
                break;
            }
            if (ctx_pool[i]->using)
                continue;
            if (!victimCtx || ctx_pool[i]->next_index > victimCtx->next_index) {
                victimCtx = ctx_pool[i];
                victimIdx = i;
            }
        }
        if (replace) ctx_pool[victimIdx] = ctx;
        pthread_mutex_unlock(&ctx_pool_mutex);
        if (replace) archive_release_ctx(victimCtx);
    }
    ret = archive_skip_to_index(ctx, idx);
    if (ret != idx) {
        ret = archive_errno(ctx->arc);
        LOGE("Skip to index failed: %s", archive_error_string(ctx->arc));
        // Previously freed without clearing the pool slot → UAF / SIGSEGV.
        archive_drop_ctx(ctx);
        return ret != 0 ? ret : ARCHIVE_FATAL;
    }
    *ctxptr = ctx;
    return 0;
}

/**
 * Stream open: single pass over headers (no zero-copy). Avoids a second full
 * archive_alloc_ctx which re-fetched the ZIP central directory over the network.
 */
static jint archive_open_stream_single_pass(jboolean sort_entries) {
    archive_ctx *ctx = archive_alloc_ctx();
    if (!ctx) return 0;

    size_t cap = 64;
    entries = calloc(cap, sizeof(entry));
    if (!entries) {
        archive_release_ctx(ctx);
        return 0;
    }
    entryCount = 0;
    max_file_size = 0;

    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname(ctx->entry);
        if (!archive_entry_is_file(ctx->entry) || !filename_is_playable_file(name))
            continue;
        if (entryCount >= cap) {
            size_t ncap = cap * 2;
            entry *grown = realloc(entries, ncap * sizeof(entry));
            if (!grown) {
                LOGE("%s", "entries realloc failed");
                archive_release_ctx(ctx);
                return 0;
            }
            entries = grown;
            memset(entries + cap, 0, (ncap - cap) * sizeof(entry));
            cap = ncap;
        }
        entries[entryCount].filename = strdup(name);
        entries[entryCount].index = (int) entryCount;
        entries[entryCount].size = archive_entry_size(ctx->entry);
        entries[entryCount].addr = NULL;
        max_file_size = max(entries[entryCount].size, max_file_size);
        entryCount++;
    }

    LOGI("%s%zu%s", "Found ", entryCount, " images in archive");
    if (!entryCount) {
        LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
        archive_release_ctx(ctx);
        free(entries);
        entries = NULL;
        return 0;
    }

    int encryptRet = archive_read_has_encrypted_entries(ctx->arc);
    need_encrypt = (encryptRet == 1);

    if (sort_entries) qsort(entries, entryCount, sizeof(entry), compare_entries);
    archive_release_ctx(ctx);
    return (int) entryCount;
}

static jint archive_open_common(JNIEnv *env, jboolean sort_entries) {
    EH_UNUSED(env);
    archive_ctx *ctx = NULL;
    ctx_pool = calloc(CTX_POOL_SIZE, sizeof(archive_ctx **));

    // Stream: one header pass only (see above).
    if (use_stream_io) {
        return archive_open_stream_single_pass(sort_entries);
    }

    ctx = archive_alloc_ctx();
    if (!ctx) return 0;

    entryCount = archive_list_all_entries(ctx);
    LOGI("%s%zu%s", "Found ", entryCount, " images in archive");
    if (!entryCount) {
        LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
        archive_release_ctx(ctx);
        return 0;
    }

    // We must read through the file|vm then we can know whether it is encrypted
    int encryptRet = archive_read_has_encrypted_entries(ctx->arc);
    switch (encryptRet) {
        case 1: // At lease 1 encrypted entry
            need_encrypt = true;
            break;
        case 0: // format supports but no encrypted entry found
        default:
            need_encrypt = false;
    }

    if (archiveAddr != MAP_FAILED) {
        int format = archive_format(ctx->arc);
        switch (format) {
            case ARCHIVE_FORMAT_ZIP:
            case ARCHIVE_FORMAT_RAR_V5:
                madvise_log_if_error(archiveAddr, archiveSize, MADV_SEQUENTIAL);
                break;
            case ARCHIVE_FORMAT_7ZIP: // Seek is bad
                madvise_log_if_error(archiveAddr, archiveSize, MADV_RANDOM);
                break;
            default:;
        }
    }
    archive_release_ctx(ctx);

    ctx = archive_alloc_ctx();
    if (!ctx) return 0;
    entries = calloc(entryCount, sizeof(entry));
    archive_map_entries_index(ctx, sort_entries);
    archive_release_ctx(ctx);
    return (int) entryCount;
}

JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openArchive(JNIEnv *env, jclass thiz, jint fd, jlong size, jboolean sort_entries) {
    EH_UNUSED(thiz);
    archive_cache_vm(env);
    stream_bridge_clear(env);
    use_stream_io = false;
    archiveAddr = mmap(0, (size_t) size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (archiveAddr == MAP_FAILED) {
        LOGE("%s%s", "mmap failed with error ", strerror(errno));
        return 0;
    }
    archiveSize = (size_t) size;
    return archive_open_common(env, sort_entries);
}

/**
 * Open archive via Kotlin [ArchiveStreamBridge] (random read/seek — SMB/WebDAV stream).
 * Does not mmap; extracts always go through decode buffers.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openArchiveStream(JNIEnv *env, jclass thiz, jobject bridge, jlong size, jboolean sort_entries) {
    EH_UNUSED(thiz);
    if (!bridge || size <= 0) return 0;
    archive_cache_vm(env);
    stream_bridge_clear(env);
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
        archiveSize = 0;
    }
    use_stream_io = true;
    archiveSize = (size_t) size;
    archiveAddr = MAP_FAILED;
    g_stream_bridge = (*env)->NewGlobalRef(env, bridge);
    jclass cls = (*env)->GetObjectClass(env, bridge);
    g_mid_read = (*env)->GetMethodID(env, cls, "nativeRead", "(I)[B");
    g_mid_seek = (*env)->GetMethodID(env, cls, "nativeSeek", "(JI)J");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_mid_read || !g_mid_seek) {
        LOGE("%s", "ArchiveStreamBridge methods missing");
        stream_bridge_clear(env);
        return 0;
    }
    return archive_open_common(env, sort_entries);
}

JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToByteBuffer(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (index < 0 || (size_t) index >= entryCount || !entries) return 0;
    entry *entry = &entries[index];
    ssize_t size = entry->size;
    if (entry->addr) {
        return (*env)->NewDirectByteBuffer(env, entry->addr, size);
    }

    if (use_stream_io) pthread_mutex_lock(&stream_mutex);

    archive_ctx *ctx = NULL;
    jobject result = 0;
    if (!archive_get_ctx(&ctx, entry->index)) {
        void *addr = acquire_decode_buffer();
        if (!addr) {
            LOGE("%s", "Decode buffer alloc failed");
            // Leave ctx marked using so it is not reused mid-failure; drop it.
            archive_drop_ctx(ctx);
        } else {
            ssize_t bytes = archive_read_data(ctx->arc, addr, size);
            if (bytes == size) {
                ctx->using = 0;
                result = (*env)->NewDirectByteBuffer(env, addr, size);
            } else {
                if (bytes < 0) {
                    LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
                } else {
                    LOGE("%s", "No enough data read, WTF?");
                }
                // Stream/ctx is in a bad state after a partial/failed read.
                archive_drop_ctx(ctx);
                release_decode_buffer(addr);
            }
        }
    }

    if (use_stream_io) pthread_mutex_unlock(&stream_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_closeArchive(JNIEnv *env, jclass thiz) {
    EH_UNUSED(thiz);
    if (ctx_pool) {
        for (int i = 0; i < CTX_POOL_SIZE; i++)
            archive_release_ctx(ctx_pool[i]);
        free(ctx_pool);
        ctx_pool = NULL;
    }
    free(passwd);
    passwd = NULL;
    need_encrypt = false;
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
    }
    archiveSize = 0;
    stream_bridge_clear(env);
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        free(decode_buffer[i]);
        decode_buffer[i] = NULL;
    }
    max_file_size = 0;
    if (entries) {
        for (int i = 0; i < entryCount; ++i) {
            free((void *) entries[i].filename);
        }
        free(entries);
        entries = NULL;
    }
    entryCount = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_needPassword(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return need_encrypt;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_providePassword(JNIEnv *env, jclass thiz, jstring str) {
    EH_UNUSED(thiz);
    struct archive_entry *entry;
    archive_ctx *ctx;
    jboolean ret = true;
    int len = (*env)->GetStringUTFLength(env, str);
    passwd = realloc(passwd, len + 1);
    (*env)->GetStringUTFRegion(env, str, 0, len, passwd);
    passwd[len] = 0;
    ctx = archive_alloc_ctx();
    char tmpBuf[4096];
    while (archive_read_next_header(ctx->arc, &entry) == ARCHIVE_OK) {
        if (!archive_entry_is_playable(entry))
            continue;
        if (!archive_entry_is_encrypted(entry))
            continue;
        if (archive_read_data(ctx->arc, tmpBuf, 4096) < ARCHIVE_OK) {
            LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
            ret = false;
        }
        break;
    }
    archive_release_ctx(ctx);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getExtension(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    const char *ext = strrchr(entries[index].filename, '.') + 1;
    return (*env)->NewStringUTF(env, ext);
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToFd(JNIEnv *env, jclass thiz, jint index, jint fd) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (index < 0 || (size_t) index >= entryCount || !entries) return JNI_FALSE;
    int arcIndex = entries[index].index;
    if (use_stream_io) pthread_mutex_lock(&stream_mutex);
    archive_ctx *ctx = NULL;
    int ret = archive_get_ctx(&ctx, arcIndex);
    if (!ret) {
        ret = archive_read_data_into_fd(ctx->arc, fd);
        if (ret == ARCHIVE_OK) {
            ctx->using = 0;
        } else {
            archive_drop_ctx(ctx);
        }
    }
    if (use_stream_io) pthread_mutex_unlock(&stream_mutex);
    return ret == ARCHIVE_OK;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_releaseByteBuffer(JNIEnv *env, jclass thiz, jobject buffer) {
    EH_UNUSED(thiz);
    void *addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!ADDR_IN_FILE_MAPPING(addr)) {
        release_decode_buffer(addr);
    }
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_archiveFdBatch(JNIEnv *env, jclass clazz, jintArray fd_batch, jobjectArray names, jint arc_fd, jint size) {
    EH_UNUSED(clazz);
    struct archive *arc = archive_write_new();
    struct stat st;
    char buff[8192];
    jint fdBatch[size];
    (*env)->GetIntArrayRegion(env, fd_batch, 0, size, fdBatch);
    archive_write_set_format_zip(arc);
    archive_write_zip_set_compression_store(arc);
    archive_write_open_fd(arc, arc_fd);
    struct archive_entry *entry = archive_entry_new();
    for (int i = 0; i < size; i++) {
        int fd = fdBatch[i];
        jobject name = (*env)->GetObjectArrayElement(env, names, i);
        const char *cname = (*env)->GetStringUTFChars(env, name, false);
        archive_entry_set_pathname(entry, cname);
        (*env)->ReleaseStringUTFChars(env, name, cname);
        fstat(fd, &st);
        archive_entry_copy_stat(entry, &st);
        archive_entry_set_perm(entry, 0644);
        archive_write_header(arc, entry);
        size_t len;
        do {
            len = read(fd, buff, sizeof(buff));
            archive_write_data(arc, buff, len);
        } while (len > 0);
        archive_write_finish_entry(arc);
        archive_entry_clear(entry);
    }
    archive_entry_free(entry);
    archive_write_close(arc);
    archive_write_free(arc);
}
