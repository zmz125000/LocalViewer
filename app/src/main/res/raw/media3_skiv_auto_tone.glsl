#if SKIV_MEASURE
void main() {
    // A uniform 32 by 18 grid estimates the frame area above SDR white.
    // The result stays on the GPU and is consumed by the following draw.
    const ivec2 grid = ivec2(32, 18);
    highp vec2 size = vec2(textureSize(uFrame, 0));
    highp float count = 0.0;
    for (int y = 0; y < grid.y; ++y) {
        for (int x = 0; x < grid.x; ++x) {
            ivec2 at = ivec2((vec2(x, y) + 0.5) * size / vec2(grid));
            highp vec3 rgb = texelFetch(uFrame, at, 0).rgb;
            highp float luminance = dot(rgb, vec3(0.26270021, 0.67799807, 0.05930172));
            count += float(luminance > 0.0203 * SKIV_HDR_SCALE);
        }
    }
    highp float strength = min(count / (float(grid.x * grid.y) * 0.08), 1.0);
    outColor = vec4(strength, 0.0, 0.0, 1.0);
}
#else
uniform highp sampler2D uStats;
const highp mat3 RGB_BT2020_TO_XYZ =
    mat3(0.63695805, 0.26270021, 0.00000000, 0.14461690, 0.67799807,
         0.02807269, 0.16888098, 0.05930172, 1.06098506);
const highp mat3 XYZ_TO_RGB_BT709 =
    mat3(3.24096994, -0.96924364, 0.05563008, -1.53738318, 1.87596750,
         -0.20397696, -0.49861076, 0.04155506, 1.05697151);

const highp float M1 = 2610.0 / 16384.0;
const highp float M2 = 2523.0 / 32.0;
const highp float C1 = 3424.0 / 4096.0;
const highp float C2 = 2413.0 / 128.0;
const highp float C3 = 2392.0 / 128.0;

highp vec3 pqOetf(highp vec3 linearColor) {
    highp vec3 t = pow(linearColor, vec3(M1));
    return pow((C1 + C2 * t) / (1.0 + C3 * t), vec3(M2));
}

highp vec3 pqEotf(highp vec3 pqColor) {
    highp vec3 t = pow(clamp(pqColor, 0.0, 1.0), vec3(1.0 / M2));
    return pow(max(t - C1, 0.0) / (C2 - C3 * t), vec3(1.0 / M1));
}

highp vec3 skivMapXyz(highp vec3 xyz, highp float peakNits);
highp vec3 displayP3Encode(highp vec3 linearP3);

void main() {
    highp vec3 rgb = texelFetch(uFrame, ivec2(gl_FragCoord.xy), 0).rgb / SKIV_HDR_SCALE;
    highp vec3 xyz = RGB_BT2020_TO_XYZ * rgb;
    // Match the still-image auto curve. Concentrate the exposure adjustment in midtones.
    highp float y = xyz.y / 0.0203;
    highp float d = (y / (1.0 + y) - 0.33) / 0.14;
    highp float midtones = -0.72 * texelFetch(uStats, ivec2(0), 0).r;
    xyz *= exp2(midtones * exp(-0.5 * d * d));

    highp vec3 mapped = skivMapXyz(xyz, SKIV_PEAK_NITS);
#if SKIV_WCG
    outColor = vec4(displayP3Encode(mapped), 1.0);
#else
    outColor = vec4(pow(mapped, vec3(1.0 / 2.2)), 1.0);
#endif
}
#endif
