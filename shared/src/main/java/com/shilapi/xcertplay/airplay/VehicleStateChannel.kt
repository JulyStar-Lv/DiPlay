// SPDX-License-Identifier: GPL-3.0-or-later
// DataStream wire reference: Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Separate duplex type-130 channel. It never delivers vehicle-state bytes to the iAP2 relay. */
internal class VehicleStateChannel(
    readKey: ByteArray,
    writeKey: ByteArray,
    private val peer: InetAddress,
    private val receive: (ByteArray) -> List<ByteArray>,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val cipher = ControlCipher(readKey, writeKey)
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    private var server: ServerSocket? = null

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, 0)) }
        synchronized(this) {
            if (closed.get()) { bound.close(); throw EOFException() }
            server = bound
        }
        Thread({
            try {
                val accepted = bound.acceptFrom(peer) { log("Route sharing: rejected unexpected peer") }
                socket = accepted
                if (closed.get()) { accepted.close(); return@Thread }
                log("Route sharing: channel connected")
                run(accepted)
            } catch (error: Exception) {
                if (!closed.get()) log("Route sharing: channel ended ${error.javaClass.simpleName}")
            } finally { close() }
        }, "diplay-route-sharing").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun run(sock: Socket) {
        val input = sock.getInputStream()
        var pending = ByteArray(0)
        while (!closed.get()) {
            val first = input.read()
            if (first < 0) {
                require(pending.isEmpty()) { "truncated vehicle package" }
                return
            }
            val second = input.read()
            if (second < 0) throw EOFException()
            val length = first or (second shl 8)
            val frame = byteArrayOf(first.toByte(), second.toByte()) + input.exact(length + 16)
            val (plain, remainder) = cipher.decrypt(frame)
            check(remainder.isEmpty())
            require(pending.size + plain.size <= OpackCodec.MAX_BYTES + 65535) { "vehicle package limit" }
            pending += plain
            var at = 0
            while (pending.size - at >= HEADER) {
                val header = ByteBuffer.wrap(pending, at, HEADER)
                val size = header.int
                require(size in HEADER..OpackCodec.MAX_BYTES) { "vehicle package size" }
                if (pending.size - at < size) break
                val kind = header.int
                val token = ByteBuffer.wrap(pending, at + 20, 8).long
                if (kind == SYNC) sendPackage(REPLY, 0, token, ByteArray(0))
                if (kind != REPLY && (kind != SYNC || size > HEADER)) {
                    val body = pending.copyOfRange(at + HEADER, at + size)
                    receive(body).forEach { sendPackage(ASYNC, COMMAND, 0, it) }
                }
                at += size
            }
            pending = pending.copyOfRange(at, pending.size)
        }
    }

    private fun sendPackage(kind: Int, command: Int, token: Long, body: ByteArray) = synchronized(writeLock) {
        require(body.size <= OpackCodec.MAX_BYTES - HEADER)
        val bytes = ByteBuffer.allocate(HEADER + body.size)
            .putInt(HEADER + body.size).putInt(kind).putLong(0)
            .putInt(command).putLong(token).putInt(0).put(body).array()
        val output = socket?.getOutputStream() ?: throw EOFException()
        output.write(cipher.encrypt(bytes))
        output.flush()
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        runCatching { socket?.close() }
    }

    private fun InputStream.exact(size: Int): ByteArray {
        val result = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = read(result, at, size - at)
            if (n < 0) throw EOFException()
            at += n
        }
        return result
    }

    private companion object {
        const val HEADER = 32
        const val SYNC = 0x73796e63
        const val ASYNC = 0x6173796e
        const val REPLY = 0x72706c79
        const val COMMAND = 0x636d6e64
    }
}
