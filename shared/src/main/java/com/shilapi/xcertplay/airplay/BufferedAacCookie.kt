package com.shilapi.xcertplay.airplay

/** The extended AAC offer is AAC-LC, 48 kHz, stereo with 1024 samples per frame. */
internal object BufferedAacCookie {
    fun supported(cookie: ByteArray): Boolean = runCatching {
        var at = 0
        fun descriptor(tag: Int, limit: Int): Int {
            require(at < limit && cookie[at++].toInt() and 255 == tag)
            var size = 0
            var complete = false
            repeat(4) {
                if (!complete) {
                    require(at < limit)
                    val byte = cookie[at++].toInt() and 255
                    size = (size shl 7) or (byte and 127)
                    complete = byte and 128 == 0
                }
            }
            require(complete && size <= limit - at)
            return at + size
        }
        val esEnd = descriptor(3, cookie.size)
        require(esEnd == cookie.size && esEnd - at >= 3)
        require(cookie[at + 2].toInt() and 0xe0 == 0) // optional ES fields are not offered
        at += 3
        val configEnd = descriptor(4, esEnd)
        // iPhone may clear the reserved low bit; still require an audio stream with upstream=0.
        require(configEnd - at >= 13 && cookie[at] == 0x40.toByte() && (cookie[at + 1].toInt() and 0xfe) == 0x14)
        at += 13
        val ascEnd = descriptor(5, configEnd)
        require(ascEnd - at in 2..8)
        // GA flags (960-frame mode, core dependency, extensions) must all be zero.
        require(cookie[at] == 0x11.toByte() && cookie[at + 1] == 0x90.toByte())
        require((at + 2 until ascEnd).all { cookie[it] == 0.toByte() })
        true
    }.getOrDefault(false)
}
