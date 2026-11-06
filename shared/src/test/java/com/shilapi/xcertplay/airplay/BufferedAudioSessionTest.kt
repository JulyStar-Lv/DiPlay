package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.io.DataOutputStream
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedAudioSessionTest {
    @Test fun mediaOnlyExperimentOmitsSyntheticRtpOnlyForExtendedMediaTimeRequests() {
        for (experiment in listOf(7, 8)) {
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(media = engine, spatial = listOf(68), timelineExperiment = experiment)
            try {
                assertTrue(engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                    "audioFormatIndex" to 68, "audioFormat" to 0, "ct" to 0, "audioType" to "media")) != null)
                val request = mapOf("rate" to 1, "mediaTimeValue" to 69348211552416L,
                    "mediaTimeScale" to 1000000000L)
                val reply = BplistCodec.decode(handle(session, "SETRATE", BplistCodec.encode(request)).body) as Map<*, *>
                assertEquals(if (experiment == 8) null else 3328714154L, reply["rtpTime"])
                assertEquals(69348211552416L, reply["mediaTimeValue"])
                assertEquals(1000000000L, reply["mediaTimeScale"])
                assertEquals(0L, reply["networkTimeTimelineID"])
                assertEquals(0L, reply["networkTimeFlags"])
                // Reply shape must not change the local sample used for output and feedback.
                val anchor = engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!
                assertEquals(3328714154L, anchor["rtpTime"])
                val entry = (engine.onFeedback(session)!!["streams"] as List<*>).single() as Map<*, *>
                assertEquals(3328714154L, entry["sampleTime"])
                assertEquals(48000.0, entry["sr"])
                assertTrue(handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0))).body.isEmpty())
                val explicit = BplistCodec.decode(handle(session, "SETRATE",
                    BplistCodec.encode(request + ("rtpTime" to 1234L))).body) as Map<*, *>
                assertEquals(1234L, explicit["rtpTime"])
                val rtpOnly = BplistCodec.decode(handle(session, "SETRATE",
                    BplistCodec.encode(mapOf("rate" to 1, "rtpTime" to 48000L))).body) as Map<*, *>
                assertEquals(48000L, rtpOnly["rtpTime"])
            } finally { session.close() }
        }
    }

    @Test fun networkFieldsExperimentCompletesTheExtendedAnchorWithoutChangingItsTimes() {
        for (experiment in listOf(6, 7)) {
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(media = engine, spatial = listOf(68), timelineExperiment = experiment)
            try {
                assertTrue(engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                    "audioFormatIndex" to 68, "audioFormat" to 0, "ct" to 0, "audioType" to "media")) != null)
                // Captured mode 6 resume: same-song position jumped from 126998 to 188007 ms.
                val request = mapOf("rate" to 1, "mediaTimeValue" to 43493990589250L,
                    "mediaTimeScale" to 1000000000L)
                val reply = BplistCodec.decode(handle(session, "SETRATE", BplistCodec.encode(request)).body) as Map<*, *>
                assertEquals(if (experiment == 7) 0L else null, reply["networkTimeTimelineID"])
                assertEquals(if (experiment == 7) 0L else null, reply["networkTimeFlags"])
                assertEquals(43493990589250L, reply["mediaTimeValue"])
                assertEquals(1000000000L, reply["mediaTimeScale"])
                assertEquals(2087711548L, reply["rtpTime"])
                val anchor = BplistCodec.decode(handle(session, "GETANCHOR", ByteArray(0)).body) as Map<*, *>
                assertEquals(reply["networkTimeSecs"], anchor["networkTimeSecs"])
                assertEquals(reply["networkTimeFrac"], anchor["networkTimeFrac"])
                assertEquals(reply["networkTimeTimelineID"], anchor["networkTimeTimelineID"])
                assertEquals(reply["networkTimeFlags"], anchor["networkTimeFlags"])
                assertTrue(handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0))).body.isEmpty())
                val entry = (engine.onFeedback(session)!!["streams"] as List<*>).single() as Map<*, *>
                assertEquals(48000.0, entry["sr"])
                val ntp = (entry["timestamp"] as BigInteger).add(BigInteger.valueOf(2_208_988_800L).shiftLeft(32))
                assertTrue(ntp.subtract(session.syncedNtp()).abs() < BigInteger.ONE.shiftLeft(32))
            } finally { session.close() }
        }
    }

    @Test fun networkFieldsExperimentLeavesClassicAnchorsUnchanged() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, timelineExperiment = 7)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            val reply = engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 48000L))!!
            assertNull(reply["networkTimeTimelineID"])
            assertNull(reply["networkTimeFlags"])
            assertNull(reply["mediaTimeValue"])
        } finally { session.close() }
    }

    @Test fun combinedAnchorExperimentUsesEmptyAckAndPreservesTheResumeMediaTime() {
        for (experiment in listOf(5, 6)) {
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(media = engine, spatial = listOf(68), timelineExperiment = experiment)
            try {
                assertTrue(engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                    "audioFormatIndex" to 68, "audioFormat" to 0, "ct" to 0, "audioType" to "media")) != null)
                engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 48000L))
                val paused = handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0)))
                assertEquals(experiment == 6, paused.body.isEmpty())
                val stopped = BplistCodec.decode(handle(session, "GETANCHOR", ByteArray(0)).body) as Map<*, *>
                assertEquals(0L, stopped["rate"])
                // Actual 16:09 resume after the first observed natural transition of mode 5.
                val response = handle(session, "SETRATE", BplistCodec.encode(mapOf("rate" to 1,
                    "mediaTimeValue" to 14252548512458L, "mediaTimeScale" to 1000000000L)))
                val reply = BplistCodec.decode(response.body) as Map<*, *>
                assertEquals(14252548512458L, reply["mediaTimeValue"])
                assertEquals(1000000000L, reply["mediaTimeScale"])
                assertEquals(684122328L, reply["rtpTime"])
            } finally { session.close() }
        }
    }

    @Test fun feedbackClockExperimentIncludesTheOemRateWithoutChangingPosition() {
        for (experiment in listOf(4, 5, 6)) {
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(media = engine, timelineExperiment = experiment)
            try {
                assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
                engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 48000L))
                val before = session.syncedNtp()
                val response = engine.onFeedback(session)!!
                val after = session.syncedNtp()
                val entry = (response["streams"] as List<*>).single() as Map<*, *>
                // WT_CarPlay's serializer at 0x95384..0x95394 writes `sr` as a double.
                if (experiment in 5..6) assertEquals(48000.0, entry["sr"]) else assertNull(entry["sr"])
                assertEquals(BufferedAudioStream.STREAM_TYPE, entry["type"])
                assertEquals(48000, entry["sampleRate"])
                assertEquals(48000L, entry["sampleTime"])
                val ntp = (entry["timestamp"] as BigInteger)
                    .add(BigInteger.valueOf(2_208_988_800L).shiftLeft(32))
                assertTrue(ntp >= before && ntp <= after)
                val wire = BplistCodec.decode(BplistCodec.encode(response)) as Map<*, *>
                val wireEntry = (wire["streams"] as List<*>).single() as Map<*, *>
                if (experiment in 5..6) assertEquals(48000.0, wireEntry["sr"]) else assertNull(wireEntry["sr"])
                assertEquals(48000L, wireEntry["sampleTime"])
            } finally { session.close() }
        }
    }

    @Test fun feedbackEpochExperimentUsesTheSameSecondsDomainAsTheOemAnchor() {
        for (experiment in listOf(3, 4, 5, 6)) {
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(media = engine, timelineExperiment = experiment)
            try {
                assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
                engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 48000L))
                val before = session.syncedNtp()
                val feedback = engine.onFeedback(session)!!
                val streams = feedback["streams"] as List<*>
                val after = session.syncedNtp()
                val entry = streams.single() as Map<*, *>
                val timestamp = entry["timestamp"] as BigInteger
                // WT_CarPlay libAirPlay: feedback packs AirTunesTime directly (0xa2990),
                // but timing UDP explicitly adds 2208988800 seconds (0xa8fa4..0xa8fc4).
                val wireNtp = if (experiment in 4..6)
                    timestamp.add(BigInteger.valueOf(2_208_988_800L).shiftLeft(32)) else timestamp
                assertTrue("feedback timestamp must follow the OEM epoch contract", wireNtp >= before && wireNtp <= after)
                assertEquals(48000L, entry["sampleTime"])
                assertEquals(48000, entry["sampleRate"])
                val wire = BplistCodec.decode(BplistCodec.encode(feedback)) as Map<*, *>
                val wireEntry = (wire["streams"] as List<*>).single() as Map<*, *>
                assertEquals(timestamp.toLong(), wireEntry["timestamp"])
            } finally { session.close() }
        }
    }

    @Test fun mediaTimeReplyExperimentPreservesTheSendersNanosecondPair() {
        checkOriginalMediaTimeReply(timelineExperiment = 2)
    }

    @Test fun diagnosticsBuildKeepsTheStageTwoControlReplies() {
        checkOriginalMediaTimeReply(timelineExperiment = 3)
    }

    @Test fun feedbackEpochBuildKeepsTheStageTwoControlReplies() {
        checkOriginalMediaTimeReply(timelineExperiment = 4)
    }

    @Test fun feedbackClockBuildKeepsTheStageTwoControlReplies() {
        checkOriginalMediaTimeReply(timelineExperiment = 5)
    }

    private fun checkOriginalMediaTimeReply(timelineExperiment: Int) {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, spatial = listOf(68), timelineExperiment = timelineExperiment)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                "audioFormatIndex" to 68, "audioFormat" to 0, "ct" to 0, "audioType" to "media")) != null)
            // Captured AutoMix resume on 2026-11-05: the song jumped from 43 seconds to its end.
            val resumed = handle(session, "SETRATE", BplistCodec.encode(mapOf(
                "rate" to 1, "mediaTimeValue" to 31815339817917L, "mediaTimeScale" to 1000000000L)))
            val reply = BplistCodec.decode(resumed.body) as Map<*, *>
            assertEquals(1527136311L, reply["rtpTime"])
            assertEquals("SETRATE must preserve the sender's original media time value", 31815339817917L, reply["mediaTimeValue"])
            assertEquals(1000000000L, reply["mediaTimeScale"])

            // Stage 2 tests the SETRATE reply alone; stage 1's empty pause ACK is not combined.
            val paused = handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0)))
            assertTrue(paused.body.isNotEmpty())
            val anchor = BplistCodec.decode(handle(session, "GETANCHOR", ByteArray(0)).body) as Map<*, *>
            assertEquals(48000L, anchor["mediaTimeScale"])
            assertEquals(0L, anchor["rate"])
        } finally { session.close() }
    }

    @Test fun timelineExperimentAcknowledgesRateAnchorWithoutReturningAReceiverAnchor() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, spatial = listOf(68), timelineExperiment = 1)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            val started = handle(session, "SETRATE", BplistCodec.encode(mapOf("rate" to 1, "rtpTime" to 48000L)))
            assertEquals(200, started.status ?: 200)
            assertEquals(48000L, (BplistCodec.decode(started.body) as Map<*, *>)["rtpTime"])

            val paused = handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0)))
            assertEquals(200, paused.status ?: 200)
            assertTrue("SETRATEANCHORTIME success must not return a receiver-generated anchor", paused.body.isEmpty())
            assertTrue(!paused.headers.containsKey("Content-Type"))
            val anchor = handle(session, "GETANCHOR", ByteArray(0))
            assertEquals(0L, (BplistCodec.decode(anchor.body) as Map<*, *>)["rate"])

            val resumed = handle(session, "SETRATEANCHORTIME",
                BplistCodec.encode(mapOf("rate" to 1, "rtpTime" to 96000L)))
            assertEquals(200, resumed.status ?: 200)
            assertTrue(resumed.body.isEmpty())
            val newAnchor = BplistCodec.decode(handle(session, "GETANCHOR", ByteArray(0)).body) as Map<*, *>
            assertEquals(96000L, newAnchor["rtpTime"])
            assertEquals(1L, newAnchor["rate"])
        } finally { session.close() }
    }

    @Test fun controlBuildKeepsTheExistingRateAnchorReply() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, timelineExperiment = 0)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 48000L))
            val paused = handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 0)))
            assertEquals(200, paused.status ?: 200)
            assertEquals(48000L, (BplistCodec.decode(paused.body) as Map<*, *>)["rtpTime"])
        } finally { session.close() }
    }

    @Test fun extendedSetupHonorsTheOfferAndUsesMediaTimeAnchors() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, spatial = listOf(65, 68))
        try {
            val spatial = validSetup() + mapOf("packetFormatAPAP" to true, "audioFormatIndex" to 65,
                "audioFormat" to 0, "ct" to 0, "audioType" to "media")
            val first = engine.onBufferedAudio(session, spatial)!!
            assertNull(engine.onBufferedAudio(session, spatial + ("audioFormatIndex" to 66)))
            assertNull(engine.onBufferedAudio(session, spatial + ("audioType" to "telephony")))
            Socket("127.0.0.1", first["dataPort"] as Int).use { }
            val anchor = engine.onBufferedAudioControl(session, "SETRATE",
                mapOf("rate" to 1, "mediaTimeValue" to 48000L, "mediaTimeScale" to 48000))!!
            assertEquals(48000L, anchor["rtpTime"])
            assertEquals(48000L, anchor["mediaTimeValue"])
            assertEquals(48000, anchor["mediaTimeScale"])
            assertEquals(0, anchor["status"])
        } finally { session.close() }
    }

    @Test fun extendedSetupIsDeclinedWithoutSpatialOptIn() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            assertNull(engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                "audioFormatIndex" to 65, "audioFormat" to 0, "ct" to 0, "audioType" to "media")))
        } finally { session.close() }
    }
    @Test
    fun disabledOutputDeclinesType103BeforeCallingTheMediaHandler() {
        var setups = 0
        val session = session(disabled = true, media = object : AirPlayMediaHandler {
            override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
                setups++
                return mapOf("type" to 103, "dataPort" to 1234)
            }
        })
        try {
            val response = handle(session, "SETUP", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 103)))))
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, setups)
        } finally { session.close() }
    }

    @Test
    fun malformedControlIsRejectedBeforeItCanDefaultToStartingPlayback() {
        var controls = 0
        val session = session(media = object : AirPlayMediaHandler {
            override fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? {
                controls++
                return null
            }
        })
        try {
            assertEquals(400, handle(session, "SETRATE", byteArrayOf(1, 2, 3)).status)
            assertEquals(0, controls)
            assertEquals(200, handle(session, "GETANCHOR", ByteArray(0)).status)
            assertEquals(1, controls)
        } finally { session.close() }
    }

    @Test
    fun rejectedReplacementPreservesTheExistingBufferedStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            val first = engine.onBufferedAudio(session, validSetup())!!
            assertNull(engine.onBufferedAudio(session, validSetup() + ("shk" to ByteArray(8))))
            assertNull(engine.onBufferedAudio(session, validSetup() + ("spf" to 960)))
            Socket("127.0.0.1", first["dataPort"] as Int).use { }
            val feedback = engine.onFeedback(session)!!["streams"] as List<*>
            assertEquals(1, feedback.size)
            assertEquals(103, (feedback.single() as Map<*, *>)["type"])
        } finally { session.close() }
    }

    @Test
    fun missingOrInvalidRateCannotStartAValidStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            assertNull(engine.onBufferedAudioControl(session, "SETRATE", emptyMap()))
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 2)))
            assertNull(engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap()))
            assertEquals(1, engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024L))!!["rate"])
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 0.5)))
            assertEquals(1, engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!["rate"])
        } finally { session.close() }
    }

    @Test
    fun replacingTheSessionClosesOldPreloadAndLateOldCleanupCannotStopTheNewOutput() {
        val events = CopyOnWriteArrayList<String>()
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { events += "start:$firstSample" }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events += "rtp:$sample" }
            override fun onAudioStopped(id: AudioStreamId) { events += "stop" }
        }
        val engine = CarPlayMediaEngine(sink)
        val old = session(media = engine)
        val replacement = session(media = engine)
        try {
            val oldSetup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", oldSetup["dataPort"] as Int).use { oldSocket ->
                sendFrame(oldSocket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                waitFor { events.contains("rtp:1024") }
                val newSetup = engine.onBufferedAudio(replacement, validSetup())!!
                oldSocket.soTimeout = 1000
                assertEquals("The retired TCP preload must be cancelled", -1, oldSocket.getInputStream().read())
                Socket("127.0.0.1", newSetup["dataPort"] as Int).use { newSocket ->
                    sendFrame(newSocket, 1, 100000)
                    engine.onBufferedAudioControl(replacement, "SETRATE", mapOf("rate" to 1, "rtpTime" to 100000))
                    waitFor { events.contains("rtp:100000") }
                    val started = events.indexOf("start:100000")
                    assertNull(engine.onBufferedAudioControl(old, "SETRATEANCHORTIME", mapOf("rate" to 0)))
                    engine.onTeardown(old, 103)
                    old.close()
                    sendFrame(newSocket, 2, 101024)
                    waitFor { events.contains("rtp:101024") }
                    assertTrue("Late old cleanup stopped the replacement renderer", events.drop(started).none { it == "stop" })
                    assertEquals(1, engine.onBufferedAudioControl(replacement, "GETANCHOR", emptyMap())!!["rate"])
                }
            }
        } finally { old.close(); replacement.close() }
    }

    @Test
    fun aRetiredSessionCannotReclaimTheBufferedRendererWithALateSetup() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = session(media = engine)
        val replacement = session(media = engine)
        try {
            assertTrue(engine.onBufferedAudio(old, validSetup()) != null)
            assertTrue(engine.onBufferedAudio(replacement, validSetup()) != null)
            assertNull(engine.onBufferedAudio(old, validSetup()))
            old.close()
            assertNull(engine.onBufferedAudio(old, validSetup()))
            assertTrue(engine.onFeedback(replacement) != null)
        } finally { old.close(); replacement.close() }
    }

    @Test
    fun replacementWaitsForDeliveryWithoutHoldingTheEngineLockAcrossAReentrantCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reentered = AtomicBoolean(false)
        val holdTimedOut = AtomicBoolean(false)
        lateinit var engine: CarPlayMediaEngine
        lateinit var old: AirPlaySession
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                entered.countDown()
                if (!release.await(30, TimeUnit.SECONDS)) {
                    holdTimedOut.set(true)
                    return
                }
                reentered.set(engine.onBufferedAudioControl(old, "GETANCHOR", emptyMap()) == null)
            }
        })
        old = session(media = engine)
        val registrations = bufferedRegistrations(engine)
        val replacement = session(media = engine)
        val third = session(media = engine)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                // feedback/anchor acquire the stream lock held by this callback. Observe only
                // the concurrent registry so the test does not release delivery by timing out.
                assertTrue(registrations.containsKey(old))
                val pending = executor.submit<Map<String, Any?>?> { engine.onBufferedAudio(replacement, validSetup()) }
                waitFor(10) { !registrations.containsKey(old) }
                assertTrue("Held sink callback timed out before controlled release", !holdTimedOut.get())
                assertTrue("Replacement must finish old delivery before publishing its port", !pending.isDone)
                assertNull(engine.onBufferedAudio(third, validSetup()))
                release.countDown()
                assertTrue(pending.get(10, TimeUnit.SECONDS) != null)
                assertTrue("Held sink callback timed out before controlled release", !holdTimedOut.get())
                assertTrue("Engine lock blocked an old sink callback during retirement", reentered.get())
            }
        } finally {
            release.countDown()
            old.close(); replacement.close(); third.close(); executor.shutdownNow()
        }
    }

    @Test
    fun closingThePendingReplacementCannotReleaseTheBarrierBeforeOldOutputCleanup() {
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val holdTimedOut = AtomicBoolean(false)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { delivered.countDown() }
            override fun onAudioStopped(id: AudioStreamId) {
                stopped.countDown()
                holdTimedOut.set(!release.await(30, TimeUnit.SECONDS))
            }
        })
        val old = session(media = engine)
        val replacement = session(media = engine)
        val third = session(media = engine)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(delivered.await(10, TimeUnit.SECONDS))
                val pending = executor.submit<Map<String, Any?>?> { engine.onBufferedAudio(replacement, validSetup()) }
                assertTrue(stopped.await(10, TimeUnit.SECONDS))
                replacement.close()
                assertTrue("Held output cleanup timed out before controlled release", !holdTimedOut.get())
                assertNull(engine.onBufferedAudio(third, validSetup()))
                release.countDown()
                assertNull(pending.get(10, TimeUnit.SECONDS))
                assertTrue("Held output cleanup timed out before controlled release", !holdTimedOut.get())
                assertNull(engine.onBufferedAudio(replacement, validSetup()))
                assertTrue(engine.onBufferedAudio(third, validSetup()) != null)
            }
        } finally {
            release.countDown()
            old.close(); replacement.close(); third.close(); executor.shutdownNow()
        }
    }

    @Test
    fun reentrantSetupDuringOldStopIsDeclinedUntilThatCallbackCompletes() {
        val delivered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val declined = AtomicBoolean(false)
        lateinit var engine: CarPlayMediaEngine
        lateinit var replacement: AirPlaySession
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { delivered.countDown() }
            override fun onAudioStopped(id: AudioStreamId) {
                declined.set(engine.onBufferedAudio(replacement, validSetup()) == null)
                stopped.countDown()
            }
        })
        val old = session(media = engine)
        replacement = session(media = engine)
        try {
            val setup = engine.onBufferedAudio(old, validSetup())!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendFrame(socket, 0, 1024)
                engine.onBufferedAudioControl(old, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024))
                assertTrue(delivered.await(3, TimeUnit.SECONDS))
                // A malformed TCP frame closes the stream from its receiver, outside the engine.
                DataOutputStream(socket.getOutputStream()).apply { writeShort(2); flush() }
                assertTrue(stopped.await(3, TimeUnit.SECONDS))
                assertTrue(declined.get())
                waitFor { engine.onBufferedAudio(replacement, validSetup()) != null }
            }
        } finally { old.close(); replacement.close() }
    }

    @Test
    fun boundedFlushPreservesEarlierMusicAndRejectsLatePacketsAcrossRtpWrap() {
        for ((formatBits, timeScale) in listOf(
            0x800000 to 0L, 0x800000 to 48000L, 0x800000 to 1_000_000_000L,
            0x400000 to 0L, 0x400000 to 44100L, 0x400000 to 1_000_000_000L,
        )) {
            val clockRate = if (formatBits == 0x400000) 44100L else 48000L
            val packets = CopyOnWriteArrayList<Int>()
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                    packets += sample
                }
            })
            val session = session(media = engine)
            val start = 0xfffff000L
            fun timestamp(index: Int) = ((start + index * 1024L) and 0xffffffffL).toInt()
            try {
                val setup = engine.onBufferedAudio(session, validSetup() + ("audioFormat" to formatBits))!!
                Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                    repeat(8) { sendFrame(socket, it, timestamp(it)) }
                    val registration = bufferedRegistrations(engine).values.single()!!
                    val stream = registration.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(registration)
                    val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
                    fun queued(): Int = synchronized(lock) {
                        (BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(stream) as java.util.ArrayDeque<*>).size
                    }
                    waitFor { queued() == 8 }
                    fun mediaTime(index: Int) = ((timestamp(index).toLong() and 0xffffffffL) * timeScale + clockRate - 1) / clockRate
                    val range = if (timeScale > 0) mapOf(
                        "flushFromMediaTimeValue" to mediaTime(3), "flushFromMediaTimeScale" to timeScale,
                        "flushUntilMediaTimeValue" to mediaTime(5), "flushUntilMediaTimeScale" to timeScale,
                    ) else mapOf("flushFromTS" to (timestamp(3).toLong() and 0xffffffffL),
                        "flushUntilTS" to (timestamp(5).toLong() and 0xffffffffL))
                    engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf(
                        "flushFromMediaTimeValue" to (timestamp(3).toLong() and 0xffffffffL), "flushFromMediaTimeScale" to 0,
                        "flushUntilTS" to (timestamp(5).toLong() and 0xffffffffL),
                    ))
                    assertEquals("An invalid lower bound must not clear the prefix", 8, queued())
                    engine.onBufferedAudioControl(session, "FLUSHBUFFERED", range)
                    assertEquals("A bounded flush removed music outside its range", 5, queued())
                    // The receiver can still hold a packet from the discarded interval.
                    sendFrame(socket, 8, timestamp(4))
                    sendFrame(socket, 9, timestamp(8))
                    waitFor { queued() == 6 }
                    engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to start))
                    waitFor { packets.size == 6 }
                    assertEquals(listOf(0, 1, 2, 6, 7, 8).map(::timestamp), packets.toList())
                }
            } finally { session.close() }
        }
    }

    @Test
    fun classic44100MediaTimeControlsUseTheNegotiatedRtpClock() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            engine.onBufferedAudio(session, validSetup() + ("audioFormat" to 0x400000))!!
            for (method in listOf("SETRATE", "SETRATEANCHORTIME")) {
                val anchor = engine.onBufferedAudioControl(session, method, mapOf(
                    "rate" to 1, "mediaTimeValue" to 1_000_000_000L, "mediaTimeScale" to 1_000_000_000L,
                ))!!
                assertEquals("$method must use the negotiated 44.1 kHz clock", 44100L, anchor["rtpTime"])
            }
        } finally { session.close() }
    }

    @Test
    fun resumeConvertsThePhonesNanosecondMediaTimeInsteadOfReusingThePausedAnchor() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            engine.onBufferedAudio(session, validSetup())!!
            engine.onBufferedAudioControl(session, "SETRATE", mapOf("rtpTime" to 1024L, "rate" to 1))
            engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 0))
            for ((scale, value, expected) in listOf(
                Triple(1_000_000_000L, 26894163811583L, 1290919862L),
                Triple(48000L, 1290919862L, 1290919862L),
                Triple(1L, Long.MAX_VALUE, 0xffff4480L),
            )) {
                val anchor = engine.onBufferedAudioControl(session, "SETRATE",
                    mapOf("mediaTimeScale" to scale, "mediaTimeValue" to value, "rate" to 1))!!
                assertEquals("Ignored or overflowed media time $value/$scale", expected, anchor["rtpTime"])
            }
        } finally { session.close() }
    }

    @Test
    fun prefixFlushKeepsTheNextItemsEarlyPacketsWhenItsTimestampMovesBackwards() {
        for (untilSequence in listOf(3219766, 0xffffff)) {
            val packets = CopyOnWriteArrayList<Int>()
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                    sample: Int, gain: Float) { packets += sample }
            })
            val session = session(media = engine, spatial = listOf(65))
            val nextItem = 733668043L
            try {
                val setup = engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                    "audioFormatIndex" to 65, "audioFormat" to 0, "ct" to 0, "audioType" to "media"))!!
                Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                    val registration = bufferedRegistrations(engine).values.single()!!
                    val stream = registration.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(registration)
                    val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
                    fun queued(): Int = synchronized(lock) {
                        (BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(stream) as java.util.ArrayDeque<*>).size
                    }
                    sendApapFrame(socket, untilSequence - 1, 1631388094L)
                    sendApapFrame(socket, (untilSequence + 1) and 0xffffff, nextItem)
                    waitFor { queued() == 2 }
                    engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf(
                        "flushUntilSeq" to untilSequence, "flushUntilMediaTimeValue" to 1636626878L,
                        "flushUntilMediaTimeScale" to 48000,
                    ))
                    assertEquals("A new item uses a different timestamp origin; its sequence is after the flush", 1, queued())
                    // A packet still in TCP is judged by the same sequence boundary.
                    sendApapFrame(socket, untilSequence, 1636626878L)
                    sendApapFrame(socket, (untilSequence + 2) and 0xffffff, nextItem + 1024)
                    waitFor { queued() == 2 }
                    assertTrue(packets.isEmpty())
                    engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to nextItem))
                    waitFor { packets.size == 2 }
                    assertEquals(listOf(nextItem.toInt(), (nextItem + 1024).toInt()), packets.toList())
                }
            } finally { session.close() }
        }
    }

    @Test
    fun boundedSequenceFlushPreservesPacketsOutsideTheRangeEvenWithRepeatedTimestamps() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine, spatial = listOf(65))
        try {
            val setup = engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                "audioFormatIndex" to 65, "audioFormat" to 0, "ct" to 0, "audioType" to "media"))!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                val registration = bufferedRegistrations(engine).values.single()!!
                val stream = registration.javaClass.getDeclaredField("stream").apply { isAccessible = true }.get(registration)
                val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
                fun sequences(): List<Int> = synchronized(lock) {
                    @Suppress("UNCHECKED_CAST")
                    (BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }
                        .get(stream) as java.util.ArrayDeque<BufferedAudioStream.Frame>).map { it.sequence }
                }
                val initial = (0..5).map { (0xfffffc + it) and 0xffffff }
                initial.forEach { sendApapFrame(socket, it, 1000L) }
                waitFor { sequences().size == 6 }
                engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf(
                    "flushFromSeq" to 4, "flushUntilSeq" to 2,
                ))
                assertEquals("A reversed sequence range must not delete the queue", initial, sequences())
                engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf(
                    "flushFromSeq" to 0xfffffe, "flushUntilSeq" to 0,
                    "flushFromTS" to 1000L, "flushUntilTS" to 1000L,
                ))
                assertEquals(listOf(0xfffffc, 0xfffffd, 1), sequences())
            }
        } finally { session.close() }
    }

    @Test
    fun prefixFlushWaitsForTheNewTimelineAnchorBeforeStartingOrReportingPosition() {
        val packets = CopyOnWriteArrayList<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                sample: Int, gain: Float) { packets += sample }
        })
        val session = session(media = engine, spatial = listOf(65))
        try {
            val setup = engine.onBufferedAudio(session, validSetup() + mapOf("packetFormatAPAP" to true,
                "audioFormatIndex" to 65, "audioFormat" to 0, "ct" to 0, "audioType" to "media"))!!
            Socket("127.0.0.1", setup["dataPort"] as Int).use { socket ->
                sendApapFrame(socket, 7838336, 2857654091L)
                engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 2857651171L))
                waitFor { packets.size == 1 }
                engine.onBufferedAudioControl(session, "FLUSHBUFFERED", mapOf(
                    "flushUntilSeq" to 7838337, "flushUntilTS" to 2863670091L,
                ))
                assertNull("The previous timeline anchor survived its prefix flush",
                    engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap()))
                val feedback = engine.onFeedback(session)!!["streams"] as List<*>
                assertTrue("Feedback still reports a position from the flushed timeline",
                    (feedback.single() as Map<*, *>)["sampleTime"] == null)
                sendApapFrame(socket, 7838338, 777529158L)
                // The new item arrived before its SETRATE, as in the captured control sequence.
                Thread.sleep(150)
                assertEquals("New media played against the flushed item's anchor", 1, packets.size)
                engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 777528134L))
                waitFor { packets.size == 2 }
                assertEquals(listOf(2857654091L.toInt(), 777529158L.toInt()), packets.toList())
            }
        } finally { session.close() }
    }

    @Test
    fun consecutiveTimestampFlushesKeepBothRangesCancelled() {
        assertFlushedFrames(false, { stream ->
            stream.flush(2048L, 1024L)
            stream.flush(5120L, 4096L)
        }, listOf(10 to 1536, 11 to 3072, 12 to 4608, 13 to 8192), listOf(3072, 8192))
    }

    @Test
    fun consecutiveSequenceFlushesKeepEarlierBoundedAndPrefixRangesCancelled() {
        for (apap in listOf(false, true)) {
            val mask = if (apap) 0xffffff else 0xffff
            for (prefix in listOf(false, true)) for (offset in listOf(0, mask - 10)) {
                fun sequence(value: Int) = (value + offset) and mask
                assertFlushedFrames(apap, { stream ->
                    stream.flush(null, untilSequence = sequence(12),
                        fromSequence = if (prefix) null else sequence(10))
                    stream.flush(null, untilSequence = sequence(22), fromSequence = sequence(20))
                }, listOf(11 to 1536, 15 to 3072, 21 to 4608, 23 to 8192)
                    .map { (seq, timestamp) -> sequence(seq) to timestamp }, listOf(3072, 8192))
            }
        }
    }

    @Test
    fun sequenceFlushesExpireBeforeTheNextSequenceWrap() {
        for (apap in listOf(false, true)) for (prefix in listOf(false, true)) {
            val mask = if (apap) 0xffffff else 0xffff
            assertFlushedFrames(apap, { stream ->
                stream.flush(null, untilSequence = 12, fromSequence = if (prefix) null else 10)
                stream.flush(null, untilSequence = 22, fromSequence = 20)
            }, listOf(11 to 1536, 15 to 3072, 21 to 4608, 23 to 8192,
                mask / 2 to 9216, mask - 1 to 10240, 11 to 11264, 21 to 12288),
                listOf(3072, 8192, 9216, 10240, 11264, 12288))
        }
    }

    private fun assertFlushedFrames(apap: Boolean, flush: (BufferedAudioStream) -> Unit,
        frames: List<Pair<Int, Int>>, expected: List<Int>) {
        val packets = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(1)
        val sink = object : MediaSink {
            private fun delivered(sample: Int) {
                packets += sample
                if (sample == expected.last()) done.countDown()
            }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) = delivered(sample)
            override fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                sample: Int, gain: Float) = delivered(sample)
        }
        BufferedAudioStream(ByteArray(32), AudioFormat(AudioCodecKind.AAC_LC, 48000, 2, 103), sink,
            nanoTime = { 0L }, apap = apap, spatialFormats = if (apap) listOf(65) else emptyList()).use { stream ->
            flush(stream)
            stream.setRate(0L, 1, BigInteger.ZERO)
            stream.start()
            Socket("127.0.0.1", stream.port).use { socket ->
                frames.forEach { (sequence, timestamp) ->
                    if (apap) sendApapFrame(socket, sequence, timestamp.toLong())
                    else sendFrame(socket, sequence, timestamp)
                }
                assertTrue("The last valid frame did not reach the sink: $packets", done.await(3, TimeUnit.SECONDS))
                assertEquals("Cancelled ranges must preserve the audio between them", expected, packets.toList())
            }
        }
    }

    private fun sendApapFrame(socket: Socket, sequence: Int, timestamp: Long) {
        val cookie = BufferedAacCookieTest.cookie()
        val plain = byteArrayOf(2, 1, 23, 18, cookie.size.toByte()) + cookie + byteArrayOf(0, 0x21)
        val header = java.nio.ByteBuffer.allocate(15).putLong(timestamp).putInt(48000)
            .put((sequence shr 16).toByte()).put((sequence shr 8).toByte()).put(sequence.toByte()).array()
        val tail = java.nio.ByteBuffer.allocate(8).putLong(sequence.toLong()).array()
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val body = header + AirPlayCrypto.chachaSeal(ByteArray(32), nonce, plain, header.copyOfRange(0, 12)) + tail
        DataOutputStream(socket.getOutputStream()).apply { writeInt(body.size + 4); write(body); flush() }
    }

    private fun sendFrame(socket: Socket, sequence: Int, timestamp: Int) {
        val header = java.nio.ByteBuffer.allocate(12).apply {
            put(0x80.toByte()); put(0x60.toByte()); putShort(sequence.toShort()); putInt(timestamp); putInt(0)
        }.array()
        val tail = java.nio.ByteBuffer.allocate(8).putLong(sequence.toLong()).array()
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val encrypted = AirPlayCrypto.chachaSeal(ByteArray(32), nonce, byteArrayOf(0x21), header.copyOfRange(4, 12))
        val body = header + encrypted + tail
        DataOutputStream(socket.getOutputStream()).apply { writeShort(body.size + 2); write(body); flush() }
    }

    private fun bufferedRegistrations(engine: CarPlayMediaEngine): java.util.concurrent.ConcurrentHashMap<*, *> =
        CarPlayMediaEngine::class.java.getDeclaredField("bufferedStreams").apply { isAccessible = true }
            .get(engine) as java.util.concurrent.ConcurrentHashMap<*, *>

    private fun waitFor(seconds: Long = 3, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition not met in time", condition())
    }

    private fun validSetup(): Map<String, Any?> = mapOf(
        "type" to 103, "shk" to ByteArray(32), "audioFormat" to 0x800000,
        "ct" to 4, "spf" to 1024, "streamConnectionID" to 1,
    )

    private fun handle(session: AirPlaySession, method: String, body: ByteArray): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response

    private fun session(disabled: Boolean = false, media: AirPlayMediaHandler, spatial: List<Int> = emptyList(),
        timelineExperiment: Int = 0): AirPlaySession = AirPlaySession(
        socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        },
        config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            mainBufferedAudio = true, disableAudioOutput = disabled,
            bufferedTimelineExperiment = timelineExperiment,
            spatialAudioFormats = spatial,
        ),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = media,
    )
}
