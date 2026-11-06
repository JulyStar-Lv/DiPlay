// SPDX-License-Identifier: GPL-3.0-or-later
// OPACK wire reference: Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** OPACK values used by vehicleStateProtocol. Limits apply before allocating or following references. */
internal object OpackCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_NODES = 65_536
    private const val MAX_DEPTH = 32
    data class Date(val secondsSince2001: Double)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= MAX_BYTES) { "OPACK size" }
        return Reader(bytes).run { value(0).also { require(at == bytes.size) { "OPACK trailing data" } } }
    }

    private class Reader(val bytes: ByteArray) {
        var at = 0
        var nodes = 0
        val seen = ArrayList<Any?>()
        fun take(n: Int): ByteArray {
            require(n >= 0 && n <= bytes.size - at) { "OPACK truncated" }
            return bytes.copyOfRange(at, at + n).also { at += n }
        }
        fun byte(): Int { require(at < bytes.size) { "OPACK truncated" }; return bytes[at++].toInt() and 255 }
        fun unsigned(n: Int): Long {
            require(n in 1..8)
            var result = 0L
            repeat(n) { i -> result = result or (byte().toLong() shl (i * 8)) }
            return result
        }
        fun length(tag: Int, base: Int): Int {
            val size = tag - base
            val n = if (size <= 32) size.toLong() else unsigned(size - 32)
            require(n in 0..MAX_BYTES.toLong()) { "OPACK length" }
            return n.toInt()
        }
        fun remember(v: Any?): Any? { seen.add(v); return v }
        fun reference(index: Long): Any? {
            require(index >= 0 && index < seen.size) { "OPACK reference" }
            return seen[index.toInt()]
        }
        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "OPACK complexity" }
            return when (val tag = byte()) {
                1 -> true
                2 -> false
                4 -> null
                5 -> remember(ByteBuffer.wrap(take(16)).let { UUID(it.long, it.long) })
                6 -> remember(Date(Double.fromBits(unsigned(8))))
                in 8..0x2f -> (tag - 8).toLong()
                in 0x30..0x33 -> {
                    val width = 1 shl (tag - 0x30)
                    val shift = 64 - width * 8
                    remember((unsigned(width) shl shift) shr shift)
                }
                0x35 -> remember(Float.fromBits(unsigned(4).toInt()).toDouble())
                0x36 -> remember(Double.fromBits(unsigned(8)))
                in 0x40..0x64 -> remember(Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(take(length(tag, 0x40)))).toString())
                in 0x70..0x94 -> remember(take(length(tag, 0x70)))
                in 0xa0..0xc0 -> reference((tag - 0xa0).toLong())
                in 0xc1..0xc4 -> reference(unsigned(tag - 0xc0))
                in 0xd0..0xdf -> {
                    val list = ArrayList<Any?>()
                    val count = tag - 0xd0
                    while (more(count, list.size)) list.add(value(depth + 1))
                    list
                }
                in 0xe0..0xef -> {
                    val map = linkedMapOf<Any?, Any?>()
                    val count = tag - 0xe0
                    while (more(count, map.size)) {
                        val key = value(depth + 1)
                        require(key is String || key is Long || key is UUID) { "OPACK dictionary key" }
                        require(!map.containsKey(key)) { "OPACK duplicate key" }
                        map[key] = value(depth + 1)
                    }
                    map
                }
                else -> throw IllegalArgumentException("OPACK tag")
            }
        }
        fun more(count: Int, size: Int): Boolean {
            if (count != 15) return size < count
            require(at < bytes.size) { "OPACK unterminated container" }
            if (bytes[at].toInt() == 3) { at++; return false }
            return true
        }
    }

    /** Outbound navigation descriptions are small; no object-reference encoding is necessary. */
    fun encode(value: Any?): ByteArray = Writer().run { value(value, 0); out.toByteArray() }

    private class Writer {
        val out = ByteArrayOutputStream()
        var nodes = 0
        fun unsigned(n: Long, width: Int) { repeat(width) { out.write((n ushr (it * 8)).toInt() and 255) } }
        fun bytes(base: Int, bytes: ByteArray) {
            require(bytes.size <= MAX_BYTES)
            val n = bytes.size
            if (n <= 32) out.write(base + n) else {
                val width = if (n <= 255) 1 else if (n <= 65535) 2 else if (n <= 0xffffff) 3 else 4
                out.write(base + 32 + width)
                unsigned(n.toLong(), width)
            }
            out.write(bytes)
        }
        fun value(v: Any?, depth: Int) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES && out.size() <= MAX_BYTES) { "OPACK complexity" }
            when (v) {
                null -> out.write(4)
                is Boolean -> out.write(if (v) 1 else 2)
                is Byte, is Short, is Int, is Long -> {
                    val n = (v as Number).toLong()
                    if (n in 0..39) out.write(8 + n.toInt()) else {
                        val width = when (n) { in -128..127 -> 1; in -32768..32767 -> 2
                            in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> 4; else -> 8 }
                        out.write(0x30 + Integer.numberOfTrailingZeros(width))
                        unsigned(n, width)
                    }
                }
                is Float, is Double -> { out.write(0x36); unsigned((v as Number).toDouble().toBits(), 8) }
                is Date -> { out.write(6); unsigned(v.secondsSince2001.toBits(), 8) }
                is String -> bytes(0x40, v.toByteArray(Charsets.UTF_8))
                is ByteArray -> bytes(0x70, v)
                is UUID -> { out.write(5); out.write(ByteBuffer.allocate(16).putLong(v.mostSignificantBits).putLong(v.leastSignificantBits).array()) }
                is List<*> -> {
                    out.write(0xd0 + minOf(v.size, 15))
                    v.forEach { value(it, depth + 1) }
                    if (v.size >= 15) out.write(3)
                }
                is Map<*, *> -> {
                    out.write(0xe0 + minOf(v.size, 15))
                    v.forEach { (k, item) -> value(k, depth + 1); value(item, depth + 1) }
                    if (v.size >= 15) out.write(3)
                }
                else -> throw IllegalArgumentException("OPACK value")
            }
            require(out.size() <= MAX_BYTES) { "OPACK size" }
        }
    }
}
