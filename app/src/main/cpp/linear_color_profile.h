#pragma once

#include "skcms.h"

// Shared ICC destination for arbitrary RGB profiles. A wide linear working
// space avoids clipping Adobe RGB / P3 colors at an intermediate sRGB boundary.
inline bool make_linear_bt2020_profile(skcms_ICCProfile* profile) {
    skcms_Init(profile);
    skcms_SetTransferFunction(profile, skcms_Identity_TransferFunction());
    skcms_Matrix3x3 matrix{};
    if (!skcms_PrimariesToXYZD50(0.708f, 0.292f, 0.170f, 0.797f, 0.131f,
                                0.046f, 0.3127f, 0.3290f, &matrix)) return false;
    skcms_SetXYZD50(profile, &matrix);
    return skcms_MakeUsableAsDestination(profile);
}
