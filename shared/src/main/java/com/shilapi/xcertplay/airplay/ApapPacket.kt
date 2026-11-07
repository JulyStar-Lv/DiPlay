package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer

/** Authenticated extended buffered audio. Times and sequence belong to APAP, not RTP. */
internal data class ApapPacket(
    val timestamp: Long,
    val sequence: Int,
    val audio: ByteArray,
    val cookie: ByteArray?,
    val cookieId: Long?,
    val formatIndex: Int?,
    val trimStart: Int,
    val trimEnd: Int,
    val gain: Float?,
    val loudnessParameters: List<Float>? = null,
    val mediaTimeValue: Long = timestamp,
    val transitionId: Long? = null,
) {
    companion object {
        const val MAX_RECORD_BYTES = 1024 * 1024
        const val MAX_COOKIE_BYTES = 4096
        private const val HEADER_BYTES = 15

        /** Inclusive BE32 framing is consumed by the caller. Never interpret unauthenticated TLVs. */
        fun open(key: ByteArray, body: ByteArray): ApapPacket? = runCatching {
            require(body.size in (HEADER_BYTES + 24)..MAX_RECORD_BYTES)
            val header = ByteBuffer.wrap(body)
            val time = header.long
            require(header.int == 48000) { "Unsupported APAP time scale" }
            val sequence = ((header.get().toInt() and 255) shl 16) or
                ((header.get().toInt() and 255) shl 8) or (header.get().toInt() and 255)
            val nonce = ByteArray(12)
            body.copyInto(nonce, 4, body.size - 8)
            val cipher = AirPlayCrypto.PacketCipher(key)
            fun decrypt(offset: Int, size: Int): ByteArray = ByteArray(body.size - HEADER_BYTES - 24).also {
                cipher.cryptInto(false, nonce, body, HEADER_BYTES, body.size - HEADER_BYTES - 8,
                    body, offset, size, it, 0)
            }
            // The 96-bit nonce with four leading zero bytes equals the original 64-bit
            // construction for these bounded records (the block counter cannot wrap).
            val extension = runCatching { decrypt(0, 12) }.getOrNull()
            if (extension != null) parse(time, sequence, extension)
            else ApapPacket(time and 0xffffffffL, sequence, decrypt(4, 8), null, null, null, 0, 0, null,
                mediaTimeValue = time)
        }.getOrNull()

        private fun parse(time: Long, sequence: Int, data: ByteArray): ApapPacket {
            var at = 0
            fun leb(): Int {
                var value = 0L
                for (shift in 0..28 step 7) {
                    require(at < data.size)
                    val b = data[at++].toInt() and 255
                    value = value or ((b and 127).toLong() shl shift)
                    require(value <= Int.MAX_VALUE)
                    if (b and 128 == 0) return value.toInt()
                }
                error("Oversized APAP integer")
            }
            fun number(start: Int, length: Int): Long {
                require(length in 1..8)
                var value = 0L
                for (i in start until start + length) value = (value shl 8) or (data[i].toLong() and 255)
                return value
            }
            fun trim(start: Int, length: Int): Int {
                require(length == 12)
                val value = number(start, 8)
                val scale = number(start + 8, 4)
                require(value >= 0 && scale in 1..Int.MAX_VALUE.toLong())
                return if (value > 1024L * scale / 48000L + 1) 1024
                else (value * 48000L / scale).coerceAtMost(1024).toInt()
            }
            var cookie: ByteArray? = null
            var cookieId: Long? = null
            var index: Int? = null
            var startTrim = 0
            var endTrim = 0
            var gain: Float? = null
            var loudnessParameters: List<Float>? = null
            var transitionId: Long? = null
            while (at < data.size) {
                val id = leb()
                if (id == 0) return ApapPacket(time and 0xffffffffL, sequence,
                    data.copyOfRange(at, data.size), cookie, cookieId, index, startTrim, endTrim, gain,
                    loudnessParameters, time, transitionId)
                val length = leb()
                require(length <= data.size - at)
                when (id) {
                    2 -> { val value = number(at, length); require(value in 0..Int.MAX_VALUE.toLong()); index = value.toInt() }
                    4 -> startTrim = trim(at, length)
                    5 -> endTrim = trim(at, length)
                    8 -> require(number(at, length) == 1024L)
                    10 -> if (length in 1..8) transitionId = number(at, length)
                    11 -> {
                        require(length == 12)
                        val values = ByteBuffer.wrap(data, at, length)
                        val loudness = values.float
                        val auxiliary = values.float
                        val peak = values.float
                        require(loudness.isFinite() && peak.isFinite())
                        loudnessParameters = listOf(loudness, auxiliary, peak)
                        // Conservative SoundCheck target; never amplify an experimental stream.
                        gain = Math.pow(10.0, ((-18f - loudness).coerceAtMost(0f) / 20f).toDouble()).toFloat()
                    }
                    17 -> cookieId = number(at, length)
                    18 -> { require(length in 1..MAX_COOKIE_BYTES); cookie = data.copyOfRange(at, at + length) }
                }
                at += length
            }
            error("APAP extension has no terminator")
        }
    }
}
