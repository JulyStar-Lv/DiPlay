package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class BplistCodecUnsignedIntegerTest {
    @Test fun unsignedClockValuesAreZeroPaddedToTheWireWidth() {
        val values = (0..63).flatMap { bit ->
            val power = BigInteger.ONE.shiftLeft(bit)
            listOf(power, power.subtract(BigInteger.ONE))
        } + listOf(BigInteger.valueOf(233735L).shiftLeft(32), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        for (value in values) {
            val bytes = BplistCodec.encode(value)
            val marker = bytes[8].toInt() and 255
            assertEquals(1, marker ushr 4)
            val width = 1 shl (marker and 15)
            assertEquals(value, BigInteger(1, bytes.copyOfRange(9, 9 + width)))
            assertEquals(value.toLong(), BplistCodec.decode(bytes))
        }
    }
}
