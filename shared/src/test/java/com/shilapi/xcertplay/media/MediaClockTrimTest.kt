package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class MediaClockTrimTest {
    @Before fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }
    @After fun cleanUp() { MediaClockTrim.learnedTrim = 0.0 }

    /**
     * A head unit whose audio clock runs [drift] fast: the iPhone sends 352-frame packets at 48 kHz, with
     * gaps of 50–150 ms followed by a catch-up burst; the head unit plays 1024-frame periods.
     */
    private class Drive(val drift: Double, val trimmed: Boolean, seed: Int = 7) {
        val target = 14_400L // 300 ms at 48 kHz, as #610's music track started
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = target.toDouble()
        var starved = 0
        var lowest = queued
        val rates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var nextPacketMs = 0.0
            var pending = 0
            var gapUntilMs = -1.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (random.nextDouble() < 0.4 / 1000) gapUntilMs = ms + 50 + random.nextDouble() * 100
                while (nextPacketMs <= ms) { pending += 352; nextPacketMs += 352 / 48.0 }
                if (ms >= gapUntilMs && pending > 0) {
                    queued += pending
                    pending = 0
                    if (trimmed) {
                        trim.sample(queued.toLong(), nowNs)
                        trim.rateDue(nowNs)?.let { rate = it; rates += it }
                    }
                }
                played += rate * (1 + drift) / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starved++; queued = 0.0 }
                }
                lowest = minOf(lowest, queued)
            }
        }
    }

    @Test fun withoutTheTrimA076PercentFastHeadUnitRunsDryEveryMinuteOrSo() {
        val drive = Drive(0.0076, trimmed = false)
        drive.run(300)
        assertTrue("starved ${drive.starved}", drive.starved >= 3)
    }

    @Test fun theTrimSettlesAtTheHeadUnitsDriftAndMusicNeverRunsDry() {
        val drive = Drive(0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertTrue("lowest ${drive.lowest}", drive.lowest > 0.2 * drive.target)
        // 48000 / 1.0076: the track plays that much slower, so the fast clock consumes 48 kHz.
        assertEquals(47_638.0, drive.trim.rate.toDouble(), 15.0)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 0.0003)
    }

    @Test fun aSlowHeadUnitIsTrimmedTheOtherWay() {
        val drive = Drive(-0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_368.0, drive.trim.rate.toDouble(), 15.0)
    }

    @Test fun anAccurateClockIsLeftAlone() {
        val drive = Drive(0.0, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_000.0, drive.trim.rate.toDouble(), 5.0)
    }

    @Test fun theTrimStaysWithinOnePointFivePercent() {
        val trim = MediaClockTrim(48_000, 14_400)
        // Nothing queued for minutes: the trim stops at its bound.
        for (tick in 0..30_000) {
            val nowNs = tick * 20_000_000L
            trim.sample(0, nowNs)
            trim.rateDue(nowNs)
        }
        assertEquals(47_280, trim.rate)
    }

    @Test fun withoutNewAudioTheTrimHolds() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(7_200, 0)
        assertNull(trim.rateDue(0))
        // The iPhone paused: no write for 2 s, so no update even though the last level was low.
        assertNull(trim.rateDue(2_000_000_000L))
        assertEquals(48_000, trim.rate)
        // Music resumes at the target level: the average restarts there.
        trim.sample(14_400, 2_100_000_000L)
        assertNull(trim.rateDue(2_100_000_000L))
        assertEquals(48_000, trim.rate)
    }

    @Test fun aLongPauseDoesNotCountAsDrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        trim.rateDue(0)
        // Five minutes later music resumes with a short first burst: one step, not 300 s, is integrated.
        val resumeNs = 300_000_000_000L
        trim.sample(10_080, resumeNs)
        assertNull(trim.rateDue(resumeNs))
        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test fun aNewStreamStartsFromTheLearnedTrim() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        assertEquals(47_640, trim.rateDue(0))
    }

    @Test fun updatesComeAtMostOnceASecond() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(7_200, 0)
        assertNull(trim.rateDue(0))
        trim.sample(14_400, 500_000_000L)
        assertNull(trim.rateDue(500_000_000L))
    }

    @Test fun startupAndRateChangesAreBoundedWithoutDisablingClockRecovery() {
        val trim = MediaClockTrim(48_000, 14_400)
        var previous = trim.rate
        for (tick in 0..1500) {
            val now = tick * 20_000_000L
            trim.sample(0, now)
            trim.rateDue(now)
            if (tick < 150) assertEquals(48_000, trim.rate)
            assertTrue("rate step at $tick: $previous -> ${trim.rate}", kotlin.math.abs(trim.rate - previous) <= 48)
            previous = trim.rate
        }
        assertEquals(47_280, trim.rate)
    }

    @Test fun writerBackpressureIsNotLearnedAndItsOldLevelIsForgottenAfterRecovery() {
        val trim = MediaClockTrim(48_000, 14_400)
        for (tick in 0..2250) {
            val now = tick * 20_000_000L
            trim.sample(16_320, now, compressedFrames = 9_000, backpressured = true)
            trim.rateDue(now)
        }
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
        assertTrue(trim.rate > 48_000)
        for (tick in 2251..4500) {
            val now = tick * 20_000_000L
            trim.sample(14_400, now)
            trim.rateDue(now)
        }
        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
    }

    @Test fun zeroTargetAndNegativeQueueInputsStaySafe() {
        val disabled = MediaClockTrim(48_000, 0)
        disabled.sample(-1, 0, -1)
        assertNull(disabled.rateDue(0))
        assertEquals(48_000, disabled.rate)
        val trim = MediaClockTrim(48_000, 14_400)
        for (tick in 0..1500) {
            val now = tick * 20_000_000L
            trim.sample(-1, now, -1)
            trim.rateDue(now)
        }
        assertEquals(47_280, trim.rate)
    }

    @Test fun aLargeCompressedQueueMustNotTrickClockControlIntoSlowingDownFurther() {
        val trim = MediaClockTrim(48_000, 14_400)
        // The #806 recording had 150-192 pending AAC packets while Android's track writes
        // reported a small hardware PCM queue. Those packets are not 'missing' music.
        for (second in 1..12) {
            trim.sample(4_800, second * 1_000_000_000L, compressedFrames = 96 * 1024L)
            trim.rateDue(second * 1_000_000_000L)
        }
        assertTrue("slow playback grows a 2-second packet backlog; rate=${trim.rate}", trim.rate >= 48_000)
        // Decoder congestion is not a characteristic of the vehicle's playback clock. A future
        // song should not inherit a +1.5% learned pitch error caused by a temporary stall.
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-8)
    }

    @Test fun pacedPipelineSurvivesStartupBurstsRadioGapsAndBothDirectionsOfClockDrift() {
        for (millis in listOf(300, 1000)) for (drift in listOf(-0.0076, 0.0, 0.0076)) {
            MediaClockTrim.learnedTrim = 0.0
            val target = 48L * millis
            val trim = MediaClockTrim(48_000, target)
            var track = target.toDouble()
            var upstream = 9_600 // 200-ms catch-up burst at music restart.
            var pcm = 0
            var nextPacket = 0.0
            var heldPackets = 0
            var consumed = 0.0
            var lastPaced = -1_000
            var starved = 0
            var peakTrack = track
            var peakUpstream = upstream
            var rate = 48_000
            for (ms in 0 until 300_000) {
                // A 120-ms radio gap every 13 seconds, then an intact delivery burst.
                while (nextPacket <= ms) { heldPackets += 1024; nextPacket += 1024 / 48.0 }
                if (ms % 13_000 !in 4_000..4_119) { upstream += heldPackets; heldPackets = 0 }
                val decoded = minOf(upstream, 1920 - pcm)
                upstream -= decoded; pcm += decoded
                while (pcm > 0) {
                    val frames = AudioWritePacer.allowedBytes(minOf(pcm, 480) * 4,
                        track.toLong(), target, 48_000, 4, true) / 4
                    if (frames == 0) { lastPaced = ms; break }
                    track += frames; pcm -= frames
                    val now = ms * 1_000_000L
                    trim.sample(track.toLong(), now, upstream.toLong(), ms - lastPaced < 250)
                    trim.rateDue(now)?.let { rate = it }
                }
                peakTrack = maxOf(peakTrack, track)
                peakUpstream = maxOf(peakUpstream, upstream)
                consumed += rate * (1 + drift) / 1000
                while (consumed >= 1024) {
                    consumed -= 1024
                    if (track < 1024) { starved++; track = 0.0 } else track -= 1024
                }
            }
            assertEquals("drift=$drift", 0, starved)
            assertTrue("target=$target track=$peakTrack", peakTrack <= target + 1920)
            assertTrue("compressed backlog grows without bound: $peakUpstream", peakUpstream < 48_000)
            assertEquals("drift=$drift rate=$rate", 48_000 / (1 + drift), rate.toDouble(), 100.0)
            assertTrue("startup burst must not teach +/-1.5%: ${MediaClockTrim.learnedTrim}",
                kotlin.math.abs(MediaClockTrim.learnedTrim) < 0.012)
        }
    }

    @Test fun repeated390MillisBatchesDoNotPostponeClockRecoveryForever() {
        val trim = MediaClockTrim(48_000, 48_000)
        for (ms in 0L..30_000L step 390L) repeat(4) { chunk ->
            val now = ms * 1_000_000L + chunk * 10_000L
            trim.sample(43_200, now)
            trim.rateDue(now)
        }
        assertTrue("rate=${trim.rate}", trim.rate < 48_000)
        assertTrue(MediaClockTrim.learnedTrim < 0.0)
        val state = trim.diagnostics(29_640_030_000L)
        assertTrue(state, state.contains("clockState=active"))
        assertTrue(state, state.contains("clockRestarts=1"))
    }

    @Test fun completedBatchPeaksRecoverBothClockDirectionsWithoutLearningTheFirstPartialWrite() {
        for (cadence in listOf(20, 390, 741)) for (drift in listOf(-0.0076, 0.0, 0.0076)) {
            MediaClockTrim.learnedTrim = 0.0
            val trim = MediaClockTrim(48_000, 48_000)
            var queued = 48_000.0
            var held = 0
            var nextPacket = 1024 / 48.0
            var consumed = 0.0
            var rate = 48_000
            var starved = 0
            var previousRate = rate
            var previousUpdateNs = 0L
            for (ms in 0..300_000) {
                while (nextPacket <= ms) { held += 1024; nextPacket += 1024 / 48.0 }
                if (ms > 0 && ms % cadence == 0) {
                    var chunk = 0
                    while (held > 0) {
                        val frames = minOf(480, held)
                        held -= frames; queued += frames
                        val now = ms * 1_000_000L + chunk++ * 10_000L
                        trim.sample(queued.toLong(), now)
                        trim.rateDue(now)?.let {
                            val seconds = ((now - previousUpdateNs) / 1e9).coerceAtMost(2.0)
                            assertTrue("cadence=$cadence rate $previousRate -> $it",
                                kotlin.math.abs(it - previousRate) <= maxOf(1, (48 * seconds).toInt() + 1))
                            previousRate = it; previousUpdateNs = now; rate = it
                        }
                    }
                }
                consumed += rate * (1 + drift) / 1000
                while (consumed >= 1024) {
                    consumed -= 1024
                    if (queued < 1024) { starved++; queued = 0.0 } else queued -= 1024
                }
            }
            assertEquals("cadence=$cadence drift=$drift", 0, starved)
            assertEquals("cadence=$cadence drift=$drift rate=$rate", 48_000 / (1 + drift), rate.toDouble(), 30.0)
            assertEquals("cadence=$cadence drift=$drift learned", 1 / (1 + drift) - 1, MediaClockTrim.learnedTrim, 0.0006)
        }
    }

    @Test fun explicitRebufferRestartsSettlingAndKeepsTheLearnedClockCorrection() {
        val trim = MediaClockTrim(48_000, 48_000, integral = -0.0075)
        trim.sample(48_000, 0)
        assertEquals(47_640, trim.rateDue(0))
        trim.restart()
        for (ms in 500L..3_000L step 20L) {
            trim.sample(36_000, ms * 1_000_000L)
            assertNull(trim.rateDue(ms * 1_000_000L))
        }
        assertEquals(47_640, trim.rate)
    }
}
