#include <jni.h>
#include <cstdint>
#include <fstream>
#include <vector>
#include <jxl/decode_cxx.h>
#include <libraw/libraw.h>
extern "C" {
#include "JXRGlue.h"
}
#ifdef min
#undef min
#endif
#ifdef max
#undef max
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_probeStillExportSize(JNIEnv* env, jclass, jstring jpath, jint kind) {
    if (!jpath) return 0;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    uint32_t w = 0, h = 0;
    try {
        if (kind == 0) {
            std::ifstream file(path, std::ios::binary);
            std::vector<uint8_t> header(256 * 1024);
            file.read(reinterpret_cast<char*>(header.data()), header.size());
            header.resize(static_cast<size_t>(file.gcount()));
            auto dec = JxlDecoderMake(nullptr);
            if (dec && !header.empty() && JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO) == JXL_DEC_SUCCESS) {
                JxlDecoderSetInput(dec.get(), header.data(), header.size());
                if (JxlDecoderProcessInput(dec.get()) == JXL_DEC_BASIC_INFO) {
                    JxlBasicInfo info{};
                    if (JxlDecoderGetBasicInfo(dec.get(), &info) == JXL_DEC_SUCCESS) { w = info.xsize; h = info.ysize; }
                }
            }
        } else if (kind == 1) {
            PKFactory* factory = nullptr;
            WMPStream* stream = nullptr;
            PKImageDecode* dec = nullptr;
            if (!Failed(PKCreateFactory(&factory, PK_SDK_VERSION)) && factory &&
                !Failed(factory->CreateStreamFromFilename(&stream, path, "rb")) && stream &&
                !Failed(PKImageDecode_Create_WMP(&dec)) && dec && !Failed(dec->Initialize(dec, stream))) {
                dec->fStreamOwner = 1;
                stream = nullptr;
                I32 x = 0, y = 0;
                if (!Failed(dec->GetSize(dec, &x, &y)) && x > 0 && y > 0) { w = x; h = y; }
            }
            if (dec) dec->Release(&dec);
            if (stream) stream->Close(&stream);
            if (factory) factory->Release(&factory);
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
