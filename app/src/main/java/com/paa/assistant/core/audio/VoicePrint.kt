package com.paa.assistant.core.audio

import android.content.Context
import android.util.Log
import com.paa.assistant.data.db.DatabaseEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import kotlin.math.sqrt

/**
 * "Voice lock": PAA acts on "Oyee PA" only when it's the owner speaking.
 *
 * Vosk's speaker model (assets/model-spk) turns a few seconds of speech into a 128-number voiceprint.
 * The owner's average voiceprint is stored encrypted with the Keystore key; audio is never stored.
 * Tested on two synthetic voices: same speaker 0.67–0.89 similarity, other speaker 0.24–0.41.
 */
object VoicePrint {
    private const val TAG = "VoicePrint"
    private const val PREFS = "paa_voiceprint"
    private const val KEY_PRINT = "print"
    private const val KEY_ON = "lock_on"

    /** Similarity at or above this = the owner. Tune with the owner (see PAA_EXECUTION_PLAN.md V2). */
    const val THRESHOLD = 0.55

    /** Fewer frames than this (≈0.6 s of speech) can't be judged reliably. */
    const val MIN_FRAMES = 60

    @Volatile private var speakerModel: SpeakerModel? = null

    suspend fun speakerModel(context: Context): SpeakerModel? = speakerModel ?: withContext(Dispatchers.IO) {
        runCatching { SpeakerModel(StorageService.sync(context, "model-spk", "model-spk")) }
            .onFailure { Log.e(TAG, "Speaker model failed: ${it.javaClass.simpleName}") }
            .getOrNull()?.also { speakerModel = it }
    }

    /** Voiceprint of [pcm] (16 kHz mono) and how many frames it rests on, or null if there was no speech. */
    fun of(model: Model, spk: SpeakerModel, pcm: ShortArray): Pair<FloatArray, Int>? = runCatching {
        Recognizer(model, VoskEngine.SAMPLE_RATE, spk).use { r ->
            r.acceptWaveForm(pcm, pcm.size)
            val json = JSONObject(r.finalResult)
            val arr = json.optJSONArray("spk") ?: return@runCatching null
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }.normalized() to json.optInt("spk_frames")
        }
    }.getOrNull()

    fun similarity(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    fun average(prints: List<FloatArray>): FloatArray =
        FloatArray(prints.first().size) { i -> prints.map { it[i] }.average().toFloat() }.normalized()

    private fun FloatArray.normalized(): FloatArray {
        val n = sqrt(sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: return this
        return FloatArray(size) { this[it] / n }
    }

    fun save(context: Context, print: FloatArray) {
        prefs(context).edit()
            .putString(KEY_PRINT, DatabaseEncryption.encrypt(print.joinToString(",")))
            .putBoolean(KEY_ON, true).apply()
    }

    fun load(context: Context): FloatArray? = prefs(context).getString(KEY_PRINT, null)?.let { enc ->
        runCatching { DatabaseEncryption.decrypt(enc).split(",").map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false) && prefs(context).contains(KEY_PRINT)

    fun setOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ON, on).apply()

    fun forget(context: Context) = prefs(context).edit().clear().apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
