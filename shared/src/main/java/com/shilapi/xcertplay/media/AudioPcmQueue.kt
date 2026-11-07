package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Owned PCM chunks, bounded in audio duration. A blocked writer cannot hold a codec output buffer. */
internal class AudioPcmQueue(sampleRate: Int, channels: Int, budgetMillis: Int) {
    private val chunkBytes = (sampleRate / 100).coerceAtLeast(1) * channels * 2
    private val queue = LinkedBlockingQueue<ByteArray>((budgetMillis / 10).coerceAtLeast(1))
    val isEmpty: Boolean get() = queue.isEmpty()
    val size: Int get() = queue.size
    val pendingBytes: Int get() = queue.sumOf { it.size }

    fun put(data: ByteArray, offset: Int, length: Int, running: () -> Boolean) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        var position = offset
        while (position < offset + length && running()) {
            val end = minOf(position + chunkBytes, offset + length)
            val owned = data.copyOfRange(position, end)
            while (running() && !queue.offer(owned, 10, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
            position = end
        }
    }

    fun poll(millis: Long): ByteArray? = queue.poll(millis, TimeUnit.MILLISECONDS)
}
