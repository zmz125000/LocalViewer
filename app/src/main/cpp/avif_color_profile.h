#pragma once

#include <avif/avif.h>
#include "ultrahdr/icc.h"

// CICP-only SDR is not color managed by every platform decoder. Describe the
// encoded RGB codes with a matching ICC fallback; preserve custom source ICCs.
// ISO gain-map bases must remain CICP-only for libavif reconstruction. Gray
// images must not receive an RGB profile, and HDR keeps its CICP contract.
inline bool ensure_avif_sdr_profile(avifImage* image) {
    const auto cp = image->colorPrimaries;
    const auto tc = image->transferCharacteristics;
    if (image->icc.size || image->gainMap || image->yuvFormat == AVIF_PIXEL_FORMAT_YUV400 ||
        (cp != 1 && cp != 9 && cp != 12) || (tc != 13 && tc != 8)) return true;
    const auto gamut = cp == 9 ? UHDR_CG_BT_2100 : cp == 12 ? UHDR_CG_DISPLAY_P3 : UHDR_CG_BT_709;
    auto profile = ultrahdr::IccHelper::writeIccProfile(tc == 8 ? UHDR_CT_LINEAR : UHDR_CT_SRGB, gamut);
    if (!profile || profile->getLength() <= ultrahdr::kICCIdentifierSize) return false;
    // IccHelper supplies a JPEG APP2 payload; AVIF stores the bare ICC profile.
    return avifImageSetProfileICC(image,
        static_cast<const uint8_t*>(profile->getData()) + ultrahdr::kICCIdentifierSize,
        profile->getLength() - ultrahdr::kICCIdentifierSize) == AVIF_RESULT_OK;
}
