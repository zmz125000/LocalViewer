#pragma once
#include "hdr_encode.h"
#include "skcms.h"
#include <type_traits>
#include <algorithm>

// Float is the export working type. Half is retained for display/legacy APIs.
template<class T> inline T store_working(float v) {
    if constexpr(std::is_same_v<T,float>)return v;
    else return float_to_half(v);
}
template<class T> inline float read_working(T v) {
    if constexpr(std::is_same_v<T,float>)return v;
    else return half_to_float(v);
}
template<class T> constexpr skcms_PixelFormat working_format() {
    return std::is_same_v<T,float>?skcms_PixelFormat_RGBA_ffff:skcms_PixelFormat_RGBA_hhhh;
}
template<class T> inline float sanitize_working(float v) {
    if(!std::isfinite(v))return 0.f;
    if constexpr(std::is_same_v<T,float>)return v;
    else return std::clamp(v,0.f,kMaxLinear);
}
