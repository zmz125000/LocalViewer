#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

struct JxlRsDecoder;

struct JxlRsInfo {
    uint32_t xsize, ysize;
    uint32_t bits_per_sample, exponent_bits_per_sample;
    bool has_alpha, has_black, animated;
    uint32_t loops;
    float intensity_target;
};

struct JxlRsColor {
    int color_space, white_point, primaries, transfer_function;
    double gamma;
};

enum class JxlRsFormat : int { u8, u16, f16, f32 };
enum class JxlRsWork { foreground, thumbnail };

extern "C" {
JxlRsDecoder* jxl_rs_open(const uint8_t*, size_t, JxlRsInfo&, bool thumbnail);
void jxl_rs_close(JxlRsDecoder*);
bool jxl_rs_set_downsample(JxlRsDecoder&, uint8_t factor, uint32_t& width, uint32_t& height);
bool jxl_rs_set_format(JxlRsDecoder&, JxlRsFormat, bool black_plane);
bool jxl_rs_color(const JxlRsDecoder&, bool original, JxlRsColor&);
bool jxl_rs_original_output(const JxlRsDecoder&);
const uint8_t* jxl_rs_icc(JxlRsDecoder&, bool original, size_t&);
int jxl_rs_next(JxlRsDecoder&, const uint8_t*, size_t, void* pixels, size_t pixel_bytes,
                void* black, size_t black_bytes, double& duration_ms);

using JxlRsReadAt = size_t (*)(void*, uint64_t, uint8_t*, size_t);
size_t jxl_rs_exif(void*, JxlRsReadAt, uint8_t*, size_t);
uint8_t* jxl_rs_gain_map_icc(const uint8_t*, size_t color_size, const uint8_t*, size_t icc_size, JxlRsColor&, size_t&);
void jxl_rs_free_icc(uint8_t*, size_t);
}

class JxlRsReader {
    const uint8_t* data_;
    size_t size_;
    JxlRsInfo info_{};
    std::unique_ptr<JxlRsDecoder, decltype(&jxl_rs_close)> decoder_;
public:
    JxlRsReader(const uint8_t* data, size_t size, JxlRsWork work = JxlRsWork::foreground)
        : data_(data), size_(size),
          decoder_(jxl_rs_open(data, size, info_, work == JxlRsWork::thumbnail), jxl_rs_close) {}

    explicit operator bool() const { return static_cast<bool>(decoder_); }
    const JxlRsInfo& info() const { return info_; }
    bool set_downsample(uint8_t factor) {
        return jxl_rs_set_downsample(*decoder_, factor, info_.xsize, info_.ysize);
    }
    bool set_format(JxlRsFormat format, bool black_plane = false) {
        return jxl_rs_set_format(*decoder_, format, black_plane);
    }
    bool color(JxlRsColor& out, bool original = false) const { return jxl_rs_color(*decoder_, original, out); }
    bool outputs_original_profile() const { return jxl_rs_original_output(*decoder_); }
    std::vector<uint8_t> icc(bool original = false) {
        size_t size = 0;
        const uint8_t* icc = jxl_rs_icc(*decoder_, original, size);
        return size > 0 && size <= 16 * 1024 * 1024 ? std::vector<uint8_t>(icc, icc + size) : std::vector<uint8_t>{};
    }
    int next(void* pixels, size_t bytes, double& duration_ms, void* black = nullptr, size_t black_bytes = 0) {
        return jxl_rs_next(*decoder_, data_, size_, pixels, bytes, black, black_bytes, duration_ms);
    }

    static std::vector<uint8_t> exif(void* context, JxlRsReadAt read) {
        std::vector<uint8_t> bytes(1024 * 1024);
        bytes.resize(jxl_rs_exif(context, read, bytes.data(), bytes.size()));
        return bytes;
    }
    static std::vector<uint8_t> exif(const uint8_t* data, size_t size) {
        if (size < 12 || std::memcmp(data, "\0\0\0\x0cJXL \r\n\x87\n", 12) != 0) return {};
        struct Input { const uint8_t* data; size_t size; } input{data, size};
        return exif(&input, [](void* context, uint64_t offset, uint8_t* out, size_t count) -> size_t {
            const auto& src = *static_cast<Input*>(context);
            if (offset >= src.size) return 0;
            count = std::min(count, src.size - static_cast<size_t>(offset));
            std::memcpy(out, src.data + offset, count);
            return count;
        });
    }
};
