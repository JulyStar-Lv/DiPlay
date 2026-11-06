package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue

/** Interactive speech should catch up after a stalled renderer, without trimming a short radio burst. */
internal object RealtimeAudioBacklog {
    fun <T> trim(queue: LinkedBlockingQueue<T>, newest: Int, clockRate: Int, sample: (T) -> Int): Int {
        fun millis(packet: T): Long = (newest - sample(packet)).toLong() * 1000 / clockRate.coerceAtLeast(1)
        val first = queue.peek() ?: return 0
        if (millis(first) <= 200) return 0
        var removed = 0
        while (true) {
            val oldest = queue.peek() ?: break
            if (millis(oldest) <= 80) break
            if (queue.remove(oldest)) removed++
        }
        return removed
    }
}
