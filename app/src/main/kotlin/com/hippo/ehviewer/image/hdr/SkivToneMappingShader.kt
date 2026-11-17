package com.hippo.ehviewer.image.hdr

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Matches sdr_tone_map.h; only global constants are calculated on the CPU. */
@RequiresApi(33)
internal fun configureSkivShader(shader: RuntimeShader, controls: SkivGpuToneMapping) {
    fun pq(value: Double): Double {
        val p = (max(value, 0.0) / 125.0).pow(.1593017578125)
        return ((.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
    }
    fun intensity(input: Double, peak: Double, white: Double) = input.pow(1.18) *
        (1.0 + white / (peak * peak) * input) / (1.0 + input / white)
    val cap = if (controls.custom) controls.capNits.toDouble().coerceIn(204.0, 10000.0) else 10000.0
    val peak = min(controls.peakNits.toDouble().coerceAtLeast(0.0), cap) / 80.0
    val white = pq(1.5)
    val peakPq = max(white, pq(peak))
    val knee = pq(controls.shoulder.toDouble().coerceIn(0.0, .99) * 203.0 / 80.0)
    val outputWhite = pq(203.0 / 80.0)
    val customPeak = max(peakPq, outputWhite)
    shader.setIntUniform("customMode", if (controls.custom) 1 else 0)
    shader.setIntUniform("sourceGamut", controls.gamut)
    shader.setFloatUniform("curve", white.toFloat(), peakPq.toFloat(), knee.toFloat(), outputWhite.toFloat())
    shader.setFloatUniform(
        "customCurve",
        customPeak.toFloat(),
        intensity(knee, customPeak, white).toFloat(),
        intensity(customPeak, customPeak, white).toFloat(),
    )
}

// setInputBuffer bypasses Android's source-to-window transform. Decode pixels
// stay linear, premultiplied, in their native gamut until the mapping is done.
// fromLinearSrgb performs the final conversion into the destination Canvas space.
internal val skivShaderSource = """
    uniform shader contents;
    uniform float2 srcSize;
    uniform float2 dstSize;
    uniform float2 dstOrigin;
    uniform int customMode;
    uniform int sourceGamut;
    uniform float4 curve;
    uniform float3 customCurve;

    float3 sourceXyz(float3 v) {
        if (sourceGamut == 2) return float3(
            dot(v,float3(.6369580626,.1446169019,.1688809693)),
            dot(v,float3(.2627002001,.6779980659,.0593017153)),
            dot(v,float3(0.,.0280726925,1.0609850883)));
        if (sourceGamut == 1) return float3(
            dot(v,float3(.4865709486,.2656676932,.1982172852)),
            dot(v,float3(.2289745641,.6917385218,.0792869141)),
            dot(v,float3(0.,.0451133819,1.0439443689)));
        return float3(
            dot(v,float3(.4123907983,.3575843275,.1804807931)),
            dot(v,float3(.2126390040,.7151686549,.0721923187)),
            dot(v,float3(.0193308182,.1191947833,.9505321383)));
    }
    float3 to709(float3 xyz) {
        return float3(dot(xyz,float3(3.2409698963,-1.5373831987,-.4986107647)),
            dot(xyz,float3(-.9692436457,1.8759675026,.0415550582)),
            dot(xyz,float3(.0556300804,-.2039769590,1.0569715500)));
    }
    float3 pq(float3 v) {
        float3 p = pow(max(v, float3(0.)) / 125., float3(.1593017578125));
        return pow((.8359375 + 18.8515625*p)/(1.+18.6875*p), float3(78.84375));
    }
    float3 inversePq(float3 v) {
        float3 p = pow(max(v,float3(0.)),float3(1./78.84375));
        return 125.*pow(max(p-.8359375,float3(0.))/max(18.8515625-18.6875*p,float3(1.e-12)),float3(1./.1593017578125));
    }
    float intensity(float v, float peak) {
        return pow(max(v,0.),1.18)*(1.+curve.x/(peak*peak)*v)/(1.+v/curve.x);
    }
    float4 main(float2 position) {
        float4 pixel = contents.eval((position + dstOrigin) * srcSize / dstSize);
        float alpha = pixel.a;
        if (alpha <= 0.) return float4(0.);
        float3 xyz = sourceXyz(pixel.rgb / alpha);
        float3 lms = pq(float3(
            dot(xyz,float3(.3592,.6976,-.0358)),
            dot(xyz,float3(-.1922,1.1004,.0755)),
            dot(xyz,float3(.007,.0749,.8434))) * (203./80.));
        float3 ictcp = float3(.5*lms.x+.5*lms.y,
            dot(lms,float3(1.6137,-3.3234,1.7097)),
            dot(lms,float3(4.378,-4.2455,-.1325)));
        float inputValue = max(ictcp.x,0.);
        if (customMode == 1 && inputValue <= curve.z)
            return float4(fromLinearSrgb(to709(xyz))*alpha,alpha);
        float mapped;
        float reference;
        if (customMode == 1) {
            mapped = curve.z + (intensity(min(inputValue,customCurve.x),customCurve.x)-customCurve.y)
                * (curve.w-curve.z) / max(customCurve.z-customCurve.y,1.e-12);
            mapped = clamp(mapped,curve.z,curve.w);
            reference = inputValue;
        } else {
            mapped = intensity(inputValue,curve.y);
            reference = pow(inputValue,1.18);
        }
        float scale = reference > 0. && mapped > 0. ? min(reference/mapped,mapped/reference) : 0.;
        ictcp = float3(mapped,ictcp.yz*scale);
        lms = inversePq(float3(
            dot(ictcp,float3(1.,.0086051457,.1110356045)),
            dot(ictcp,float3(1.,-.0086051457,-.1110356045)),
            dot(ictcp,float3(1.,.5600488596,-.3206374702))));
        xyz = float3(dot(lms,float3(2.0701800567,-1.3264568761,.2066160068)),
            dot(lms,float3(.3649882500,.6804673629,-.0454217531)),
            dot(lms,float3(-.0495955422,-.0494211612,1.1879959417)));
        float3 rgb = clamp(to709(xyz) * (customMode == 1 ? 80./203. : 1.),float3(0.),float3(1.));
        return float4(fromLinearSrgb(rgb)*alpha,alpha);
    }
""".trimIndent()
