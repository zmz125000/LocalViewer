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
#include <stdatomic.h>
#include <sys/mman.h>

#include <jni.h>
#include <android/log.h>
#include <zlib.h>

#include <archive.h>
#include <archive_entry.h>

#define LOG_TAG "libarchive_wrapper"

#include "natsort/strnatcmp.h"
#include "ehviewer.h"

/**
 * Cooperative abort for long native archive work (browse cover open/extract).
 * Reader sets this before waiting on ArchiveAccess so blocking JNI can exit
 * promptly — coroutine cancellation alone cannot interrupt stream_pread.
 */
static atomic_bool archive_abort_requested = false;

static inline int archive_should_abort(void) {
    return atomic_load_explicit(&archive_abort_requested, memory_order_acquire);
}

static inline void archive_clear_abort(void) {
    atomic_store_explicit(&archive_abort_requested, false, memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_requestArchiveAbort(JNIEnv *env, jclass clazz) {
    EH_UNUSED(env);
    EH_UNUSED(clazz);
    atomic_store_explicit(&archive_abort_requested, true, memory_order_release);
}

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
    /** Stream direct-index: ZIP local header off, or TAR member data off. */
    int64_t local_header_offset;
    int64_t compressed_size;
    uint16_t compression_method;
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
/** Stream ZIP opened via EOCD + central-directory parse. */
static bool use_zip_cd_index = false;
/** Stream TAR opened via header-only walk (seek past bodies). */
static bool use_tar_index = false;
/** Bytes actually pulled through stream I/O (diagnostics + scan budget). */
static int64_t stream_bytes_read = 0;
/**
 * Soft budget for non-ZIP cover scans (network TAR/solid/libarchive fallback).
 * 0 = unlimited. ZIP CD path ignores this (EOCD+CD is intentionally uncapped).
 */
static int64_t stream_scan_limit = 0;
/** Set when a read was refused because [stream_scan_limit] would be exceeded. */
static bool stream_scan_hit_limit = false;
/**
 * Stream open confirmed a container (ZIP CD / TAR headers) with zero playable
 * images — callers must not fall through to another format probe.
 */
static bool stream_index_finished_empty = false;

static void stream_scan_reset(int64_t limit) {
    stream_bytes_read = 0;
    stream_scan_limit = limit > 0 ? limit : 0;
    stream_scan_hit_limit = false;
    stream_index_finished_empty = false;
}

/** @return 1 if further stream I/O should stop (limit hit). */
static int stream_scan_exhausted(void) {
    if (stream_scan_limit <= 0) return 0;
    if (stream_bytes_read >= stream_scan_limit) {
        stream_scan_hit_limit = true;
        return 1;
    }
    return 0;
}

// Progressive TAR header walk (lazy first page + grow listed count).
// Discovery order only — never mid-session qsort (indices must stay stable for seek bar).
static bool tar_walk_active = false;
static bool tar_walk_complete = false;
static la_int64_t tar_walk_pos = 0;
static char *tar_walk_pending = NULL;
static int tar_walk_zero_blocks = 0;
static size_t tar_walk_cap = 0;

static void tar_walk_reset(void) {
    free(tar_walk_pending);
    tar_walk_pending = NULL;
    tar_walk_active = false;
    tar_walk_complete = false;
    tar_walk_pos = 0;
    tar_walk_zero_blocks = 0;
    tar_walk_cap = 0;
}

static inline int filename_is_playable_file(const char *name);
static inline int compare_entries(const void *a, const void *b);

// --- Stream I/O via Kotlin ArchiveStreamBridge (remote ZIP/TAR) ---
// Process-global archive lifetime barrier. JNI operations that dereference or replace
// native session resources hold this mutex. In particular, open/close must not free a
// libarchive handle or stream bridge while an extract/solid callback is still unwinding.
// ZIP: EOCD+CD index; TAR: 512-byte headers only (seek past member data).
// Do NOT define JNI_OnLoad here — Rust libehviewer already exports it.
static JavaVM *g_vm = NULL;
static bool use_stream_io = false;
static jobject g_stream_bridge = NULL;
static jmethodID g_mid_read = NULL;
static jmethodID g_mid_seek = NULL;
static uint8_t *g_stream_buf = NULL;
static size_t g_stream_buf_cap = 0;
static pthread_mutex_t stream_mutex = PTHREAD_MUTEX_INITIALIZER;
/** Logical stream cursor (kept in sync with Kotlin [ArchiveStreamBridge]). */
static la_int64_t g_stream_pos = 0;

// --- Solid sequential extract (RAR/CBR/7z fake-stream) ---
// Pull API: open → nextPlayable → extract/skip → … → close.
// Helpers live after stream callbacks (see archive_alloc_solid_seq_ctx).
static bool use_solid_seq = false;
static archive_ctx *solid_ctx = NULL;
static int solid_next_index = 0;
static int solid_have_current = 0;
static char solid_ext[16];
static char solid_name[512];
static int64_t solid_unc_size = 0;

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

/** Sync Kotlin bridge position to absolute [pos] (SEEK_SET). */
static int stream_sync_java_pos(JNIEnv *env, la_int64_t pos) {
    if (!env || !g_stream_bridge || !g_mid_seek) return -1;
    jlong r = (*env)->CallLongMethod(env, g_stream_bridge, g_mid_seek, (jlong) pos, (jint) 0);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }
    if (r < 0) return -1;
    return 0;
}

static la_ssize_t stream_read_cb(struct archive *a, void *client_data, const void **buff) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    JNIEnv *env = archive_get_env();
    if (!env || !g_stream_bridge || !g_mid_read) return ARCHIVE_FATAL;
    if (archive_should_abort()) {
        *buff = NULL;
        return ARCHIVE_FATAL;
    }
    if ((size_t) g_stream_pos >= archiveSize) {
        *buff = NULL;
        return 0;
    }
    if (stream_scan_exhausted()) {
        *buff = NULL;
        return ARCHIVE_FATAL;
    }
    // Chunk size; Kotlin ReadAhead coalesces sequential ranges further.
    // 1 MiB reduces JNI / bridge round-trips during solid sequential extract.
    jint chunk = 1024 * 1024;
    if (stream_scan_limit > 0) {
        int64_t left = stream_scan_limit - stream_bytes_read;
        if (left <= 0) {
            stream_scan_hit_limit = true;
            *buff = NULL;
            return ARCHIVE_FATAL;
        }
        if (left < (int64_t) chunk) chunk = (jint) left;
    }
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
    g_stream_pos += n;
    stream_bytes_read += n;
    *buff = g_stream_buf;
    return (la_ssize_t) n;
}

/** Absolute pread for ZIP CD indexing / direct extract (counts toward stream_bytes_read). */
static int stream_pread(uint8_t *dst, la_int64_t off, size_t len) {
    if (!dst || len == 0) return 0;
    if (archive_should_abort()) return -1;
    if (off < 0 || (size_t) off > archiveSize) return -1;
    size_t max = archiveSize - (size_t) off;
    if (len > max) len = max;
    if (stream_scan_exhausted()) return -1;
    JNIEnv *env = archive_get_env();
    if (!env || !g_stream_bridge || !g_mid_read || !g_mid_seek) return -1;
    g_stream_pos = off;
    if (stream_sync_java_pos(env, off) != 0) return -1;
    size_t got = 0;
    while (got < len) {
        if (archive_should_abort()) return -1;
        if (stream_scan_limit > 0 && stream_bytes_read >= stream_scan_limit) {
            stream_scan_hit_limit = true;
            break;
        }
        size_t want = len - got;
        if (want > (size_t) (1024 * 1024)) want = (size_t) (1024 * 1024);
        if (stream_scan_limit > 0) {
            int64_t left = stream_scan_limit - stream_bytes_read;
            if (left <= 0) {
                stream_scan_hit_limit = true;
                break;
            }
            if ((int64_t) want > left) want = (size_t) left;
        }
        jint chunk = (jint) want;
        jbyteArray arr = (*env)->CallObjectMethod(env, g_stream_bridge, g_mid_read, chunk);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            return -1;
        }
        if (!arr) break;
        jsize n = (*env)->GetArrayLength(env, arr);
        if (n <= 0) {
            (*env)->DeleteLocalRef(env, arr);
            break;
        }
        if ((size_t) n > len - got) n = (jsize) (len - got);
        (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *) (dst + got));
        (*env)->DeleteLocalRef(env, arr);
        got += (size_t) n;
        g_stream_pos = off + (la_int64_t) got;
        stream_bytes_read += n;
        if ((size_t) n < (size_t) chunk) break;
    }
    return (int) got;
}

static uint16_t zip_u16(const uint8_t *p) {
    return (uint16_t) (p[0] | (p[1] << 8));
}

static uint32_t zip_u32(const uint8_t *p) {
    return (uint32_t) (p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

static uint64_t zip_u64(const uint8_t *p) {
    return (uint64_t) zip_u32(p) | ((uint64_t) zip_u32(p + 4) << 32);
}

/**
 * Open stream ZIP by EOCD + central directory only (no local-header / member walk).
 * Typical comic zip: ~64–128 KiB network vs tens–hundreds of MiB when libarchive
 * walks every local header (even with seek skips + readahead).
 * @param cover_only keep only the natural-first playable entry (browse thumbs).
 * @return entry count, or 0 if not a zip / parse failed (caller falls back).
 */
static jint zip_stream_open_from_cd(jboolean sort_entries, bool cover_only) {
    if (archiveSize < 22) return 0;
    // ZIP CD is intentionally uncapped (EOCD + CD only — not a full-body walk).
    int64_t saved_limit = stream_scan_limit;
    stream_scan_limit = 0;
    stream_bytes_read = 0;
    stream_scan_hit_limit = false;

    // Progressive EOCD tail: most zips have empty/short comments (EOCD in last 22–1 KiB).
    // Avoid always pulling the full 64 KiB+22 max-comment window over SMB/WebDAV.
    // Grow: 1 KiB → 16 KiB (libarchive-style) → full 65535+22.
    static const size_t eocd_try_lens[] = { 1024u, 16u * 1024u, 65535u + 22u };
    uint8_t *tail = NULL;
    size_t tail_len = 0;
    ssize_t eocd = -1;
    for (size_t t = 0; t < sizeof(eocd_try_lens) / sizeof(eocd_try_lens[0]); t++) {
        size_t want = eocd_try_lens[t];
        if (want > archiveSize) want = archiveSize;
        if (want < 22) {
            free(tail);
            return 0;
        }
        if (want <= tail_len && eocd >= 0) break;
        if (want <= tail_len) continue;
        uint8_t *grown = (uint8_t *) realloc(tail, want);
        if (!grown) {
            free(tail);
            return 0;
        }
        tail = grown;
        // Full re-read of the larger end-window (prefix changes when expanding).
        if (stream_pread(tail, (la_int64_t) (archiveSize - want), want) != (int) want) {
            free(tail);
            return 0;
        }
        tail_len = want;

        eocd = -1;
        for (ssize_t i = (ssize_t) tail_len - 22; i >= 0; i--) {
            if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                eocd = i;
                break;
            }
        }
        if (eocd >= 0) break;
    }
    if (eocd < 0 || !tail) {
        free(tail);
        stream_scan_limit = saved_limit;
        return 0;
    }

    uint32_t cd_size32 = zip_u32(tail + eocd + 12);
    uint32_t cd_off32 = zip_u32(tail + eocd + 16);
    uint64_t cd_size = cd_size32;
    uint64_t cd_off = cd_off32;
    uint64_t total_entries = zip_u16(tail + eocd + 10);

    // ZIP64: locator sits immediately before EOCD when fields are 0xFFFF/0xFFFFFFFF.
    if (cd_off32 == 0xFFFFFFFFu || cd_size32 == 0xFFFFFFFFu ||
        zip_u16(tail + eocd + 8) == 0xFFFFu || zip_u16(tail + eocd + 10) == 0xFFFFu) {
        if (eocd >= 20 &&
            tail[eocd - 20] == 'P' && tail[eocd - 19] == 'K' &&
            tail[eocd - 18] == 6 && tail[eocd - 17] == 7) {
            uint64_t eocd64_off = zip_u64(tail + eocd - 20 + 8);
            uint8_t eocd64[56];
            if (stream_pread(eocd64, (la_int64_t) eocd64_off, 56) == 56 &&
                eocd64[0] == 'P' && eocd64[1] == 'K' && eocd64[2] == 6 && eocd64[3] == 6) {
                total_entries = zip_u64(eocd64 + 32);
                cd_size = zip_u64(eocd64 + 40);
                cd_off = zip_u64(eocd64 + 48);
            }
        }
    }
    free(tail);

    if (cd_size == 0 || cd_off >= archiveSize || cd_size > archiveSize ||
        cd_off + cd_size > archiveSize || cd_size > 64ull * 1024 * 1024) {
        stream_scan_limit = saved_limit;
        return 0;
    }

    uint8_t *cd = (uint8_t *) malloc((size_t) cd_size);
    if (!cd) {
        stream_scan_limit = saved_limit;
        return 0;
    }
    if (stream_pread(cd, (la_int64_t) cd_off, (size_t) cd_size) != (int) cd_size) {
        free(cd);
        stream_scan_limit = saved_limit;
        return 0;
    }

    size_t cap = cover_only ? 1
                            : (total_entries > 0 && total_entries < 100000 ? (size_t) total_entries : 64);
    if (!cover_only && cap < 16) cap = 16;
    entries = calloc(cap, sizeof(entry));
    if (!entries) {
        free(cd);
        stream_scan_limit = saved_limit;
        return 0;
    }
    entryCount = 0;
    max_file_size = 0;
    need_encrypt = false;

    size_t pos = 0;
    while (pos + 46 <= (size_t) cd_size) {
        if (cd[pos] != 'P' || cd[pos + 1] != 'K' || cd[pos + 2] != 1 || cd[pos + 3] != 2)
            break;
        uint16_t gp_flag = zip_u16(cd + pos + 8);
        uint16_t method = zip_u16(cd + pos + 10);
        uint32_t comp32 = zip_u32(cd + pos + 20);
        uint32_t uncomp32 = zip_u32(cd + pos + 24);
        uint16_t name_len = zip_u16(cd + pos + 28);
        uint16_t extra_len = zip_u16(cd + pos + 30);
        uint16_t comment_len = zip_u16(cd + pos + 32);
        uint32_t local32 = zip_u32(cd + pos + 42);
        uint64_t comp_size = comp32;
        uint64_t uncomp_size = uncomp32;
        uint64_t local_off = local32;

        size_t name_off = pos + 46;
        size_t extra_off = name_off + name_len;
        size_t next = extra_off + extra_len + comment_len;
        if (next > (size_t) cd_size || name_off + name_len > (size_t) cd_size) break;

        // ZIP64 extra (0x0001)
        if ((comp32 == 0xFFFFFFFFu || uncomp32 == 0xFFFFFFFFu || local32 == 0xFFFFFFFFu) &&
            extra_len >= 4) {
            size_t ex = 0;
            while (ex + 4 <= extra_len) {
                uint16_t tag = zip_u16(cd + extra_off + ex);
                uint16_t sz = zip_u16(cd + extra_off + ex + 2);
                if (ex + 4 + sz > extra_len) break;
                if (tag == 0x0001) {
                    size_t o = ex + 4;
                    if (uncomp32 == 0xFFFFFFFFu && o + 8 <= ex + 4 + sz) {
                        uncomp_size = zip_u64(cd + extra_off + o);
                        o += 8;
                    }
                    if (comp32 == 0xFFFFFFFFu && o + 8 <= ex + 4 + sz) {
                        comp_size = zip_u64(cd + extra_off + o);
                        o += 8;
                    }
                    if (local32 == 0xFFFFFFFFu && o + 8 <= ex + 4 + sz) {
                        local_off = zip_u64(cd + extra_off + o);
                    }
                    break;
                }
                ex += 4 + sz;
            }
        }

        if (gp_flag & 1) need_encrypt = true;

        char *name = (char *) malloc(name_len + 1);
        if (!name) break;
        memcpy(name, cd + name_off, name_len);
        name[name_len] = '\0';

        // Skip directories (name ends with /) and non-images / mac junk.
        bool is_dir = name_len > 0 && name[name_len - 1] == '/';
        if (!is_dir && filename_is_playable_file(name) &&
            (method == 0 || method == 8) && uncomp_size > 0 && uncomp_size < (1ull << 31)) {
            if (cover_only) {
                // Keep natural-first only (full CD still in RAM; no multi-entry table).
                if (entryCount == 0) {
                    entries[0].filename = name;
                    entries[0].index = 0;
                    entries[0].size = (ssize_t) uncomp_size;
                    entries[0].addr = NULL;
                    entries[0].local_header_offset = (int64_t) local_off;
                    entries[0].compressed_size = (int64_t) comp_size;
                    entries[0].compression_method = method;
                    max_file_size = (ssize_t) uncomp_size;
                    entryCount = 1;
                } else if (strnatcmp(name, entries[0].filename) < 0) {
                    free((void *) entries[0].filename);
                    entries[0].filename = name;
                    entries[0].size = (ssize_t) uncomp_size;
                    entries[0].local_header_offset = (int64_t) local_off;
                    entries[0].compressed_size = (int64_t) comp_size;
                    entries[0].compression_method = method;
                    max_file_size = (ssize_t) uncomp_size;
                } else {
                    free(name);
                }
            } else {
                if (entryCount >= cap) {
                    size_t ncap = cap * 2;
                    entry *grown = realloc(entries, ncap * sizeof(entry));
                    if (!grown) {
                        free(name);
                        break;
                    }
                    memset(grown + cap, 0, (ncap - cap) * sizeof(entry));
                    entries = grown;
                    cap = ncap;
                }
                entries[entryCount].filename = name;
                entries[entryCount].index = (int) entryCount;
                entries[entryCount].size = (ssize_t) uncomp_size;
                entries[entryCount].addr = NULL;
                entries[entryCount].local_header_offset = (int64_t) local_off;
                entries[entryCount].compressed_size = (int64_t) comp_size;
                entries[entryCount].compression_method = method;
                max_file_size = max((ssize_t) uncomp_size, max_file_size);
                entryCount++;
            }
        } else {
            free(name);
        }
        pos = next;
    }
    free(cd);

    if (!entryCount) {
        free(entries);
        entries = NULL;
        // Valid ZIP CD with no playable images — do not probe TAR/libarchive.
        stream_index_finished_empty = true;
        use_zip_cd_index = true;
        stream_scan_limit = saved_limit;
        LOGI("Found 0 images in archive (ZIP CD empty, %lld bytes net)",
             (long long) stream_bytes_read);
        return 0;
    }
    if (!cover_only && sort_entries) qsort(entries, entryCount, sizeof(entry), compare_entries);
    use_zip_cd_index = true;
    stream_scan_limit = saved_limit;
    LOGI("Found %zu images in archive (ZIP CD%s, %lld bytes net)",
         entryCount, cover_only ? " cover" : "", (long long) stream_bytes_read);
    return (int) entryCount;
}

/** Inflate (method 8) or store (method 0) one ZIP member using CD sizes. */
static int zip_stream_extract_entry(entry *e, void *out, size_t out_cap) {
    if (!e || !out || e->size <= 0 || (size_t) e->size > out_cap) return -1;
    if (e->local_header_offset < 0) return -1;

    uint8_t lh[30];
    if (stream_pread(lh, e->local_header_offset, 30) != 30) return -1;
    if (lh[0] != 'P' || lh[1] != 'K' || lh[2] != 3 || lh[3] != 4) {
        LOGE("%s", "ZIP local header signature mismatch");
        return -1;
    }
    uint16_t name_len = zip_u16(lh + 26);
    uint16_t extra_len = zip_u16(lh + 28);
    int64_t data_off = e->local_header_offset + 30 + name_len + extra_len;
    if (data_off < 0 || (size_t) data_off > archiveSize) return -1;

    int64_t csz = e->compressed_size;
    if (csz < 0 || (size_t) (data_off + csz) > archiveSize) return -1;

    if (e->compression_method == 0) {
        if (csz != e->size) return -1;
        if (stream_pread((uint8_t *) out, data_off, (size_t) e->size) != (int) e->size) return -1;
        return 0;
    }
    if (e->compression_method != 8) {
        LOGE("ZIP method %u not supported for stream extract", e->compression_method);
        return -1;
    }

    uint8_t *comp = (uint8_t *) malloc((size_t) csz);
    if (!comp) return -1;
    if (stream_pread(comp, data_off, (size_t) csz) != (int) csz) {
        free(comp);
        return -1;
    }

    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    // Negative windowBits = raw DEFLATE (ZIP).
    if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) {
        free(comp);
        return -1;
    }
    zs.next_in = comp;
    zs.avail_in = (uInt) csz;
    zs.next_out = (Bytef *) out;
    zs.avail_out = (uInt) e->size;
    int zret = inflate(&zs, Z_FINISH);
    inflateEnd(&zs);
    free(comp);
    if (zret != Z_STREAM_END && zret != Z_OK) {
        LOGE("ZIP inflate failed: %d", zret);
        return -1;
    }
    if (zs.total_out != (uLong) e->size) {
        LOGE("ZIP inflate size mismatch %lu vs %zd", zs.total_out, e->size);
        return -1;
    }
    return 0;
}

// --- TAR header-only stream index (analog of ZIP EOCD/CD) ---
// ustar/GNU/pax: read 512-byte headers, advance past padded bodies without reading them.

#define TAR_BLOCK 512

static int64_t tar_parse_octal(const uint8_t *p, size_t n) {
    int64_t v = 0;
    size_t i = 0;
    while (i < n && (p[i] == ' ' || p[i] == '\0')) i++;
    for (; i < n && p[i] >= '0' && p[i] <= '7'; i++) {
        v = (v << 3) + (p[i] - '0');
    }
    return v;
}

/** GNU base-256 size (high bit of first byte set) or classic octal. */
static int64_t tar_parse_size_field(const uint8_t *p) {
    if (p[0] & 0x80) {
        uint64_t uv = 0;
        for (int i = 1; i < 12; i++) {
            uv = (uv << 8) | p[i];
        }
        return (int64_t) uv;
    }
    return tar_parse_octal(p, 12);
}

static int tar_header_is_zero(const uint8_t *h) {
    for (int i = 0; i < TAR_BLOCK; i++) {
        if (h[i] != 0) return 0;
    }
    return 1;
}

/** Validate ustar/GNU checksum (unsigned sum; spaces in chksum field). */
static int tar_checksum_ok(const uint8_t *h) {
    unsigned sum = 0;
    for (int i = 0; i < TAR_BLOCK; i++) {
        sum += (i >= 148 && i < 156) ? (unsigned) ' ' : h[i];
    }
    int64_t stored = tar_parse_octal(h + 148, 8);
    return stored == (int64_t) sum;
}

static int64_t tar_padded_size(int64_t size) {
    if (size <= 0) return 0;
    return (size + TAR_BLOCK - 1) & ~(int64_t) (TAR_BLOCK - 1);
}

/** Build path from ustar name + prefix (or pending long/pax name). */
static char *tar_make_name(const uint8_t *h, const char *override_name) {
    if (override_name && override_name[0]) {
        return strdup(override_name);
    }
    char name[100 + 1];
    char prefix[155 + 1];
    memcpy(name, h, 100);
    name[100] = '\0';
    // Trim embedded NULs already; strip trailing spaces sometimes seen.
    for (int i = 99; i >= 0 && (name[i] == '\0' || name[i] == ' '); i--) name[i] = '\0';
    memcpy(prefix, h + 345, 155);
    prefix[155] = '\0';
    for (int i = 154; i >= 0 && (prefix[i] == '\0' || prefix[i] == ' '); i--) prefix[i] = '\0';
    if (prefix[0] == '\0') {
        return strdup(name);
    }
    size_t pl = strlen(prefix);
    size_t nl = strlen(name);
    char *full = (char *) malloc(pl + 1 + nl + 1);
    if (!full) return NULL;
    memcpy(full, prefix, pl);
    full[pl] = '/';
    memcpy(full + pl + 1, name, nl + 1);
    return full;
}

/** Read pax body and return path= value if present (caller frees). */
static char *tar_pax_extract_path(const uint8_t *body, size_t len) {
    size_t i = 0;
    while (i < len) {
        // "LEN key=value\n"
        size_t start = i;
        size_t rec_len = 0;
        while (i < len && body[i] >= '0' && body[i] <= '9') {
            rec_len = rec_len * 10 + (size_t) (body[i] - '0');
            i++;
        }
        if (i >= len || body[i] != ' ' || rec_len == 0) break;
        i++; // space
        if (start + rec_len > len) break;
        const uint8_t *kv = body + i;
        size_t kv_len = rec_len - (i - start);
        if (kv_len > 0 && kv[kv_len - 1] == '\n') kv_len--;
        if (kv_len > 5 && memcmp(kv, "path=", 5) == 0) {
            size_t vlen = kv_len - 5;
            char *path = (char *) malloc(vlen + 1);
            if (!path) return NULL;
            memcpy(path, kv + 5, vlen);
            path[vlen] = '\0';
            return path;
        }
        i = start + rec_len;
    }
    return NULL;
}

/**
 * One step of TAR header walk. Caller holds stream_mutex when concurrent with extract.
 * @param stop_after_images stop once this many *new* images were added this call
 *        (0 = unlimited until EOF / error).
 * @return 1 if more headers may remain, 0 if walk finished (EOF/error/end).
 */
static int tar_walk_step(int stop_after_images) {
    if (!tar_walk_active || tar_walk_complete) return 0;
    uint8_t hdr[TAR_BLOCK];
    int added = 0;

    while ((size_t) tar_walk_pos + TAR_BLOCK <= archiveSize) {
        if (archive_should_abort()) {
            tar_walk_active = false;
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            return 0;
        }
        if (stream_scan_exhausted()) {
            // Budget hit mid-walk — not EOF; leave incomplete for caller.
            tar_walk_active = false;
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            return 0;
        }
        int nread = stream_pread(hdr, tar_walk_pos, TAR_BLOCK);
        if (nread != TAR_BLOCK) {
            // Three outcomes: scan-limit stop, retryable error, or incomplete short read.
            // Only verified end (loop exit / double zero blocks) sets tar_walk_complete.
            // Network/JNI failure (nread < 0) must NOT freeze a truncated member list.
            if (stream_scan_hit_limit) {
                tar_walk_active = false;
            } else if (nread < 0) {
                tar_walk_active = false; /* incomplete/retryable — not structure-complete */
            } else {
                /* Short/zero while a full block was still inside archiveSize — incomplete. */
                tar_walk_active = false;
            }
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            return 0;
        }
        if (tar_header_is_zero(hdr)) {
            tar_walk_zero_blocks++;
            if (tar_walk_zero_blocks >= 2) {
                tar_walk_complete = true;
                tar_walk_active = false;
                free(tar_walk_pending);
                tar_walk_pending = NULL;
                return 0;
            }
            tar_walk_pos += TAR_BLOCK;
            continue;
        }
        tar_walk_zero_blocks = 0;
        if (!tar_checksum_ok(hdr)) {
            /* Compatibility: preserve usable members before a corrupt/junk tail. */
            tar_walk_complete = true;
            tar_walk_active = false;
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            return 0;
        }

        int64_t size = tar_parse_size_field(hdr + 124);
        if (size < 0) {
            tar_walk_complete = true;
            tar_walk_active = false;
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            return 0;
        }
        char typeflag = (char) hdr[156];
        int64_t data_off = tar_walk_pos + TAR_BLOCK;
        int64_t padded = tar_padded_size(size);
        if ((uint64_t) data_off + (uint64_t) padded > (uint64_t) archiveSize + TAR_BLOCK) {
            if ((uint64_t) data_off + (uint64_t) size > (uint64_t) archiveSize) {
                tar_walk_complete = true;
                tar_walk_active = false;
                free(tar_walk_pending);
                tar_walk_pending = NULL;
                return 0;
            }
            padded = tar_padded_size(size);
            if ((uint64_t) data_off + (uint64_t) size > (uint64_t) archiveSize) {
                tar_walk_complete = true;
                tar_walk_active = false;
                free(tar_walk_pending);
                tar_walk_pending = NULL;
                return 0;
            }
        }

        if (typeflag == 'L' || typeflag == 'K') {
            if (typeflag == 'L' && size > 0 && size < 64 * 1024) {
                free(tar_walk_pending);
                tar_walk_pending = (char *) malloc((size_t) size + 1);
                if (tar_walk_pending &&
                    stream_pread((uint8_t *) tar_walk_pending, data_off, (size_t) size) == (int) size) {
                    tar_walk_pending[size] = '\0';
                    size_t nlen = strnlen(tar_walk_pending, (size_t) size);
                    tar_walk_pending[nlen] = '\0';
                } else {
                    free(tar_walk_pending);
                    tar_walk_pending = NULL;
                }
            }
            tar_walk_pos = data_off + padded;
            continue;
        }

        if (typeflag == 'x' || typeflag == 'g') {
            if (typeflag == 'x' && size > 0 && size < 64 * 1024) {
                uint8_t *body = (uint8_t *) malloc((size_t) size);
                if (body && stream_pread(body, data_off, (size_t) size) == (int) size) {
                    char *path = tar_pax_extract_path(body, (size_t) size);
                    if (path) {
                        free(tar_walk_pending);
                        tar_walk_pending = path;
                    }
                }
                free(body);
            }
            tar_walk_pos = data_off + padded;
            continue;
        }

        bool is_reg = (typeflag == '0' || typeflag == '\0' || typeflag == '7');
        size_t name_nlen = strnlen((const char *) hdr, 100);
        bool is_dir = (typeflag == '5') ||
                      (name_nlen > 0 && ((const char *) hdr)[name_nlen - 1] == '/');

        if (is_reg && !is_dir && size > 0 && size < (1ll << 31)) {
            char *name = tar_make_name(hdr, tar_walk_pending);
            free(tar_walk_pending);
            tar_walk_pending = NULL;
            if (name && filename_is_playable_file(name)) {
                if (entryCount >= tar_walk_cap) {
                    size_t ncap = tar_walk_cap ? tar_walk_cap * 2 : 64;
                    entry *grown = realloc(entries, ncap * sizeof(entry));
                    if (!grown) {
                        free(name);
                        tar_walk_complete = true;
                        tar_walk_active = false;
                        return 0;
                    }
                    memset(grown + tar_walk_cap, 0, (ncap - tar_walk_cap) * sizeof(entry));
                    entries = grown;
                    tar_walk_cap = ncap;
                }
                entries[entryCount].filename = name;
                entries[entryCount].index = (int) entryCount;
                entries[entryCount].size = (ssize_t) size;
                entries[entryCount].addr = NULL;
                entries[entryCount].local_header_offset = data_off;
                entries[entryCount].compressed_size = size;
                entries[entryCount].compression_method = 0;
                max_file_size = max((ssize_t) size, max_file_size);
                entryCount++;
                added++;
                tar_walk_pos = data_off + padded;
                if (stop_after_images > 0 && added >= stop_after_images) {
                    return 1;
                }
                continue;
            } else {
                free(name);
            }
        } else {
            free(tar_walk_pending);
            tar_walk_pending = NULL;
        }

        tar_walk_pos = data_off + padded;
    }

    tar_walk_complete = true;
    tar_walk_active = false;
    free(tar_walk_pending);
    tar_walk_pending = NULL;
    return 0;
}

/**
 * Open stream TAR: progressive (first image) or full walk.
 * Progressive never sorts — stable indices for growing seek bar.
 */
static jint tar_stream_open_from_headers(jboolean sort_entries, bool cover_only, bool progressive) {
    tar_walk_reset();
    if (archiveSize < TAR_BLOCK) return 0;
    // Keep caller's scan budget (cover caps); do not zero stream_bytes_read if ZIP
    // already ran — but ZIP path restores its own counter. Reset only walk state.
    stream_bytes_read = 0;
    stream_scan_hit_limit = false;

    uint8_t hdr[TAR_BLOCK];
    if (stream_pread(hdr, 0, TAR_BLOCK) != TAR_BLOCK) return 0;
    if (tar_header_is_zero(hdr) || !tar_checksum_ok(hdr)) return 0;

    tar_walk_cap = 64;
    entries = calloc(tar_walk_cap, sizeof(entry));
    if (!entries) return 0;
    entryCount = 0;
    max_file_size = 0;
    need_encrypt = false;
    tar_walk_pos = 0;
    tar_walk_zero_blocks = 0;
    tar_walk_pending = NULL;
    tar_walk_active = true;
    tar_walk_complete = false;
    use_tar_index = true;

    bool stop_early = progressive || cover_only;
    if (stop_early) {
        tar_walk_step(1);
    } else {
        tar_walk_step(0);
    }

    if (!entryCount) {
        free(entries);
        entries = NULL;
        // Valid TAR: finished empty, or aborted by scan budget (not a format miss).
        if (tar_walk_complete || stream_scan_hit_limit) {
            stream_index_finished_empty = true;
            use_tar_index = true;
            LOGI("Found 0 images in archive (TAR%s, %lld bytes net%s)",
                 cover_only ? " cover" : "",
                 (long long) stream_bytes_read,
                 stream_scan_hit_limit ? ", scan cap" : " complete");
            return 0;
        }
        tar_walk_reset();
        use_tar_index = false;
        return 0;
    }

    if (!progressive && !cover_only && sort_entries && tar_walk_complete) {
        qsort(entries, entryCount, sizeof(entry), compare_entries);
        for (size_t i = 0; i < entryCount; i++) {
            entries[i].index = (int) i;
        }
    }

    if (cover_only) {
        tar_walk_active = false;
        tar_walk_complete = true;
        free(tar_walk_pending);
        tar_walk_pending = NULL;
        // Cover found — allow member extract without budget abort.
        stream_scan_limit = 0;
        stream_scan_hit_limit = false;
    }

    LOGI("Found %zu images in archive (TAR headers%s%s, %lld bytes net)",
         entryCount,
         cover_only ? " cover" : (progressive ? " progressive" : ""),
         tar_walk_complete ? " complete" : " partial",
         (long long) stream_bytes_read);
    return (int) entryCount;
}

/** Continue progressive TAR walk; add up to max_new images. Returns total count. */
static jint tar_stream_continue(int max_new) {
    pthread_mutex_lock(&stream_mutex);
    if (!use_tar_index || !tar_walk_active || tar_walk_complete) {
        jint n = (jint) entryCount;
        pthread_mutex_unlock(&stream_mutex);
        return n;
    }
    if (max_new <= 0) max_new = 8;
    tar_walk_step(max_new);
    jint n = (jint) entryCount;
    bool complete = tar_walk_complete;
    int64_t bytes_read = stream_bytes_read;
    pthread_mutex_unlock(&stream_mutex);
    if (complete) {
        LOGI("TAR progressive walk complete: %zu images, %lld bytes net",
             (size_t) n, (long long) bytes_read);
    }
    return n;
}

/** Store-only extract from TAR member data offset. */
static int tar_stream_extract_entry(entry *e, void *out, size_t out_cap) {
    if (!e || !out || e->size <= 0 || (size_t) e->size > out_cap) return -1;
    if (e->local_header_offset < 0) return -1;
    if ((size_t) e->local_header_offset + (size_t) e->size > archiveSize) return -1;
    if (stream_pread((uint8_t *) out, e->local_header_offset, (size_t) e->size) != (int) e->size) {
        return -1;
    }
    return 0;
}

/** SEEK_END uses [archiveSize] from open (stat/HEAD), not a scan. */
static la_int64_t stream_seek_cb(struct archive *a, void *client_data, la_int64_t offset, int whence) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    la_int64_t next;
    switch (whence) {
        case SEEK_SET:
            next = offset;
            break;
        case SEEK_CUR:
            next = g_stream_pos + offset;
            break;
        case SEEK_END:
            next = (la_int64_t) archiveSize + offset;
            break;
        default:
            return ARCHIVE_FATAL;
    }
    if (next < 0 || (uint64_t) next > (uint64_t) archiveSize) {
        // Match memory_read_seek: out-of-range is FAILED, not FATAL.
        return ARCHIVE_FAILED;
    }
    g_stream_pos = next;
    JNIEnv *env = archive_get_env();
    if (stream_sync_java_pos(env, next) != 0) {
        return ARCHIVE_FATAL;
    }
    return next;
}

/** Skip by seek (never read+discard). Needed for TAR listing/extract over stream I/O. */
static la_int64_t stream_skip_cb(struct archive *a, void *client_data, la_int64_t request) {
    EH_UNUSED(a);
    EH_UNUSED(client_data);
    if (request <= 0) return 0;
    if (g_stream_pos < 0) return 0;
    la_int64_t remaining = (la_int64_t) archiveSize - g_stream_pos;
    if (remaining <= 0) return 0;
    if (request > remaining) request = remaining;
    la_int64_t next = g_stream_pos + request;
    g_stream_pos = next;
    JNIEnv *env = archive_get_env();
    if (stream_sync_java_pos(env, next) != 0) {
        return ARCHIVE_FATAL;
    }
    return request;
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
    g_stream_pos = 0;
    use_stream_io = false;
    use_zip_cd_index = false;
    use_tar_index = false;
    stream_bytes_read = 0;
    tar_walk_reset();
}

/* Keep in sync with IMAGE_EXTENSIONS in MediaTypes.kt (room for heics/heifs). */
#define SUPPORT_EXT_COUNT 22

const char supportExt[SUPPORT_EXT_COUNT][8] = {
        "jpeg",
        "jpg",
        "jpe",
        "jfif",
        "png",
        "gif",
        "webp",
        "bmp",
        "ico",
        "wbmp",
        "heic",
        "heif",
        "heics",
        "heifs",
        "hif",
        "avif",
        "svg",
        "svgz",
        "jxr",
        "wdp",
        "hdp",
        "jxl",
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

static void solid_seq_reset_state(void) {
    if (solid_ctx) {
        archive_release_ctx(solid_ctx);
        solid_ctx = NULL;
    }
    use_solid_seq = false;
    solid_next_index = 0;
    solid_have_current = 0;
    solid_ext[0] = 0;
    solid_name[0] = 0;
    solid_unc_size = 0;
}

static void solid_fill_ext_from_name(const char *name) {
    solid_ext[0] = 0;
    if (!name) return;
    const char *dot = strrchr(name, '.');
    if (!dot || !dot[1]) return;
    size_t n = 0;
    for (const char *p = dot + 1; *p && n < sizeof(solid_ext) - 1; p++) {
        char c = ascii_lower(*p);
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) break;
        solid_ext[n++] = c;
    }
    solid_ext[n] = 0;
}

/** Sequential solid open over stream bridge (RAR/7z; seek kept for 7z EOF headers). */
static archive_ctx *archive_alloc_solid_seq_ctx(void) {
    archive_ctx *ctx = calloc(1, sizeof(archive_ctx));
    if (!ctx) return NULL;
    ctx->arc = archive_read_new();
    ctx->using = 1;
    archive_read_support_format_rar5(ctx->arc);
    archive_read_support_format_rar(ctx->arc);
    archive_read_support_format_7zip(ctx->arc);
    archive_read_support_format_zip(ctx->arc);
    archive_read_support_filter_gzip(ctx->arc);
    archive_read_support_filter_xz(ctx->arc);
    if (passwd)
        archive_read_add_passphrase(ctx->arc, passwd);
    g_stream_pos = 0;
    JNIEnv *env = archive_get_env();
    stream_sync_java_pos(env, 0);
    archive_read_set_skip_callback(ctx->arc, stream_skip_cb);
    archive_read_set_seek_callback(ctx->arc, stream_seek_cb);
    int err = archive_read_open(ctx->arc, NULL, stream_open_cb, stream_read_cb, stream_close_cb);
    if (err < ARCHIVE_OK) {
        LOGE("%s%s", "Solid sequential open failed: ", archive_error_string(ctx->arc));
        archive_read_free(ctx->arc);
        free(ctx);
        return NULL;
    }
    return ctx;
}

static archive_ctx *archive_alloc_ctx() {
    archive_ctx *ctx = calloc(1, sizeof(archive_ctx));
    ctx->arc = archive_read_new();
    ctx->using = 1;
    if (use_stream_io) {
        // Stream: seekable ZIP + TAR only (no streamable-zip / 7z / rar).
        archive_read_support_format_zip_seekable(ctx->arc);
        archive_read_support_format_tar(ctx->arc);
        archive_read_support_filter_gzip(ctx->arc);
        archive_read_support_filter_xz(ctx->arc);
    } else {
        archive_read_support_format_tar(ctx->arc);
        archive_read_support_format_7zip(ctx->arc);
        archive_read_support_format_rar5(ctx->arc);
        archive_read_support_format_zip(ctx->arc);
        archive_read_support_filter_gzip(ctx->arc);
        archive_read_support_filter_xz(ctx->arc);
    }
    archive_read_set_option(ctx->arc, "zip", "ignorecrc32", "1");
    if (passwd)
        archive_read_add_passphrase(ctx->arc, passwd);
    int err;
    if (use_stream_io) {
        g_stream_pos = 0;
        JNIEnv *env = archive_get_env();
        stream_sync_java_pos(env, 0);
        archive_read_set_skip_callback(ctx->arc, stream_skip_cb);
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
 * Stream open: ZIP EOCD+CD, then TAR header-only index; libarchive as last resort.
 * @param cover_only thumb path: one natural-first ZIP entry / first TAR image only.
 * @param progressive_tar reader: stop TAR after first image; continue via continueStreamTarIndex.
 */
static jint archive_open_stream_single_pass(jboolean sort_entries, bool cover_only,
                                           bool progressive_tar) {
    use_zip_cd_index = false;
    use_tar_index = false;
    tar_walk_reset();
    // stream_scan_* already set by openArchiveStream; only reset bytes for this open.
    stream_bytes_read = 0;
    stream_scan_hit_limit = false;
    stream_index_finished_empty = false;

    // ZIP central-directory index (no member walk). Uncapped inside zip helper.
    jint zip_n = zip_stream_open_from_cd(sort_entries, cover_only);
    if (zip_n > 0) return zip_n;
    if (stream_index_finished_empty) return 0;

    // TAR: progressive (reader) or full / cover-only. Honours stream_scan_limit.
    jint tar_n = tar_stream_open_from_headers(
            sort_entries, cover_only, progressive_tar && !cover_only);
    if (tar_n > 0) return tar_n;
    if (stream_index_finished_empty || stream_scan_hit_limit) return 0;

    // Fallback: libarchive with skip→seek (odd formats / edge cases).
    archive_ctx *ctx = archive_alloc_ctx();
    if (!ctx) return 0;

    size_t cap = cover_only ? 1 : 64;
    entries = calloc(cap, sizeof(entry));
    if (!entries) {
        archive_release_ctx(ctx);
        return 0;
    }
    entryCount = 0;
    max_file_size = 0;

    int last_r = ARCHIVE_OK;
    while ((last_r = archive_read_next_header(ctx->arc, &ctx->entry)) == ARCHIVE_OK) {
        if (stream_scan_exhausted()) break;
        const char *name = archive_entry_pathname(ctx->entry);
        if (!archive_entry_is_file(ctx->entry) || !filename_is_playable_file(name))
            continue;
        if (entryCount >= cap) {
            if (cover_only) break;
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
        entries[entryCount].local_header_offset = -1;
        entries[entryCount].compressed_size = -1;
        entries[entryCount].compression_method = 0;
        max_file_size = max(entries[entryCount].size, max_file_size);
        entryCount++;
        if (cover_only) {
            // Cover found — allow full page extract without budget abort.
            stream_scan_limit = 0;
            stream_scan_hit_limit = false;
            break;
        }
    }

    LOGI("Found %zu images in archive (libarchive, %lld bytes net%s)",
         entryCount, (long long) stream_bytes_read,
         stream_scan_hit_limit ? ", scan cap" : "");
    if (!entryCount) {
        if (last_r == ARCHIVE_EOF || stream_scan_hit_limit) {
            stream_index_finished_empty = true;
        } else {
            LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
        }
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

static jint archive_open_common(JNIEnv *env, jboolean sort_entries, bool cover_only,
                                bool progressive_tar) {
    EH_UNUSED(env);
    archive_ctx *ctx = NULL;
    ctx_pool = calloc(CTX_POOL_SIZE, sizeof(archive_ctx **));

    // Stream: ZIP full CD, or TAR progressive / full.
    if (use_stream_io) {
        return archive_open_stream_single_pass(sort_entries, cover_only, progressive_tar);
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
    pthread_mutex_lock(&stream_mutex);
    archive_cache_vm(env);
    archive_clear_abort();
    solid_seq_reset_state();
    stream_bridge_clear(env);
    use_stream_io = false;
    archiveAddr = mmap(0, (size_t) size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (archiveAddr == MAP_FAILED) {
        LOGE("%s%s", "mmap failed with error ", strerror(errno));
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }
    archiveSize = (size_t) size;
    jint result = archive_open_common(env, sort_entries, false, false);
    pthread_mutex_unlock(&stream_mutex);
    return result;
}

/**
 * Open archive via Kotlin [ArchiveStreamBridge] (random read/seek — SMB/WebDAV stream).
 * Does not mmap; extracts always go through decode buffers.
 * @param cover_only if true, index only the cover page (natural-first ZIP / first TAR image).
 * @param progressive_tar if true (reader), TAR stops after first image; continue via
 *        continueStreamTarIndex. Seek bar grows as more headers are discovered.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openArchiveStream(
        JNIEnv *env, jclass thiz, jobject bridge, jlong size, jboolean sort_entries,
        jboolean cover_only, jboolean progressive_tar, jlong max_scan_bytes) {
    EH_UNUSED(thiz);
    if (!bridge || size <= 0) return 0;
    pthread_mutex_lock(&stream_mutex);
    archive_cache_vm(env);
    archive_clear_abort();
    solid_seq_reset_state();
    stream_bridge_clear(env);
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
        archiveSize = 0;
    }
    use_stream_io = true;
    archiveSize = (size_t) size;
    archiveAddr = MAP_FAILED;
    g_stream_pos = 0;
    // Non-ZIP cover budget (0 = unlimited). ZIP CD path clears limit while parsing.
    stream_scan_reset(max_scan_bytes);
    g_stream_bridge = (*env)->NewGlobalRef(env, bridge);
    jclass cls = (*env)->GetObjectClass(env, bridge);
    g_mid_read = (*env)->GetMethodID(env, cls, "nativeRead", "(I)[B");
    g_mid_seek = (*env)->GetMethodID(env, cls, "nativeSeek", "(JI)J");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_mid_read || !g_mid_seek) {
        LOGE("%s", "ArchiveStreamBridge methods missing");
        stream_bridge_clear(env);
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }
    // Reader progressive TAR; cover_only never progressive (thumb stops at first image).
    bool prog = progressive_tar == JNI_TRUE && cover_only != JNI_TRUE;
    jint result = archive_open_common(env, sort_entries, cover_only == JNI_TRUE, prog);
    pthread_mutex_unlock(&stream_mutex);
    return result;
}

/** Bytes pulled through stream I/O during the active open/extract session. */
JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getStreamBytesRead(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return (jlong) stream_bytes_read;
}

/** True when a non-ZIP scan budget aborted further reads. */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_isArchiveScanLimited(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return stream_scan_hit_limit ? JNI_TRUE : JNI_FALSE;
}

/**
 * True when stream open finished a ZIP/TAR/libarchive probe with zero playable
 * images (or hit the scan budget). Used by cover extract to decide NoImages vs Skip.
 */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_isStreamIndexFinishedEmpty(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return stream_index_finished_empty ? JNI_TRUE : JNI_FALSE;
}

/** Continue progressive TAR index; returns total listed count. */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_continueStreamTarIndex(
        JNIEnv *env, jclass thiz, jint max_new) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return tar_stream_continue((int) max_new);
}

/** True when stream index walk finished (ZIP always true after open; TAR progressive when done). */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_isStreamIndexComplete(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (use_zip_cd_index) return JNI_TRUE;
    if (use_tar_index) return tar_walk_complete ? JNI_TRUE : JNI_FALSE;
    // libarchive fallback / unknown — treat as complete after open.
    return JNI_TRUE;
}

/**
 * Open RAR/7z (and friends) for sequential pull extract over [ArchiveStreamBridge].
 * Returns 1 on success, 0 on failure. Does not build a full entry list.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openSolidSequential(
        JNIEnv *env, jclass thiz, jobject bridge, jlong size, jlong max_scan_bytes) {
    EH_UNUSED(thiz);
    if (!bridge || size <= 0) return 0;
    pthread_mutex_lock(&stream_mutex);
    archive_cache_vm(env);
    archive_clear_abort();
    // Tear down any prior archive session (mmap / zip stream / solid).
    if (ctx_pool) {
        for (int i = 0; i < CTX_POOL_SIZE; i++)
            archive_release_ctx(ctx_pool[i]);
        free(ctx_pool);
        ctx_pool = NULL;
    }
    if (entries) {
        for (int i = 0; i < (int) entryCount; ++i)
            free((void *) entries[i].filename);
        free(entries);
        entries = NULL;
        entryCount = 0;
    }
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
    }
    solid_seq_reset_state();
    stream_bridge_clear(env);

    use_stream_io = true;
    use_zip_cd_index = false;
    use_tar_index = false;
    archiveSize = (size_t) size;
    archiveAddr = MAP_FAILED;
    need_encrypt = false;
    g_stream_pos = 0;
    // Cover scans pass a budget; full solid reader passes 0 (unlimited).
    stream_scan_reset(max_scan_bytes);
    g_stream_bridge = (*env)->NewGlobalRef(env, bridge);
    jclass cls = (*env)->GetObjectClass(env, bridge);
    g_mid_read = (*env)->GetMethodID(env, cls, "nativeRead", "(I)[B");
    g_mid_seek = (*env)->GetMethodID(env, cls, "nativeSeek", "(JI)J");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_mid_read || !g_mid_seek) {
        LOGE("%s", "ArchiveStreamBridge methods missing (solid)");
        stream_bridge_clear(env);
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }
    solid_ctx = archive_alloc_solid_seq_ctx();
    if (!solid_ctx) {
        stream_bridge_clear(env);
        use_stream_io = false;
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }
    use_solid_seq = true;
    LOGI("Solid sequential open ok (size=%lld)", (long long) size);
    pthread_mutex_unlock(&stream_mutex);
    return 1;
}

/**
 * Advance to next playable image member.
 * @return index (>=0), -1 EOF, -2 error. Idempotent if current not yet extracted/skipped.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidNextPlayable(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    if (!use_solid_seq || !solid_ctx) {
        pthread_mutex_unlock(&stream_mutex);
        return -2;
    }
    if (solid_have_current) {
        jint result = solid_next_index;
        pthread_mutex_unlock(&stream_mutex);
        return result;
    }
    int r;
    while ((r = archive_read_next_header(solid_ctx->arc, &solid_ctx->entry)) == ARCHIVE_OK) {
        if (archive_should_abort()) {
            pthread_mutex_unlock(&stream_mutex);
            return -2;
        }
        if (stream_scan_exhausted()) {
            pthread_mutex_unlock(&stream_mutex);
            return -1; // treat as end; isArchiveScanLimited distinguishes budget
        }
        if (archive_entry_is_encrypted(solid_ctx->entry))
            need_encrypt = true;
        if (!archive_entry_is_playable(solid_ctx->entry)) {
            // Consume non-image bodies so solid stream advances.
            if (archive_read_data_skip(solid_ctx->arc) < ARCHIVE_OK && stream_scan_hit_limit) {
                pthread_mutex_unlock(&stream_mutex);
                return -1;
            }
            continue;
        }
        const char *name = archive_entry_pathname(solid_ctx->entry);
        if (!name) name = "";
        size_t nlen = strlen(name);
        if (nlen >= sizeof(solid_name)) nlen = sizeof(solid_name) - 1;
        memcpy(solid_name, name, nlen);
        solid_name[nlen] = 0;
        solid_fill_ext_from_name(name);
        solid_unc_size = archive_entry_size(solid_ctx->entry);
        solid_have_current = 1;
        // Found a playable member — lift cover scan budget so extract is not aborted.
        stream_scan_limit = 0;
        stream_scan_hit_limit = false;
        jint result = solid_next_index;
        pthread_mutex_unlock(&stream_mutex);
        return result;
    }
    if (r == ARCHIVE_EOF || stream_scan_hit_limit) {
        if (r == ARCHIVE_EOF) stream_index_finished_empty = true;
        pthread_mutex_unlock(&stream_mutex);
        return -1;
    }
    LOGE("%s%s", "solidNextPlayable: ", archive_error_string(solid_ctx->arc));
    pthread_mutex_unlock(&stream_mutex);
    return -2;
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidCurrentExtension(JNIEnv *env, jclass thiz) {
    EH_UNUSED(thiz);
    if (!use_solid_seq || !solid_have_current || !solid_ext[0])
        return (*env)->NewStringUTF(env, "bin");
    return (*env)->NewStringUTF(env, solid_ext);
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidCurrentName(JNIEnv *env, jclass thiz) {
    EH_UNUSED(thiz);
    if (!use_solid_seq || !solid_have_current)
        return (*env)->NewStringUTF(env, "");
    return (*env)->NewStringUTF(env, solid_name);
}

JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidCurrentUncSize(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (!use_solid_seq || !solid_have_current) return 0;
    return (jlong) solid_unc_size;
}

/** Extract current playable member into [fd]. Advances playable cursor on success. */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidExtractCurrentToFd(JNIEnv *env, jclass thiz, jint fd) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    if (!use_solid_seq || !solid_ctx || !solid_have_current || fd < 0) {
        pthread_mutex_unlock(&stream_mutex);
        return JNI_FALSE;
    }
    int ret = archive_read_data_into_fd(solid_ctx->arc, fd);
    if (ret == ARCHIVE_OK) {
        solid_have_current = 0;
        solid_next_index++;
        pthread_mutex_unlock(&stream_mutex);
        return JNI_TRUE;
    }
    LOGE("%s%s", "solidExtractCurrentToFd: ", archive_error_string(solid_ctx->arc));
    pthread_mutex_unlock(&stream_mutex);
    return JNI_FALSE;
}

/** Skip current playable body without writing (still decompresses solid). Advances cursor. */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_solidSkipCurrent(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    if (!use_solid_seq || !solid_ctx || !solid_have_current) {
        pthread_mutex_unlock(&stream_mutex);
        return JNI_FALSE;
    }
    int ret = archive_read_data_skip(solid_ctx->arc);
    if (ret == ARCHIVE_OK || ret == ARCHIVE_EOF) {
        solid_have_current = 0;
        solid_next_index++;
        pthread_mutex_unlock(&stream_mutex);
        return JNI_TRUE;
    }
    LOGE("%s%s", "solidSkipCurrent: ", archive_error_string(solid_ctx->arc));
    pthread_mutex_unlock(&stream_mutex);
    return JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToByteBuffer(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    if (index < 0 || (size_t) index >= entryCount || !entries || archive_should_abort()) {
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }
    entry *entry = &entries[index];
    ssize_t size = entry->size;
    if (entry->addr) {
        jobject result = (*env)->NewDirectByteBuffer(env, entry->addr, size);
        pthread_mutex_unlock(&stream_mutex);
        return result;
    }

    jobject result = 0;
    void *addr = acquire_decode_buffer();
    if (!addr) {
        LOGE("%s", "Decode buffer alloc failed");
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }

    if (use_stream_io && use_zip_cd_index) {
        // Direct range-read + inflate — one member only (no archive re-scan).
        if (zip_stream_extract_entry(entry, addr, (size_t) size) == 0) {
            result = (*env)->NewDirectByteBuffer(env, addr, size);
        } else {
            LOGE("%s%d", "ZIP stream extract failed for index ", index);
            release_decode_buffer(addr);
        }
    } else if (use_stream_io && use_tar_index) {
        if (tar_stream_extract_entry(entry, addr, (size_t) size) == 0) {
            result = (*env)->NewDirectByteBuffer(env, addr, size);
        } else {
            LOGE("%s%d", "TAR stream extract failed for index ", index);
            release_decode_buffer(addr);
        }
    } else {
        archive_ctx *ctx = NULL;
        if (!archive_get_ctx(&ctx, entry->index)) {
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
                archive_drop_ctx(ctx);
                release_decode_buffer(addr);
            }
        } else {
            release_decode_buffer(addr);
        }
    }

    pthread_mutex_unlock(&stream_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_closeArchive(JNIEnv *env, jclass thiz) {
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    solid_seq_reset_state();
    if (ctx_pool) {
        for (int i = 0; i < CTX_POOL_SIZE; i++)
            archive_release_ctx(ctx_pool[i]);
        free(ctx_pool);
        ctx_pool = NULL;
    }
    free(passwd);
    passwd = NULL;
    need_encrypt = false;
    use_zip_cd_index = false;
    use_tar_index = false;
    stream_bytes_read = 0;
    tar_walk_reset();
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
    pthread_mutex_unlock(&stream_mutex);
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
    pthread_mutex_lock(&stream_mutex);
    struct archive_entry *entry;
    archive_ctx *ctx;
    jboolean ret = true;
    int len = (*env)->GetStringUTFLength(env, str);
    passwd = realloc(passwd, len + 1);
    (*env)->GetStringUTFRegion(env, str, 0, len, passwd);
    passwd[len] = 0;

    // Solid sequential: recreate session from start with passphrase.
    if (use_solid_seq) {
        if (solid_ctx) {
            archive_release_ctx(solid_ctx);
            solid_ctx = NULL;
        }
        solid_next_index = 0;
        solid_have_current = 0;
        solid_ctx = archive_alloc_solid_seq_ctx();
        if (!solid_ctx) {
            pthread_mutex_unlock(&stream_mutex);
            return JNI_FALSE;
        }
        // Probe first encrypted playable.
        char tmpBuf[4096];
        while (archive_read_next_header(solid_ctx->arc, &solid_ctx->entry) == ARCHIVE_OK) {
            if (!archive_entry_is_playable(solid_ctx->entry)) {
                archive_read_data_skip(solid_ctx->arc);
                continue;
            }
            if (archive_entry_is_encrypted(solid_ctx->entry)) {
                if (archive_read_data(solid_ctx->arc, tmpBuf, sizeof(tmpBuf)) < ARCHIVE_OK) {
                    LOGE("%s%s", "Solid password probe failed: ", archive_error_string(solid_ctx->arc));
                    ret = false;
                }
            }
            break;
        }
        // Re-open clean for pull API after probe.
        archive_release_ctx(solid_ctx);
        solid_ctx = archive_alloc_solid_seq_ctx();
        solid_next_index = 0;
        solid_have_current = 0;
        need_encrypt = false;
        jboolean result = ret && solid_ctx != NULL;
        pthread_mutex_unlock(&stream_mutex);
        return result;
    }

    ctx = archive_alloc_ctx();
    if (!ctx) {
        pthread_mutex_unlock(&stream_mutex);
        return JNI_FALSE;
    }
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
    pthread_mutex_unlock(&stream_mutex);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getExtension(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(thiz);
    const char *ext = "";
    if (entries && index >= 0 && (size_t) index < entryCount && entries[index].filename) {
        const char *dot = strrchr(entries[index].filename, '.');
        if (dot && dot[1]) ext = dot + 1;
    }
    return (*env)->NewStringUTF(env, ext);
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getArchiveFilename(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(thiz);
    const char *name = "";
    if (entries && index >= 0 && (size_t) index < entryCount && entries[index].filename) {
        name = entries[index].filename;
    }
    return (*env)->NewStringUTF(env, name);
}

/**
 * Stream direct-index: ZIP local-header offset or TAR data offset for [index].
 * Used for next-page readahead warm. Returns -1 if unavailable.
 */
JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getStreamMemberOffset(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (use_stream_io && entries && index >= 0 && (size_t) index < entryCount &&
        (use_zip_cd_index || use_tar_index)) {
        return entries[index].local_header_offset;
    }
    return -1;
}

/**
 * Compressed (ZIP) or raw (TAR) byte length for stream warm. -1 if unknown.
 */
JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getStreamMemberLength(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (use_stream_io && entries && index >= 0 && (size_t) index < entryCount &&
        (use_zip_cd_index || use_tar_index)) {
        return entries[index].compressed_size;
    }
    return -1;
}

/** Uncompressed size for decode buffer. */
JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getStreamMemberUncSize(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (use_stream_io && entries && index >= 0 && (size_t) index < entryCount &&
        (use_zip_cd_index || use_tar_index)) {
        return (jlong) entries[index].size;
    }
    return -1;
}

/** ZIP method or 0 for TAR. */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getStreamMemberMethod(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (use_stream_io && entries && index >= 0 && (size_t) index < entryCount &&
        (use_zip_cd_index || use_tar_index)) {
        return (jint) entries[index].compression_method;
    }
    return -1;
}

/** True when active stream index is TAR (store). */
JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_isStreamTarIndex(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return use_tar_index ? JNI_TRUE : JNI_FALSE;
}

/**
 * Install disk-cached stream member table (offsets/sizes) — skip ZIP CD / TAR header walk.
 * Parallel arrays length = n. names used for getExtension only.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_loadStreamIndex(
        JNIEnv *env, jclass thiz, jobject bridge, jlong size,
        jlongArray offsets, jlongArray uncSizes, jlongArray compSizes,
        jintArray methods, jobjectArray names, jboolean is_tar) {
    EH_UNUSED(thiz);
    if (!bridge || size <= 0 || !offsets || !uncSizes || !compSizes || !methods || !names) {
        return 0;
    }
    jsize n = (*env)->GetArrayLength(env, offsets);
    if (n <= 0 ||
        (*env)->GetArrayLength(env, uncSizes) != n ||
        (*env)->GetArrayLength(env, compSizes) != n ||
        (*env)->GetArrayLength(env, methods) != n ||
        (*env)->GetArrayLength(env, names) != n) {
        return 0;
    }
    if (n > 500000) return 0;

    pthread_mutex_lock(&stream_mutex);
    archive_clear_abort();
    archive_cache_vm(env);
    solid_seq_reset_state();
    stream_bridge_clear(env);
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
    }
    if (entries) {
        for (int i = 0; i < (int) entryCount; ++i)
            free((void *) entries[i].filename);
        free(entries);
        entries = NULL;
        entryCount = 0;
    }
    if (ctx_pool) {
        for (int i = 0; i < CTX_POOL_SIZE; i++)
            archive_release_ctx(ctx_pool[i]);
        free(ctx_pool);
        ctx_pool = NULL;
    }

    use_stream_io = true;
    use_zip_cd_index = (is_tar != JNI_TRUE);
    use_tar_index = (is_tar == JNI_TRUE);
    archiveSize = (size_t) size;
    archiveAddr = MAP_FAILED;
    need_encrypt = false;
    g_stream_pos = 0;
    stream_bytes_read = 0;
    g_stream_bridge = (*env)->NewGlobalRef(env, bridge);
    jclass cls = (*env)->GetObjectClass(env, bridge);
    g_mid_read = (*env)->GetMethodID(env, cls, "nativeRead", "(I)[B");
    g_mid_seek = (*env)->GetMethodID(env, cls, "nativeSeek", "(JI)J");
    (*env)->DeleteLocalRef(env, cls);
    if (!g_mid_read || !g_mid_seek) {
        LOGE("%s", "loadStreamIndex: bridge methods missing");
        stream_bridge_clear(env);
        use_zip_cd_index = false;
        use_tar_index = false;
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }

    entries = calloc((size_t) n, sizeof(entry));
    if (!entries) {
        stream_bridge_clear(env);
        use_zip_cd_index = false;
        use_tar_index = false;
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }

    jlong *offs = (*env)->GetLongArrayElements(env, offsets, NULL);
    jlong *uncs = (*env)->GetLongArrayElements(env, uncSizes, NULL);
    jlong *comps = (*env)->GetLongArrayElements(env, compSizes, NULL);
    jint *meths = (*env)->GetIntArrayElements(env, methods, NULL);
    if (!offs || !uncs || !comps || !meths) {
        if (offs) (*env)->ReleaseLongArrayElements(env, offsets, offs, JNI_ABORT);
        if (uncs) (*env)->ReleaseLongArrayElements(env, uncSizes, uncs, JNI_ABORT);
        if (comps) (*env)->ReleaseLongArrayElements(env, compSizes, comps, JNI_ABORT);
        if (meths) (*env)->ReleaseIntArrayElements(env, methods, meths, JNI_ABORT);
        free(entries);
        entries = NULL;
        stream_bridge_clear(env);
        use_zip_cd_index = false;
        use_tar_index = false;
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }

    max_file_size = 0;
    int ok = 1;
    for (jsize i = 0; i < n; i++) {
        if (offs[i] < 0 || uncs[i] <= 0 || uncs[i] >= (1ll << 31)) {
            ok = 0;
            break;
        }
        jstring jn = (jstring) (*env)->GetObjectArrayElement(env, names, i);
        const char *utf = jn ? (*env)->GetStringUTFChars(env, jn, NULL) : NULL;
        char *fname = NULL;
        if (utf && utf[0]) {
            fname = strdup(utf);
        } else {
            char tmp[32];
            snprintf(tmp, sizeof(tmp), "%d.bin", (int) i);
            fname = strdup(tmp);
        }
        if (utf && jn) (*env)->ReleaseStringUTFChars(env, jn, utf);
        if (jn) (*env)->DeleteLocalRef(env, jn);
        if (!fname) {
            ok = 0;
            break;
        }
        entries[i].filename = fname;
        entries[i].index = (int) i;
        entries[i].size = (ssize_t) uncs[i];
        entries[i].addr = NULL;
        entries[i].local_header_offset = (int64_t) offs[i];
        entries[i].compressed_size = comps[i] > 0 ? (int64_t) comps[i] : (int64_t) uncs[i];
        entries[i].compression_method = meths[i] >= 0 ? (uint16_t) meths[i] : 0;
        if (entries[i].size > max_file_size) max_file_size = entries[i].size;
    }

    (*env)->ReleaseLongArrayElements(env, offsets, offs, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, uncSizes, uncs, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, compSizes, comps, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, methods, meths, JNI_ABORT);

    if (!ok) {
        for (jsize i = 0; i < n; i++) {
            free((void *) entries[i].filename);
        }
        free(entries);
        entries = NULL;
        entryCount = 0;
        stream_bridge_clear(env);
        use_zip_cd_index = false;
        use_tar_index = false;
        pthread_mutex_unlock(&stream_mutex);
        return 0;
    }

    entryCount = (size_t) n;
    // Match openArchiveStream: decode buffers sized from max_file_size via existing paths.
    if (!ctx_pool) {
        ctx_pool = calloc(CTX_POOL_SIZE, sizeof(archive_ctx *));
    }
    LOGI("loadStreamIndex: %d entries (%s) size=%lld",
         (int) n, is_tar ? "tar" : "zip", (long long) size);
    pthread_mutex_unlock(&stream_mutex);
    return (jint) n;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToFd(JNIEnv *env, jclass thiz, jint index, jint fd) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    if (index < 0 || (size_t) index >= entryCount || !entries || archive_should_abort()) {
        pthread_mutex_unlock(&stream_mutex);
        return JNI_FALSE;
    }
    int arcIndex = entries[index].index;
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
    pthread_mutex_unlock(&stream_mutex);
    return ret == ARCHIVE_OK;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_releaseByteBuffer(JNIEnv *env, jclass thiz, jobject buffer) {
    EH_UNUSED(thiz);
    pthread_mutex_lock(&stream_mutex);
    void *addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!ADDR_IN_FILE_MAPPING(addr)) {
        release_decode_buffer(addr);
    }
    pthread_mutex_unlock(&stream_mutex);
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
