package com.paa.assistant.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class VoiceCalibrationTest {

    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** A speaker = a direction; samples = that direction plus noise. */
    private fun samples(base: FloatArray, n: Int, noise: Float, seed: Int): List<FloatArray> {
        val r = Random(seed)
        return List(n) { unit(FloatArray(base.size) { base[it] + noise * (r.nextFloat() - 0.5f) }) }
    }

    private val owner = unit(FloatArray(128) { Random(1).nextFloat() - 0.5f })
    private val other = unit(FloatArray(128) { Random(2).nextFloat() - 0.5f })

    @Test
    fun consistentOwnerHasNoOutlier() {
        assertNull(VoiceCalibration.outlier(samples(owner, 8, 0.05f, 3)))
    }

    @Test
    fun foreignSampleIsTheOutlier() {
        val s = samples(owner, 7, 0.05f, 4) + listOf(other)
        assertEquals(7, VoiceCalibration.outlier(s))
    }

    @Test
    fun thresholdSitsBelowOwnerAndAboveOthers() {
        val r = VoiceCalibration.calibrate(listOf(0.82, 0.85, 0.79), listOf(0.30, 0.41))
        assertNull(r.problem)
        assertEquals(0.71, r.threshold, 1e-9)  // 0.79 − 0.08
        assertTrue(r.threshold > 0.41)
    }

    @Test
    fun closeImpostorRaisesThresholdOrRefuses() {
        val raised = VoiceCalibration.calibrate(listOf(0.90, 0.92), listOf(0.80))
        assertNull(raised.problem)
        assertEquals(0.85, raised.threshold, 1e-9)  // 0.80 + 0.05
        val refused = VoiceCalibration.calibrate(listOf(0.80, 0.82), listOf(0.76))
        assertNotNull(refused.problem)
    }

    @Test
    fun inconsistentOwnerSamplesAreRefused() {
        assertNotNull(VoiceCalibration.calibrate(listOf(0.50, 0.70), emptyList()).problem)
    }

    @Test
    fun thresholdNeverBelowFloor() {
        assertEquals(VoiceCalibration.FLOOR, VoiceCalibration.calibrate(listOf(0.61, 0.62), emptyList()).threshold, 1e-9)
    }

    @Test
    fun recordingChecks() {
        assertNotNull(VoiceCalibration.recordingProblem(ShortArray(64000), 300))  // silence
        val loud = ShortArray(64000) { if (it % 2 == 0) 32767 else -32768 }
        assertNotNull(VoiceCalibration.recordingProblem(loud, 300))  // clipped
        val speech = ShortArray(64000) { ((it % 100) * 60 - 3000).toShort() }
        assertNull(VoiceCalibration.recordingProblem(speech, 300))
        assertNotNull(VoiceCalibration.recordingProblem(speech, 50))  // too little speech
    }
}
