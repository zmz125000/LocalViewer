#pragma once
// Adapted from SKIV's SDR export path (src/utility/image.cpp and
// include/utility/image.h). Copyright (c) 2024 Aemony.
// Copyright 2024 Andon "Kaldaien" Coleman (tone_mapping.hlsli).
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
// THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.
#include "hdr_parallel.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace skiv {
using RGB=std::array<double,3>;
using Matrix=std::array<RGB,3>;
constexpr Matrix rgb709_xyz={{{.412390798330307,.357584327459335,.180480793118477},
    {.212639003992081,.715168654918671,.072192318737507},
    {.019330818206072,.119194783270359,.950532138347626}}};
constexpr Matrix rgb2020_xyz={{{.636958062648773,.144616901874542,.168880969285965},
    {.262700200080872,.677998065948486,.059301715344191},
    {0.,.028072692453861,1.060985088348389}}};
constexpr Matrix rgbp3_xyz={{{.486570948648216,.265667693169093,.198217285234363},
    {.228974564069749,.691738521836506,.079286914093745},
    {0.,.045113381858903,1.043944368900976}}};
constexpr Matrix xyz_lms={{{.3592,.6976,-.0358},{-.1922,1.1004,.0755},{.007,.0749,.8434}}};
constexpr Matrix lms_xyz={{{2.070180056695614,-1.326456876103021,.206616006847855},
    {.364988250032657,.680467362852235,-.045421753075853},
    {-.049595542238932,-.049421161186757,1.187995941732803}}};
constexpr Matrix xyz709={{{3.240969896316528,-1.537383198738098,-.498610764741898},
    {-.969243645668030,1.875967502593994,.041555058211088},
    {.055630080401897,-.203976958990097,1.056971549987793}}};
constexpr Matrix lms_ictcp={{{.5,.5,0.},{1.6137,-3.3234,1.7097},{4.378,-4.2455,-.1325}}};
constexpr Matrix ictcp_lms={{{1.,.00860514569398152,.11103560447547328},
    {1.,-.00860514569398152,-.11103560447547328},
    {1.,.560048859562639,-.3206374702321221}}};
inline RGB transform(const Matrix& m,const RGB& v) {
    return {m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2],
        m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2],
        m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]};
}
inline Matrix inverse(const Matrix& m) {
    const double a=m[0][0],b=m[0][1],c=m[0][2],d=m[1][0],e=m[1][1],f=m[1][2],g=m[2][0],h=m[2][1],i=m[2][2];
    const double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
    return {{{(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det},
        {(f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det},
        {(d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det}}};
}
// SKIV's intermediate scRGB scale is 80 nits per unit, with PQ peak 125.
inline double pq(double sc) {
    const double p=std::pow(std::max(sc,0.)/125.,.1593017578125);
    return std::pow((.8359375+18.8515625*p)/(1.+18.6875*p),78.84375);
}
inline double inverse_pq(double code) {
    const double p=std::pow(std::max(code,0.),1./78.84375);
    return 125.*std::pow(std::max(p-.8359375,0.)/std::max(18.8515625-18.6875*p,1e-12),1./.1593017578125);
}
inline double intensity(double input,double peak,double white) {
    return std::pow(std::max(input,0.),1.18)*(1.+white/(peak*peak)*input)/(1.+input/white);
}
template<class Pixels> inline RGB input_xyz(const Pixels& pixels,size_t at,const Matrix& space) {
    RGB rgb{};
    for(unsigned c=0;c<3;++c)rgb[c]=(std::isfinite(pixels[at+c])?double(pixels[at+c]):0.)*(203./80.);
    return transform(space,rgb);
}
inline double midtone_gain(double y,float midtones) {
    if(midtones==0.f)return 1.;
    const double linear=std::max(y,0.)*(80./203.);
    const double distance=(linear/(1.+linear)-.33)/.14;
    return std::exp2(midtones*std::exp(-.5*distance*distance));
}
struct ToneAnalysis {
    double peak = 0.; // scRGB units, 80 nits per unit.
    float midtones = 0.f;
};

// Match SKIV's 65536-bin, 99.94th-percentile luminance estimate. Measure
// visible HDR area in the same pass as the histogram bounds.
template<class Pixels> inline ToneAnalysis analyze_tone(const Pixels& pixels,const Matrix& space,bool auto_curve) {
    double low=std::numeric_limits<double>::infinity(),high=-low;
    uint64_t count=0;
    double visible=0.,hdr=0.;
    for(size_t i=0;i<pixels.size();i+=4) {
        const float alpha=pixels[i+3];
        if(!std::isfinite(alpha)||alpha<=0.f)continue;
        const double y=input_xyz(pixels,i,space)[1];
        low=std::min(low,y);high=std::max(high,y);++count;
        if(auto_curve) {
            const float weight=std::min(alpha,1.f);
            visible+=weight;
            if(y>203./80.)hdr+=weight;
        }
    }
    if(!count)return {};
    const float midtones=visible>0. ? -.72f*float(std::min(hdr/(visible*.08),1.)) : 0.f;
    double peak=std::max(high,0.);
    if(high>low) {
        std::vector<uint64_t> histogram(65536);
        for(size_t i=0;i<pixels.size();i+=4)if(std::isfinite(pixels[i+3])&&pixels[i+3]>0.f) {
            const double y=std::max(input_xyz(pixels,i,space)[1],0.);
            const auto bin=size_t(std::clamp(std::round((y-low)/(high-low)*65536.),0.,65535.));++histogram[bin];
        }
        double percent=100.;
        for(int bin=65535;bin>=0;--bin) {
            percent-=100.*double(histogram[size_t(bin)])/double(count);
            if(percent<=99.94) {
                peak=std::max(0.,low+(high-low)*double(bin)/65536.);
                break;
            }
        }
    }
    // The midtone adjustment is monotonic in luminance, so it can be applied
    // directly to the percentile once instead of evaluating it per bin or pixel.
    return {peak*midtone_gain(peak,midtones),midtones};
}
} // namespace skiv

// gamut: 0 BT.709, 1 Display P3, 2 BT.2020. Working pixels use 203-nit units.
// Stock follows SKIV export's 120-nit PQ target, 10000-nit cap and 1.18 power.
// Custom anchors that curve at the manual shoulder and 203-nit SDR white.
template<class Pixels> inline void tone_map_skiv_in_place(Pixels& hdr,int gamut,bool custom,float shoulder,float cap_nits,bool auto_curve=false) {
    using namespace skiv;
    const Matrix& space=gamut==2?rgb2020_xyz:gamut==1?rgbp3_xyz:rgb709_xyz;
    const Matrix from_xyz=inverse(space);
    const double cap=custom?std::clamp(double(cap_nits),204.,10000.):10000.;
    const auto analysis=analyze_tone(hdr,space,custom&&auto_curve);
    const float midtones=analysis.midtones;
    const double peak=std::min(analysis.peak,cap/80.);
    const double white=pq(1.5),peak_pq=std::max(white,pq(peak));
    const double knee=std::clamp(double(shoulder),0.,.99);
    const double input_knee=pq(knee*203./80.),output_white=pq(203./80.);
    const double custom_peak=std::max(peak_pq,output_white);
    const double curve_knee=intensity(input_knee,custom_peak,white),curve_peak=intensity(custom_peak,custom_peak,white);
    auto& output=hdr;
    const auto& source=hdr;
    const size_t pixels=hdr.size()/4;
    hdr_parallel_pixels(pixels,hdr_pixel_jobs(pixels),[&](size_t begin,size_t end,unsigned) {
    for(size_t pixel=begin;pixel<end;++pixel) {
        const size_t i=pixel*4;
        if(!std::isfinite(source[i+3])||source[i+3]<=0.f){output[i]=output[i+1]=output[i+2]=0.f;continue;}
        auto xyz=input_xyz(source,i,space);
        const double gain=midtone_gain(xyz[1],midtones);
        if(midtones!=0.f)for(auto& value:xyz)value*=gain;
        auto lms=transform(xyz_lms,xyz);for(auto& value:lms)value=pq(value);
        auto ictcp=transform(lms_ictcp,lms);const double input=std::max(ictcp[0],0.);
        if(custom&&input<=input_knee) {
            if(midtones!=0.f)for(unsigned c=0;c<3;++c) {
                const float value=source[i+c];
                output[i+c]=std::isfinite(value)?float(value*gain):0.f;
            }
            continue;
        }
        double reference,mapped;
        if(custom) {
            const double clipped=std::min(input,custom_peak);
            mapped=input_knee+(intensity(clipped,custom_peak,white)-curve_knee)*
                (output_white-input_knee)/std::max(curve_peak-curve_knee,1e-12);
            mapped=std::clamp(mapped,input_knee,output_white);reference=input;
        } else {mapped=intensity(input,peak_pq,white);reference=std::pow(input,1.18);}
        const double scale=reference>0.&&mapped>0.?std::min(reference/mapped,mapped/reference):0.;
        ictcp[0]=mapped;ictcp[1]*=scale;ictcp[2]*=scale;
        lms=transform(ictcp_lms,ictcp);for(auto& value:lms)value=inverse_pq(value);
        auto rgb=transform(xyz709,transform(lms_xyz,lms));
        for(auto& value:rgb)value=std::clamp(value*(custom?80./203.:1.),0.,1.);
        // SKIV clips in its SDR BT.709 space. Rematrix that result back to the
        // export gamut so encoders keep their existing, matching ICC/CICP tags.
        if(gamut)rgb=transform(from_xyz,transform(rgb709_xyz,rgb));
        for(unsigned c=0;c<3;++c)output[i+c]=float(std::clamp(rgb[c],0.,1.));
    }
    });
}

inline std::vector<float> tone_map_skiv_base(std::vector<float> hdr,int gamut,bool custom,float shoulder,float cap_nits,bool auto_curve=false) {
    tone_map_skiv_in_place(hdr,gamut,custom,shoulder,cap_nits,auto_curve);
    return hdr;
}
