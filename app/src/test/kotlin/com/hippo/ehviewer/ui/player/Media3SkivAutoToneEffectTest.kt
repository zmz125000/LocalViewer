package com.hippo.ehviewer.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SkivAutoToneEffectTest {
    @Test
    fun streamStateTracksActiveHlgAndIgnoresStaleOwnerRelease() {
        val state = Media3SkivAutoToneState()
        val pqOwner = Any()
        val hlgOwner = Any()

        assertNull(state.activeHlg)
        state.activate(pqOwner, hlg = false)
        assertEquals(false, state.activeHlg)

        state.activate(hlgOwner, hlg = true)
        assertEquals(true, state.activeHlg)

        state.deactivate(pqOwner)
        assertEquals(true, state.activeHlg)

        state.deactivate(hlgOwner)
        assertNull(state.activeHlg)
    }

    @Test
    fun effectAndMatrixEqualityDistinguishesPqFromHlg() {
        val state = Media3SkivAutoToneState()
        assertEquals(Media3SkivAutoToneEffect(false, state), Media3SkivAutoToneEffect(false, state))
        assertNotEquals(Media3SkivAutoToneEffect(false, state), Media3SkivAutoToneEffect(true, state))
        assertEquals(Media3SkivAutoToneMatrix(false), Media3SkivAutoToneMatrix(false))
        assertNotEquals(Media3SkivAutoToneMatrix(false), Media3SkivAutoToneMatrix(true))
    }

    @Test
    fun fusedShadersMatchMedia3FinalShaderInterface() {
        val rawDir = File("src/main/res/raw").takeIf(File::isDirectory) ?: File("app/src/main/res/raw")
        val autoTone = File(rawDir, "media3_skiv_auto_tone.glsl").readText()
        val skiv = File(rawDir, "media3_skiv_tone_mapping.glsl").readText()
        val displayP3 = File(rawDir, "media3_display_p3.glsl").readText()

        val vertex = buildSkivAutoToneVertexShader(autoTone)
        val pqFragment = buildSkivAutoToneFragmentShader(autoTone, skiv, "", shoulder = 75, wideColor = false, hlg = false)
        val hlgWcgFragment = buildSkivAutoToneFragmentShader(autoTone, skiv, displayP3, shoulder = 80, wideColor = true, hlg = true)

        assertTrue(vertex.startsWith("#version 300 es"))
        assertTrue("in vec4 aFramePosition;" in vertex)
        assertTrue("uniform mat4 uTransformationMatrix;" in vertex)
        assertTrue("uniform mat4 uTexTransformationMatrix;" in vertex)
        assertTrue("uniform highp sampler2D uTexSampler;" in vertex)
        assertTrue("out vec2 vTexSamplingCoord;" in vertex)
        assertTrue("flat out highp vec4 vAutoTone;" in vertex)

        assertTrue(pqFragment.startsWith("#version 300 es"))
        assertTrue("#define SKIV_PEAK_NITS 2000.0" in pqFragment)
        assertTrue("#define SKIV_WCG 0" in pqFragment)
        assertTrue("#define SKIV_SHOULDER_PERCENT 75" in pqFragment)
        assertTrue("uniform highp sampler2D uTexSampler;" in pqFragment)
        assertTrue("in vec2 vTexSamplingCoord;" in pqFragment)
        assertTrue("flat in highp vec4 vAutoTone;" in pqFragment)
        assertTrue("texture(uTexSampler, vTexSamplingCoord)" in pqFragment)
        assertFalse("uStats" in pqFragment)
        assertFalse("uFrame" in pqFragment)

        assertTrue("#define SKIV_PEAK_NITS 1000.0" in hlgWcgFragment)
        assertTrue("#define SKIV_WCG 1" in hlgWcgFragment)
        assertTrue("#define SKIV_SHOULDER_PERCENT 80" in hlgWcgFragment)
        assertTrue("displayP3Encode(" in hlgWcgFragment)
    }
}
