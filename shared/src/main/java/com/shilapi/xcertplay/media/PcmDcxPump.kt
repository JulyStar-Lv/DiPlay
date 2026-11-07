package com.shilapi.xcertplay.media

import java.io.Closeable
import java.nio.ByteBuffer

/** One decode worker owns this pump. Closing discards audio; idle completion drains the song tail. */
internal class PcmDcxPump(
    private val port: Port,
    private val running: () -> Boolean,
    private val onPcm: (ByteArray) -> Unit,
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    data class Format(val rate: Int, val channels: Int, val encoding: Int, val mask: Int) {
        fun validate() { check(this == EXPECTED) { "PCM DCX: unsupported negotiated format $this" } }
    }
    data class Input(val index: Int, val bytes: ByteBuffer)
    data class Output(val bytes: ByteArray, val format: Format, val timeUs: Long, val eos: Boolean = false)
    interface Port : Closeable {
        fun input(timeoutUs: Long): Input?
        fun queue(input: Input, size: Int, timeUs: Long, eos: Boolean = false)
        fun output(timeoutUs: Long): Output?
        fun flush()
    }

    // Collect independently of the vendor submission limit enforced in sendPending().
    private val pending = ByteArray(1536 * FRAME_BYTES)
    private var pendingSize = 0
    private var pendingTimeUs = 0L
    private var nextTimeUs = 0L
    private var lastSubmitNs = 0L
    private var lastProgressNs: Long? = null
    private var lastReportNs = nanoTime()
    private var ending = false
    private var ended = false
    private var closed = false
    private var firstOutput = true
    private var inputFrames = 0L
    private var outputFrames = 0L

    fun submit(bytes: ByteArray, timeUs: Long) {
        active()
        require(bytes.size % FRAME_BYTES == 0) { "PCM DCX: incomplete input frame" }
        if (bytes.isEmpty()) return
        if (ended) {
            port.flush()
            ending = false; ended = false; lastProgressNs = null
            report("PCM DCX: resumed after EOS; codec flushed")
        }
        lastSubmitNs = nanoTime()
        var offset = 0
        while (offset < bytes.size) {
            active()
            if (pendingSize == 0) pendingTimeUs = timeUs + offset / FRAME_BYTES * 1_000_000L / RATE
            val count = minOf(bytes.size - offset, pending.size - pendingSize)
            bytes.copyInto(pending, pendingSize, offset, offset + count)
            pendingSize += count; offset += count
            if (pendingSize == pending.size) sendPending()
        }
        checkProgress()
    }

    /** Called even when the input queue is empty, so asynchronous output and short tails cannot stick. */
    fun poll() {
        active()
        val idleNs = nanoTime() - lastSubmitNs
        if (pendingSize > 0 && idleNs >= 50_000_000L) sendPending()
        drain()
        if (!ended && lastProgressNs != null && idleNs >= 1_000_000_000L) finish()
        checkProgress()
        if (nanoTime() - lastReportNs >= 5_000_000_000L) {
            report("PCM DCX: stats inputFrames=$inputFrames outputFrames=$outputFrames pendingFrames=${pendingSize / FRAME_BYTES} eos=$ended")
            lastReportNs = nanoTime()
        }
    }

    private fun sendPending() {
        var offset = 0
        while (offset < pendingSize) {
            val input = awaitInput()
            active()
            input.bytes.clear()
            val capacity = input.bytes.remaining() / FRAME_BYTES * FRAME_BYTES
            check(capacity > 0) { "PCM DCX: input buffer cannot hold one frame" }
            val count = minOf(pendingSize - offset, capacity, MAX_INPUT_BYTES)
            val timeUs = pendingTimeUs + offset / FRAME_BYTES * 1_000_000L / RATE
            input.bytes.put(pending, offset, count)
            port.queue(input, count, timeUs)
            if (lastProgressNs == null) lastProgressNs = nanoTime()
            inputFrames += count / FRAME_BYTES
            offset += count
            nextTimeUs = pendingTimeUs + offset / FRAME_BYTES * 1_000_000L / RATE
            drain()
        }
        pendingSize = 0
    }

    private fun awaitInput(): Input {
        val deadline = nanoTime() + STALL_NS
        while (true) {
            active()
            drain()
            port.input(10_000L)?.let { return it }
            check(nanoTime() < deadline) { "PCM DCX: input stalled; no samples were silently dropped" }
        }
    }

    private fun drain(timeoutUs: Long = 0) {
        var waitUs = timeoutUs
        while (!ended) {
            active()
            val output = port.output(waitUs) ?: return
            waitUs = 0
            output.format.validate()
            check(output.bytes.size % FRAME_BYTES == 0) { "PCM DCX: incomplete output frame" }
            if (output.bytes.isNotEmpty()) {
                outputFrames += output.bytes.size / FRAME_BYTES
                lastProgressNs = nanoTime()
                if (firstOutput) {
                    firstOutput = false
                    report("PCM DCX: first output verified format=${output.format} ptsUs=${output.timeUs}; renderer/DRC effect unverified")
                }
                onPcm(output.bytes)
            }
            if (output.eos) {
                check(ending) { "PCM DCX: unexpected output EOS" }
                ended = true
                report("PCM DCX: EOS inputFrames=$inputFrames outputFrames=$outputFrames")
            }
        }
    }

    fun finish() {
        active()
        if (ended) return
        sendPending()
        val input = awaitInput()
        ending = true
        port.queue(input, 0, nextTimeUs, eos = true)
        val deadline = nanoTime() + STALL_NS
        while (!ended) {
            drain(10_000L)
            check(ended || nanoTime() < deadline) { "PCM DCX: output EOS timed out" }
        }
    }

    private fun checkProgress() {
        if (!ended) lastProgressNs?.let {
            check(nanoTime() - it < STALL_NS) { "PCM DCX: no PCM output for 2 seconds" }
        }
    }

    private fun active() {
        if (closed || !running() || Thread.currentThread().isInterrupted) throw InterruptedException("PCM DCX stopped")
    }

    override fun close() {
        if (closed) return
        closed = true
        try { port.close() } finally {
            report("PCM DCX: stopped inputFrames=$inputFrames outputFrames=$outputFrames discardedTailFrames=${pendingSize / FRAME_BYTES}")
        }
    }

    companion object {
        const val NAME = "c2.dolby.dcx.pcm.decoder"
        const val MIME = "audio/vnd.dolby.pcm-dcx"
        const val RATE = 48000
        const val FRAME_BYTES = 24
        // SWD.89.906 PCM mode uses 256-frame blocks. Its native input vector is
        // 12288 bytes, and inputs >=8192 skip its resize check before memmove.
        // MediaCodec's larger Java buffer does not enlarge that native vector.
        const val MAX_INPUT_BYTES = 256 * FRAME_BYTES
        private const val STALL_NS = 2_000_000_000L
        val EXPECTED = Format(RATE, 12, 2, 0xb58fc) // Android PCM16 and Java position mask, not HAL enums.
    }
}
