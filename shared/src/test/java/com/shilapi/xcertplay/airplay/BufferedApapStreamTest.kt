package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedApapStreamTest {
    @Test fun fragmentedBe32RecordsAuthenticateAndSwitchToStereoWithoutAnRtpHeader() {
        val received = CountDownLatch(1)
        var actual: ByteArray? = null
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                assertEquals(AudioCodecKind.AAC_LC, format.codec)
                assertEquals(48000, format.sampleRate)
                assertEquals(2, format.channels)
            }
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int, gain: Float) {
                assertEquals(0.3548f, gain, 0.001f)
                actual = rtp.copyOfRange(12, rtp.size)
                received.countDown()
            }
        }
        val key = ByteArray(32)
        val audio = byteArrayOf(0x21, 0x22, 0x23)
        val cookie = BufferedAacCookieTest.cookie()
        val loudness = ByteBuffer.allocate(12).putFloat(-9f).putFloat(-1f).putFloat(-2f).array()
        val plain = byteArrayOf(2, 1, 23, 18, cookie.size.toByte()) + cookie +
            byteArrayOf(11, 12) + loudness + byteArrayOf(0) + audio
        val header = ByteBuffer.allocate(15).putLong(48000).putInt(48000).put(byteArrayOf(0, 0, 1)).array()
        val nonce = ByteArray(12)
        val sealed = header + AirPlayCrypto.chachaSeal(key, nonce, plain, header.copyOfRange(0, 12)) + ByteArray(8)
        val record = ByteBuffer.allocate(4 + sealed.size).putInt(4 + sealed.size).put(sealed).array()
        BufferedAudioStream(key, AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 103), sink,
            apap = true, spatialFormats = listOf(65)).use { stream ->
            stream.start()
            Socket("127.0.0.1", stream.port).use { socket ->
                stream.setRate(48000L, 1, BigInteger.ZERO)
                val output = socket.getOutputStream()
                // TCP boundaries need not coincide with either prefix or authenticated body.
                output.write(record, 0, 3); output.flush()
                output.write(record, 3, record.size - 3); output.flush()
                assertTrue(received.await(3, TimeUnit.SECONDS))
                assertArrayEquals(audio, actual)
            }
        }
    }
}
