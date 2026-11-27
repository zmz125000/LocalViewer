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
#include "pq_transfer.h"
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
constexpr Matrix multiply(const Matrix& a,const Matrix& b) {
    Matrix result{};
    for(unsigned row=0;row<3;++row)for(unsigned col=0;col<3;++col)
        result[row][col]=a[row][0]*b[0][col]+a[row][1]*b[1][col]+a[row][2]*b[2][col];
    return result;
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
    return pq_transfer::encode(sc*(1./125.));
}
inline double inverse_pq(double code) {
    return 125.*pq_transfer::decode(code);
}
inline double pow118(double input) {
    if(!(input>0.))return 0.;
    if(input>=1.)return std::pow(input,1.18);
    constexpr unsigned steps=2048;
    static const auto table=[] {
        std::array<float,steps+1> values{};
        for(unsigned i=0;i<=steps;++i) {
            const double x=double(i)/steps;
            values[i]=float(std::pow(x*x,1.18));
        }
        return values;
    }();
    const double position=std::sqrt(input)*steps;
    const auto index=std::min(unsigned(position),steps-1);
    return table[index]+(table[index+1]-table[index])*(position-index);
}
inline double intensity(double input,double peak,double white) {
    return pow118(input)*(1.+white/(peak*peak)*input)/(1.+input/white);
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
inline double luminance(const Matrix& space,const RGB& rgb) {
    const auto& y=space[1];
    return y[0]*rgb[0]+y[1]*rgb[1]+y[2]*rgb[2];
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
            const double y=luminance(space,rgb);
            local.low=std::min(local.low,y);local.high=std::max(local.high,y);++local.count;
            if(!auto_curve)continue;
            for(auto& value:rgb)value=std::max(value,0.);
            const double clipped_y=luminance(space,rgb);
            const double p=metering_pq(clipped_y);
            const double weight=std::min(alpha,1.f);
            local.clipped_low=std::min(local.clipped_low,clipped_y);
            local.clipped_high=std::max(local.clipped_high,clipped_y);
            local.visible+=weight;
            local.sum_pq+=weight*p;
            if(clipped_y>203./80.)local.hdr_area+=weight;
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
        constexpr unsigned bins=65536;
        const bool active=tone.active();
        std::vector<uint64_t> histograms(size_t(bins)*jobs);
        hdr_parallel_pixels(count_pixels,jobs,[&](size_t begin,size_t end,unsigned job) {
            auto* histogram=histograms.data()+size_t(bins)*job;
            for(size_t i=begin*4;i<end*4;i+=4) {
                if(!std::isfinite(pixels[i+3])||pixels[i+3]<=0.f)continue;
                const double y=std::max(luminance(space,input_rgb(pixels,i,active)),0.);
                const auto bin=size_t(std::clamp(std::round((y-low)/(high-low)*bins),0.,double(bins-1)));
                ++histogram[bin];
            }
        });
        double percent=100.;
        for(int bin=bins-1;bin>=0;--bin) {
            uint64_t frequency=0;
            for(unsigned job=0;job<jobs;++job)frequency+=histograms[size_t(bins)*job+bin];
            percent-=100.*double(frequency)/double(count);
            if(percent<=99.94) {
                peak=std::max(0.,low+(high-low)*double(bin)/bins);
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
    const Matrix to_lms=multiply(xyz_lms,space);
    constexpr Matrix lms_to_rgb709=multiply(xyz709,lms_xyz);
    const Matrix rgb709_to_gamut=gamut?multiply(inverse(space),rgb709_xyz):Matrix{};
    const double cap=custom?std::clamp(double(cap_nits),204.,10000.):10000.;
    const auto analysis=analyze_tone(hdr,space,custom&&auto_curve);
    const auto& tone=analysis.auto_tone;
    const double peak=std::min(analysis.peak,cap/80.);
    const double white=pq(1.5),peak_pq=std::max(white,pq(peak));
    const double knee=std::clamp(double(shoulder),0.,.99);
    const double input_knee=pq(knee*203./80.),output_white=pq(203./80.);
    const double custom_peak=std::max(peak_pq,output_white);
    const double curve_knee=intensity(input_knee,custom_peak,white),curve_peak=intensity(custom_peak,custom_peak,white);
    const double curve_scale=(output_white-input_knee)/std::max(curve_peak-curve_knee,1e-12);
    const double peak_factor=custom?white/(custom_peak*custom_peak):white/(peak_pq*peak_pq);
    const double inverse_white=1./white;
    const double output_scale=custom?80./203.:1.;
    auto& output=hdr;
    const auto& source=hdr;
    const size_t pixels=hdr.size()/4;
    hdr_parallel_pixels(pixels,hdr_pixel_jobs(pixels),[&](size_t begin,size_t end,unsigned) {
    for(size_t pixel=begin;pixel<end;++pixel) {
        const size_t i=pixel*4;
        if(!std::isfinite(source[i+3])||source[i+3]<=0.f){output[i]=output[i+1]=output[i+2]=0.f;continue;}
        const auto rgb_input=input_rgb(source,i,tone.active());
        const double gain=tone.active()?auto_tone_gain(luminance(space,rgb_input),tone):1.;
        auto lms=transform(to_lms,rgb_input);
        for(auto& value:lms)value=pq(value*gain);
        auto ictcp=transform(lms_ictcp,lms);const double input=std::max(ictcp[0],0.);
        if(custom&&input<=input_knee) {
            if(tone.active())for(unsigned c=0;c<3;++c)output[i+c]=float(rgb_input[c]*gain*(80./203.));
            continue;
        }
        const double curve_input=custom?std::min(input,custom_peak):input;
        const double powered=pow118(curve_input);
        const double curve_value=powered*(1.+peak_factor*curve_input)/(1.+curve_input*inverse_white);
        double reference=powered,mapped=curve_value;
        if(custom) {
            mapped=std::clamp(input_knee+(curve_value-curve_knee)*curve_scale,input_knee,output_white);
            reference=input;
        }
        const double scale=reference>0.&&mapped>0.?std::min(reference/mapped,mapped/reference):0.;
        ictcp[0]=mapped;ictcp[1]*=scale;ictcp[2]*=scale;
        lms=transform(ictcp_lms,ictcp);for(auto& value:lms)value=inverse_pq(value);
        auto rgb=transform(lms_to_rgb709,lms);
        for(auto& value:rgb)value=std::clamp(value*output_scale,0.,1.);
        // SKIV clips in its SDR BT.709 space. Rematrix that result back to the
        // export gamut so encoders keep their existing, matching ICC/CICP tags.
        if(gamut)rgb=transform(rgb709_to_gamut,rgb);
        for(unsigned c=0;c<3;++c)output[i+c]=float(std::clamp(rgb[c],0.,1.));
    }
    });
}

inline std::vector<float> tone_map_skiv_base(std::vector<float> hdr,int gamut,bool custom,float shoulder,float cap_nits,bool auto_curve=false) {
    tone_map_skiv_in_place(hdr,gamut,custom,shoulder,cap_nits,auto_curve);
    return hdr;
}
