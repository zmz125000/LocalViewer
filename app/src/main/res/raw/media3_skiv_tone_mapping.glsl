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

highp vec3 pqOetf(highp vec3 linearColor);

// XYZ is display light in 10000-nit units.
highp vec3 skivMapXyz(highp vec3 xyz, highp float peakNits) {
#if SKIV_WCG
    const highp mat3 xyzToOutputRgb = mat3(
         2.493497, -0.829489,  0.035846,
        -0.931384,  1.762664, -0.076172,
        -0.402711,  0.023625,  0.956885);
#else
    const highp mat3 xyzToOutputRgb = XYZ_TO_RGB_BT709;
#endif
    const highp mat3 xyzToLms = mat3(
        0.3592, -0.1922, 0.0070,
        0.6976, 1.1004, 0.0749,
        -0.0358, 0.0755, 0.8434 );
    const highp mat3 lmsToXyz = mat3(
        2.070180057, 0.364988250, -0.049595542,
        -1.326456876, 0.680467363, -0.049421161,
        0.206616007, -0.045421753, 1.187995942 );
    const highp mat3 lmsToIctcp = mat3(
        0.5, 1.6137, 4.3780,
        0.5, -3.3234, -4.2455,
        0.0, 1.7097, -0.1325 );
    const highp mat3 ictcpToLms = mat3(
        1.0, 1.0, 1.0,
        0.008605146, -0.008605146, 0.560048860,
        0.111035604, -0.111035604, -0.320637470 );

    highp vec3 linearLms = max(xyzToLms * xyz, vec3(0.0));
#if SKIV_PIECEWISE
    const highp float outputNits = 203.0;
    const highp float shoulder = float(SKIV_SHOULDER_PERCENT) / 100.0;
    const highp float linearKnee = shoulder * outputNits / 10000.0;
    // ICtCp intensity averages PQ-encoded L and M. If both are below the
    // linear knee, their encoded average is below the PQ knee as well.
    if (max(linearLms.x, linearLms.y) <= linearKnee)
        return clamp(xyzToOutputRgb * xyz * (10000.0 / outputNits), 0.0, 1.0);
#else
    const highp float outputNits = 80.0;
#endif
    highp vec3 ictcp = lmsToIctcp * pqOetf(linearLms);
    highp float inputIntensity = ictcp.x;
#if SKIV_PIECEWISE
    highp float knee = pqOetf(vec3(linearKnee)).x;
    if (inputIntensity <= knee)
        return clamp(xyzToOutputRgb * xyz * (10000.0 / outputNits), 0.0, 1.0);
#endif
    highp float peak = pqOetf(vec3(peakNits / 10000.0)).x;
    highp float white = pqOetf(vec3(120.0 / 10000.0)).x;
    highp float reference = pow(inputIntensity, 1.18);
    highp float mapped = reference * (1.0 + white * inputIntensity / (peak * peak)) /
                                    (1.0 + inputIntensity / white);
#if SKIV_PIECEWISE
    highp float target = pqOetf(vec3(outputNits / 10000.0)).x;
    highp float lo = pow(knee, 1.18) * (1.0 + white * knee / (peak * peak)) / (1.0 + knee / white);
    highp float hi = pow(peak, 1.18) * (1.0 + white / peak) / (1.0 + peak / white);
    mapped = mix(knee, target, clamp((mapped - lo) / max(hi - lo, 1e-6), 0.0, 1.0));
    reference = inputIntensity;
#endif
    ictcp.x = mapped;
    ictcp.yz *= min(reference, mapped) / max(max(reference, mapped), 1e-6);
    linearLms = pqEotf(ictcpToLms * ictcp);
    return clamp(xyzToOutputRgb * (lmsToXyz * linearLms) * (10000.0 / outputNits), 0.0, 1.0);
}

// The Media3 sampler provides PQ display light in 10000-nit units or
// HLG scene light. Convert HLG using a 1000-nit reference display and gamma 1.2.
#ifndef SKIV_AUTO_TONE
highp vec3 skivToSdr(highp vec3 bt2020) {
    highp vec3 xyz = RGB_BT2020_TO_XYZ * bt2020;
    highp float peakNits = SKIV_PIECEWISE == 1 ? 2000.0 : 1000.0;
    if (uInputColorTransfer == COLOR_TRANSFER_HLG) {
        xyz *= 0.1 * pow(max(xyz.y, 0.0), 0.2);
        peakNits = 1000.0;
    }
    return skivMapXyz(xyz, peakNits);
}
#endif
