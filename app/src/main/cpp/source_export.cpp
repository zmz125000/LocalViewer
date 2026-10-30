#include "hdr_encode.h"
#include "hdr_parallel.h"
#include "thumbnail_mapped_input.h"
#include "ultrahdr/jpegdecoderhelper.h"
#include "ultrahdr/jpegencoderhelper.h"
#include "ultrahdr/icc.h"
#include <avif/avif.h>
#include "skcms.h"
#include <jxl/decode_cxx.h>
#include <jxl/cms.h>
#include <jxl/resizable_parallel_runner_cxx.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

namespace {
using Image = std::unique_ptr<avifImage, decltype(&avifImageDestroy)>;
using Decoder = std::unique_ptr<avifDecoder, decltype(&avifDecoderDestroy)>;
using Uhdr = std::unique_ptr<uhdr_codec_private_t, void (*)(uhdr_codec_private_t*)>;
constexpr unsigned kLimit = 80000000;
bool ok(uhdr_error_info_t e) { return e.error_code == UHDR_CODEC_OK; }

// Original EXIF orientation remains structural even when photographic EXIF is disabled.
unsigned orientation(const uint8_t* data, size_t size) {
    if (!data || size < 8) return 1;
    const bool le = data[0] == 'I' && data[1] == 'I';
    if (!le && !(data[0] == 'M' && data[1] == 'M')) return 1;
    auto u16 = [&](size_t p) -> uint32_t { return le ? data[p] | uint32_t(data[p+1]) << 8 : uint32_t(data[p]) << 8 | data[p+1]; };
    auto u32 = [&](size_t p) -> uint32_t { return le ? u16(p) | u16(p+2) << 16 : u16(p) << 16 | u16(p+2); };
    const size_t at = u32(4);
    if (at > size - 2) return 1;
    const size_t n = u16(at);
    if (n > (size - at - 2) / 12) return 1;
    for (size_t i=0; i<n; ++i) {
        const size_t p = at + 2 + 12*i;
        if (u16(p) == 0x112 && u16(p+2) == 3 && u32(p+4) == 1) {
            const auto v = u16(p+8); return v >= 1 && v <= 8 ? v : 1;
        }
    }
    return 1;
}
std::vector<uint8_t> orientation_exif(unsigned value) {
    return {'M','M',0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,static_cast<uint8_t>(value),0,0,0,0,0,0};
}
uhdr_color_gamut_t gamut(int p) { return p == 9 ? UHDR_CG_BT_2100 : p == 12 ? UHDR_CG_DISPLAY_P3 : UHDR_CG_BT_709; }
int primary(uhdr_color_gamut_t p) { return p == UHDR_CG_BT_2100 ? 9 : p == UHDR_CG_DISPLAY_P3 ? 12 : 1; }
// AV1 stores 8/10/12-bit samples; only wider sources fall back to the configured AVIF depth.
unsigned avif_source_depth(unsigned bits, const StillEncodeQuality& quality) {
    return bits <= 8 ? 8 : bits <= 10 ? 10 : bits <= 12 ? 12 : quality.avif_depth();
}

Image from_rgb(unsigned w, unsigned h, const void* pixels, bool sixteen, unsigned depth,
               int primaries, int transfer, StillEncodeQuality quality, bool map = false, bool gray = false) {
    Image image(avifImageCreate(w,h,depth,gray?AVIF_PIXEL_FORMAT_YUV400:AVIF_PIXEL_FORMAT_YUV444),avifImageDestroy);
    if (!image || uint64_t(w)*h > kLimit) return Image(nullptr,avifImageDestroy);
    image->colorPrimaries = static_cast<avifColorPrimaries>(primaries);
    image->transferCharacteristics = static_cast<avifTransferCharacteristics>(transfer);
    // Identity keeps RGB codes exact only when lossless; lossy AV1 quantizes the U/V planes
    // harder, which would shift R and B. AV1 forbids identity for monochrome.
    image->matrixCoefficients = quality.avif_lossless() && !gray ? AVIF_MATRIX_COEFFICIENTS_IDENTITY :
        primaries == 9 ? AVIF_MATRIX_COEFFICIENTS_BT2020_NCL : AVIF_MATRIX_COEFFICIENTS_BT709;
    image->yuvRange = AVIF_RANGE_FULL;
    avifRGBImage rgb; avifRGBImageSetDefaults(&rgb,image.get());
    rgb.format = AVIF_RGB_FORMAT_RGBA; rgb.depth = sixteen ? 16 : 8;
    rgb.pixels = static_cast<uint8_t*>(const_cast<void*>(pixels)); rgb.rowBytes = w*4*(sixteen?2:1);
    if (avifImageRGBToYUV(image.get(),&rgb) != AVIF_RESULT_OK) image.reset();
    return image;
}
bool rgb_pixels(const avifImage* image, unsigned depth, std::vector<uint8_t>& pixels) {
    if (!image || !image->width || !image->height || uint64_t(image->width)*image->height > kLimit) return false;
    pixels.resize(size_t(image->width)*image->height*4*(depth>8?2:1));
    avifRGBImage rgb; avifRGBImageSetDefaults(&rgb,image);
    rgb.format=AVIF_RGB_FORMAT_RGBA; rgb.depth=depth; rgb.alphaPremultiplied=AVIF_FALSE;
    rgb.pixels=pixels.data(); rgb.rowBytes=image->width*4*(depth>8?2:1);
    return avifImageYUVToRGB(image,&rgb)==AVIF_RESULT_OK;
}
std::vector<uint8_t> jpeg_icc(const void* input,size_t size) {
    const auto* data=static_cast<const uint8_t*>(input);
    struct Part {const uint8_t* data=nullptr;size_t size=0;};
    std::vector<Part> parts;size_t at=2,total=0;
    while(at+4<=size) {
        if(data[at++]!=255)break;
        while(at<size && data[at]==255)++at;
        if(at>=size)break;
        const unsigned marker=data[at++];if(marker==0xda || marker==0xd9)break;
        if(marker==1 || (marker>=0xd0 && marker<=0xd7))continue;
        if(at+2>size)break;
        const size_t length=size_t(data[at])<<8|data[at+1];
        if(length<2 || length>size-at)break;
        const auto* payload=data+at+2;
        if(marker==0xe2 && length>=16 && !std::memcmp(payload,"ICC_PROFILE\0",12)) {
            const unsigned sequence=payload[12],count=payload[13];
            if(!count || !sequence || sequence>count)return {};
            if(parts.empty())parts.resize(count);
            if(parts.size()!=count || parts[sequence-1].data)return {};
            parts[sequence-1]={payload+14,length-16};total+=length-16;
            if(total>16*1024*1024)return {};
        }
        at+=length;
    }
    std::vector<uint8_t> profile;profile.reserve(total);
    for(const auto& part:parts) {
        if(!part.data)return {};
        profile.insert(profile.end(),part.data,part.data+part.size);
    }
    return profile;
}

Image jpeg_image(const void* bytes,size_t size, StillEncodeQuality quality, bool map,
                 std::vector<uint8_t>* icc = nullptr, std::vector<uint8_t>* exif = nullptr) {
    ultrahdr::JpegDecoderHelper decoder;
    if (!ok(decoder.decompressImage(bytes,size,ultrahdr::DECODE_STREAM))) return Image(nullptr,avifImageDestroy);
    const auto raw=decoder.getDecompressedImage();
    if (uint64_t(raw.w)*raw.h > kLimit) return Image(nullptr,avifImageDestroy);
    const bool gray=raw.fmt==UHDR_IMG_FMT_8bppYCbCr400;
    if (!gray && raw.fmt!=UHDR_IMG_FMT_24bppRGB888 && raw.fmt!=UHDR_IMG_FMT_32bppRGBA8888) return Image(nullptr,avifImageDestroy);
    const unsigned channels=gray?1:raw.fmt==UHDR_IMG_FMT_24bppRGB888?3:4;
    std::vector<uint8_t> rgba(size_t(raw.w)*raw.h*4);
    for (unsigned y=0;y<raw.h;++y) for(unsigned x=0;x<raw.w;++x) {
        const auto* p=static_cast<const uint8_t*>(raw.planes[0])+(size_t(y)*raw.stride[0]+x)*channels;
        auto* dst=rgba.data()+(size_t(y)*raw.w+x)*4;
        dst[0]=p[0];dst[1]=p[gray?0:1];dst[2]=p[gray?0:2];dst[3]=255;
    }
    auto profile=jpeg_icc(bytes,size);
    const uint8_t prefix[]={'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
    std::vector<uint8_t> tagged(prefix,prefix+sizeof(prefix));tagged.insert(tagged.end(),profile.begin(),profile.end());
    auto cg=ultrahdr::IccHelper::readIccColorGamut(tagged.data(),tagged.size());
    const bool gray_profile=profile.size()>=20 && !std::memcmp(profile.data()+16,"GRAY",4);
    auto image=from_rgb(raw.w,raw.h,rgba.data(),false,8,map?2:primary(cg),map?2:decoder.getICCSize()?2:13,quality,map,gray_profile);
    if (icc) *icc=std::move(profile);
    if (exif && decoder.getEXIFSize()>6) {
        auto* data=static_cast<const uint8_t*>(decoder.getEXIFPtr());
        exif->assign(data+6,data+decoder.getEXIFSize());
    }
    return image;
}

int jpeg_to_jxl(const void* data,size_t size,const char* path,StillEncodeQuality quality,bool keep_exif,jint* info) {
    ultrahdr::JpegDecoderHelper decoder;
    if(!ok(decoder.decompressImage(data,size,ultrahdr::DECODE_STREAM)))return -94;
    const auto rgb=decoder.getDecompressedImage();
    if(uint64_t(rgb.w)*rgb.h>kLimit)return -94;
    StillSourceColor color;
    color.channels=rgb.fmt==UHDR_IMG_FMT_8bppYCbCr400?1:rgb.fmt==UHDR_IMG_FMT_24bppRGB888?3:rgb.fmt==UHDR_IMG_FMT_32bppRGBA8888?4:0;
    if(!color.channels || rgb.stride[0]!=rgb.w)return -94;
    auto icc=jpeg_icc(data,size);color.icc=icc.data();color.icc_size=icc.size();
    std::vector<uint8_t> exif;
    if(decoder.getEXIFSize()>6) {
        auto* bytes=static_cast<const uint8_t*>(decoder.getEXIFPtr());
        exif.assign(bytes+6,bytes+decoder.getEXIFSize());
    }
    color.orientation=orientation(exif.data(),exif.size());
    if(keep_exif){quality.exif=exif.data();quality.exif_size=exif.size();}
    const int rc=encode_jxl_source(rgb.w,rgb.h,rgb.planes[0],false,color,path,quality);
    info[0]=rgb.w;info[1]=rgb.h;info[2]=0;info[3]=13;info[4]=0;
    return rc==0?1:rc;
}

Image jxl_image(const uint8_t* data,size_t size,int target,StillEncodeQuality quality,bool keep_exif,std::vector<uint8_t>& icc,
                 std::vector<uint8_t>& exif,bool& unsupported,const char* output,int& encoded_result,jint* output_info) {
    unsupported=false;
    auto runner=JxlResizableParallelRunnerMake(nullptr);
    auto decoder=JxlDecoderMake(nullptr);if(!decoder || !runner)return Image(nullptr,avifImageDestroy);
    JxlDecoderSetParallelRunner(decoder.get(),JxlResizableParallelRunner,runner.get());
    int events=JXL_DEC_BASIC_INFO|JXL_DEC_COLOR_ENCODING|JXL_DEC_FULL_IMAGE;
    if(keep_exif)events|=JXL_DEC_BOX|JXL_DEC_BOX_COMPLETE;
    if(JxlDecoderSubscribeEvents(decoder.get(),events)!=JXL_DEC_SUCCESS)return Image(nullptr,avifImageDestroy);
    if(keep_exif)JxlDecoderSetDecompressBoxes(decoder.get(),JXL_TRUE);
    const auto* cms=JxlGetDefaultCms();if(cms)JxlDecoderSetCms(decoder.get(),*cms);
    JxlDecoderSetUnpremultiplyAlpha(decoder.get(),JXL_TRUE);
    JxlDecoderSetInput(decoder.get(),data,size);JxlDecoderCloseInput(decoder.get());
    JxlBasicInfo info{};JxlColorEncoding color{};bool structured=false,complete=false,collecting=false;
    std::vector<uint8_t> pixels,box;
    JxlPixelFormat format{4,JXL_TYPE_UINT8,JXL_NATIVE_ENDIAN,0};
    for(;;) {
        const auto status=JxlDecoderProcessInput(decoder.get());
        if(status==JXL_DEC_BASIC_INFO) {
            if(JxlDecoderGetBasicInfo(decoder.get(),&info)!=JXL_DEC_SUCCESS || uint64_t(info.xsize)*info.ysize>kLimit)return Image(nullptr,avifImageDestroy);
            JxlResizableParallelRunnerSetThreads(runner.get(),std::min<size_t>(4,hdr_pixel_jobs(size_t(info.xsize)*info.ysize)));
            format.data_type=info.exponent_bits_per_sample && target==1?
                (info.bits_per_sample<=16?JXL_TYPE_FLOAT16:JXL_TYPE_FLOAT):info.bits_per_sample>8?JXL_TYPE_UINT16:JXL_TYPE_UINT8;
        } else if(status==JXL_DEC_COLOR_ENCODING) {
            structured=JxlDecoderGetColorAsEncodedProfile(decoder.get(),JXL_COLOR_PROFILE_TARGET_ORIGINAL,&color)==JXL_DEC_SUCCESS;
            if(target!=1 && info.exponent_bits_per_sample && info.intensity_target>255.f &&
               (!structured || (color.transfer_function!=JXL_TRANSFER_FUNCTION_PQ && color.transfer_function!=JXL_TRANSFER_FUNCTION_HLG))) {
                unsupported=true;return Image(nullptr,avifImageDestroy);
            }
            size_t count=0;
            if(JxlDecoderGetICCProfileSize(decoder.get(),JXL_COLOR_PROFILE_TARGET_ORIGINAL,&count)!=JXL_DEC_SUCCESS || count>16*1024*1024)return Image(nullptr,avifImageDestroy);
            icc.resize(count);
            // Non-XYB samples already are in the original space (libjxl rejects re-targeting them);
            // XYB would otherwise decode an ICC-only original as linear sRGB.
            if(count && (JxlDecoderGetColorAsICCProfile(decoder.get(),JXL_COLOR_PROFILE_TARGET_ORIGINAL,icc.data(),icc.size())!=JXL_DEC_SUCCESS ||
                (!info.uses_original_profile && JxlDecoderSetOutputColorProfile(decoder.get(),nullptr,icc.data(),icc.size())!=JXL_DEC_SUCCESS)))return Image(nullptr,avifImageDestroy);
        } else if(status==JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t count=0;if(JxlDecoderImageOutBufferSize(decoder.get(),&format,&count)!=JXL_DEC_SUCCESS)return Image(nullptr,avifImageDestroy);
            pixels.resize(count);if(JxlDecoderSetImageOutBuffer(decoder.get(),&format,pixels.data(),pixels.size())!=JXL_DEC_SUCCESS)return Image(nullptr,avifImageDestroy);
        } else if(status==JXL_DEC_BOX) {
            JxlBoxType type;
            if(JxlDecoderGetBoxType(decoder.get(),type,JXL_TRUE)==JXL_DEC_SUCCESS && !memcmp(type,"Exif",4)) {
                box.resize(1024*1024);collecting=JxlDecoderSetBoxBuffer(decoder.get(),box.data(),box.size())==JXL_DEC_SUCCESS;
            }
        } else if(status==JXL_DEC_BOX_COMPLETE && collecting) {
            box.resize(box.size()-JxlDecoderReleaseBoxBuffer(decoder.get()));collecting=false;
            if(box.size()>=4) {
                const uint32_t offset=uint32_t(box[0])<<24|uint32_t(box[1])<<16|uint32_t(box[2])<<8|box[3];
                if(offset<=box.size()-4)exif.assign(box.begin()+4+offset,box.end());
            }
            if(complete)break;
        } else if(status==JXL_DEC_BOX_NEED_MORE_OUTPUT && collecting) {
            JxlDecoderReleaseBoxBuffer(decoder.get());collecting=false;if(complete)break;
        } else if(status==JXL_DEC_FULL_IMAGE) {
            complete=true;if(!keep_exif || !exif.empty())break;
            JxlDecoderSkipFrames(decoder.get(),0x7fffffffu);
        } else if(status==JXL_DEC_SUCCESS)break;
        else if(status==JXL_DEC_ERROR || status==JXL_DEC_NEED_MORE_INPUT) {
            if(complete)break;return Image(nullptr,avifImageDestroy);
        }
    }
    if(!complete)return Image(nullptr,avifImageDestroy);
    int p=2,t=2;
    if(structured) {
        p=color.primaries==JXL_PRIMARIES_P3?(color.white_point==JXL_WHITE_POINT_DCI?11:12):static_cast<int>(color.primaries);
        t=color.transfer_function==JXL_TRANSFER_FUNCTION_GAMMA?
            (std::abs(color.gamma-1.0/2.2)<0.0001?4:std::abs(color.gamma-1.0/2.8)<0.0001?5:2):static_cast<int>(color.transfer_function);
    }
    if(!exif.empty()) {
        size_t offset=0;
        if(avifGetExifOrientationOffset(exif.data(),exif.size(),&offset)==AVIF_RESULT_OK && offset<exif.size())exif[offset]=1;
    }
    if(target==1) {
        StillSourceColor source;source.bits=info.bits_per_sample;source.exponent_bits=info.exponent_bits_per_sample;
        source.intensity_target=info.intensity_target;source.primaries=p;source.transfer=t;
        source.icc=icc.data();source.icc_size=icc.size();
        if((t==16 || t==18) && (p==1 || p==9 || p==12))source.icc_size=0;
        quality.exif=exif.data();quality.exif_size=exif.size();
        const int result=encode_jxl_source(info.xsize,info.ysize,pixels.data(),info.bits_per_sample>8,source,output,quality);
        encoded_result=result==0?1:result;
        output_info[0]=info.xsize;output_info[1]=info.ysize;output_info[2]=0;output_info[3]=t;output_info[4]=static_cast<jint>(std::ceil(info.intensity_target));
        return Image(nullptr,avifImageDestroy);
    }
    // An AVIF integer base cannot retain extended float/linear HDR verbatim.
    // Explicit HDR rendering remains available through the existing HDR targets.
    if(info.exponent_bits_per_sample && t!=16 && t!=18 && info.intensity_target>255.f) {
        unsupported=true;return Image(nullptr,avifImageDestroy);
    }
    const bool gray=icc.size()>=20 && !std::memcmp(icc.data()+16,"GRAY",4);
    auto image=from_rgb(info.xsize,info.ysize,pixels.data(),info.bits_per_sample>8,avif_source_depth(info.bits_per_sample,quality),p,t,quality,false,gray);
    if(image && !icc.empty() && avifImageSetProfileICC(image.get(),icc.data(),icc.size())!=AVIF_RESULT_OK)image.reset();
    // libjxl already applied orientation to the pixels.
    if(image && image->exif.size)avifRWDataFree(&image->exif);
    return image;
}

bool from_uhdr_metadata(const uhdr_gainmap_metadata_t& src,avifGainMap* dst,int base_primary) {
    for(unsigned c=0;c<3;++c) {
        if (!avifDoubleToSignedFraction(std::log2(src.min_content_boost[c]),&dst->gainMapMin[c]) ||
            !avifDoubleToSignedFraction(std::log2(src.max_content_boost[c]),&dst->gainMapMax[c]) ||
            !avifDoubleToUnsignedFraction(src.gamma[c],&dst->gainMapGamma[c]) ||
            !avifDoubleToSignedFraction(src.offset_sdr[c],&dst->baseOffset[c]) ||
            !avifDoubleToSignedFraction(src.offset_hdr[c],&dst->alternateOffset[c])) return false;
    }
    if (!avifDoubleToUnsignedFraction(std::log2(src.hdr_capacity_min),&dst->baseHdrHeadroom) ||
        !avifDoubleToUnsignedFraction(std::log2(src.hdr_capacity_max),&dst->alternateHdrHeadroom)) return false;
    dst->useBaseColorSpace=src.use_base_cg?AVIF_TRUE:AVIF_FALSE;
    dst->altColorPrimaries=static_cast<avifColorPrimaries>(base_primary);
    dst->altTransferCharacteristics=AVIF_TRANSFER_CHARACTERISTICS_PQ;
    dst->altMatrixCoefficients=AVIF_MATRIX_COEFFICIENTS_BT709;
    dst->altYUVRange=AVIF_RANGE_FULL;dst->altDepth=16;dst->altPlaneCount=3;
    return true;
}
template<typename T> double fraction(T v) { return v.d ? double(v.n)/v.d : NAN; }
bool to_uhdr_metadata(const avifImage* base,uhdr_gainmap_metadata_t& dst) {
    const auto* src=base->gainMap;
    // Ultra HDR JPEG cannot represent an HDR base or arbitrary alternate ICC/primaries.
    if (!src || fraction(src->baseHdrHeadroom)>fraction(src->alternateHdrHeadroom)) return false;
    for(unsigned c=0;c<3;++c) {
        dst.min_content_boost[c]=std::exp2(fraction(src->gainMapMin[c]));
        dst.max_content_boost[c]=std::exp2(fraction(src->gainMapMax[c]));
        dst.gamma[c]=fraction(src->gainMapGamma[c]);
        dst.offset_sdr[c]=fraction(src->baseOffset[c]);dst.offset_hdr[c]=fraction(src->alternateOffset[c]);
    }
    dst.hdr_capacity_min=std::exp2(fraction(src->baseHdrHeadroom));
    dst.hdr_capacity_max=std::exp2(fraction(src->alternateHdrHeadroom));dst.use_base_cg=src->useBaseColorSpace;
    return true;
}
bool downsize_map(avifImage* base,unsigned scale) {
    if (!base->gainMap || !base->gainMap->image) return true;
    auto* map=base->gainMap->image;
    if(scale<=1)return true;
    const double factor=std::min({1.0,double((base->width+scale-1)/scale)/map->width,
        double((base->height+scale-1)/scale)/map->height});
    const unsigned w=std::max(1u,static_cast<unsigned>(std::floor(map->width*factor)));
    const unsigned h=std::max(1u,static_cast<unsigned>(std::floor(map->height*factor)));
    if (w==map->width && h==map->height) return true;
    avifDiagnostics diag{};
    return avifImageScale(map,w,h,&diag)==AVIF_RESULT_OK;
}
// ISO gain-map CICP is sufficient when it is equivalent to the source ICC.
// Keep RGB and gain-map math unchanged; never strip a custom profile by guessing.
bool gain_map_cicp(avifImage* image) {
    if(!image->gainMap || !image->icc.size)return true;
    skcms_ICCProfile source{};
    if(!skcms_Parse(image->icc.data,image->icc.size,&source))return false;
    for(auto cg : {UHDR_CG_BT_709,UHDR_CG_DISPLAY_P3,UHDR_CG_BT_2100}) {
        auto profile=ultrahdr::IccHelper::writeIccProfile(UHDR_CT_SRGB,cg);
        skcms_ICCProfile standard{};
        if(profile && profile->getLength()>ultrahdr::kICCIdentifierSize &&
           skcms_Parse(static_cast<const uint8_t*>(profile->getData())+ultrahdr::kICCIdentifierSize,
                       profile->getLength()-ultrahdr::kICCIdentifierSize,&standard) &&
           skcms_ApproximatelyEqualProfiles(&source,&standard)) {
            image->colorPrimaries=static_cast<avifColorPrimaries>(primary(cg));
            image->transferCharacteristics=AVIF_TRANSFER_CHARACTERISTICS_SRGB;
            if(image->gainMap->altColorPrimaries==0 || image->gainMap->altColorPrimaries==2)image->gainMap->altColorPrimaries=image->colorPrimaries;
            avifRWDataFree(&image->icc);return true;
        }
    }
    return false;
}

int write_avif_source(avifImage* image,const char* path,StillEncodeQuality quality) {
    if((image->transferCharacteristics==16 || image->transferCharacteristics==18) &&
       (image->colorPrimaries==1 || image->colorPrimaries==9 || image->colorPrimaries==12))avifRWDataFree(&image->icc);
    if (quality.exif_size && avifImageSetMetadataExif(image,quality.exif,quality.exif_size)!=AVIF_RESULT_OK) return -81;
    std::unique_ptr<avifEncoder,decltype(&avifEncoderDestroy)> encoder(avifEncoderCreate(),avifEncoderDestroy);
    if(!encoder)return -81;
    encoder->codecChoice=AVIF_CODEC_CHOICE_AOM;encoder->quality=quality.avif();
    encoder->qualityAlpha=AVIF_QUALITY_LOSSLESS;encoder->qualityGainMap=quality.avif();
    encoder->speed=6;encoder->maxThreads=std::min(4u,hdr_pixel_jobs(size_t(image->width)*image->height));encoder->autoTiling=AVIF_TRUE;
    avifRWData bytes=AVIF_DATA_EMPTY;
    const auto rc=avifEncoderWrite(encoder.get(),image,&bytes);
    if(rc!=AVIF_RESULT_OK) {__android_log_print(ANDROID_LOG_ERROR,"StillConvert","Source AVIF: %s %s",avifResultToString(rc),encoder->diag.error);avifRWDataFree(&bytes);return -82;}
    FILE* f=fopen(path,"wb");bool good=false;
    if(f){good=fwrite(bytes.data,1,bytes.size,f)==bytes.size;if(fclose(f)!=0)good=false;}
    avifRWDataFree(&bytes);return good?1:-83;
}
bool jpeg_compress(const avifImage* image,int quality,bool base,std::vector<uint8_t>& out,const avifImage* gain_owner=nullptr) {
    std::vector<uint8_t> rgba;
    if(!rgb_pixels(image,8,rgba))return false;
    // Compact in place; libultrahdr's RGB JPEG encoder consumes three channels.
    for (size_t i=0;i<size_t(image->width)*image->height;++i) {
        rgba[i*3]=rgba[i*4];rgba[i*3+1]=rgba[i*4+1];rgba[i*3+2]=rgba[i*4+2];
    }
    uhdr_raw_image_t raw{};raw.fmt=UHDR_IMG_FMT_24bppRGB888;raw.cg=gamut(image->colorPrimaries);
    raw.ct=UHDR_CT_SRGB;raw.range=UHDR_CR_FULL_RANGE;raw.w=image->width;raw.h=image->height;
    raw.planes[0]=rgba.data();raw.stride[0]=image->width;
    std::vector<uint8_t> icc;
    if(base || gain_owner) {
        const auto* profile_data=image->icc.data;size_t profile_size=image->icc.size;
        int profile_primaries=image->colorPrimaries;
        if(gain_owner) {
            const auto* gm=gain_owner->gainMap;
            profile_data=gm->altICC.data;profile_size=gm->altICC.size;
            profile_primaries=gm->altColorPrimaries;
            if(profile_primaries==0 || profile_primaries==2)profile_primaries=gain_owner->colorPrimaries;
            if(!profile_size && gm->useBaseColorSpace && gain_owner->icc.size) {
                profile_data=gain_owner->icc.data;profile_size=gain_owner->icc.size;
            }
        }
        if(profile_size) {
            const uint8_t prefix[]={'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
            icc.assign(prefix,prefix+sizeof(prefix));icc.insert(icc.end(),profile_data,profile_data+profile_size);
        } else {
            auto p=ultrahdr::IccHelper::writeIccProfile(gain_owner?UHDR_CT_LINEAR:UHDR_CT_SRGB,gamut(profile_primaries));
            if(p)icc.assign(static_cast<const uint8_t*>(p->getData()),static_cast<const uint8_t*>(p->getData())+p->getLength());
        }
    }
    // The RGB helper encodes 4:4:4, never 4:2:0.
    ultrahdr::JpegEncoderHelper enc;
    const bool split=icc.size()>65533;
    if(!ok(enc.compressImage(&raw,quality,split?nullptr:icc.data(),split?0:icc.size())))return false;
    const auto bytes=enc.getCompressedImage();
    out.assign(static_cast<const uint8_t*>(bytes.data),static_cast<const uint8_t*>(bytes.data)+bytes.data_sz);
    if(split) {
        constexpr size_t part_size=65519;
        const size_t raw_size=icc.size()-14,count=(raw_size+part_size-1)/part_size;
        if(count>255)return false;
        std::vector<uint8_t> markers;
        for(size_t part=0;part<count;++part) {
            const size_t at=part*part_size,n=std::min(part_size,raw_size-at),length=n+16;
            markers.insert(markers.end(),{255,226,static_cast<uint8_t>(length>>8),static_cast<uint8_t>(length)});
            markers.insert(markers.end(),icc.begin(),icc.begin()+12);
            markers.push_back(static_cast<uint8_t>(part+1));markers.push_back(static_cast<uint8_t>(count));
            markers.insert(markers.end(),icc.begin()+14+at,icc.begin()+14+at+n);
        }
        out.insert(out.begin()+2,markers.begin(),markers.end());
    }
    return true;
}
unsigned image_orientation(const avifImage* image) {
    const unsigned rotation[]={1,8,3,6};
    unsigned result=(image->transformFlags & AVIF_TRANSFORM_IROT)?rotation[image->irot.angle%4]:1;
    if(image->transformFlags & AVIF_TRANSFORM_IMIR) {
        const unsigned vertical[]={0,2,1,4,3,6,5,8,7};
        const unsigned horizontal[]={0,4,3,2,1,8,7,6,5};
        result=(image->imir.axis==0?horizontal:vertical)[result];
    }
    return result;
}
std::vector<uint8_t> with_orientation(const uint8_t* data,size_t size,unsigned value) {
    if(!data || size<8)return orientation_exif(value);
    std::vector<uint8_t> bytes(data,data+size);
    size_t offset=0;
    if(avifGetExifOrientationOffset(bytes.data(),bytes.size(),&offset)!=AVIF_RESULT_OK)return orientation_exif(value);
    if(offset<bytes.size()){bytes[offset]=value;return bytes;}
    const bool le=bytes[0]=='I';
    auto u16=[&](size_t p)->uint32_t{return le?bytes[p]|uint32_t(bytes[p+1])<<8:uint32_t(bytes[p])<<8|bytes[p+1];};
    auto u32=[&](size_t p)->uint32_t{return le?u16(p)|u16(p+2)<<16:u16(p)<<16|u16(p+2);};
    auto set16=[&](size_t p,uint32_t v){bytes[p]=le?v&255:v>>8;bytes[p+1]=le?v>>8:v&255;};
    auto set32=[&](size_t p,uint32_t v){if(le){set16(p,v);set16(p+2,v>>16);}else{set16(p,v>>16);set16(p+2,v);}};
    const size_t root=u32(4);if(root>size-2)return orientation_exif(value);
    const size_t n=u16(root);if(n>=65535 || n>(size-root-2)/12)return orientation_exif(value);
    const size_t next=root+2+n*12;
    const uint32_t next_ifd=next<=size-4?u32(next):0;
    const size_t fresh=(size+1)&~size_t(1);
    bytes.resize(fresh+2+(n+1)*12+4,0);
    std::memcpy(bytes.data()+fresh+2,bytes.data()+root+2,n*12);
    set16(fresh,n+1);const size_t entry=fresh+2+n*12;
    set16(entry,0x112);set16(entry+2,3);set32(entry+4,1);set16(entry+8,value);
    set32(fresh+2+(n+1)*12,next_ifd);set32(4,fresh);return bytes;
}

int write_gainmap_jpeg(avifImage* image,const char* path,StillEncodeQuality quality,
                      const uint8_t* original_map=nullptr,size_t original_map_size=0) {
    if(image->transferCharacteristics!=AVIF_TRANSFER_CHARACTERISTICS_SRGB && image->icc.size==0)return -84;
    if(image->transformFlags & AVIF_TRANSFORM_CLAP)return -90;
    if(image->alphaPlane) {
        const uint32_t maximum=(1u<<image->depth)-1;
        for(uint32_t y=0;y<image->height;++y)for(uint32_t x=0;x<image->width;++x) {
            const auto* row=image->alphaPlane+size_t(y)*image->alphaRowBytes;
            const uint32_t alpha=image->depth>8?reinterpret_cast<const uint16_t*>(row)[x]:row[x];
            if(alpha!=maximum)return -97; // JPEG cannot retain an alpha-bearing gain-map rendition.
        }
    }
    std::vector<uint8_t> oriented;
    if(image->transformFlags & (AVIF_TRANSFORM_IROT|AVIF_TRANSFORM_IMIR)) {
        oriented=with_orientation(quality.exif,quality.exif_size,image_orientation(image));
        quality.exif=oriented.data();quality.exif_size=oriented.size();
    }
    uhdr_gainmap_metadata_t metadata{};
    if(!to_uhdr_metadata(image,metadata))return -85;
    std::vector<uint8_t> base,map;
    if(!jpeg_compress(image,quality.jpeg(),true,base))return -86;
    if(quality.exif_size) {
        if(quality.exif_size>65527)return -87;
        const size_t length=quality.exif_size+8;
        std::vector<uint8_t> marker={255,225,static_cast<uint8_t>(length>>8),static_cast<uint8_t>(length),'E','x','i','f',0,0};
        marker.insert(marker.end(),quality.exif,quality.exif+quality.exif_size);
        base.insert(base.begin()+2,marker.begin(),marker.end());
    }
    if(!original_map && !jpeg_compress(image->gainMap->image,quality.jpeg(),false,map,image))return -86;
    Uhdr enc(uhdr_create_encoder(),uhdr_release_encoder);if(!enc)return -87;
    uhdr_compressed_image_t b{base.data(),base.size(),base.size(),gamut(image->colorPrimaries),UHDR_CT_SRGB,UHDR_CR_FULL_RANGE};
    uhdr_compressed_image_t g{original_map?const_cast<uint8_t*>(original_map):map.data(),original_map?original_map_size:map.size(),original_map?original_map_size:map.size(),gamut(image->gainMap->altColorPrimaries),UHDR_CT_LINEAR,UHDR_CR_FULL_RANGE};
    if(!ok(uhdr_enc_set_compressed_image(enc.get(),&b,UHDR_BASE_IMG)) || !ok(uhdr_enc_set_gainmap_image(enc.get(),&g,&metadata)))return -87;
    if(!ok(uhdr_encode(enc.get())))return -88;
    const auto* bytes=uhdr_get_encoded_stream(enc.get());if(!bytes)return -88;
    FILE* f=fopen(path,"wb");if(!f)return -83;
    const bool wrote=fwrite(bytes->data,1,bytes->data_sz,f)==bytes->data_sz;
    const bool closed=fclose(f)==0;return wrote&&closed?1:-83;
}
int write_source(avifImage* image,int target,const char* path,StillEncodeQuality quality,jint* info,
                 const uint8_t* original_map=nullptr,size_t original_map_size=0) {
    if(!image)return -80;
    const bool gain=image->gainMap && image->gainMap->image;
    info[0]=image->width;info[1]=image->height;info[2]=gain && target!=1;info[3]=image->transferCharacteristics;
    if(gain && target!=1) {
        const auto oldW=image->gainMap->image->width,oldH=image->gainMap->image->height;
        if(!downsize_map(image,quality.jpeg_gain_map_scale()))return -89;
        if(oldW!=image->gainMap->image->width || oldH!=image->gainMap->image->height)original_map=nullptr;
    }
    if(target==0)return gain?write_gainmap_jpeg(image,path,quality,original_map,original_map_size):0;
    if(target==2) {
        // Equivalent ICCs can use CICP; custom ICCs remain intact in the ISO container.
        gain_map_cicp(image);
        if(image->gainMap && image->gainMap->image->yuvFormat!=AVIF_PIXEL_FORMAT_YUV444) {
            auto* map=image->gainMap->image;std::vector<uint8_t> rgb;
            if(!rgb_pixels(map,map->depth>8?16:8,rgb))return -91;
            auto packed=from_rgb(map->width,map->height,rgb.data(),map->depth>8,map->depth,2,2,quality,true);
            if(!packed)return -91;
            image->gainMap->image=packed.release();avifImageDestroy(map);
        }
        // A decoded AVIF keeps its YUV planes, depth, subsampling, matrix and range; any
        // repack through RGB would re-quantize or resample the source codes.
        return write_avif_source(image,path,quality);
    }
    StillSourceColor color; color.bits=image->depth;color.primaries=image->colorPrimaries;
    color.transfer=image->transferCharacteristics;color.icc=image->icc.data;color.icc_size=image->icc.size;
    if((color.transfer==16 || color.transfer==18) && (color.primaries==1 || color.primaries==9 || color.primaries==12))color.icc_size=0;
    color.orientation=orientation(quality.exif,quality.exif_size);
    if(image->transformFlags) {
        if(image->transformFlags & AVIF_TRANSFORM_CLAP)return -90;
        color.orientation=image_orientation(image);
    }
    const bool sixteen=image->depth>8;
    std::vector<uint8_t> rgb;
    if(!rgb_pixels(image,sixteen?16:8,rgb))return -91;
    const int rc=encode_jxl_source(image->width,image->height,rgb.data(),sixteen,color,path,quality);
    return rc==0?1:rc;
}
} // namespace

// 0=not handled; 1=encoded; negative=real error, never silently discard a detected gain map.
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_convertSourceEncodedStill(JNIEnv* env,jclass,jobject input,jint kind,
        jint target,jstring output,jint preset,jfloat distance,jint codec_quality,jint avif_depth,jboolean downsize,
        jboolean keep_exif,jintArray out_info) try {
    if(!input||!output||!out_info||env->GetArrayLength(out_info)<5)return -80;
    const auto* data=static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    const auto size=env->GetDirectBufferCapacity(input);if(!data||size<=0)return -80;
    ThumbnailOutputPath path(env,output);if(!path.get())return -83;
    StillEncodeQuality quality(preset,distance,codec_quality,avif_depth,downsize==JNI_TRUE);
    Image image(nullptr,avifImageDestroy);Decoder decoder(nullptr,avifDecoderDestroy);
    avifImage* decoded_image=nullptr;
    Uhdr jpeg(nullptr,uhdr_release_decoder);std::vector<uint8_t> exif,icc;
    const uint8_t* original_map=nullptr;size_t original_map_size=0;
    if(kind==1) {
        decoder.reset(avifDecoderCreate());if(!decoder)return -80;
        decoder->ignoreExif=AVIF_FALSE;decoder->ignoreXMP=AVIF_TRUE;
        decoder->imageContentToDecode=target==1?AVIF_IMAGE_CONTENT_DECODE_DEFAULT:AVIF_IMAGE_CONTENT_ALL;decoder->maxThreads=std::min(4u,hdr_pixel_jobs(kLimit));
        if(avifDecoderSetIOMemory(decoder.get(),data,size)!=AVIF_RESULT_OK)return -92;
        auto parsed=avifDecoderParse(decoder.get());
        if(parsed!=AVIF_RESULT_OK) {decoder->ignoreExif=AVIF_TRUE;parsed=avifDecoderParse(decoder.get());}
        if(parsed!=AVIF_RESULT_OK)return -92;
        if(target==0 && !decoder->image->gainMap)return 0;
        if(avifDecoderNextImage(decoder.get())!=AVIF_RESULT_OK)return -93;
        decoded_image=decoder->image;
        if(decoded_image->exif.size)exif.assign(decoded_image->exif.data,decoded_image->exif.data+decoded_image->exif.size);
    } else if(kind==0) {
        jpeg.reset(uhdr_create_decoder());if(!jpeg)return -80;
        uhdr_compressed_image_t source{const_cast<uint8_t*>(data),size_t(size),size_t(size),UHDR_CG_UNSPECIFIED,UHDR_CT_UNSPECIFIED,UHDR_CR_FULL_RANGE};
        const bool gain=ok(uhdr_dec_set_image(jpeg.get(),&source)) && ok(uhdr_dec_probe(jpeg.get()));
        if(target==0 && !gain)return 0;
        const auto* base=gain?uhdr_dec_get_base_image(jpeg.get()):nullptr;
        if(target==1) {
            jint info[5]{};
            const int rc=jpeg_to_jxl(base?base->data:data,base?base->data_sz:size,path.get(),quality,keep_exif==JNI_TRUE,info);
            env->SetIntArrayRegion(out_info,0,5,info);return rc;
        }
        image=jpeg_image(base?base->data:data,base?base->data_sz:size,quality,false,&icc,&exif);
        if(!image)return -94;
        if(!icc.empty() && avifImageSetProfileICC(image.get(),icc.data(),icc.size())!=AVIF_RESULT_OK)return -80;
        if(gain && target!=1) {
            const auto* map=uhdr_dec_get_gainmap_image(jpeg.get());const auto* metadata=uhdr_dec_get_gainmap_metadata(jpeg.get());
            if(!map||!metadata)return -95;
            std::vector<uint8_t> gain_icc;
            auto gain_image=jpeg_image(map->data,map->data_sz,quality,true,&gain_icc);
            image->gainMap=avifGainMapCreate();if(!gain_image||!image->gainMap)return -95;
            image->gainMap->image=gain_image.release();
            if(!from_uhdr_metadata(*metadata,image->gainMap,image->colorPrimaries))return -95;
            if(!gain_icc.empty()) {
                const uint8_t prefix[]={'I','C','C','_','P','R','O','F','I','L','E',0,1,1};
                std::vector<uint8_t> tagged(prefix,prefix+sizeof(prefix));tagged.insert(tagged.end(),gain_icc.begin(),gain_icc.end());
                const auto alternate=ultrahdr::IccHelper::readIccColorGamut(tagged.data(),tagged.size());
                if(alternate!=UHDR_CG_UNSPECIFIED)image->gainMap->altColorPrimaries=static_cast<avifColorPrimaries>(primary(alternate));
                else if(avifRWDataSet(&image->gainMap->altICC,gain_icc.data(),gain_icc.size())!=AVIF_RESULT_OK)return -95;
            }
            original_map=static_cast<const uint8_t*>(map->data);original_map_size=map->data_sz;
        }
    } else if(kind==3) {
        bool unsupported=false;
        int encoded=0;jint info[5]{};
        image=jxl_image(data,size,target,quality,keep_exif==JNI_TRUE,icc,exif,unsupported,path.get(),encoded,info);
        if(encoded){env->SetIntArrayRegion(out_info,0,5,info);return encoded;}
        if(unsupported)return 0;
        if(!image)return -94;
        // Source EXIF may describe the original orientation; pixels are already oriented.
        size_t offset=0;
        if(!exif.empty() && avifGetExifOrientationOffset(exif.data(),exif.size(),&offset)==AVIF_RESULT_OK && offset<exif.size())exif[offset]=1;
    } else return 0;
    avifImage* source_image=decoded_image?decoded_image:image.get();
    const unsigned orient=orientation(exif.data(),exif.size());
    if(keep_exif!=JNI_TRUE)exif=orientation_exif(orient);
    quality.exif=exif.data();quality.exif_size=exif.size();
    // AVIF's stored transforms are authoritative; applying EXIF to an already transformed
    // source would overwrite them. Keep the source metadata directly in that case.
    if(kind==1 && target==2) {
        if(keep_exif!=JNI_TRUE)avifRWDataFree(&source_image->exif);
        quality.exif=nullptr;quality.exif_size=0;
    }
    jint info[5]{};const int result=write_source(source_image,target,path.get(),quality,info,original_map,original_map_size);
    env->SetIntArrayRegion(out_info,0,5,info);return result;
} catch(...) {return -99;}

// Transfer-encoded, straight RGBA16 from HEVC/platform decoding; all buffers are borrowed.
extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_HdrConvertKt_encodeSourceRgb(JNIEnv* env,jclass,jobject pixels,jint width,jint height,
        jint bits,jint primaries,jint transfer,jbyteArray icc,jint storage,jint target,jstring output,
        jint preset,jfloat distance,jint codec_quality,jint avif_depth,jboolean downsize,jbyteArray exif,
        jobject gain_pixels,jint gain_width,jint gain_height,jboolean gain_jpeg,jfloatArray gain_metadata,jintArray out_info) try {
    if(!pixels||!output||!out_info||width<=0||height<=0||uint64_t(width)*height>kLimit||env->GetArrayLength(out_info)<5)return -80;
    const auto* data=env->GetDirectBufferAddress(pixels);
    const unsigned format=storage&3;
    if(!data||env->GetDirectBufferCapacity(pixels)<int64_t(width)*height*(format==1?4:8))return -80;
    std::vector<uint16_t> converted;
    if(format==2 || (storage&4)) {
        const size_t count=size_t(width)*height;converted.resize(count*4);
        hdr_parallel_pixels(count,hdr_pixel_jobs(count),[&](size_t begin,size_t end,unsigned) {
            for(size_t i=begin;i<end;++i) {
                float a=format==1?static_cast<const uint8_t*>(data)[i*4+3]/255.f:
                    format==2?half_to_float(static_cast<const uint16_t*>(data)[i*4+3]):static_cast<const uint16_t*>(data)[i*4+3]/65535.f;
                a=std::isfinite(a)?std::clamp(a,0.f,1.f):0.f;
                for(unsigned c=0;c<4;++c) {
                    float v=c==3?a:format==1?static_cast<const uint8_t*>(data)[i*4+c]/255.f:
                        format==2?half_to_float(static_cast<const uint16_t*>(data)[i*4+c]):static_cast<const uint16_t*>(data)[i*4+c]/65535.f;
                    if(c<3 && (storage&4))v=a>0.f?v/a:0.f;
                    converted[i*4+c]=static_cast<uint16_t>(std::lround(std::isfinite(v)?std::clamp(v,0.f,1.f)*65535.f:0.f));
                }
            }
        });
        data=converted.data();
    }
    StillEncodeQuality quality(preset,distance,codec_quality,avif_depth,downsize==JNI_TRUE);
    // Preserve source precision for JXL rather than applying AVIF's depth setting.
    if(target==1) {
        StillSourceColor color;color.bits=bits;color.primaries=primaries;color.transfer=transfer;
        std::vector<uint8_t> profile,metadata;
        if(icc){profile.resize(env->GetArrayLength(icc));env->GetByteArrayRegion(icc,0,profile.size(),reinterpret_cast<jbyte*>(profile.data()));}
        if(exif){metadata.resize(env->GetArrayLength(exif));env->GetByteArrayRegion(exif,0,metadata.size(),reinterpret_cast<jbyte*>(metadata.data()));}
        color.icc=profile.data();color.icc_size=profile.size();
        const unsigned orient=(storage>>8)&15;color.orientation=orient>=1 && orient<=8?orient:1;
        if((transfer==16 || transfer==18) && (primaries==1 || primaries==9 || primaries==12))color.icc_size=0;
        quality.exif=metadata.data();quality.exif_size=metadata.size();
        ThumbnailOutputPath path(env,output);if(!path.get())return -83;
        const int rc=encode_jxl_source(width,height,data,format!=1 || !converted.empty(),color,path.get(),quality);
        jint info[]={width,height,0,transfer,0};env->SetIntArrayRegion(out_info,0,5,info);return rc==0?1:rc;
    }
    bool gray=false;
    if(icc && env->GetArrayLength(icc)>=20) {
        jbyte signature[4];env->GetByteArrayRegion(icc,16,4,signature);gray=!std::memcmp(signature,"GRAY",4);
    }
    auto image=from_rgb(width,height,data,format!=1 || !converted.empty(),avif_source_depth(bits,quality),primaries,transfer,quality,false,gray);
    if(!image)return -91;
    if(icc) {
        std::vector<uint8_t> profile(env->GetArrayLength(icc));env->GetByteArrayRegion(icc,0,profile.size(),reinterpret_cast<jbyte*>(profile.data()));
        if(avifImageSetProfileICC(image.get(),profile.data(),profile.size())!=AVIF_RESULT_OK)return -80;
    }
    std::vector<uint8_t> metadata;
    if(exif){metadata.resize(env->GetArrayLength(exif));env->GetByteArrayRegion(exif,0,metadata.size(),reinterpret_cast<jbyte*>(metadata.data()));}
    const unsigned orient=(storage>>8)&15;
    if(orient>1 && orient<=8)metadata=with_orientation(metadata.data(),metadata.size(),orient);
    quality.exif=metadata.data();quality.exif_size=metadata.size();
    const uint8_t* original_gain=nullptr;size_t original_gain_size=0;
    if(gain_pixels && gain_metadata && env->GetArrayLength(gain_metadata)>=17) {
        const auto* gain=env->GetDirectBufferAddress(gain_pixels);const auto size=env->GetDirectBufferCapacity(gain_pixels);
        if(!gain || size<=0)return -95;
        Image map(nullptr,avifImageDestroy);
        if(gain_jpeg==JNI_TRUE) {
            map=jpeg_image(gain,size,quality,true);
            original_gain=static_cast<const uint8_t*>(gain);original_gain_size=size;
        }
        else if(gain_width>0 && gain_height>0 && int64_t(gain_width)*gain_height*4<=size)map=from_rgb(gain_width,gain_height,gain,false,8,2,2,quality,true);
        if(!map)return -95;
        float values[17];env->GetFloatArrayRegion(gain_metadata,0,17,values);
        uhdr_gainmap_metadata_t gm{};
        for(unsigned c=0;c<3;++c){gm.min_content_boost[c]=values[c];gm.max_content_boost[c]=values[3+c];gm.gamma[c]=values[6+c];gm.offset_sdr[c]=values[9+c];gm.offset_hdr[c]=values[12+c];}
        gm.hdr_capacity_min=values[15];gm.hdr_capacity_max=values[16];gm.use_base_cg=1;
        image->gainMap=avifGainMapCreate();if(!image->gainMap)return -95;
        image->gainMap->image=map.release();if(!from_uhdr_metadata(gm,image->gainMap,primaries))return -95;
    }
    ThumbnailOutputPath path(env,output);if(!path.get())return -83;
    jint info[5]{};const int result=write_source(image.get(),target,path.get(),quality,info,original_gain,original_gain_size);
    env->SetIntArrayRegion(out_info,0,5,info);return result;
} catch(...) {return -99;}
