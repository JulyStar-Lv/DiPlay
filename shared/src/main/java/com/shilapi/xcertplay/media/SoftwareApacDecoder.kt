package com.shilapi.xcertplay.media

import java.io.Closeable

/** Optional, locally supplied APAC channel decoder. Ordinary builds contain no third-party codec. */
class SoftwareApacDecoder(cookie: ByteArray) : Closeable {
    private var handle = if (available && cookie.size in 1..4096) nativeCreate(cookie) else 0L
    val channels: Int = if (handle != 0L) nativeInfo(handle, 0) else 0
    val layoutTag: Int = if (handle != 0L) nativeInfo(handle, 1) else 0
    val configured: Boolean get() = handle != 0L

    fun decode(packet: ByteArray): ByteArray {
        check(handle != 0L) { "APAC configuration unsupported" }
        require(packet.size in 1..65536)
        return nativeDecode(handle, packet) ?: error("APAC packet type or coding tool unsupported")
    }

    fun reset() { if (handle != 0L) nativeReset(handle) }
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }

    companion object {
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("diplay_apac"); nativeAvailable() }.getOrDefault(false)
        }
        @JvmStatic private external fun nativeAvailable(): Boolean
        @JvmStatic private external fun nativeCreate(cookie: ByteArray): Long
        @JvmStatic private external fun nativeInfo(handle: Long, field: Int): Int
        @JvmStatic private external fun nativeDecode(handle: Long, packet: ByteArray): ByteArray?
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
