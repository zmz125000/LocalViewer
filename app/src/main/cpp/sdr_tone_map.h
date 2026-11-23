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
template<class Pixels> inline RGB input_rgb(const Pixels& pixels,size_t at,bool nonnegative=false) {
    RGB rgb{};
    for(unsigned c=0;c<3;++c) {
        const float value=pixels[at+c];
        rgb[c]=(std::isfinite(value)?double(value):0.)*(203./80.);
        if(nonnegative)rgb[c]=std::max(rgb[c],0.);
    }
    return rgb;
}
struct AutoTone {
    float strength_ev = 0.f;
    float center_pq = .52f;
    float hi_pq = .78f;
    double lo_y = 0., hi_y = 0.; // scRGB, 80 nits per unit
    bool active() const { return strength_ev > 1e-4f; }
};
inline float smooth(float lo,float hi,float x) {
    const float t=std::clamp((x-lo)/(hi-lo),0.f,1.f);
    return t*t*(3.f-2.f*t);
}
inline double metering_pq(double y) {
    return y>0. ? pq(std::min(y,125.)) : 0.;
}
inline double auto_tone_gain(double y,const AutoTone& tone) {
    constexpr float lo_pq=.25f;
    if(!tone.active()||y<=tone.lo_y||y>=tone.hi_y)return 1.;
    const float p=float(metering_pq(y));
    const float weight=p<tone.center_pq?smooth(lo_pq,tone.center_pq,p):
        1.f-smooth(tone.center_pq,tone.hi_pq,p);
    return std::exp2(-tone.strength_ev*weight);
}
struct ToneAnalysis {
    double peak = 0.; // scRGB units, 80 nits per unit.
    AutoTone auto_tone;
};

struct ToneStats {
    double low=std::numeric_limits<double>::infinity(),high=-low;
    double clipped_low=low,clipped_high=high;
    double visible=0.,sum_pq=0.,hdr_area=0.,excess_pq=0.;
    uint64_t count=0;
};

// SKIV peak and JXRConverter's perceptual auto tone meter share the bounds scan.
template<class Pixels> inline ToneAnalysis analyze_tone(const Pixels& pixels,const Matrix& space,bool auto_curve) {
    const size_t count_pixels=pixels.size()/4;
    const unsigned jobs=hdr_pixel_jobs(count_pixels);
    std::vector<ToneStats> partials(jobs);
    const double shoulder_pq=pq(.5*203./80.);
    hdr_parallel_pixels(count_pixels,jobs,[&](size_t begin,size_t end,unsigned job) {
        auto& local=partials[job];
        for(size_t pixel=begin;pixel<end;++pixel) {
            const size_t i=pixel*4;
            const float alpha=pixels[i+3];
            if(!std::isfinite(alpha)||alpha<=0.f)continue;
            auto rgb=input_rgb(pixels,i);
            const double y=transform(space,rgb)[1];
            local.low=std::min(local.low,y);local.high=std::max(local.high,y);++local.count;
            if(!auto_curve)continue;
            for(auto& value:rgb)value=std::max(value,0.);
            const double luminance=transform(space,rgb)[1];
            const double p=metering_pq(luminance);
            const double weight=std::min(alpha,1.f);
            local.clipped_low=std::min(local.clipped_low,luminance);
            local.clipped_high=std::max(local.clipped_high,luminance);
            local.visible+=weight;
            local.sum_pq+=weight*p;
            if(luminance>203./80.)local.hdr_area+=weight;
            local.excess_pq+=weight*std::max(p-shoulder_pq,0.);
        }
    });
    ToneStats total;
    for(const auto& local:partials) {
        total.low=std::min(total.low,local.low);total.high=std::max(total.high,local.high);
        total.clipped_low=std::min(total.clipped_low,local.clipped_low);
        total.clipped_high=std::max(total.clipped_high,local.clipped_high);
        total.count+=local.count;
        total.visible+=local.visible;total.sum_pq+=local.sum_pq;
        total.hdr_area+=local.hdr_area;total.excess_pq+=local.excess_pq;
    }
    const uint64_t count=total.count;
    if(!count)return {};
    AutoTone tone;
    if(total.visible>0.) {
        const float mean_pq=float(total.sum_pq/total.visible);
        const float hdr_gate=smooth(.01f,.08f,float(total.hdr_area/total.visible));
        const float daylight_gate=smooth(.395f,.470f,mean_pq);
        const float pressure=std::sqrt(std::clamp(float(total.excess_pq/total.visible)/.085f,0.f,1.f));
        tone.strength_ev=1.20f*hdr_gate*daylight_gate*pressure;
        if(tone.active()) {
            tone.center_pq=std::clamp(.52f+.55f*(mean_pq-.43f),.52f,.60f);
            tone.hi_pq=std::clamp(tone.center_pq+.26f,.78f,.86f);
            tone.lo_y=inverse_pq(.25f);
            tone.hi_y=inverse_pq(tone.hi_pq);
        } else {
            tone.strength_ev=0.f;
        }
    }
    const double low=tone.active()?total.clipped_low:total.low;
    const double high=tone.active()?total.clipped_high:total.high;
    double peak=std::max(high,0.);
    if(high>low) {
        std::vector<uint64_t> histogram(65536);
        for(size_t i=0;i<pixels.size();i+=4)if(std::isfinite(pixels[i+3])&&pixels[i+3]>0.f) {
            const double y=std::max(transform(space,input_rgb(pixels,i,tone.active()))[1],0.);
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
    // The perceptual curve is monotonic, so adjust the percentile once.
    return {peak*auto_tone_gain(peak,tone),tone};
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
    const auto& tone=analysis.auto_tone;
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
        const auto rgb_input=input_rgb(source,i,tone.active());
        auto xyz=transform(space,rgb_input);
        const double gain=auto_tone_gain(xyz[1],tone);
        if(tone.active())for(auto& value:xyz)value*=gain;
        auto lms=transform(xyz_lms,xyz);for(auto& value:lms)value=pq(value);
        auto ictcp=transform(lms_ictcp,lms);const double input=std::max(ictcp[0],0.);
        if(custom&&input<=input_knee) {
            if(tone.active())for(unsigned c=0;c<3;++c)output[i+c]=float(rgb_input[c]*gain*(80./203.));
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
