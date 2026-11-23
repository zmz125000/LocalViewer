const highp float M1 = 2610.0 / 16384.0;
const highp float M2 = 2523.0 / 32.0;
const highp float C1 = 3424.0 / 4096.0;
const highp float C2 = 2413.0 / 128.0;
const highp float C3 = 2392.0 / 128.0;

highp vec3 pqOetf(highp vec3 linearColor) {
    highp vec3 t = pow(linearColor, vec3(M1));
    return pow((C1 + C2 * t) / (1.0 + C3 * t), vec3(M2));
}

#if SKIV_MEASURE
void main() {
    // Sample the same frame the curve will render, with no GPU readback.
    const ivec2 grid = ivec2(32, 18);
    highp vec2 size = vec2(textureSize(uFrame, 0));
    highp float hdrPixels = 0.0;
    highp float sumPq = 0.0;
    highp float excessPq = 0.0;
    highp float shoulderPq = pqOetf(vec3(0.5 * 0.0203)).x;
    for (int y = 0; y < grid.y; ++y) {
        for (int x = 0; x < grid.x; ++x) {
            ivec2 at = ivec2((vec2(x, y) + 0.5) * size / vec2(grid));
            highp vec3 rgb = max(texelFetch(uFrame, at, 0).rgb, 0.0) / SKIV_HDR_SCALE;
            highp float luminance = dot(rgb, vec3(0.26270021, 0.67799807, 0.05930172));
            highp float p = luminance > 0.0 ? pqOetf(vec3(min(luminance, 1.0))).x : 0.0;
            hdrPixels += float(luminance > 0.0203);
            sumPq += p;
            excessPq += max(p - shoulderPq, 0.0);
        }
    }
    const highp float count = float(grid.x * grid.y);
    highp float meanPq = sumPq / count;
    highp float hdrGate = smoothstep(0.01, 0.08, hdrPixels / count);
    highp float daylightGate = smoothstep(0.395, 0.470, meanPq);
    highp float pressure = sqrt(clamp(excessPq / (count * 0.085), 0.0, 1.0));
    highp float strength = 1.20 * hdrGate * daylightGate * pressure;
    highp float center = clamp(0.52 + 0.55 * (meanPq - 0.43), 0.52, 0.60);
    outColor = vec4(strength > 0.0001 ? strength : 0.0, center, 0.0, 1.0);
}
#else
uniform highp sampler2D uStats;
const highp mat3 RGB_BT2020_TO_XYZ =
    mat3(0.63695805, 0.26270021, 0.00000000, 0.14461690, 0.67799807,
         0.02807269, 0.16888098, 0.05930172, 1.06098506);
const highp mat3 XYZ_TO_RGB_BT709 =
    mat3(3.24096994, -0.96924364, 0.05563008, -1.53738318, 1.87596750,
         -0.20397696, -0.49861076, 0.04155506, 1.05697151);

highp vec3 pqEotf(highp vec3 pqColor) {
    highp vec3 t = pow(clamp(pqColor, 0.0, 1.0), vec3(1.0 / M2));
    return pow(max(t - C1, 0.0) / (C2 - C3 * t), vec3(1.0 / M1));
}

highp vec3 skivMapXyz(highp vec3 xyz, highp float peakNits);
highp vec3 displayP3Encode(highp vec3 linearP3);

void main() {
    highp vec3 rgb = texelFetch(uFrame, ivec2(gl_FragCoord.xy), 0).rgb / SKIV_HDR_SCALE;
    highp vec2 tone = texelFetch(uStats, ivec2(0), 0).rg;
    if (tone.x > 0.0) rgb = max(rgb, 0.0);
    highp vec3 xyz = RGB_BT2020_TO_XYZ * rgb;
    if (tone.x > 0.0) {
        highp float p = pqOetf(vec3(clamp(xyz.y, 0.0, 1.0))).x;
        highp float high = clamp(tone.y + 0.26, 0.78, 0.86);
        highp float weight = p < tone.y ? smoothstep(0.25, tone.y, p) : 1.0 - smoothstep(tone.y, high, p);
        xyz *= exp2(-tone.x * weight);
    }

    highp vec3 mapped = skivMapXyz(xyz, SKIV_PEAK_NITS);
#if SKIV_WCG
    outColor = vec4(displayP3Encode(mapped), 1.0);
#else
    outColor = vec4(pow(mapped, vec3(1.0 / 2.2)), 1.0);
#endif
}
#endif
