// Host checks of production pixel preparation, JPEG/UHDR exports and JXL encoder
// round trips through the reader's real JXL decoder. See CMakeLists.txt to build.
#include "../../app/src/main/cpp/hdr_convert.cpp"
#undef LOG_TAG
#include "../../app/src/main/cpp/jxl_hdr.cpp"
#include <chrono>
#include <jxl/encode_cxx.h>
#include <stdexcept>

static void check(bool ok, const char* text) { if (!ok) throw std::runtime_error(text); }

static void test_lookup_and_alpha() {
    for (unsigned code = 0; code < 65536; ++code) {
        check(half_srgb_table()[code] == srgb_oetf(half_to_float(code)), "OETF lookup changed");
        check(half_to_srgb_u8(code) == linear_to_srgb_u8(half_to_float(code)), "8-bit lookup changed");
    }
    // Force multiple pixel workers; cover all half codes, opaque and mixed alpha.
    const size_t n = 65536 * 8;
    std::vector<uint16_t> original(n * 4);
    for (size_t i = 0; i < n; ++i) {
        original[4*i] = i % 65536;
        original[4*i+1] = (i+17) % 65536;
        original[4*i+2] = (i+257) % 65536;
        original[4*i+3] = (i%3) == 0 ? 0x3c00 : (i%3) == 1 ? 0x3800 : i%65536;
    }
    auto expected = original;
    for (size_t i=0;i<n;++i) {
        float a=half_to_float(expected[4*i+3]);
        if (!std::isfinite(a) || a<=0.f) { for (unsigned c=0;c<4;++c) expected[4*i+c]=0; continue; }
        a=std::min(a,1.f);
        for(unsigned c=0;c<3;++c) {float v=half_to_float(expected[4*i+c])/a;expected[4*i+c]=float_to_half(std::isfinite(v)&&v>0.f?v:0.f);}
        expected[4*i+3]=float_to_half(a);
    }
    unpremultiply_f16(original.data(),n);
    check(original==expected,"parallel unpremultiply changed pixels");
}

static void test_roundtrip(int gamut, bool hdr) {
    constexpr unsigned w=512,h=512;
    std::vector<uint16_t> input(w*h*4);
    const float peak=hdr?13.7f:1.f;
    for(size_t i=0;i<size_t(w)*h;++i) {
        const float a=i%3==0?0.f:i%3==1?0.5f:1.f;
        for(unsigned c=0;c<3;++c) input[4*i+c]=float_to_half((float((i+37*c)%1024)/1023.f)*peak*a);
        input[4*i+3]=float_to_half(a);
    }
    const char* path="/tmp/localviewer-still-roundtrip.jxl";
    auto start=std::chrono::steady_clock::now();
    check(encode_still_export(reinterpret_cast<const uint8_t*>(input.data()),input.size()*2,w,h,true,gamut,hdr?3:2,true,path)==0,"JXL encode failed");
    std::ifstream stream(path,std::ios::binary);
    std::vector<uint8_t> compressed((std::istreambuf_iterator<char>(stream)),{});
    std::vector<uint16_t> output;
    unsigned dw=0,dh=0;uhdr_color_gamut_t cg;bool declared=false;
    check(decode_jxl_to_linear_f16(compressed.data(),compressed.size(),output,dw,dh,&cg,&declared,nullptr,false)==0,"reader JXL decode failed");
    check(dw==w&&dh==h&&declared==hdr,"HDR flag or dimensions lost");
    check(cg==export_gamut(gamut),"working gamut lost");
    auto straight=input;unpremultiply_f16(straight.data(),w*h);
    float maxError=0.f; float worstA=0, worstB=0; size_t worst=0;
    for(size_t i=0;i<size_t(w)*h;++i) {
        check(output[4*i+3]==straight[4*i+3],"alpha precision lost");
        for(unsigned c=0;c<3;++c) {
            float a=half_to_float(straight[4*i+c]);float b=half_to_float(output[4*i+c]);
            float error=std::abs(a-b)/std::max(0.01f,a); if(error>maxError){maxError=error;worstA=a;worstB=b;worst=4*i+c;}
        }
    }
    std::printf("roundtrip gamut=%d hdr=%d worst=%zu expected=%.7f actual=%.7f maxError=%.7f\n",gamut,hdr,worst,worstA,worstB,maxError); std::fflush(stdout);
    check(maxError<0.0025f,"HDR luminance or color changed");
    std::printf("JXL %s gamut=%d roundtrip: %zu bytes, max relative error %.5f, %.1f ms\n",hdr?"HDR":"SDR",gamut,compressed.size(),maxError,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count());
    std::remove(path);
}

static void test_existing_encoded_profiles(int transfer) {
    const bool pq = transfer == 16, hlg = transfer == 18;
    const bool is_hdr = pq || hlg;
    auto encoder = JxlEncoderMake(nullptr);
    JxlBasicInfo info; JxlEncoderInitBasicInfo(&info);
    info.xsize=16; info.ysize=16; info.bits_per_sample=16;
    info.uses_original_profile=JXL_TRUE; info.intensity_target=pq?10000.f:hlg?1000.f:255.f;
    JxlColorEncoding color{};
    color.color_space=JXL_COLOR_SPACE_RGB; color.white_point=JXL_WHITE_POINT_D65;
    color.primaries=is_hdr?JXL_PRIMARIES_2100:JXL_PRIMARIES_P3;
    color.transfer_function=pq?JXL_TRANSFER_FUNCTION_PQ:hlg?JXL_TRANSFER_FUNCTION_HLG:JXL_TRANSFER_FUNCTION_SRGB;
    color.rendering_intent=JXL_RENDERING_INTENT_RELATIVE;
    check(JxlEncoderSetBasicInfo(encoder.get(),&info)==JXL_ENC_SUCCESS,"fixture basic info");
    check(JxlEncoderSetColorEncoding(encoder.get(),&color)==JXL_ENC_SUCCESS,"fixture color");
    auto* frame=JxlEncoderFrameSettingsCreate(encoder.get(),nullptr);
    check(JxlEncoderSetFrameLossless(frame,JXL_TRUE)==JXL_ENC_SUCCESS,"fixture lossless");
    JxlPixelFormat format{3,JXL_TYPE_UINT16,JXL_NATIVE_ENDIAN,0};
    std::vector<uint16_t> source(16*16*3);
    for(size_t i=0;i<source.size();++i)source[i]=static_cast<uint16_t>((i%3+1)*16384-1);
    check(JxlEncoderAddImageFrame(frame,&format,source.data(),source.size()*2)==JXL_ENC_SUCCESS,"fixture frame");
    JxlEncoderCloseInput(encoder.get());
    std::vector<uint8_t> bytes(65536);uint8_t* next=bytes.data();size_t available=bytes.size();
    check(JxlEncoderProcessOutput(encoder.get(),&next,&available)==JXL_ENC_SUCCESS,"fixture encode");
    bytes.resize(bytes.size()-available);
    std::vector<uint16_t> output;unsigned w=0,h=0;uhdr_color_gamut_t cg;bool hdr=false;
    check(decode_jxl_to_linear_f16(bytes.data(),bytes.size(),output,w,h,&cg,&hdr,nullptr,false)==0,"encoded-profile reader decode");
    check(hdr==is_hdr&&cg==(is_hdr?UHDR_CG_BT_2100:UHDR_CG_DISPLAY_P3),"encoded-profile metadata lost");
    float scene[3]{};
    for(unsigned c=0;c<3;++c) {
        const float code=source[c]/65535.f;
        scene[c]=code<=0.5f?code*code/3.f:(std::exp((code-0.55991073f)/0.17883277f)+0.28466892f)/12.f;
    }
    const float luminance=0.2627f*scene[0]+0.6780f*scene[1]+0.0593f*scene[2];
    for(unsigned c=0;c<3;++c) {
        float code=source[c]/65535.f;
        float expected=srgb_eotf(code);
        if(pq) {
            double p=std::pow(double(code),32.0/2523.0);
            expected=float(std::pow(std::max(p-3424.0/4096.0,0.0)/(2413.0/128.0-2392.0/128.0*p),16384.0/2610.0)*10000.0/203.0);
        }
        if(hlg) expected=scene[c]*std::pow(luminance,0.2f)*1000.f/203.f;
        std::printf("encoded profile transfer=%d channel=%u expected=%f actual=%f\n",transfer,c,expected,half_to_float(output[c])); std::fflush(stdout);
        check(std::abs(half_to_float(output[c])-expected)<0.003f*std::max(1.f,expected),"encoded source transfer applied incorrectly");
    }
}

int main() {
    test_lookup_and_alpha();
    test_existing_encoded_profiles(13);
    test_existing_encoded_profiles(16);
    test_existing_encoded_profiles(18);
    for(int gamut=0;gamut<3;++gamut) for(bool hdr:{false,true}) test_roundtrip(gamut,hdr);
    std::vector<uint16_t> opaque(64*64*4,0x3c00);
    for(int mode:{0,1}) {
        const char* path="/tmp/localviewer-still-export.jpg";
        check(encode_still_export(reinterpret_cast<const uint8_t*>(opaque.data()),opaque.size()*2,64,64,true,0,mode,true,path)==0,"JPEG/UHDR export failed");
        std::remove(path);
    }
    std::puts("All production still-export checks passed");
}
