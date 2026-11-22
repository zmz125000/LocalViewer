#include <jni.h>
#include <cstdint>
#include <fcntl.h>
#include <fstream>
#include <unistd.h>
#include <vector>
#include "jxl_rs.h"
#include "thumbnail_mapped_input.h"
#include <libraw/libraw.h>

namespace {
uint16_t read_le16(const uint8_t* bytes) {
    return static_cast<uint16_t>(bytes[0]) | (static_cast<uint16_t>(bytes[1]) << 8);
}

uint32_t read_le32(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}

void probe_jxl_size(const char* path, uint32_t& width, uint32_t& height) {
    // jxl-rs reports BasicInfo after the ICC profile, which can exceed the
    // bounded read used for non-mappable inputs. Mapping avoids a full-file copy.
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ThumbnailMappedInput mapped(fd);
    close(fd);
    std::vector<uint8_t> header;
    const uint8_t* data = mapped.data;
    size_t size = mapped.size;
    if (!data) {
        std::ifstream file(path, std::ios::binary);
        header.resize(256 * 1024);
        file.read(reinterpret_cast<char*>(header.data()), header.size());
        data = header.data();
        size = static_cast<size_t>(file.gcount());
    }
    JxlRsReader decoder(data, size);
    if (decoder) {
        width = decoder.info().xsize;
        height = decoder.info().ysize;
    }
}

// JXR's TIFF-like container stores width/height as inline LONG values. Probe
// those without a decoder or full-image allocation. In particular, do not use
// jxrlib's filename stream: a failed fopen leaves an allocated stream whose
// Close callback unconditionally calls fclose(nullptr), aborting on Android.
bool probe_jxr_size(const char* path, uint32_t& width, uint32_t& height) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return false;
    const auto length = file.tellg();
    if (length < std::streamoff(8)) return false;
    file.seekg(0);
    uint8_t header[8];
    if (!file.read(reinterpret_cast<char*>(header), sizeof(header)) ||
        header[0] != 'I' || header[1] != 'I' || read_le16(header + 2) != 0x01bc) return false;
    const uint32_t offset = read_le32(header + 4);
    if (offset < sizeof(header) || static_cast<uint64_t>(offset) + 2 > static_cast<uint64_t>(length)) return false;
    file.seekg(offset);
    uint8_t count_bytes[2];
    if (!file.read(reinterpret_cast<char*>(count_bytes), sizeof(count_bytes))) return false;
    const uint16_t count = read_le16(count_bytes);
    if (static_cast<uint64_t>(offset) + 2 + static_cast<uint64_t>(count) * 12 > static_cast<uint64_t>(length)) return false;
    uint32_t w = 0, h = 0;
    for (uint32_t i = 0; i < count; ++i) {
        uint8_t entry[12];
        if (!file.read(reinterpret_cast<char*>(entry), sizeof(entry))) return false;
        if (read_le16(entry + 2) != 4 || read_le32(entry + 4) != 1) continue;
        const uint16_t tag = read_le16(entry);
        if (tag == 0xbc80) w = read_le32(entry + 8);
        if (tag == 0xbc81) h = read_le32(entry + 8);
    }
    if (!w || !h) return false;
    width = w;
    height = h;
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeStillExportSize(JNIEnv* env, jclass, jstring jpath, jint kind) {
    if (!jpath) return 0;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    uint32_t w = 0, h = 0;
    try {
        if (kind == 0) {
            probe_jxl_size(path, w, h);
        } else if (kind == 1) {
            probe_jxr_size(path, w, h);
        } else if (kind == 2) {
            LibRaw raw;
            if (raw.open_file(path) == LIBRAW_SUCCESS) {
                // Sensor extent is a conservative bound for the cropped/rotated image.
                w = raw.imgdata.sizes.raw_width;
                h = raw.imgdata.sizes.raw_height;
            }
        }
    } catch (...) { w = h = 0; }
    env->ReleaseStringUTFChars(jpath, path);
    if (!w || !h) return 0;
    return static_cast<jlong>((static_cast<uint64_t>(w) << 32) | h);
}
