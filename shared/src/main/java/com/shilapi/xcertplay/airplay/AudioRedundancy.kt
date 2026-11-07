package com.shilapi.xcertplay.airplay

/** CarPlay's RFC 2198 headers are cleartext, before the single AEAD-sealed block payload. */
internal object AudioRedundancy {
    data class Block(val payloadType: Int, val timestampOffset: Int, val length: Int)
    data class Layout(val headerBytes: Int, val blocks: List<Block>)

    fun inspect(wire: ByteArray, negotiated: Boolean): Layout {
        require(wire.size >= 36) { "Short encrypted audio packet" }
        require(wire[0].toInt() and 0xff == 0x80) { "Unsupported audio RTP header" }
        // The vendor handler identifies the RED family this way, ignoring marker and variant bits.
        val red = (wire[1].toInt() and 0x7a) == 0x68
        if (!red) return Layout(12, emptyList())
        require(negotiated) { "Unnegotiated audio redundancy" }
        val end = wire.size - 24
        var cursor = 12
        val blocks = mutableListOf<Block>()
        while (true) {
            require(cursor < end) { "Missing RED primary header" }
            val first = wire[cursor].toInt() and 0xff
            val type = first and 0x7f
            require((type and 0x7a) != 0x68) { "Nested RED payload" }
            if (first and 0x80 == 0) {
                blocks += Block(type, 0, -1)
                cursor++
                break
            }
            require(blocks.size < 16 && cursor + 4 < end) { "Invalid RED header chain" }
            val b1 = wire[cursor + 1].toInt() and 0xff
            val b2 = wire[cursor + 2].toInt() and 0xff
            val b3 = wire[cursor + 3].toInt() and 0xff
            blocks += Block(type, (b1 shl 6) or (b2 ushr 2), ((b2 and 3) shl 8) or b3)
            cursor += 4
        }
        val payloadBytes = end - cursor
        require(blocks.dropLast(1).sumOf { it.length } <= payloadBytes) { "RED blocks exceed payload" }
        return Layout(cursor, blocks)
    }

    /** Normalize every block to ordinary RTP; the per-renderer jitter buffer removes duplicates. */
    fun unpack(rtp: ByteArray, layout: Layout): List<ByteArray> {
        if (layout.blocks.isEmpty()) return listOf(rtp)
        val sequence = ((rtp[2].toInt() and 255) shl 8) or (rtp[3].toInt() and 255)
        val timestamp = readTimestamp(rtp)
        var cursor = layout.headerBytes
        return layout.blocks.mapIndexed { index, block ->
            val length = if (block.length < 0) rtp.size - cursor else block.length
            require(length >= 0 && cursor + length <= rtp.size) { "Invalid RED block length" }
            ByteArray(12 + length).also { packet ->
                rtp.copyInto(packet, 0, 0, 12)
                packet[1] = block.payloadType.toByte()
                val restored = sequence - (layout.blocks.size - 1) + index
                packet[2] = (restored ushr 8).toByte()
                packet[3] = restored.toByte()
                val sample = timestamp - block.timestampOffset
                for (byte in 0..3) packet[4 + byte] = (sample ushr (24 - byte * 8)).toByte()
                rtp.copyInto(packet, 12, cursor, cursor + length)
                cursor += length
            }
        }
    }

    fun readTimestamp(rtp: ByteArray): Int =
        ((rtp[4].toInt() and 255) shl 24) or ((rtp[5].toInt() and 255) shl 16) or
            ((rtp[6].toInt() and 255) shl 8) or (rtp[7].toInt() and 255)
}
