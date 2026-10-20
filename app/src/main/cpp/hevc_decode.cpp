#include "hevc_decode.h"

#include <jni.h>
#include <libde265/de265.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <thread>

namespace {
constexpr size_t kMaxPixels = 64u * 1024u * 1024u;
constexpr size_t kMaxInput = 64u * 1024u * 1024u;

bool copy_picture(const de265_image* image, HevcPlanes& out) {
    HevcPlanes decoded;
    decoded.width = de265_get_image_width(image, 0);
    decoded.height = de265_get_image_height(image, 0);
    decoded.depth = de265_get_bits_per_pixel(image, 0);
    if (decoded.width <= 0 || decoded.height <= 0 || decoded.width > 16384 || decoded.height > 16384 ||
        decoded.depth < 8 || decoded.depth > 16 ||
        size_t(decoded.width) * decoded.height > kMaxPixels) return false;
    const bool mono = de265_get_chroma_format(image) == de265_chroma_mono;
    decoded.chroma_width = mono ? (decoded.width + 1) / 2 : de265_get_image_width(image, 1);
    decoded.chroma_height = mono ? (decoded.height + 1) / 2 : de265_get_image_height(image, 1);
    if (decoded.chroma_width <= 0 || decoded.chroma_height <= 0 ||
        decoded.chroma_width > decoded.width || decoded.chroma_height > decoded.height) return false;
    const size_t luma_count = size_t(decoded.width) * decoded.height;
    const size_t chroma_count = size_t(decoded.chroma_width) * decoded.chroma_height;
    decoded.samples.resize(luma_count + 2 * chroma_count);
    for (int channel = 0; channel < 3; ++channel) {
        const int w = channel == 0 ? decoded.width : decoded.chroma_width;
        const int h = channel == 0 ? decoded.height : decoded.chroma_height;
        auto* dst = decoded.samples.data() + (channel == 0 ? 0 : luma_count + (channel - 1) * chroma_count);
        if (mono && channel != 0) {
            std::fill_n(dst, chroma_count, uint16_t(1u << (decoded.depth - 1)));
            continue;
        }
        if (de265_get_bits_per_pixel(image, channel) != decoded.depth ||
            de265_get_image_width(image, channel) != w || de265_get_image_height(image, channel) != h) return false;
        int stride = 0;
        const uint8_t* src = de265_get_image_plane(image, channel, &stride);
        const int bytes = decoded.depth > 8 ? 2 : 1;
        if (!src || stride < w * bytes) return false;
        for (int y = 0; y < h; ++y) {
            const auto* row = src + size_t(y) * stride;
            if (bytes == 2) {
                std::memcpy(dst + size_t(y) * w, row, size_t(w) * 2);
            } else {
                std::copy_n(row, w, dst + size_t(y) * w);
            }
        }
    }
    out = std::move(decoded);
    return true;
}
}  // namespace

bool decode_hevc_annex_b(const uint8_t* csd, size_t csd_size,
                        const uint8_t* picture, size_t picture_size, HevcPlanes& out) {
    if (!csd || !csd_size || csd_size > kMaxInput || !picture || !picture_size || picture_size > kMaxInput) return false;
    std::unique_ptr<de265_decoder_context, decltype(&de265_free_decoder)> decoder(de265_new_decoder(), de265_free_decoder);
    if (!decoder) return false;
    auto* limits = de265_get_security_limits(decoder.get());
    limits->max_image_size_pixels = kMaxPixels;
    limits->max_NAL_size_bytes = kMaxInput;
    de265_set_parameter_bool(decoder.get(), DE265_DECODER_PARAM_SUPPRESS_FAULTY_PICTURES, true);
    const unsigned hw = std::thread::hardware_concurrency();
    const unsigned threads = std::clamp((hw == 0 ? 4u : hw) / 2, 1u, 4u);
    if (!de265_isOK(de265_start_worker_threads(decoder.get(), threads)) ||
        !de265_isOK(de265_push_data(decoder.get(), csd, static_cast<int>(csd_size), 0, nullptr)) ||
        !de265_isOK(de265_push_data(decoder.get(), picture, static_cast<int>(picture_size), 0, nullptr)) ||
        !de265_isOK(de265_flush_data(decoder.get()))) return false;
    int more = 1;
    for (unsigned step = 0; more && step < 4096; ++step) {
        const auto result = de265_decode(decoder.get(), &more);
        if (const auto* image = de265_peek_next_picture(decoder.get())) return copy_picture(image, out);
        if (!de265_isOK(result)) return false;
    }
    return false;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_decodeHevcAnnexB(
        JNIEnv* env, jclass, jbyteArray jCsd, jbyteArray jPicture, jintArray jInfo) {
    if (!jCsd || !jPicture || !jInfo || env->GetArrayLength(jInfo) < 5) return nullptr;
    const jsize csd_size = env->GetArrayLength(jCsd), picture_size = env->GetArrayLength(jPicture);
    if (csd_size <= 0 || picture_size <= 0 || size_t(csd_size) > kMaxInput || size_t(picture_size) > kMaxInput) return nullptr;
    auto* csd = env->GetByteArrayElements(jCsd, nullptr);
    if (!csd) return nullptr;
    auto* picture = env->GetByteArrayElements(jPicture, nullptr);
    if (!picture) {
        env->ReleaseByteArrayElements(jCsd, csd, JNI_ABORT);
        return nullptr;
    }
    HevcPlanes planes;
    const bool ok = decode_hevc_annex_b(reinterpret_cast<const uint8_t*>(csd), csd_size,
                                       reinterpret_cast<const uint8_t*>(picture), picture_size, planes);
    env->ReleaseByteArrayElements(jCsd, csd, JNI_ABORT);
    env->ReleaseByteArrayElements(jPicture, picture, JNI_ABORT);
    if (!ok) return nullptr;
    const jint info[]{planes.width, planes.height, planes.depth, planes.chroma_width, planes.chroma_height};
    env->SetIntArrayRegion(jInfo, 0, 5, info);
    auto result = env->NewShortArray(static_cast<jsize>(planes.samples.size()));
    if (result) env->SetShortArrayRegion(result, 0, static_cast<jsize>(planes.samples.size()),
                                        reinterpret_cast<const jshort*>(planes.samples.data()));
    return result;
}
