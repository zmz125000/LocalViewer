/*
 * Camera RAW (DNG / CR2 / NEF / …).
 *
 * 64-bit: LibRaw demosaic → packed RGBA for the reader.
 *   present 0: 8-bit sRGB
 *   present 1: deep color, linear Display P3, float16 clamped to 0..1
 *   present 2: deep color + Android 16 HDR, linear Rec.2020, float16 with
 *              highlight headroom. The advanced-color switch is not a third mode;
 *              the caller selects 2 whenever HDR display is on and the panel is HDR.
 * Every ABI: embedded JPEG preview for covers and for a failed demosaic.
 * 64-bit covers with no preview get a long-edge-512 demosaic JPEG.
 */
#include <android/log.h>

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <csetjmp>
#include <vector>

#if defined(EHVIEWER_HDR_CODECS)
#include <jpeglib.h>
#include "libraw/libraw.h"

struct RawJpegError {
    jpeg_error_mgr pub;
    jmp_buf jump;
};

// libjpeg's error_exit is a C function pointer. C++ linkage will not convert.
extern "C" void raw_still_jpeg_bail(j_common_ptr cinfo) {
    auto* err = reinterpret_cast<RawJpegError*>(cinfo->err);
    longjmp(err->jump, 1);
}
#endif

namespace {

constexpr const char* kTag = "RawStill";

void log_err(const char* msg) { __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", msg); }

bool write_file(const char* path, const uint8_t* data, size_t n) {
    if (!path || !data || n == 0) return false;
    FILE* fp = std::fopen(path, "wb");
    if (!fp) return false;
    size_t wrote = std::fwrite(data, 1, n, fp);
    std::fclose(fp);
    return wrote == n;
}

bool read_file(const char* path, std::vector<uint8_t>& out) {
    FILE* fp = std::fopen(path, "rb");
    if (!fp) return false;
    if (fseeko(fp, 0, SEEK_END) != 0) {
        std::fclose(fp);
        return false;
    }
    off_t sz = ftello(fp);
    if (sz <= 0 || sz > 256LL * 1024LL * 1024LL) {
        std::fclose(fp);
        return false;
    }
    if (fseeko(fp, 0, SEEK_SET) != 0) {
        std::fclose(fp);
        return false;
    }
    out.resize(static_cast<size_t>(sz));
    size_t n = std::fread(out.data(), 1, out.size(), fp);
    std::fclose(fp);
    return n == out.size();
}

// Largest embedded JPEG (SOI + APP/DQT … EOI). One forward pass.
bool find_embedded_jpeg(const uint8_t* data, size_t len, size_t& off, size_t& nout) {
    size_t best_off = 0;
    size_t best_len = 0;
    size_t soi = static_cast<size_t>(-1);
    if (len < 4) return false;
    for (size_t i = 0; i + 1 < len; ++i) {
        if (data[i] != 0xff) continue;
        uint8_t marker = data[i + 1];
        if (marker == 0xd8 && i + 3 < len && data[i + 2] == 0xff) {
            uint8_t app = data[i + 3];
            if (app == 0xe0 || app == 0xe1 || app == 0xe2 || app == 0xdb) soi = i;
            ++i;
            continue;
        }
        if (marker == 0xd9 && soi != static_cast<size_t>(-1)) {
            size_t cand = i + 2 - soi;
            if (cand > best_len) {
                best_len = cand;
                best_off = soi;
            }
            soi = static_cast<size_t>(-1);
            ++i;
        }
    }
    if (best_len < 512) return false;
    off = best_off;
    nout = best_len;
    return true;
}

bool write_embedded_jpeg(const uint8_t* data, size_t len, const char* out_path) {
    size_t off = 0;
    size_t n = 0;
    if (!find_embedded_jpeg(data, len, off, n)) return false;
    return write_file(out_path, data + off, n);
}

#if defined(EHVIEWER_HDR_CODECS)

bool write_rgba8_jpeg(const uint8_t* rgba, int w, int h, const char* path, int quality) {
    if (!rgba || w <= 0 || h <= 0 || !path) return false;
    FILE* fp = std::fopen(path, "wb");
    if (!fp) return false;
    jpeg_compress_struct cinfo{};
    RawJpegError jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = raw_still_jpeg_bail;
    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        std::fclose(fp);
        return false;
    }
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, fp);
    cinfo.image_width = static_cast<JDIMENSION>(w);
    cinfo.image_height = static_cast<JDIMENSION>(h);
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    jpeg_start_compress(&cinfo, TRUE);
    std::vector<uint8_t> row(static_cast<size_t>(w) * 3);
    while (cinfo.next_scanline < cinfo.image_height) {
        const uint8_t* src = rgba + static_cast<size_t>(cinfo.next_scanline) * static_cast<size_t>(w) * 4;
        for (int x = 0; x < w; ++x) {
            row[static_cast<size_t>(x) * 3] = src[static_cast<size_t>(x) * 4];
            row[static_cast<size_t>(x) * 3 + 1] = src[static_cast<size_t>(x) * 4 + 1];
            row[static_cast<size_t>(x) * 3 + 2] = src[static_cast<size_t>(x) * 4 + 2];
        }
        JSAMPROW rows[1] = {row.data()};
        jpeg_write_scanlines(&cinfo, rows, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    std::fclose(fp);
    return true;
}

uint16_t float_to_half(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    uint32_t sign = (bits >> 16) & 0x8000u;
    int32_t exp = static_cast<int32_t>((bits >> 23) & 0xff) - 127 + 15;
    uint32_t mant = bits & 0x7fffffu;
    if ((bits & 0x7fffffffu) > 0x7f800000u) return static_cast<uint16_t>(sign | 0x7e00u);
    if (((bits >> 23) & 0xff) == 0xff) return static_cast<uint16_t>(sign | 0x7c00u);
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        uint32_t shift = static_cast<uint32_t>(1 - exp);
        uint32_t half_mant = mant >> (shift + 13);
        if ((mant >> (shift + 12)) & 1u) half_mant += 1u;
        return static_cast<uint16_t>(sign | half_mant);
    }
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
    uint16_t half = static_cast<uint16_t>(sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13));
    if (mant & 0x1000u) half = static_cast<uint16_t>(half + 1);
    return half;
}

struct Decoded {
    std::vector<uint8_t> pixels;
    int w = 0;
    int h = 0;
    int format = 0;
    int is_hdr = 0;
    int gamut = 0;
    float boost = 1.f;
};

void dst_size(int w, int h, int max_edge, int& dw, int& dh) {
    int long_edge = std::max(w, h);
    if (max_edge <= 0 || long_edge <= max_edge) {
        dw = w;
        dh = h;
        return;
    }
    double scale = static_cast<double>(max_edge) / static_cast<double>(long_edge);
    dw = std::max(1, static_cast<int>(std::lround(w * scale)));
    dh = std::max(1, static_cast<int>(std::lround(h * scale)));
}

float sample_component(const uint8_t* base, int bits, int colors, int x, int y, int w, int channel) {
    size_t i = (static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)) * static_cast<size_t>(colors);
    if (bits == 16) {
        uint16_t v = 0;
        std::memcpy(&v, base + i * 2 + static_cast<size_t>(channel) * 2, sizeof(v));
        return static_cast<float>(v);
    }
    return static_cast<float>(base[i + static_cast<size_t>(channel)]);
}

// Diffuse white ≈ 90th percentile of sampled luminance. Peak is the brightest sample.
void linear_white_and_peak(const uint8_t* base, int w, int h, int colors, int bits, float& white, float& peak) {
    const int npx = w * h;
    int step = 1;
    while (npx / step > 50000) step *= 2;
    std::vector<float> ys;
    ys.reserve(static_cast<size_t>(npx / step + 1));
    peak = 0.f;
    const float full = bits == 16 ? 65535.f : 255.f;
    for (int i = 0; i < npx; i += step) {
        int y = i / w;
        int x = i - y * w;
        int ch = std::min(colors, 3);
        float r = sample_component(base, bits, colors, x, y, w, 0);
        float g = ch > 1 ? sample_component(base, bits, colors, x, y, w, 1) : r;
        float b = ch > 2 ? sample_component(base, bits, colors, x, y, w, 2) : r;
        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        ys.push_back(lum);
        peak = std::max(peak, std::max(r, std::max(g, b)));
    }
    if (ys.empty()) {
        white = full;
        peak = full;
        return;
    }
    size_t idx = static_cast<size_t>(0.90 * static_cast<double>(ys.size() - 1));
    std::nth_element(ys.begin(), ys.begin() + static_cast<std::ptrdiff_t>(idx), ys.end());
    white = std::max(ys[idx], 1.f);
    peak = std::max(peak, white);
}

bool pack_image(const libraw_processed_image_t* img, int max_edge, int present, float panel_boost, Decoded& out) {
    if (!img || img->width == 0 || img->height == 0) return false;
    if (img->type != LIBRAW_IMAGE_BITMAP) return false;
    const int sw = img->width;
    const int sh = img->height;
    const int colors = img->colors >= 1 ? img->colors : 1;
    const size_t bpp = img->bits == 16 ? 2u : 1u;
    const size_t need = static_cast<size_t>(sw) * static_cast<size_t>(sh) * static_cast<size_t>(colors) * bpp;
    if (img->data_size < need) return false;
    const bool deep = present != 0;
    if (deep && img->bits != 16) return false;
    if (!deep && img->bits != 8) return false;
    int dw = 0;
    int dh = 0;
    dst_size(sw, sh, max_edge, dw, dh);
    const size_t bytes = static_cast<size_t>(dw) * static_cast<size_t>(dh) * (deep ? 8u : 4u);
    if (bytes == 0 || bytes > 400u * 1024u * 1024u) return false;

    float white = deep ? 65535.f : 255.f;
    float peak = white;
    if (deep) linear_white_and_peak(img->data, sw, sh, colors, img->bits, white, peak);
    float headroom = 1.f;
    if (present == 2 && white > 0.f) {
        float panel = std::isfinite(panel_boost) ? std::clamp(panel_boost, 1.f, 64.f) : 1.f;
        headroom = std::clamp(peak / white, 1.f, panel);
    }

    out.pixels.assign(bytes, 0);
    out.w = dw;
    out.h = dh;
    out.format = deep ? 1 : 0;
    out.is_hdr = present == 2 ? 1 : 0;
    out.gamut = present == 2 ? 2 : (present == 1 ? 1 : 0);
    out.boost = present == 2 ? headroom : 1.f;
    const float cap = present == 2 ? out.boost : 1.f;

    for (int y = 0; y < dh; ++y) {
        int y0 = y * sh / dh;
        int y1 = std::max(y0 + 1, (y + 1) * sh / dh);
        for (int x = 0; x < dw; ++x) {
            int x0 = x * sw / dw;
            int x1 = std::max(x0 + 1, (x + 1) * sw / dw);
            double acc[3] = {0, 0, 0};
            int count = 0;
            for (int sy = y0; sy < y1; ++sy) {
                for (int sx = x0; sx < x1; ++sx) {
                    for (int c = 0; c < 3; ++c) {
                        int src_c = colors == 1 ? 0 : std::min(c, colors - 1);
                        acc[c] += sample_component(img->data, img->bits, colors, sx, sy, sw, src_c);
                    }
                    ++count;
                }
            }
            if (count <= 0) continue;
            size_t o = (static_cast<size_t>(y) * static_cast<size_t>(dw) + static_cast<size_t>(x)) * (deep ? 8u : 4u);
            if (!deep) {
                for (int c = 0; c < 3; ++c) {
                    int v = static_cast<int>(std::lround(acc[c] / count));
                    out.pixels[o + static_cast<size_t>(c)] = static_cast<uint8_t>(std::clamp(v, 0, 255));
                }
                out.pixels[o + 3] = 255;
            } else {
                for (int c = 0; c < 3; ++c) {
                    float v = static_cast<float>((acc[c] / count) / white);
                    if (!std::isfinite(v)) v = 0.f;
                    v = std::clamp(v, 0.f, cap);
                    uint16_t half = float_to_half(v);
                    out.pixels[o + static_cast<size_t>(c) * 2] = static_cast<uint8_t>(half & 0xff);
                    out.pixels[o + static_cast<size_t>(c) * 2 + 1] = static_cast<uint8_t>(half >> 8);
                }
                uint16_t alpha = 0x3c00;
                out.pixels[o + 6] = static_cast<uint8_t>(alpha & 0xff);
                out.pixels[o + 7] = static_cast<uint8_t>(alpha >> 8);
            }
        }
    }
    return true;
}

void configure_process(LibRaw& raw, int present, int max_edge, int demosaic_qual) {
    auto& p = raw.imgdata.params;
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.use_camera_matrix = 1;
    p.user_qual = demosaic_qual;
    p.half_size = 0;
    int full_w = raw.imgdata.sizes.width;
    int full_h = raw.imgdata.sizes.height;
    int long_edge = std::max(full_w, full_h);
    if (max_edge > 0 && max_edge <= 4096 && long_edge / 2 >= max_edge) p.half_size = 1;
    if (present == 0) {
        p.output_bps = 8;
        p.output_color = 1;  // sRGB
        p.no_auto_bright = 0;
        p.highlight = 0;
        p.gamm[0] = 0.45;
        p.gamm[1] = 4.5;
    } else {
        p.output_bps = 16;
        p.output_color = present == 2 ? 8 : 7;  // Rec.2020 or DCI-P3
        p.no_auto_bright = 1;
        p.highlight = 1;
        p.gamm[0] = 1.0;
        p.gamm[1] = 1.0;
        p.bright = 1.f;
    }
}

bool process_open_raw(LibRaw& raw, int max_edge, int present, float panel_boost, int demosaic_qual, Decoded& out) {
    configure_process(raw, present, max_edge, demosaic_qual);
    if (raw.unpack() != LIBRAW_SUCCESS) return false;
    if (raw.dcraw_process() != LIBRAW_SUCCESS) return false;
    int err = 0;
    libraw_processed_image_t* img = raw.dcraw_make_mem_image(&err);
    if (!img || err != LIBRAW_SUCCESS) {
        if (img) LibRaw::dcraw_clear_mem(img);
        return false;
    }
    bool ok = pack_image(img, max_edge, present, panel_boost, out);
    LibRaw::dcraw_clear_mem(img);
    return ok;
}

bool libraw_thumb_file(const char* path, const uint8_t* mem, size_t mem_len, const char* out_path) {
    LibRaw raw;
    int rc = mem ? raw.open_buffer(mem, mem_len) : raw.open_file(path);
    if (rc != LIBRAW_SUCCESS) return false;
    if (raw.unpack_thumb() != LIBRAW_SUCCESS) return false;
    int err = 0;
    libraw_processed_image_t* img = raw.dcraw_make_mem_thumb(&err);
    if (!img || err != LIBRAW_SUCCESS) {
        if (img) LibRaw::dcraw_clear_mem(img);
        return false;
    }
    bool ok = false;
    if (img->type == LIBRAW_IMAGE_JPEG && img->data_size > 512) {
        ok = write_file(out_path, img->data, img->data_size);
    } else if (img->type == LIBRAW_IMAGE_BITMAP && img->bits == 8 && img->colors >= 3) {
        Decoded packed;
        if (pack_image(img, 0, /*present=*/0, 1.f, packed)) {
            ok = write_rgba8_jpeg(packed.pixels.data(), packed.w, packed.h, out_path, 85);
        }
    }
    LibRaw::dcraw_clear_mem(img);
    return ok;
}

bool demosaic_cover_jpeg(const char* path, const uint8_t* mem, size_t mem_len, const char* out_path) {
    LibRaw raw;
    int rc = mem ? raw.open_buffer(mem, mem_len) : raw.open_file(path);
    if (rc != LIBRAW_SUCCESS) return false;
    Decoded decoded;
    // Bilinear is enough for a 512 px cover.
    if (!process_open_raw(raw, 512, /*present=*/0, 1.f, /*demosaic_qual=*/0, decoded)) return false;
    if (decoded.format != 0) return false;
    return write_rgba8_jpeg(decoded.pixels.data(), decoded.w, decoded.h, out_path, 85);
}

jbyteArray decoded_to_java(JNIEnv* env, const Decoded& decoded, jintArray j_info, jfloatArray j_boost) {
    if (env->GetArrayLength(j_info) < 6 || env->GetArrayLength(j_boost) < 1) return nullptr;
    jint meta[6] = {decoded.w, decoded.h, decoded.format, decoded.is_hdr, decoded.gamut, 0};
    env->SetIntArrayRegion(j_info, 0, 6, meta);
    env->SetFloatArrayRegion(j_boost, 0, 1, &decoded.boost);
    if (decoded.pixels.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(decoded.pixels.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(decoded.pixels.size()),
                            reinterpret_cast<const jbyte*>(decoded.pixels.data()));
    return arr;
}

#endif  // EHVIEWER_HDR_CODECS

int extract_preview(const char* path, const uint8_t* mem, size_t mem_len, const char* out_path, bool demosaic) {
    if (!out_path || (!path && !mem)) return -1;
#if defined(EHVIEWER_HDR_CODECS)
    try {
        if (libraw_thumb_file(path, mem, mem_len, out_path)) return 0;
    } catch (...) {
        log_err("LibRaw thumb failed");
    }
#endif
    if (mem) {
        if (write_embedded_jpeg(mem, mem_len, out_path)) return 0;
    } else if (path) {
        std::vector<uint8_t> bytes;
        if (read_file(path, bytes) && write_embedded_jpeg(bytes.data(), bytes.size(), out_path)) return 0;
    }
#if defined(EHVIEWER_HDR_CODECS)
    if (demosaic) {
        try {
            if (demosaic_cover_jpeg(path, mem, mem_len, out_path)) return 0;
        } catch (...) {
            log_err("RAW cover demosaic failed");
        }
    }
#else
    (void)demosaic;
#endif
    return -100;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeRawFileToDirect(JNIEnv* env, jclass, jstring j_path, jint max_edge,
                                                               jint present, jfloat panel_boost, jintArray j_info,
                                                               jfloatArray j_boost) {
#if !defined(EHVIEWER_HDR_CODECS)
    (void)env;
    (void)j_path;
    (void)max_edge;
    (void)present;
    (void)panel_boost;
    (void)j_info;
    (void)j_boost;
    return nullptr;
#else
    if (!j_path || !j_info || !j_boost) return nullptr;
    const char* path = env->GetStringUTFChars(j_path, nullptr);
    if (!path) return nullptr;
    jbyteArray result = nullptr;
    try {
        LibRaw raw;
        if (raw.open_file(path) == LIBRAW_SUCCESS) {
            Decoded decoded;
            int mode = present < 0 ? 0 : (present > 2 ? 2 : present);
            if (process_open_raw(raw, max_edge, mode, panel_boost, /*demosaic_qual=*/3, decoded)) {
                result = decoded_to_java(env, decoded, j_info, j_boost);
            }
        }
    } catch (...) {
        log_err("RAW file decode failed");
        result = nullptr;
    }
    env->ReleaseStringUTFChars(j_path, path);
    return result;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeRawBytesToDirect(JNIEnv* env, jclass, jbyteArray j_input, jint max_edge,
                                                                jint present, jfloat panel_boost, jintArray j_info,
                                                                jfloatArray j_boost) {
#if !defined(EHVIEWER_HDR_CODECS)
    (void)env;
    (void)j_input;
    (void)max_edge;
    (void)present;
    (void)panel_boost;
    (void)j_info;
    (void)j_boost;
    return nullptr;
#else
    if (!j_input || !j_info || !j_boost) return nullptr;
    const jsize len = env->GetArrayLength(j_input);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(j_input, nullptr);
    if (!bytes) return nullptr;
    jbyteArray result = nullptr;
    try {
        LibRaw raw;
        if (raw.open_buffer(bytes, static_cast<size_t>(len)) == LIBRAW_SUCCESS) {
            Decoded decoded;
            int mode = present < 0 ? 0 : (present > 2 ? 2 : present);
            if (process_open_raw(raw, max_edge, mode, panel_boost, /*demosaic_qual=*/3, decoded)) {
                result = decoded_to_java(env, decoded, j_info, j_boost);
            }
        }
    } catch (...) {
        log_err("RAW buffer decode failed");
        result = nullptr;
    }
    env->ReleaseByteArrayElements(j_input, bytes, JNI_ABORT);
    return result;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractRawPreviewFile(JNIEnv* env, jclass, jstring j_path, jstring j_out,
                                                               jboolean demosaic) {
    if (!j_path || !j_out) return -1;
    const char* path = env->GetStringUTFChars(j_path, nullptr);
    const char* out = env->GetStringUTFChars(j_out, nullptr);
    int rc = -1;
    if (path && out) rc = extract_preview(path, nullptr, 0, out, demosaic == JNI_TRUE);
    if (path) env->ReleaseStringUTFChars(j_path, path);
    if (out) env->ReleaseStringUTFChars(j_out, out);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_extractRawPreviewBytes(JNIEnv* env, jclass, jbyteArray j_input, jstring j_out,
                                                                jboolean demosaic) {
    if (!j_input || !j_out) return -1;
    const char* out = env->GetStringUTFChars(j_out, nullptr);
    if (!out) return -1;
    const jsize len = env->GetArrayLength(j_input);
    jbyte* bytes = len > 0 ? env->GetByteArrayElements(j_input, nullptr) : nullptr;
    int rc = -1;
    if (bytes) {
        rc = extract_preview(nullptr, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len), out,
                             demosaic == JNI_TRUE);
        env->ReleaseByteArrayElements(j_input, bytes, JNI_ABORT);
    }
    env->ReleaseStringUTFChars(j_out, out);
    return rc;
}
