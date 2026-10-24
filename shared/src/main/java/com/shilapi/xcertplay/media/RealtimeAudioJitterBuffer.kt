package com.shilapi.xcertplay.media

import java.util.TreeMap

/** Decoder-thread owned. Time is injected so reordering and loss deadlines are deterministic. */
internal class RealtimeAudioJitterBuffer(
    private val clockRate: Int,
    private val policy: AudioPlayoutPolicy,
    private val maxPackets: Int = 192,
    private val concealQuantumTicks: Int = 1,
) {
    data class Packet(val rtp: ByteArray, val sample: Int, val duration: Int, val arrivalNs: Long)
    sealed interface Output {
        data class Received(val packet: Packet, val resetDecoder: Boolean) : Output
        data class Missing(val ticks: Int) : Output
    }

    private val packets = TreeMap<Long, Packet>()
    private var newestSequence: Long? = null
    private var expectedSequence: Long? = null
    private var expectedSample: Int? = null
    private var firstArrivalNs = 0L
    private var resumeAtNs = 0L
    private var lastArrivalNs: Long? = null
    private var ssrc: Int? = null
    private var resetDecoder = false
    @Volatile var duplicates = 0L; private set
    @Volatile var late = 0L; private set
    @Volatile var concealed = 0L; private set
    @Volatile var resets = 0L; private set
    val size: Int get() = packets.size

    fun offer(rtp: ByteArray, sample: Int, duration: Int, nowNs: Long) {
        if (rtp.size < 12 || duration <= 0) return
        val source = readInt(rtp, 8)
        // A new SSRC starts a new timeline. Idle prompts retain the sequence watermark so RED cannot replay old speech.
        val idle = lastArrivalNs?.let { nowNs - it > 500_000_000L } ?: false
        if (ssrc != null && ssrc != source) restart()
        ssrc = source
        val raw = ((rtp[2].toInt() and 255) shl 8) or (rtp[3].toInt() and 255)
        val latest = newestSequence
        val sequence = if (latest == null) raw.toLong() else latest + (raw - latest.toInt()).toShort().toInt()
        if (expectedSequence?.let { sequence < it } == true) { late++; return }
        if (packets.containsKey(sequence)) { duplicates++; return }
        if (idle && packets.isEmpty()) {
            expectedSample = null
            resumeAtNs = nowNs + policy.reorderMillis * 1_000_000L
            resetDecoder = true
            resets++
        }
        if (packets.isEmpty() && expectedSequence == null) firstArrivalNs = nowNs
        packets[sequence] = Packet(rtp, sample, duration, nowNs)
        newestSequence = maxOf(latest ?: sequence, sequence)
        lastArrivalNs = nowNs
        if (packets.size > maxPackets) {
            // A stalled output may catch up, but must not retain unbounded obsolete speech.
            while (packets.size > maxPackets / 2) packets.pollFirstEntry()
            expectedSequence = null
            expectedSample = null
            resetDecoder = true
            resets++
        }
    }

    fun poll(nowNs: Long): Output? {
        val first = packets.firstEntry() ?: return null
        if (nowNs < resumeAtNs) return null
        var sequence = expectedSequence
        if (sequence == null) {
            if (nowNs - firstArrivalNs < policy.reorderMillis * 1_000_000L) return null
            sequence = first.key
            expectedSequence = sequence
        }
        if (first.key > sequence) {
            // Give a reordered original or a later packet's RED block a bounded chance to arrive.
            if (nowNs - first.value.arrivalNs < policy.reorderMillis * 1_000_000L) return null
            val missingPackets = first.key - sequence
            val gap = expectedSample?.let { first.value.sample - it } ?: 0
            val ticks = if (missingPackets > 0) gap.toLong() / missingPackets else 0
            val quantum = concealQuantumTicks.coerceAtLeast(1)
            if (gap > 0 && gap.toLong() * 1000 <= policy.maxConcealMillis.toLong() * clockRate &&
                gap.toLong() % missingPackets == 0L && ticks > 0 && ticks % quantum == 0L) {
                expectedSequence = sequence + 1
                expectedSample = expectedSample!! + ticks.toInt()
                concealed++
                return Output.Missing(ticks.toInt())
            }
            expectedSequence = first.key
            expectedSample = null
            resetDecoder = true
            resets++
        }
        packets.pollFirstEntry()
        // Timestamp jumps without a sequence gap include DTX and prompt boundaries; they are not lost audio.
        val discontinuity = expectedSample?.let { it != first.value.sample } ?: false
        if (discontinuity) resets++
        val reset = resetDecoder || discontinuity
        resetDecoder = false
        expectedSequence = first.key + 1
        expectedSample = first.value.sample + first.value.duration
        return Output.Received(first.value, reset)
    }

    private fun restart() {
        packets.clear()
        newestSequence = null
        expectedSequence = null
        expectedSample = null
        resetDecoder = true
        resets++
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
}
