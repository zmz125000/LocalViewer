// SPDX-License-Identifier: GPL-3.0-only
// GLSL adaptation of MPC Video Renderer's HDR-to-SDR Metadata spline (adipose).
// https://github.com/zmz125000/VideoRenderer/blob/70811bc/Shaders/convert/hdr_tone_mapping_spline.hlsl

highp vec3 pqOetf(highp vec3 linearColor);

// Evaluate the PQ Hermite segment with a unit slope at the knee and zero
// slope at the white endpoint. All luminances are relative to 10,000 nits.
highp float mpcvrSpline(highp float linearLight, highp float knee,
                       highp float width, highp float endpoint, highp float white) {
    highp float encoded = pqOetf(vec3(linearLight)).x;
    if (encoded <= knee) {
        return linearLight;
    }
    highp float t = clamp((encoded - knee) / width, 0.0, 1.0);
    highp float blend = t * t * (3.0 - 2.0 * t);
    highp float mapped = mix(knee, endpoint, blend) + width * t * (1.0 - t) * (1.0 - t);
    return pqEotf(vec3(min(mapped, white))).x;
}

highp vec3 mpcvrToSdr(highp vec3 bt2020) {
    const highp float sdrWhite = 125.0 / 10000.0;
    const highp float peak = float(MPCVR_PEAK_NITS) / 10000.0;
    const highp float flatAt = 4000.0 / 10000.0;
    const highp vec3 luminance = vec3(0.2627, 0.6780, 0.0593);
    const highp mat3 toLms = mat3(
        0.412109375, 0.166748047, 0.024169922,
        0.523925781, 0.720458984, 0.075439453,
        0.063964844, 0.112792969, 0.900390625);
    const highp mat3 fromLms = mat3(
         3.436606694, -0.791329556, -0.025949900,
        -2.506452119,  1.983600452, -0.098913715,
         0.069845424, -0.192270896,  1.124863614);
    const highp mat3 toBt709 = XYZ_TO_RGB_BT709 * RGB_BT2020_TO_XYZ;

    if (uInputColorTransfer == COLOR_TRANSFER_HLG) {
        bt2020 *= 0.1 * pow(max(dot(luminance, bt2020), 0.0), 0.2);
    }
    highp float y = dot(luminance, bt2020);
    // The original test uses 1e-6 nits, equivalent to 1e-10 here.
    if (y <= 1e-10) {
        return clamp(toBt709 * bt2020 / sdrWhite, 0.0, 1.0);
    }

    highp float white = pqOetf(vec3(sdrWhite)).x;
    highp float knee = max(1.5 * white - 0.5, 0.0);
    highp float width = pqOetf(vec3(max(peak, flatAt))).x - knee;
    highp float endpoint = white;
#if MPCVR_PEAK_NITS < 4000
    // Solve for the endpoint that maps the actual content peak to SDR white.
    highp float t = (pqOetf(vec3(peak)).x - knee) / width;
    highp float blend = t * t * (3.0 - 2.0 * t);
    endpoint = (white - (1.0 - blend) * knee - width * t * (1.0 - t) * (1.0 - t)) / blend;
#endif

    highp float maximum = max(bt2020.r, max(bt2020.g, bt2020.b));
    highp vec3 encoded = pqOetf(bt2020);
    highp float encodedMax = max(encoded.r, max(encoded.g, encoded.b));
    highp float encodedMin = min(encoded.r, min(encoded.g, encoded.b));
    highp float saturation = clamp(1.0 - encodedMin / encodedMax, 0.0, 1.0);
    highp float level = y * pow(max(maximum / y, 1.0), saturation);
    highp vec3 scaled = bt2020 * (mpcvrSpline(level, knee, width, endpoint, white) / level);

    highp vec3 lms = max(toLms * bt2020, 0.0);
    highp vec3 channels = max(fromLms * vec3(
        mpcvrSpline(lms.x, knee, width, endpoint, white),
        mpcvrSpline(lms.y, knee, width, endpoint, white),
        mpcvrSpline(lms.z, knee, width, endpoint, white)), 0.0);

    highp vec3 scaled709 = toBt709 * scaled;
    highp float overshoot = max(max(scaled709.r, max(scaled709.g, scaled709.b)) / sdrWhite, 1.0);
    highp float lmsMix = 1.0 - (1.0 - 0.2003) * pow(overshoot, -2.73);
    highp vec3 mapped709 = toBt709 * max(mix(scaled, channels, lmsMix), 0.0);
    highp float mappedMax = max(mapped709.r, max(mapped709.g, mapped709.b));
    return clamp(mapped709 / max(sdrWhite, mappedMax), 0.0, 1.0);
}
