package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

/** Budgets describe the use of the sound, independently of its Android output route. */
internal data class AudioPlayoutPolicy(
    val reorderMillis: Int,
    val maxConcealMillis: Int,
    val pcmQueueMillis: Int,
    val tailMillis: Int,
    val music: Boolean,
    val startupFloorMillis: Int = 0,
) {
    /** Silence is only a heuristic for music EOF: an intact Wi-Fi batch can arrive >500 ms late. */
    fun idleStartMillis(startMillis: Long): Long = if (music) maxOf(1_500L, startMillis * 2L)
        else tailMillis.toLong()

    fun trackPlan(sampleRate: Int, channels: Int, minBytes: Int): MediaAudioBuffer.Plan {
        val frame = channels * 2
        val twentyMillis = (sampleRate / 50) * frame
        val start = maxOf(frame, minOf(minBytes, twentyMillis),
            (sampleRate.toLong() * startupFloorMillis / 1000).toInt() * frame)
        return MediaAudioBuffer.Plan(maxOf(minBytes * 2, twentyMillis * 2, start * 2), start)
    }

    companion object {
        fun forStream(format: AudioFormat, mediaRoute: Boolean): AudioPlayoutPolicy =
            when (format.audioType.lowercase()) {
                "telephony" -> AudioPlayoutPolicy(20, 60, 40, 20, false)
                "speechrecognition" -> AudioPlayoutPolicy(40, 80, 40, 30, false)
                "default", "alert" -> AudioPlayoutPolicy(60, 120, 60, 40, false, startupFloorMillis = 60)
                else -> if (mediaRoute) AudioPlayoutPolicy(60, 120, 40, 500, true)
                    else AudioPlayoutPolicy(60, 120, 60, 40, false)
            }
    }
}
