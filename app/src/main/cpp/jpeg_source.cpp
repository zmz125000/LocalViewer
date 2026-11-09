#include "jpeg_source.h"
#include "skcms.h"
#include <cstdio>
#include <jpeglib.h>
#include <csetjmp>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

namespace {
struct JpegState {
    jpeg_decompress_struct decoder{};
    jpeg_error_mgr error{};
    std::jmp_buf jump;
    char message[JMSG_LENGTH_MAX]{};
    ~JpegState() { jpeg_destroy_decompress(&decoder); }
};
void jpeg_failure(j_common_ptr info) {
    auto* state=static_cast<JpegState*>(info->client_data);
    info->err->format_message(info,state->message);
    std::longjmp(state->jump,1);
}
}

JpegSourceImage decode_source_jpeg(const uint8_t* data,size_t size,bool native_color) {
    // Keep all objects alive across libjpeg's setjmp error boundary. In
    // particular, never longjmp across a locally constructed scanline vector.
    auto state=std::make_unique<JpegState>();
    auto raster=std::make_unique<JpegSourceImage>();
    auto& image=*raster;
    auto& decoder=state->decoder;
    decoder.err=jpeg_std_error(&state->error);state->error.error_exit=jpeg_failure;
    decoder.client_data=state.get();
    if(setjmp(state->jump))throw std::runtime_error(std::string("JPEG decode failed: ")+state->message);
    jpeg_create_decompress(&decoder);
    jpeg_mem_src(&decoder,data,size);
    jpeg_save_markers(&decoder,JPEG_APP0+1,0xffff);
    if(jpeg_read_header(&decoder,TRUE)!=JPEG_HEADER_OK)throw std::runtime_error("Invalid JPEG header");
    image.width=decoder.image_width;image.height=decoder.image_height;
    image.gray=decoder.num_components==1;
    image.cmyk=decoder.jpeg_color_space==JCS_CMYK||decoder.jpeg_color_space==JCS_YCCK;
    if(!image.width||!image.height||uint64_t(image.width)*image.height>80000000)
        throw std::runtime_error("JPEG dimensions exceed limit");
    if(decoder.data_precision!=8)throw std::runtime_error("Unsupported JPEG sample precision");
    image.icc=source_jpeg_icc(data,size);
    for(auto* marker=decoder.marker_list;marker;marker=marker->next)
        if(marker->data_length>=6&&!std::memcmp(marker->data,"Exif\0\0",6)) {
            image.exif.assign(marker->data+6,marker->data+marker->data_length);break;
        }
    const bool cmyk=image.cmyk;
    decoder.out_color_space=cmyk?JCS_CMYK:native_color?(decoder.num_components==1?JCS_GRAYSCALE:JCS_RGB):JCS_EXT_RGBA;
    jpeg_start_decompress(&decoder);
    image.channels=decoder.output_components;
    image.pixels.resize(size_t(image.width)*image.height*image.channels);
    while(decoder.output_scanline<decoder.output_height) {
        JSAMPROW row=image.pixels.data()+size_t(decoder.output_scanline)*image.width*image.channels;
        if(jpeg_read_scanlines(&decoder,&row,1)!=1)throw std::runtime_error("Truncated JPEG pixels");
    }
    jpeg_finish_decompress(&decoder);
    if(cmyk) {
        // skcms accepts inverted CMYK (as emitted by Adobe JPEGs). Normalize
        // conventional CMYK to that convention before either color path.
        if(!decoder.saw_Adobe_marker)for(auto& v:image.pixels)v=255-v;
        if(!native_color)source_jpeg_to_rgb(image);
    }
    return std::move(image);
}

void source_jpeg_to_rgb(JpegSourceImage& image) {
    if(!image.cmyk)return;
    if(!image.icc.empty()) {
        skcms_ICCProfile source{};
        if(!skcms_Parse(image.icc.data(),image.icc.size(),&source)||source.data_color_space!=skcms_Signature_CMYK)
            throw std::runtime_error("Invalid CMYK JPEG ICC profile");
        if(!skcms_Transform(image.pixels.data(),skcms_PixelFormat_RGBA_8888,skcms_AlphaFormat_Unpremul,&source,
                           image.pixels.data(),skcms_PixelFormat_RGBA_8888,skcms_AlphaFormat_Opaque,skcms_sRGB_profile(),size_t(image.width)*image.height))
            throw std::runtime_error("CMYK JPEG color transform failed");
        // Pixels are now sRGB; never attach the original four-channel ICC.
        image.icc.clear();
    } else {
        for(size_t i=0;i<image.pixels.size();i+=4) {
            const unsigned black=image.pixels[i+3];
            for(unsigned c=0;c<3;++c)image.pixels[i+c]=(unsigned(image.pixels[i+c])*black+127)/255;
            image.pixels[i+3]=255;
        }
    }
    image.cmyk=false;
    image.gray=false;
    image.channels=4;
}

namespace {
struct JpegWriter {
    jpeg_compress_struct encoder{};
    jpeg_error_mgr error{};
    std::jmp_buf jump;
    FILE* file=nullptr;
    std::vector<uint8_t> row;
    ~JpegWriter() { jpeg_destroy_compress(&encoder);if(file)std::fclose(file); }
};
void jpeg_write_failure(j_common_ptr info) {
    std::longjmp(static_cast<JpegWriter*>(info->client_data)->jump,1);
}
}
int encode_cmyk_jpeg(unsigned w,unsigned h,const uint8_t* pixels,const StillSourceColor& color,const char* path,StillEncodeQuality quality) {
    auto state=std::make_unique<JpegWriter>();auto& encoder=state->encoder;
    encoder.err=jpeg_std_error(&state->error);state->error.error_exit=jpeg_write_failure;encoder.client_data=state.get();
    if(setjmp(state->jump))return -86;
    jpeg_create_compress(&encoder);
    state->file=std::fopen(path,"wb");if(!state->file)return -83;
    jpeg_stdio_dest(&encoder,state->file);
    encoder.image_width=w;encoder.image_height=h;encoder.input_components=4;encoder.in_color_space=JCS_CMYK;
    jpeg_set_defaults(&encoder);jpeg_set_quality(&encoder,quality.jpeg(),TRUE);
    for(int c=0;c<4;++c)encoder.comp_info[c].h_samp_factor=encoder.comp_info[c].v_samp_factor=1;
    if(color.bits>8)state->row.resize(size_t(w)*4);
    jpeg_start_compress(&encoder,TRUE);
    if(color.icc_size)jpeg_write_icc_profile(&encoder,color.icc,static_cast<unsigned>(color.icc_size));
    if(quality.exif_size) {
        if(quality.exif_size>65527)return -87;
        // Scratch storage lives in libjpeg's pool, outside the longjmp stack.
        auto* exif=static_cast<JOCTET*>(encoder.mem->alloc_small(reinterpret_cast<j_common_ptr>(&encoder),JPOOL_IMAGE,quality.exif_size+6));
        std::memcpy(exif,"Exif\0\0",6);std::memcpy(exif+6,quality.exif,quality.exif_size);
        jpeg_write_marker(&encoder,JPEG_APP0+1,exif,static_cast<unsigned>(quality.exif_size+6));
    }
    while(encoder.next_scanline<h) {
        const size_t at=size_t(encoder.next_scanline)*w*4;
        JSAMPROW row=const_cast<JSAMPROW>(pixels+at);
        if(color.bits>8) {
            for(size_t c=0;c<size_t(w)*4;++c) {
                uint16_t value;std::memcpy(&value,pixels+(at+c)*2,2);
                state->row[c]=static_cast<uint8_t>((unsigned(value)*255+32767)/65535);
            }
            row=state->row.data();
        }
        jpeg_write_scanlines(&encoder,&row,1);
    }
    jpeg_finish_compress(&encoder);
    const bool wrote=std::ferror(state->file)==0;
    const bool closed=std::fclose(state->file)==0;state->file=nullptr;
    return wrote&&closed?1:-83;
}

// SOF component count from the already mapped input. No pixel decode or file reopen.
bool source_jpeg_is_cmyk(const uint8_t* data,size_t size) {
    if(!data || size<4 || data[0]!=255 || data[1]!=216)return false;
    size_t at=2;
    while(at<size) {
        if(data[at++]!=255)return false;
        while(at<size && data[at]==255)++at;
        if(at>=size)return false;
        const unsigned marker=data[at++];
        if(marker==0xda || marker==0xd9)return false;
        if(marker==1 || (marker>=0xd0 && marker<=0xd7))continue;
        if(size-at<2)return false;
        const size_t length=size_t(data[at])<<8|data[at+1];
        if(length<2 || length>size-at)return false;
        if(marker>=0xc0 && marker<=0xcf && marker!=0xc4 && marker!=0xc8 && marker!=0xcc)
            return length>=8 && data[at+7]==4;
        at+=length;
    }
    return false;
}
