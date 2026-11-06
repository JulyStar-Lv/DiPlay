package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.SoftwareApacDecoder
import com.shilapi.xcertplay.media.SpatialPcm
import java.io.Closeable

/** Owned by the buffered feed thread/lock. Cookies remain attached to queued frames across item changes. */
internal class BufferedApacOutput(private val allowedFormats: List<Int>, private val log: (String) -> Unit) : Closeable {
    data class Output(val format: AudioFormat, val rtp: ByteArray, val gain: Float)
    private val cookies = LinkedHashMap<Long, ByteArray>()
    private var cookie: ByteArray? = null
    private var decoder: SoftwareApacDecoder? = null
    private var aac = false
    private var sequence: Int? = null

    fun storeCookie(id: Long, bytes: ByteArray) {
        require(bytes.size in 1..ApapPacket.MAX_COOKIE_BYTES)
        if (cookies.containsKey(id)) return
        require(cookies.size < 16)
        cookies[id] = bytes.copyOf()
    }

    fun render(packet: ApapPacket): Output? {
        val gain = packet.gain ?: 1f
        packet.cookie?.let { bytes -> packet.cookieId?.let { storeCookie(it, bytes) } }
        val next = packet.cookie ?: packet.cookieId?.let { cookies[it] ?: error("Unknown APAC cookie ID") }
        if (next != null && cookie?.contentEquals(next) != true) {
            decoder?.close(); decoder = null
            aac = BufferedAacCookie.supported(next)
            if (!aac) {
                val configured = SoftwareApacDecoder(next)
                if (!configured.configured) { configured.close(); error("Unsupported APAC cookie") }
                val index = when (configured.channels) { 6 -> 65; 12 -> 68; else -> -1 }
                if (index !in allowedFormats) { configured.close(); error("APAC layout was not offered") }
                decoder = configured
                log("Spatial audio: APAC configured channels=${configured.channels} layout=0x${configured.layoutTag.toString(16)}")
            } else log("Spatial audio: switched to AAC-LC stereo")
            cookie = next.copyOf()
            sequence = null
        }
        packet.loudnessParameters?.let {
            log("Buffered loudness: codec=${if (aac) "AAC_LC" else "APAC"} " +
                "parameters=$it targetDb=-18 gain=$gain; applied once after decode")
        }
        if (packet.audio.isEmpty()) return null
        if (aac) {
            require(packet.formatIndex == null || packet.formatIndex == 23)
            require(packet.trimStart == 0 && packet.trimEnd == 0) { "AAC APAP trims are not supported yet" }
            return Output(AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 103), rtp(packet.audio), gain)
        }
        val active = decoder ?: error("APAC audio arrived without a supported cookie")
        require(packet.formatIndex == null || packet.formatIndex == if (active.channels == 6) 65 else 68)
        sequence?.let {
            if (((it + 1) and 0xffffff) != packet.sequence) {
                active.reset()
                log("Spatial audio: sequence discontinuity; decoder reset")
            }
        }
        sequence = packet.sequence
        val pcm = SpatialPcm.convert(active.decode(packet.audio), active.layoutTag, packet.trimStart, packet.trimEnd, 1f)
        if (pcm.isEmpty()) return null
        return Output(AudioFormat(AudioCodecKind.LPCM, 48000, active.channels, 103,
            pcmChannelMask = SpatialPcm.mask(active.layoutTag), pcmLittleEndian = true), rtp(pcm), gain)
    }

    private fun rtp(payload: ByteArray): ByteArray = ByteArray(12 + payload.size).also {
        it[0] = 0x80.toByte(); payload.copyInto(it, 12)
    }

    fun reset() { decoder?.reset(); sequence = null }
    override fun close() { decoder?.close(); decoder = null; cookie = null; cookies.clear() }
}
