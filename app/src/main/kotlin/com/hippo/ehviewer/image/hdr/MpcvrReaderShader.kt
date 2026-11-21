// SPDX-License-Identifier: GPL-3.0-only
// AGSL adaptation of MPC Video Renderer's HDR-to-SDR Metadata spline (adipose).
// https://github.com/zmz125000/VideoRenderer/blob/70811bc/Shaders/convert/hdr_tone_mapping_spline.hlsl

package com.hippo.ehviewer.image.hdr

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import kotlin.math.pow

/** Prepare the same peak-dependent spline used by the native reader mapper. */
@RequiresApi(33)
internal fun configureMpcvrReaderShader(shader: RuntimeShader, controls: ReaderGpuToneMapping) {
    fun pq(nits: Double): Double {
        val p = (nits / 10000.0).pow(.1593017578125)
        return ((.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
    }

    val peak = controls.peakNits.toDouble().coerceIn(204.0, 10000.0)
    val white = pq(203.0)
    val knee = (1.5 * white - 0.5).coerceAtLeast(0.0)
    val width = pq(maxOf(peak, 4000.0)) - knee
    val endpoint = if (peak < 4000.0) {
        val t = (pq(peak) - knee) / width
        val blend = t * t * (3.0 - 2.0 * t)
        (white - (1.0 - blend) * knee - width * t * (1.0 - t) * (1.0 - t)) / blend
    } else {
        white
    }
    shader.setIntUniform("sourceGamut", controls.gamut)
    shader.setFloatUniform("curve", knee.toFloat(), width.toFloat(), endpoint.toFloat(), white.toFloat())
}

internal val mpcvrReaderShaderSource = """
    uniform shader contents;
    uniform float2 srcSize;
    uniform float2 dstSize;
    uniform float2 dstOrigin;
    uniform int sourceGamut;
    uniform float4 curve;

    const float3x3 rgb709To2020 = float3x3(
        .6274038774, .0690973055, .0163914375,
        .3292830183, .9195403810, .0880133095,
        .0433130905, .0113623167, .8955952143);
    const float3x3 p3To2020 = float3x3(
        .7538330126, .0457438711, -.0012103409,
        .1985973644, .9417772329, .0176017170,
        .0475696124, .0124789302, .9836085947);
    const float3x3 rgb2020To709 = float3x3(
        1.6604910666, -.1245505105, -.0181507591,
        -.5876411295, 1.1328999243, -.1005789082,
        -.0728499150, -.0083494195, 1.1187297106);
    const float3x3 rgb2020ToP3 = float3x3(
        1.3435782995, -.0652974857, .0028217887,
        -.2821796694, 1.0757879074, -.0195984947,
        -.0613986080, -.0104904594, 1.0167767365);
    const float3x3 p3To709 = float3x3(
        1.2249401763, -.0420569528, -.0196375543,
        -.2249401710, 1.0420569886, -.0786360550,
        0., 0., 1.0982736167);
    const float3x3 toLms = float3x3(
        .412109375, .166748047, .024169922,
        .523925781, .720458984, .075439453,
        .063964844, .112792969, .900390625);
    const float3x3 fromLms = float3x3(
        3.436606694, -.791329556, -.025949900,
        -2.506452119, 1.983600452, -.098913715,
        .069845424, -.192270896, 1.124863614);

    $readerPqShaderFunctions

    float maxChannel(float3 rgb) {
        return max(rgb.r, max(rgb.g, rgb.b));
    }
    float3 toSourceGamut(float3 rgb) {
        if (sourceGamut == 2) return rgb;
        if (sourceGamut == 1) return rgb2020ToP3 * rgb;
        return rgb2020To709 * rgb;
    }
    // Reader F16 uses linear 203-nit units; the shared PQ functions use 80-nit units.
    float spline(float value) {
        float encoded = pq(float3(value * (203. / 80.))).r;
        if (encoded <= curve.x) return value;
        float t = clamp((encoded - curve.x) / curve.y, 0., 1.);
        float blend = t * t * (3. - 2. * t);
        float mapped = mix(curve.x, curve.z, blend) + curve.y * t * (1. - t) * (1. - t);
        return inversePq(float3(min(mapped, curve.w))).r * (80. / 203.);
    }
    float4 main(float2 position) {
        float4 pixel = contents.eval((position + dstOrigin) * srcSize / dstSize);
        float alpha = pixel.a;
        if (alpha <= 0.) return float4(0.);
        float3 rgb = pixel.rgb / alpha;
        if (sourceGamut == 1) rgb = p3To2020 * rgb;
        else if (sourceGamut == 0) rgb = rgb709To2020 * rgb;
        rgb = clamp(rgb, float3(0.), float3(10000. / 203.));

        float y = dot(rgb, float3(.2627, .6780, .0593));
        if (y > 1.e-6 / 203.) {
            float3 encoded = pq(rgb * (203. / 80.));
            float saturation = clamp(1. - min(encoded.r, min(encoded.g, encoded.b)) / maxChannel(encoded), 0., 1.);
            float level = y * pow(max(maxChannel(rgb) / y, 1.), saturation);
            float3 scaled = rgb * (spline(level) / level);
            float3 lms = max(toLms * rgb, float3(0.));
            float3 channels = max(fromLms * float3(spline(lms.x), spline(lms.y), spline(lms.z)), float3(0.));
            float overshoot = max(maxChannel(toSourceGamut(scaled)), 1.);
            rgb = mix(scaled, channels, 1. - (1. - .2003) * pow(overshoot, -2.73));
        }

        rgb = toSourceGamut(rgb);
        rgb = clamp(rgb / max(1., maxChannel(rgb)), float3(0.), float3(1.));
        if (sourceGamut == 2) rgb = rgb2020To709 * rgb;
        else if (sourceGamut == 1) rgb = p3To709 * rgb;
        return float4(fromLinearSrgb(rgb) * alpha, alpha);
    }
""".trimIndent()
