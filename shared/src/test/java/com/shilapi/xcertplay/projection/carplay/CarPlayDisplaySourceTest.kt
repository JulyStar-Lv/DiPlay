package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class CarPlayDisplaySourceTest {
    private class Output : VehicleDisplayEndpoint {
        override val capabilities = DisplayCapabilities(navigation = true, mediaText = true, calls = true)
        val snapshots = mutableListOf<DisplaySnapshot>()
        var closed = false
        override fun submit(snapshot: DisplaySnapshot) { check(!closed); snapshots.add(snapshot) }
        override fun close() { closed = true }
    }
    private val outputs = mutableListOf<Output>()
    private var now = 1_000L
    private val source = CarPlayDisplaySource(VehicleDisplayFactory { Output().also { outputs.add(it) } }, { now })
    private fun latest() = outputs.last().snapshots.last()

    @Test fun retainsInitialMetadataThenClearsAndChangesEpochOnReconnect() {
        source.onMedia(CarPlayNowPlaying(title = "A", artist = "B", playing = true))
        assertTrue(outputs.isEmpty())
        source.start()
        val old = latest().session
        assertEquals("A", latest().media.title)
        source.end()
        assertTrue(outputs.single().closed)
        source.onMedia(CarPlayNowPlaying(title = "late old song"))
        source.start()
        assertNotEquals(old, latest().session)
        assertEquals(MediaState(), latest().media)
        source.close()
        source.start()
        assertEquals(2, outputs.size)
    }

    @Test fun combinesRouteCacheAndIncrementsWithoutInventingMissingDistance() {
        source.onFrame(buildRaw(0x5202) { u16(1, 7); u8(3, 1); u8(8, 0); string(4, "Next") })
        source.start()
        source.onFrame(buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(7, 5000); u64(6, 120) })
        assertEquals(ManeuverKind.LEFT, latest().navigation.maneuver)
        assertNull(latest().navigation.distanceMeters)
        source.onFrame(buildRaw(0x5201) { u32(10, 150) })
        val retained = latest().navigation
        assertEquals(150L, retained.distanceMeters)
        assertEquals(120_000L, retained.remainingMillis)
        assertEquals("Next", retained.nextRoad)
        assertTrue(outputs.single().snapshots.zipWithNext().all { (a, b) -> b.revision > a.revision })
        source.onFrame(buildRaw(0x5201) { u8(1, 2) })
        assertEquals(NavigationPhase.ARRIVED, latest().navigation.phase)
        assertNull(latest().navigation.distanceMeters)
        source.onFrame(buildRaw(0x5201) { u8(1, 0) })
        assertEquals(NavigationPhase.ENDED, latest().navigation.phase)
    }

    @Test fun malformedPatchIsAtomicAndUnknownManeuverStaysUnknown() {
        source.start()
        source.onFrame(buildRaw(0x5202) { u16(1, 3); u8(3, 250) })
        source.onFrame(buildRaw(0x5201) { u16List(13, listOf(3)); u32(10, 10); string(3, "Old") })
        val before = latest()
        source.onFrame(buildRaw(0x5201) { string(3, "Invalid new road"); u8(10, 1) }) // wrong scalar size
        assertEquals(before, latest())
        assertEquals(ManeuverKind.UNKNOWN, latest().navigation.maneuver)
        now = 2_000
        source.onFrame(buildRaw(0x5201) { u16List(13, emptyList()) })
        assertEquals(ManeuverKind.UNKNOWN, latest().navigation.maneuver)
        assertNull(latest().navigation.distanceMeters)
    }

    @Test fun callsKeepIdentityExplicitClearAndOriginalConnectedTimeAcrossWaiting() {
        source.start()
        source.onFrame(buildRaw(0x4155) { string(4, "a"); u8(2, 2); string(0, "123"); string(1, "Alice") })
        source.onFrame(buildRaw(0x4155) { string(4, "a"); u8(2, 4) })
        val connected = latest().calls.single().connectedAtMillis
        now = 7_000
        source.onFrame(buildRaw(0x4155) { string(4, "b"); u8(2, 2); string(1, "Bob") })
        assertEquals(2, latest().calls.size)
        source.onFrame(buildRaw(0x4155) { string(4, "b"); u8(2, 0) })
        source.onFrame(buildRaw(0x4155) { string(4, "a"); string(1, "") })
        val remaining = latest().calls.single()
        assertNull(remaining.name)
        assertEquals("123", remaining.number)
        assertEquals(connected, remaining.connectedAtMillis)
        source.end()
        source.start()
        assertTrue(latest().calls.isEmpty())
    }

    @Test fun musicTitleDoesNotBecomeInventedLyrics() {
        source.start()
        source.onMedia(CarPlayNowPlaying(title = "dynamic line", artist = "Artist"))
        assertEquals("dynamic line", latest().media.title)
        assertEquals(PlaybackState.UNKNOWN, latest().media.playback)
        assertEquals(LyricsSupport.NONE, latest().media.lyricsSupport)
        assertNull(latest().media.currentLyric)
        source.onMedia(CarPlayNowPlaying(artist = "Artist"))
        assertNull(latest().media.title)
        source.onFrame(buildRaw(0x5001) { group(1) { u8(0, 1) } })
        source.onMedia(CarPlayNowPlaying(artist = "Artist", playing = true))
        assertEquals(PlaybackState.PLAYING, latest().media.playback)
        source.onFrame(buildRaw(0x5001) { group(1) { u8(0, 0) } })
        assertEquals(PlaybackState.PAUSED, latest().media.playback)
    }

    @Test fun failingDisplayDoesNotEscapeIntoPhoneIngress() {
        var failures = 0
        val bad = CarPlayDisplaySource(VehicleDisplayFactory { throw SecurityException("private data") }, onFailure = { failures++ })
        bad.start()
        bad.onFrame(buildRaw(0x5201) { u8(1, 0) })
        bad.close()
        assertEquals(1, failures)
    }

    @Test fun navigationDiagnosticsWhitelistFieldsAndDistinguishMalformedFrames() {
        val events = mutableListOf<String>()
        val observed = CarPlayDisplaySource(VehicleDisplayFactory { Output() }, { now }, onNavigationDiagnostic = events::add)
        observed.start()
        observed.onFrame(buildRaw(0x5202) { u16(1, 7); u8(3, 1); string(4, "Private next road") })
        observed.onFrame(buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); string(3, "Private current road"); u32(10, 150); u32(7, 5000); u64(6, 120) })
        assertTrue(events.last().contains("valid=true changed=true active=true phase=GUIDING maneuver=LEFT distance=150 remaining=5000 seconds=120"))
        observed.onFrame(buildRaw(0x5201) { string(3, "Private invalid road"); u8(10, 1) })
        assertTrue(events.last().contains("valid=false changed=false"))
        observed.onFrame(buildRaw(0x4155) { string(4, "private-call"); string(1, "Private caller"); u8(2, 2) })
        assertEquals(4, events.size) // start + three navigation frames only
        assertFalse(events.joinToString().contains("Private"))
        observed.end()
        assertTrue(events.last().contains("action=end"))
    }

    @Test fun failingNavigationDiagnosticsCannotInterruptPhoneOrCleanup() {
        val output = Output()
        val observed = CarPlayDisplaySource(VehicleDisplayFactory { output }, onNavigationDiagnostic = { throw IllegalStateException() })
        observed.start()
        observed.onFrame(buildRaw(0x5201) { u8(1, 1); u32(10, 50) })
        assertEquals(50L, output.snapshots.last().navigation.distanceMeters)
        observed.end()
        assertTrue(output.closed)
    }
}
