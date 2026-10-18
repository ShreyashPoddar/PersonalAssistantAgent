package com.paa.assistant.core.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure checks for voice-lock enrolment: is a recording usable, do the owner's samples agree, and where to put the
 * accept/reject line between the owner's scores and other people's.
 */
object VoiceCalibration {
    /** A 4 s sentence should give well over this (≈1.5 s of actual speech). */
    const val ENROL_MIN_FRAMES = 150

    /** Each owner sample must match the average of the others at least this well. */
    const val MIN_AGREEMENT = 0.60

    /** Never accept below this, whatever the samples say. */
    const val FLOOR = 0.55
    const val CEILING = 0.85

    /** Room below the owner's weakest sample, so the owner on a bad day still gets in. */
    const val OWNER_MARGIN = 0.08

    /** Room above the best impostor score. */
    const val IMPOSTOR_MARGIN = 0.05

    /** Why a recording can't be used, or null if it can. */
    fun recordingProblem(pcm: ShortArray, frames: Int): String? {
        if (pcm.isEmpty()) return "Nothing was recorded — check the microphone permission."
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        val clipped = pcm.count { abs(it.toInt()) >= 32000 }.toDouble() / pcm.size
        return when {
            rms < 300 -> "Too quiet — hold the phone like a call and speak normally."
            clipped > 0.01 -> "Too loud or too close — the sound was distorted. Hold it a little further away."
            frames < ENROL_MIN_FRAMES -> "Didn't hear enough speech — read the whole sentence without pausing."
            else -> null
        }
    }

    /** Each sample's similarity to the average of all the others. */
    fun leaveOneOut(prints: List<FloatArray>): List<Double> = prints.indices.map { i ->
        VoicePrint.similarity(prints[i], VoicePrint.average(prints.filterIndexed { j, _ -> j != i }))
    }

    /** Index of the sample that disagrees with the rest, or null if all agree. */
    fun outlier(prints: List<FloatArray>): Int? {
        if (prints.size < 3) return null
        val loo = leaveOneOut(prints)
        val worst = loo.indices.minByOrNull { loo[it] } ?: return null
        return worst.takeIf { loo[it] < MIN_AGREEMENT }
    }

    data class Result(val threshold: Double, val ownerMin: Double, val impostorMax: Double?, val problem: String?)

    /**
     * Threshold from the owner's leave-one-out scores and (optional) other people's scores against the owner.
     * [problem] is set when the voice lock would not be safe or reliable.
     */
    fun calibrate(ownerScores: List<Double>, impostorScores: List<Double>): Result {
        val ownerMin = ownerScores.minOrNull() ?: return Result(FLOOR, 0.0, null, "No samples.")
        val impostorMax = impostorScores.maxOrNull()
        var t = (ownerMin - OWNER_MARGIN).coerceIn(FLOOR, CEILING)
        if (impostorMax != null) t = maxOf(t, impostorMax + IMPOSTOR_MARGIN).coerceAtMost(CEILING)
        val problem = when {
            ownerMin < MIN_AGREEMENT -> "Your samples don't agree well enough — record again in a quiet room."
            impostorMax != null && impostorMax + IMPOSTOR_MARGIN > ownerMin - 0.02 ->
                "Someone else scored too close to you (%.2f vs your %.2f) — the voice lock can't tell you apart reliably."
                    .format(impostorMax, ownerMin)
            else -> null
        }
        return Result(t, ownerMin, impostorMax, problem)
    }
}
