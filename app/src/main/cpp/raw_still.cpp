/*
 * Camera RAW (DNG / CR2 / NEF / …).
 *
 * 64-bit: LibRaw demosaic → packed RGBA for the reader.
 *   present 0: 8-bit sRGB. Linear samples, then the camera look, then the sRGB transfer.
 *   present 1: deep color, Display P3 float16 clamped to 0..1, after the camera look.
 *   present 2: deep color + Android 16 HDR, Rec.2020 float16, after the camera look.
 *              The look (hue/saturation, tone curve, baseline exposure) matches deep
 *              color up to the 90th-percentile paper white. Above that, the tone
 *              curve is not applied: its shoulder flattens highlight gradation into
 *              white, and boosting those pixels only makes the white brighter.
 *              The advanced-color switch is not a third mode;
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

#include "raw_camera_look.h"

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

#if defined(EHVIEWER_HDR_CODECS)
// OnePlus DNGs store four CFA black levels and omit BlackLevelRepeatDim.
// LibRaw then leaves black at 0, so the image sits on that pedestal and looks
// grey. Count 1 is already applied by LibRaw. Auto-bright is off, so the tag
// has to be installed for every present mode.
struct DngBlackLevel {
    int repeat_rows = 0;
    int repeat_cols = 0;
    int count = 0;
    float values[16] = {};
};

uint32_t tiff_u16(const uint8_t* p, bool le) {
    uint32_t v = le ? (uint32_t)p[0] | ((uint32_t)p[1] << 8) : (uint32_t)p[1] | ((uint32_t)p[0] << 8);
    return v;
}

uint32_t tiff_u32(const uint8_t* p, bool le) {
    return le ? (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24)
              : (uint32_t)p[3] | ((uint32_t)p[2] << 8) | ((uint32_t)p[1] << 16) | ((uint32_t)p[0] << 24);
}

int32_t tiff_i32(const uint8_t* p, bool le) {
    return static_cast<int32_t>(tiff_u32(p, le));
}

bool tiff_real_at(const uint8_t* data, size_t len, bool le, int type, uint32_t count, uint32_t value_field, int index,
                  float& out) {
    size_t item = 0;
    if (type == 1 || type == 6 || type == 7) item = 1;
    else if (type == 3 || type == 8) item = 2;
    else if (type == 4 || type == 9 || type == 11) item = 4;
    else if (type == 5 || type == 10 || type == 12) item = 8;
    else return false;
    if (index < 0 || static_cast<uint32_t>(index) >= count) return false;
    size_t nbytes = item * static_cast<size_t>(count);
    const uint8_t* p = nullptr;
    uint8_t inline_bytes[4];
    if (nbytes <= 4) {
        if (le) {
            inline_bytes[0] = static_cast<uint8_t>(value_field);
            inline_bytes[1] = static_cast<uint8_t>(value_field >> 8);
            inline_bytes[2] = static_cast<uint8_t>(value_field >> 16);
            inline_bytes[3] = static_cast<uint8_t>(value_field >> 24);
        } else {
            inline_bytes[0] = static_cast<uint8_t>(value_field >> 24);
            inline_bytes[1] = static_cast<uint8_t>(value_field >> 16);
            inline_bytes[2] = static_cast<uint8_t>(value_field >> 8);
            inline_bytes[3] = static_cast<uint8_t>(value_field);
        }
        p = inline_bytes;
    } else {
        if (static_cast<size_t>(value_field) > len || nbytes > len - value_field) return false;
        p = data + value_field;
    }
    p += item * static_cast<size_t>(index);
    if (type == 1) {
        out = p[0];
        return true;
    }
    if (type == 3) {
        out = static_cast<float>(tiff_u16(p, le));
        return true;
    }
    if (type == 4) {
        out = static_cast<float>(tiff_u32(p, le));
        return true;
    }
    if (type == 5 || type == 10) {
        int32_t num = type == 10 ? tiff_i32(p, le) : static_cast<int32_t>(tiff_u32(p, le));
        int32_t den = type == 10 ? tiff_i32(p + 4, le) : static_cast<int32_t>(tiff_u32(p + 4, le));
        if (den == 0) return false;
        out = static_cast<float>(static_cast<double>(num) / static_cast<double>(den));
        return true;
    }
    if (type == 11) {
        uint32_t bits = tiff_u32(p, le);
        std::memcpy(&out, &bits, sizeof(out));
        return std::isfinite(out);
    }
    return false;
}

bool read_dng_black(const uint8_t* data, size_t len, DngBlackLevel& out) {
    if (!data || len < 8) return false;
    bool le = data[0] == 'I' && data[1] == 'I';
    bool be = data[0] == 'M' && data[1] == 'M';
    if (!le && !be) return false;
    if (tiff_u16(data + 2, le) != 42) return false;
    uint32_t ifd = tiff_u32(data + 4, le);
    DngBlackLevel cfa;
    bool have_cfa = false;
    DngBlackLevel any;
    bool have_any = false;
    std::vector<uint32_t> seen;
    for (int depth = 0; depth < 8 && ifd != 0; ++depth) {
        if (ifd > len - 2) break;
        bool again = false;
        for (uint32_t prev : seen) {
            if (prev == ifd) again = true;
        }
        if (again) break;
        seen.push_back(ifd);
        uint32_t n = tiff_u16(data + ifd, le);
        if (static_cast<size_t>(ifd) + 2 + static_cast<size_t>(n) * 12 + 4 > len) break;
        int photo = -1;
        int rows = 0;
        int cols = 0;
        int count = 0;
        float values[16];
        std::vector<uint32_t> subs;
        for (uint32_t i = 0; i < n; ++i) {
            const uint8_t* e = data + ifd + 2 + i * 12;
            uint32_t tag = tiff_u16(e, le);
            uint32_t type = tiff_u16(e + 2, le);
            uint32_t cnt = tiff_u32(e + 4, le);
            uint32_t field = tiff_u32(e + 8, le);
            if (tag == 0x0106 && cnt >= 1) {
                float v = 0;
                if (tiff_real_at(data, len, le, static_cast<int>(type), cnt, field, 0, v)) photo = static_cast<int>(v);
            } else if (tag == 0x014A && cnt >= 1 && cnt <= 8) {
                for (uint32_t s = 0; s < cnt; ++s) {
                    float v = 0;
                    if (tiff_real_at(data, len, le, static_cast<int>(type), cnt, field, static_cast<int>(s), v) && v > 0) {
                        subs.push_back(static_cast<uint32_t>(v));
                    }
                }
            } else if (tag == 0xC619 && cnt >= 2) {
                float a = 0;
                float b = 0;
                if (tiff_real_at(data, len, le, static_cast<int>(type), cnt, field, 0, a) &&
                    tiff_real_at(data, len, le, static_cast<int>(type), cnt, field, 1, b)) {
                    rows = static_cast<int>(a);
                    cols = static_cast<int>(b);
                }
            } else if (tag == 0xC61A && cnt >= 1 && cnt <= 16) {
                count = static_cast<int>(cnt);
                for (int k = 0; k < count; ++k) {
                    values[k] = 0;
                    if (!tiff_real_at(data, len, le, static_cast<int>(type), cnt, field, k, values[k])) count = 0;
                }
            }
        }
        if (count > 0) {
            DngBlackLevel got;
            got.repeat_rows = rows;
            got.repeat_cols = cols;
            got.count = count;
            for (int k = 0; k < count; ++k) got.values[k] = values[k];
            if (!have_any) {
                any = got;
                have_any = true;
            }
            if (photo == 32803) {
                cfa = got;
                have_cfa = true;
            }
        }
        uint32_t next = tiff_u32(data + ifd + 2 + n * 12, le);
        if (!subs.empty()) ifd = subs[0];
        else ifd = next;
    }
    if (have_cfa) {
        out = cfa;
        return true;
    }
    if (have_any) {
        out = any;
        return true;
    }
    return false;
}

void apply_missing_dng_black(LibRaw& raw, const uint8_t* data, size_t len) {
    DngBlackLevel black;
    if (!read_dng_black(data, len, black) || black.count <= 0) return;
    int rows = black.repeat_rows;
    int cols = black.repeat_cols;
    if (rows <= 0 || cols <= 0) {
        if (black.count == 4) {
            rows = 2;
            cols = 2;
        } else if (black.count == 1) {
            rows = 1;
            cols = 1;
        } else {
            return;
        }
    }
    if (rows * cols > black.count || rows > 4 || cols > 4) return;
    const auto& color = raw.imgdata.color;
    int maximum = color.maximum > 0 ? static_cast<int>(color.maximum) : 65535;
    auto usable = [&](float v) { return std::isfinite(v) && v > 0.5f && v < static_cast<float>(maximum) * 0.5f; };
    if (!usable(black.values[0])) return;
    int want = static_cast<int>(std::lround(black.values[0]));
    // LibRaw stores CFA BlackLevel as an integer, so 63.9375 becomes 63.
    // The leftover count is stretched by the linear 16-bit scale and a dark
    // frame looks grey. Install the rounded tag
    // when that integer is short of it. user_black replaces the pattern.
    if (color.black >= static_cast<unsigned>(want)) return;
    unsigned have = 0;
    for (int i = 0; i < 4; ++i) have = std::max(have, color.cblack[i]);
    if (have >= static_cast<unsigned>(want)) return;
    if (color.cblack[4] > 0 && color.cblack[5] > 0) {
        int npat = color.cblack[4] * color.cblack[5];
        if (npat > 4096) npat = 4096;
        unsigned pat = color.cblack[6];
        for (int i = 1; i < npat; ++i) pat = std::min(pat, color.cblack[6 + i]);
        if (pat >= static_cast<unsigned>(want)) return;
    }
    bool equal = true;
    for (int i = 1; i < rows * cols; ++i) {
        if (!usable(black.values[i]) || std::fabs(black.values[i] - black.values[0]) > 0.5f) equal = false;
    }
    if (equal) {
        raw.imgdata.params.user_black = want;
        for (int i = 0; i < 4; ++i) raw.imgdata.params.user_cblack[i] = 0;
        return;
    }
    float sum[4] = {};
    int n[4] = {};
    for (int r = 0; r < rows; ++r) {
        for (int c = 0; c < cols; ++c) {
            float v = black.values[r * cols + c];
            if (!usable(v)) return;
            int channel = raw.COLOR(r, c);
            if (channel < 0 || channel > 3) channel = 0;
            sum[channel] += v;
            n[channel] += 1;
        }
    }
    for (int i = 0; i < 4; ++i) {
        if (n[i] > 0) raw.imgdata.params.user_cblack[i] = static_cast<int>(std::lround(sum[i] / n[i]));
    }
}
#endif

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
// HDR uses this only to decide which pixels sit above paper white. The camera look
// still divides by full scale, same as deep color.
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

// Same mapping as RawPresent.rawLinearSample. scene_over_white is 1 at paper white for HDR,
// and at sensor white for deep color.
// Highlight stops compress only above 1.
float raw_linear_sample(float scene_over_white, float exposure_ev, float highlight_stops, float cap) {
    if (!std::isfinite(scene_over_white) || !std::isfinite(exposure_ev) || !std::isfinite(highlight_stops) ||
        !std::isfinite(cap)) {
        return 0.f;
    }
    if (!(scene_over_white > 0.f)) return 0.f;
    const double base = static_cast<double>(scene_over_white) * std::exp2(static_cast<double>(exposure_ev));
    double y;
    if (!(highlight_stops > 0.f) || !(base > 1.0)) {
        y = base;
    } else {
        y = 1.0 + (base - 1.0) * std::exp2(-static_cast<double>(highlight_stops));
    }
    if (!std::isfinite(y) || y <= 0.0) return 0.f;
    const double limit = cap >= 1.f ? cap : 1.0;
    if (y > limit) y = limit;
    return static_cast<float>(y);
}

float zone_smooth(float edge0, float edge1, float x) {
    float t = (x - edge0) / (edge1 - edge0);
    t = std::clamp(t, 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

// shadows, midtones, and highlights are −1..1. 0 leaves the pixel alone.
// The weight is taken from compressed luminance so a value above 1 still counts
// as a highlight, and each slider mostly moves its own part of the picture.
void apply_raw_zones(float& r, float& g, float& b, float shadows, float midtones, float highlights) {
    shadows = std::isfinite(shadows) ? std::clamp(shadows, -1.f, 1.f) : 0.f;
    midtones = std::isfinite(midtones) ? std::clamp(midtones, -1.f, 1.f) : 0.f;
    highlights = std::isfinite(highlights) ? std::clamp(highlights, -1.f, 1.f) : 0.f;
    if (shadows == 0.f && midtones == 0.f && highlights == 0.f) return;
    r = std::max(r, 0.f);
    g = std::max(g, 0.f);
    b = std::max(b, 0.f);
    const float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float s = luma / (1.f + luma);
    const float shadow_w = 1.f - zone_smooth(0.02f, 0.42f, s);
    const float mid_d = (s - 0.33f) / 0.14f;
    const float mid_w = std::exp(-0.5f * mid_d * mid_d);
    const float high_w = zone_smooth(0.38f, 0.90f, s);
    const float ev = shadows * shadow_w * 1.5f + midtones * mid_w * 1.f + highlights * high_w * 1.5f;
    float gain = std::exp2(ev);
    if (!std::isfinite(gain) || gain < 0.f) gain = 1.f;
    const float lift = shadows * shadow_w * 0.04f;
    r = std::max(0.f, r * gain + lift);
    g = std::max(0.f, g * gain + lift);
    b = std::max(0.f, b * gain + lift);
}

bool pack_image(const libraw_processed_image_t* img, int max_edge, int present, float panel_boost,
                float exposure_ev, float highlight_stops, const rawlook::CameraLook* look, bool hdr_linear,
                float camera_white, float shadows, float midtones, float highlights, Decoded& out) {
    if (!img || img->width == 0 || img->height == 0) return false;
    if (img->type != LIBRAW_IMAGE_BITMAP) return false;
    const int sw = img->width;
    const int sh = img->height;
    const int colors = img->colors >= 1 ? img->colors : 1;
    const size_t bpp = img->bits == 16 ? 2u : 1u;
    const size_t need = static_cast<size_t>(sw) * static_cast<size_t>(sh) * static_cast<size_t>(colors) * bpp;
    if (img->data_size < need) return false;
    const bool deep = present != 0;
    const bool linear16 = img->bits == 16;
    // 8-bit input is an already-rendered preview bitmap. 16-bit input is linear.
    if (deep && !linear16) return false;
    if (!linear16 && img->bits != 8) return false;
    int dw = 0;
    int dh = 0;
    dst_size(sw, sh, max_edge, dw, dh);
    const size_t bytes = static_cast<size_t>(dw) * static_cast<size_t>(dh) * (deep ? 8u : 4u);
    if (bytes == 0 || bytes > 400u * 1024u * 1024u) return false;

    const bool hdr = present == 2;
    const bool linear_hdr = hdr && hdr_linear;
    // 10000/203 matches hdr_encode.h kMaxLinear. HDR does not clamp to 1.
    const float panel = std::isfinite(panel_boost) ? std::clamp(panel_boost, 1.f, 64.f) : 1.f;
    const float cap = hdr ? std::min(panel, 10000.f / 203.f) : 1.f;
    // camera_white is the 16-bit code of the camera white level after LibRaw
    // has left highlight headroom in the buffer. 0 means full scale.
    const float full = linear16 ? 65535.f : 255.f;
    const float white = (linear16 && camera_white > 1.f && camera_white < full) ? camera_white : full;
    float paper = white;
    if (hdr && linear16) {
        float sensor_peak = white;
        linear_white_and_peak(img->data, sw, sh, colors, img->bits, paper, sensor_peak);
    }
    if (!(paper > 0.f)) paper = 1.f;

    out.pixels.assign(bytes, 0);
    out.w = dw;
    out.h = dh;
    out.format = deep ? 1 : 0;
    out.is_hdr = hdr ? 1 : 0;
    // gamut 2 is the shared linear BT.2020 space. Headroom is the half-float values.
    out.gamut = hdr ? 2 : (present == 1 ? 1 : 0);
    out.boost = 1.f;
    float peak = 1.f;

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
            if (!linear16) {
                for (int c = 0; c < 3; ++c) {
                    int v = static_cast<int>(std::lround(acc[c] / count));
                    out.pixels[o + static_cast<size_t>(c)] = static_cast<uint8_t>(std::clamp(v, 0, 255));
                }
                out.pixels[o + 3] = 255;
            } else {
                double ch[3];
                for (int c = 0; c < 3; ++c) ch[c] = acc[c] / count;
                float rgb[3];
                const float scale = linear_hdr ? paper : white;
                for (int c = 0; c < 3; ++c) rgb[c] = static_cast<float>(ch[c] / scale);
                // LibRaw's linear RGB, before the camera look. The tone curve then
                // shapes the adjusted values instead of a slider fighting the shoulder.
                apply_raw_zones(rgb[0], rgb[1], rgb[2], shadows, midtones, highlights);
                auto rec_luma = [](float r, float g, float b) {
                    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
                };
                const float lum = rec_luma(rgb[0], rgb[1], rgb[2]) * scale;
                if (look && !linear_hdr && hdr && lum > paper) {
                    // Curve the paper-white version of this color, then extend with the
                    // hue/sat result (no shoulder) so the roll-off stays proportional.
                    const float u = lum / paper;
                    float at_paper[3] = {rgb[0] / u, rgb[1] / u, rgb[2] / u};
                    rawlook::apply_camera_look(*look, exposure_ev, at_paper[0], at_paper[1], at_paper[2]);
                    rawlook::apply_camera_look(*look, exposure_ev, rgb[0], rgb[1], rgb[2], false);
                    const float lin_luma = rec_luma(rgb[0], rgb[1], rgb[2]);
                    const float target = rec_luma(at_paper[0], at_paper[1], at_paper[2]) * u;
                    if (lin_luma > 1e-4f && target > 0.f) {
                        const float gain = target / lin_luma;
                        for (int c = 0; c < 3; ++c) rgb[c] *= gain;
                    }
                } else if (look && !linear_hdr) {
                    rawlook::apply_camera_look(*look, exposure_ev, rgb[0], rgb[1], rgb[2]);
                } else {
                    float gain = std::exp2(std::isfinite(exposure_ev) ? exposure_ev : 0.f);
                    if (!std::isfinite(gain) || gain < 0.f) gain = 1.f;
                    for (int c = 0; c < 3; ++c) rgb[c] *= gain;
                }
                if (!deep) {
                    for (int c = 0; c < 3; ++c) {
                        int v = static_cast<int>(std::lround(rawlook::srgb_encode(rgb[c]) * 255.f));
                        out.pixels[o + static_cast<size_t>(c)] = static_cast<uint8_t>(std::clamp(v, 0, 255));
                    }
                    out.pixels[o + 3] = 255;
                } else {
                    // Exposure already ran inside the camera look, ahead of the tone curve.
                    for (int c = 0; c < 3; ++c) {
                        float v = raw_linear_sample(rgb[c], 0.f, highlight_stops, cap);
                        peak = std::max(peak, v);
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
    }
    if (hdr) out.boost = peak;
    return true;
}

// 0 camera, 1 auto, 2 daylight, 3 cloudy, 4 shade, 5 tungsten, 6 fluorescent, 7 flash, 8 kelvin.
bool wb_coeff(const LibRaw& raw, int index, float mul[4]) {
    if (index < 0 || index >= 256) return false;
    const int* c = raw.imgdata.color.WB_Coeffs[index];
    if (c[0] <= 0 || c[1] <= 0 || c[2] <= 0) return false;
    mul[0] = static_cast<float>(c[0]);
    mul[1] = static_cast<float>(c[1]);
    mul[2] = static_cast<float>(c[2]);
    mul[3] = c[3] > 0 ? static_cast<float>(c[3]) : mul[1];
    return true;
}

bool wb_coeff_any(const LibRaw& raw, const int* ids, int count, float mul[4]) {
    for (int i = 0; i < count; ++i) {
        if (wb_coeff(raw, ids[i], mul)) return true;
    }
    return false;
}

// Daylight locus from 4000 K. Below that, the Planckian locus (Illuminant A at 2856 K).
bool kelvin_xy(float kelvin, double& x, double& y) {
    double t = std::clamp(static_cast<double>(kelvin), 1667.0, 25000.0);
    double t2 = t * t;
    double t3 = t2 * t;
    if (t >= 4000.0) {
        if (t <= 7000.0) {
            x = -4.6070e9 / t3 + 2.9678e6 / t2 + 0.09911e3 / t + 0.244063;
        } else {
            x = -2.0064e9 / t3 + 1.9018e6 / t2 + 0.24748e3 / t + 0.237040;
        }
        y = -3.000 * x * x + 2.870 * x - 0.275;
    } else {
        x = -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910;
        if (t <= 2222.0) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        } else {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        }
    }
    return y > 1e-6;
}

bool mul_from_wbct(const float ct[64][5], float kelvin, float mul[4]) {
    struct Pt {
        float t;
        float m[4];
    };
    Pt pts[64];
    int n = 0;
    for (int i = 0; i < 64; ++i) {
        if (!(ct[i][0] > 1.f) || !(ct[i][1] > 0.f) || !(ct[i][2] > 0.f) || !(ct[i][3] > 0.f)) continue;
        pts[n].t = ct[i][0];
        pts[n].m[0] = ct[i][1];
        pts[n].m[1] = ct[i][2];
        pts[n].m[2] = ct[i][3];
        pts[n].m[3] = ct[i][4] > 0.f ? ct[i][4] : ct[i][2];
        ++n;
    }
    if (n < 2) return false;
    std::sort(pts, pts + n, [](const Pt& a, const Pt& b) { return a.t < b.t; });
    int w = 1;
    for (int i = 1; i < n; ++i) {
        if (pts[i].t > pts[w - 1].t + 0.5f) pts[w++] = pts[i];
    }
    n = w;
    if (n < 2) return false;
    auto copy_pt = [&](const Pt& p) {
        for (int c = 0; c < 4; ++c) mul[c] = p.m[c];
    };
    if (kelvin <= pts[0].t) {
        copy_pt(pts[0]);
        return mul[0] > 0.f;
    }
    if (kelvin >= pts[n - 1].t) {
        copy_pt(pts[n - 1]);
        return mul[0] > 0.f;
    }
    for (int i = 1; i < n; ++i) {
        if (kelvin > pts[i].t) continue;
        float m0 = 1e6f / pts[i - 1].t;
        float m1 = 1e6f / pts[i].t;
        float m = 1e6f / kelvin;
        float u = (m0 == m1) ? 0.f : (m0 - m) / (m0 - m1);
        u = std::clamp(u, 0.f, 1.f);
        for (int c = 0; c < 4; ++c) mul[c] = pts[i - 1].m[c] + (pts[i].m[c] - pts[i - 1].m[c]) * u;
        return mul[0] > 0.f;
    }
    return false;
}

bool mul_from_matrix(const LibRaw& raw, float kelvin, float mul[4]) {
    double x = 0;
    double y = 0;
    if (!kelvin_xy(kelvin, x, y)) return false;
    const auto& m = raw.imgdata.color.cam_xyz;
    double acc = 0;
    for (int c = 0; c < 3; ++c) acc += std::fabs(m[c][0]) + std::fabs(m[c][1]) + std::fabs(m[c][2]);
    if (acc < 0.01) return false;
    double X = x / y;
    double Y = 1.0;
    double Z = (1.0 - x - y) / y;
    int colors = raw.imgdata.idata.colors;
    for (int c = 0; c < 4; ++c) {
        int src = c;
        if (c == 3 && (colors < 4 || (std::fabs(m[3][0]) + std::fabs(m[3][1]) + std::fabs(m[3][2]) < 1e-6))) src = 1;
        double cam = m[src][0] * X + m[src][1] * Y + m[src][2] * Z;
        if (!(cam > 1e-4)) cam = 1e-4;
        mul[c] = static_cast<float>(1.0 / cam);
    }
    return mul[0] > 0.f;
}

void use_multipliers(LibRaw& raw, const float mul[4]) {
    auto& p = raw.imgdata.params;
    p.use_camera_wb = 0;
    p.use_auto_wb = 0;
    for (int i = 0; i < 4; ++i) p.user_mul[i] = mul[i];
}

bool apply_kelvin(LibRaw& raw, float kelvin) {
    float mul[4];
    if (mul_from_wbct(raw.imgdata.color.WBCT_Coeffs, kelvin, mul) || mul_from_matrix(raw, kelvin, mul)) {
        use_multipliers(raw, mul);
        return true;
    }
    auto& p = raw.imgdata.params;
    p.use_camera_wb = 0;
    p.use_auto_wb = 0;
    return false;
}

void configure_white_balance(LibRaw& raw, int wb_mode, int kelvin) {
    auto& p = raw.imgdata.params;
    for (int i = 0; i < 4; ++i) p.user_mul[i] = 0.f;
    float mul[4];
    switch (wb_mode) {
        case 1:
            p.use_camera_wb = 0;
            p.use_auto_wb = 1;
            break;
        case 2: {
            const int ids[] = {LIBRAW_WBI_Daylight, LIBRAW_WBI_D55, LIBRAW_WBI_D65};
            if (wb_coeff_any(raw, ids, 3, mul)) {
                use_multipliers(raw, mul);
            } else {
                p.use_camera_wb = 0;
                p.use_auto_wb = 0;
            }
            break;
        }
        case 3: {
            const int ids[] = {LIBRAW_WBI_Cloudy, LIBRAW_WBI_FineWeather};
            if (wb_coeff_any(raw, ids, 2, mul)) use_multipliers(raw, mul);
            else apply_kelvin(raw, 6000.f);
            break;
        }
        case 4:
            if (wb_coeff(raw, LIBRAW_WBI_Shade, mul)) use_multipliers(raw, mul);
            else apply_kelvin(raw, 7500.f);
            break;
        case 5: {
            const int ids[] = {LIBRAW_WBI_Tungsten, LIBRAW_WBI_StudioTungsten, LIBRAW_WBI_Ill_A};
            if (wb_coeff_any(raw, ids, 3, mul)) use_multipliers(raw, mul);
            else apply_kelvin(raw, 2850.f);
            break;
        }
        case 6: {
            const int ids[] = {LIBRAW_WBI_Fluorescent, LIBRAW_WBI_FL_D, LIBRAW_WBI_FL_N,
                               LIBRAW_WBI_FL_W,       LIBRAW_WBI_FL_WW, LIBRAW_WBI_FL_L};
            if (wb_coeff_any(raw, ids, 6, mul)) use_multipliers(raw, mul);
            else apply_kelvin(raw, 4000.f);
            break;
        }
        case 7:
            if (wb_coeff(raw, LIBRAW_WBI_Flash, mul)) use_multipliers(raw, mul);
            else apply_kelvin(raw, 5500.f);
            break;
        case 8:
            apply_kelvin(raw, static_cast<float>(std::clamp(kelvin, 2000, 12000)));
            break;
        default:
            p.use_camera_wb = 1;
            p.use_auto_wb = 0;
            break;
    }
}

// identify() copies an embedded color matrix only while opening. 3 always copies it.
// The default 1 does that for DNG, or when camera white balance is already set.
void request_embedded_matrix(LibRaw& raw) {
    raw.imgdata.params.use_camera_matrix = 3;
}

// LibRaw stores the tag and never multiplies by it. -999 means absent.
float dng_baseline_ev(const LibRaw& raw) {
    const float ev = raw.imgdata.color.dng_levels.baseline_exposure;
    if (!std::isfinite(ev) || ev <= -100.f || ev >= 100.f) return 0.f;
    return ev;
}

void configure_process(LibRaw& raw, int present, int max_edge, int demosaic_qual, int wb_mode, int kelvin) {
    auto& p = raw.imgdata.params;
    configure_white_balance(raw, wb_mode, kelvin);
    // Too late to copy a missed matrix. Kept so it matches the value set before open.
    p.use_camera_matrix = 3;
    p.user_qual = demosaic_qual;
    p.half_size = 0;
    // Histogram stretch and a lowered white point both brighten past as-shot.
    p.no_auto_bright = 1;
    p.bright = 1.f;
    p.adjust_maximum_thr = 0.f;
    // Preview and cover requests stay at or under 4096. The visible size is known
    // after open, before unpack. Under 24 MP stays full size. Above that, half size.
    // View original passes a larger edge and stays full size.
    if (max_edge > 0 && max_edge <= 4096) {
        const auto& sz = raw.imgdata.sizes;
        int64_t pixels = static_cast<int64_t>(sz.width) * static_cast<int64_t>(sz.height);
        if (pixels <= 0) {
            pixels = static_cast<int64_t>(sz.raw_width) * static_cast<int64_t>(sz.raw_height);
        }
        if (pixels > 24000000LL) p.half_size = 1;
    }
    // 0 flattens every channel that reaches the white level to the same code,
    // before exposure, so later EV cannot bring the detail back. 2 keeps that
    // headroom and blends the clipped channel instead of leaving it pink.
    p.highlight = 2;
    p.gamm[0] = 1.0;
    p.gamm[1] = 1.0;
    if (present == 0) {
        p.output_bps = 16;
        p.output_color = 1;  // linear sRGB. The sRGB transfer is applied after the camera look.
    } else {
        p.output_bps = 16;
        p.output_color = present == 2 ? 8 : 7;  // Rec.2020 or DCI-P3 D65
    }
}

// LibRaw maps camera white to 65535 and clips anything above it. Samples between
// the camera white level and the real sensor peak are still in the file; this
// raises the white level so they survive in the 16-bit image. The scale-colors
// hook is protected, so only a subclass can install it.
struct HeadroomRaw : LibRaw {
    float sat_fit = 1.f;
    HeadroomRaw() { callbacks.pre_scalecolors_cb = &HeadroomRaw::keep_sensor_headroom; }

    static void keep_sensor_headroom(void* ctx) {
        auto* raw = static_cast<HeadroomRaw*>(ctx);
        if (!raw) return;
        unsigned maximum = raw->imgdata.color.maximum;
        unsigned data_max = raw->imgdata.color.data_maximum;
        if (maximum == 0 || data_max <= maximum) return;
        unsigned cap = maximum > (std::numeric_limits<unsigned>::max() / 8u) ? std::numeric_limits<unsigned>::max()
                                                                              : maximum * 8u;
        unsigned raised = std::min(data_max, cap);
        if (raised <= maximum) return;
        raw->sat_fit = static_cast<float>(raised) / static_cast<float>(maximum);
        raw->imgdata.color.maximum = raised;
    }
};

// highlight 2 scales to the strongest white-balance channel, which darkens the
// frame versus highlight 0. pre_mul was divided by that peak, so the span is
// 1/min(pre_mul). Combined with the raised white level, this is how far camera
// white sits below the 16-bit ceiling.
float highlight_fit(const LibRaw& raw, float sat_fit) {
    float dmin = 1e30f;
    bool any = false;
    for (int c = 0; c < 4; ++c) {
        float v = raw.imgdata.color.pre_mul[c];
        if (!(v > 0.f) || !std::isfinite(v)) continue;
        dmin = std::min(dmin, v);
        any = true;
    }
    float wb_fit = 1.f;
    if (any && dmin > 0.f && dmin < 1.f) wb_fit = 1.f / dmin;
    if (!std::isfinite(wb_fit) || wb_fit < 1.f) wb_fit = 1.f;
    if (wb_fit > 8.f) wb_fit = 8.f;
    float sat = std::isfinite(sat_fit) && sat_fit > 1.f ? sat_fit : 1.f;
    if (sat > 8.f) sat = 8.f;
    float fit = wb_fit * sat;
    if (!std::isfinite(fit) || fit < 1.f) return 1.f;
    return fit;
}

bool process_open_raw(HeadroomRaw& raw, const uint8_t* tiff, size_t tiff_len, int max_edge, int present, float panel_boost,
                      int demosaic_qual, int wb_mode, float exposure_ev, float highlight_stops, int kelvin,
                      bool hdr_linear, float shadows, float midtones, float highlights, Decoded& out) {
    configure_process(raw, present, max_edge, demosaic_qual, wb_mode, kelvin);
    if (raw.unpack() != LIBRAW_SUCCESS) return false;
    // After unpack: lossless JPEG may have cleared the DNG black tag.
    apply_missing_dng_black(raw, tiff, tiff_len);
    if (raw.dcraw_process() != LIBRAW_SUCCESS) return false;
    const float fit = highlight_fit(raw, raw.sat_fit);
    const float camera_white = fit > 1.f ? 65535.f / fit : 65535.f;
    int err = 0;
    libraw_processed_image_t* img = raw.dcraw_make_mem_image(&err);
    if (!img || err != LIBRAW_SUCCESS) {
        if (img) LibRaw::dcraw_clear_mem(img);
        return false;
    }
    // Linear HDR skips the camera look, so it also skips BaselineExposure.
    // The other modes apply that exposure inside the look, before the tone curve.
    const bool linear_hdr = present == 2 && hdr_linear;
    const float user_ev = std::isfinite(exposure_ev) ? exposure_ev : 0.f;
    const float pack_ev = linear_hdr ? user_ev : user_ev + dng_baseline_ev(raw);
    int space = present == 2 ? 2 : (present == 1 ? 1 : 0);
    float temp = 5500.f;
    if (wb_mode == 8) {
        temp = static_cast<float>(kelvin);
    } else {
        float measured = raw.imgdata.makernotes.common.CameraTemperature;
        if (std::isfinite(measured) && measured >= 1667.f && measured <= 25000.f) temp = measured;
    }
    rawlook::CameraLook look;
    const rawlook::CameraLook* look_ptr = nullptr;
    if (!linear_hdr) {
        rawlook::prepare_camera_look(tiff, tiff_len, temp, space, look);
        look_ptr = &look;
    }
    bool ok = pack_image(img, max_edge, present, panel_boost, pack_ev, highlight_stops, look_ptr, linear_hdr,
                          camera_white, shadows, midtones, highlights, out);
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
        if (pack_image(img, 0, /*present=*/0, 1.f, 0.f, 0.f, nullptr, false, 0.f, 0.f, 0.f, 0.f, packed)) {
            ok = write_rgba8_jpeg(packed.pixels.data(), packed.w, packed.h, out_path, 85);
        }
    }
    LibRaw::dcraw_clear_mem(img);
    return ok;
}

bool demosaic_cover_jpeg(const char* path, const uint8_t* mem, size_t mem_len, const char* out_path) {
    HeadroomRaw raw;
    request_embedded_matrix(raw);
    int rc = mem ? raw.open_buffer(mem, mem_len) : raw.open_file(path);
    if (rc != LIBRAW_SUCCESS) return false;
    Decoded decoded;
    // Bilinear is enough for a 512 px cover. Same as-shot process as the reader.
    if (!process_open_raw(raw, mem, mem_len, 512, /*present=*/0, 1.f, /*demosaic_qual=*/0, /*wb_mode=*/0, 0.f, 0.f,
                          5200, false, 0.f, 0.f, 0.f, decoded)) {
        return false;
    }
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
                                                               jint present, jfloat panel_boost, jfloat exposure_ev,
                                                               jint wb_mode, jint kelvin, jfloat highlight_stops,
                                                               jboolean hdr_linear, jfloat shadows, jfloat midtones,
                                                               jfloat highlights, jintArray j_info, jfloatArray j_boost) {
#if !defined(EHVIEWER_HDR_CODECS)
    (void)env;
    (void)j_path;
    (void)max_edge;
    (void)present;
    (void)panel_boost;
    (void)exposure_ev;
    (void)wb_mode;
    (void)kelvin;
    (void)highlight_stops;
    (void)hdr_linear;
    (void)shadows;
    (void)midtones;
    (void)highlights;
    (void)j_info;
    (void)j_boost;
    return nullptr;
#else
    if (!j_path || !j_info || !j_boost) return nullptr;
    const char* path = env->GetStringUTFChars(j_path, nullptr);
    if (!path) return nullptr;
    jbyteArray result = nullptr;
    try {
        HeadroomRaw raw;
        request_embedded_matrix(raw);
        if (raw.open_file(path) == LIBRAW_SUCCESS) {
            Decoded decoded;
            int mode = present < 0 ? 0 : (present > 2 ? 2 : present);
            float exposure = std::isfinite(exposure_ev) ? std::clamp(exposure_ev, -3.f, 3.f) : 0.f;
            float stops = std::isfinite(highlight_stops) ? std::clamp(highlight_stops, 0.f, 3.f) : 0.f;
            auto zone = [](float v) { return std::isfinite(v) ? std::clamp(v, -1.f, 1.f) : 0.f; };
            int wb = std::clamp(wb_mode, 0, 8);
            int temp = std::clamp(kelvin, 2000, 12000);
            std::vector<uint8_t> file;
            const uint8_t* tiff = nullptr;
            size_t tiff_len = 0;
            // Profile tags and a missing DNG black level both live in the container.
            if (read_file(path, file)) {
                tiff = file.data();
                tiff_len = file.size();
            }
            if (process_open_raw(raw, tiff, tiff_len, max_edge, mode, panel_boost, /*demosaic_qual=*/3, wb, exposure,
                                 stops, temp, hdr_linear == JNI_TRUE, zone(shadows), zone(midtones), zone(highlights),
                                 decoded)) {
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
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeRawBytesToDirect(JNIEnv* env, jclass, jbyteArray j_input,
                                                                jint max_edge, jint present, jfloat panel_boost,
                                                                jfloat exposure_ev, jint wb_mode, jint kelvin,
                                                                jfloat highlight_stops, jboolean hdr_linear,
                                                                jfloat shadows, jfloat midtones, jfloat highlights,
                                                                jintArray j_info, jfloatArray j_boost) {
#if !defined(EHVIEWER_HDR_CODECS)
    (void)env;
    (void)j_input;
    (void)max_edge;
    (void)present;
    (void)panel_boost;
    (void)exposure_ev;
    (void)wb_mode;
    (void)kelvin;
    (void)highlight_stops;
    (void)hdr_linear;
    (void)shadows;
    (void)midtones;
    (void)highlights;
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
        HeadroomRaw raw;
        request_embedded_matrix(raw);
        if (raw.open_buffer(bytes, static_cast<size_t>(len)) == LIBRAW_SUCCESS) {
            Decoded decoded;
            int mode = present < 0 ? 0 : (present > 2 ? 2 : present);
            float exposure = std::isfinite(exposure_ev) ? std::clamp(exposure_ev, -3.f, 3.f) : 0.f;
            float stops = std::isfinite(highlight_stops) ? std::clamp(highlight_stops, 0.f, 3.f) : 0.f;
            auto zone = [](float v) { return std::isfinite(v) ? std::clamp(v, -1.f, 1.f) : 0.f; };
            int wb = std::clamp(wb_mode, 0, 8);
            int temp = std::clamp(kelvin, 2000, 12000);
            const uint8_t* tiff = reinterpret_cast<const uint8_t*>(bytes);
            size_t tiff_len = static_cast<size_t>(len);
            if (process_open_raw(raw, tiff, tiff_len, max_edge, mode, panel_boost, /*demosaic_qual=*/3, wb, exposure,
                                 stops, temp, hdr_linear == JNI_TRUE, zone(shadows), zone(midtones), zone(highlights),
                                 decoded)) {
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
