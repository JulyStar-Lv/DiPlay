package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class CarPlayDisplaySourceTest {
    private class Output : VehicleDisplayEndpoint {
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

    @Test fun amapStartRouteKeepsItsMeaningAndDoesNotSelectThePreloadedNextTurn() {
        source.start()
        source.onFrame(buildRaw(0x5202) {
            u16(1, 4); u8(3, 11); string(4, "请行驶到西南门")
        })
        source.onFrame(buildRaw(0x5202) {
            u16(1, 5); u8(3, 1); string(4, "进入 天元西路")
        })
        source.onFrame(buildRaw(0x5201) {
            u8(1, 1); u16List(13, listOf(4, 5)); u32(10, 3000); u32(7, 19100); u64(6, 1740)
        })
        assertEquals("START_ROUTE", latest().navigation.maneuver.name)
        assertEquals("请行驶到西南门", latest().navigation.nextRoad)
        assertEquals(3000L, latest().navigation.distanceMeters)
        source.onFrame(buildRaw(0x5201) { u16List(13, listOf(5)); u32(10, 120) })
        assertEquals(ManeuverKind.LEFT, latest().navigation.maneuver)
        assertEquals("进入 天元西路", latest().navigation.nextRoad)
        assertEquals(120L, latest().navigation.distanceMeters)
    }

    @Test fun appleAndAmapSpecialManeuversKeepTheirSemanticsThroughSparseUpdates() {
        // CPManeuver.h from the iOS SDK; keep-left, ramps and highway changes are not slight turns.
        val cases = mapOf(
            0 to ManeuverKind.UNKNOWN, 3 to ManeuverKind.STRAIGHT, 5 to ManeuverKind.FOLLOW_ROAD,
            8 to ManeuverKind.OFF_RAMP, 9 to ManeuverKind.ON_RAMP, 11 to ManeuverKind.START_ROUTE,
            13 to ManeuverKind.KEEP_LEFT, 14 to ManeuverKind.KEEP_RIGHT,
            15 to ManeuverKind.FERRY_ENTER, 16 to ManeuverKind.FERRY_EXIT, 17 to ManeuverKind.FERRY_CHANGE,
            18 to ManeuverKind.START_ROUTE_U_TURN_LEFT, 19 to ManeuverKind.ROUNDABOUT_U_TURN,
            20 to ManeuverKind.AT_END_LEFT, 21 to ManeuverKind.AT_END_RIGHT,
            22 to ManeuverKind.OFF_RAMP_LEFT, 23 to ManeuverKind.OFF_RAMP_RIGHT,
            24 to ManeuverKind.DESTINATION_LEFT, 25 to ManeuverKind.DESTINATION_RIGHT,
            26 to ManeuverKind.U_TURN_WHEN_POSSIBLE_LEFT,
            49 to ManeuverKind.SLIGHT_LEFT, 50 to ManeuverKind.SLIGHT_RIGHT,
            51 to ManeuverKind.CHANGE_HIGHWAY, 52 to ManeuverKind.CHANGE_HIGHWAY_LEFT,
            53 to ManeuverKind.CHANGE_HIGHWAY_RIGHT, 250 to ManeuverKind.UNKNOWN,
        )
        source.start()
        for ((type, expected) in cases) {
            source.onFrame(buildRaw(0x5202) { u16(1, type); u8(3, type); u8(8, 0); string(4, "Road $type") })
            source.onFrame(buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(type)); u32(10, 150) })
            assertEquals("type $type", expected, latest().navigation.maneuver)
            source.onFrame(buildRaw(0x5201) { u32(10, 100) })
            assertEquals(expected, latest().navigation.maneuver)
            assertEquals("Road $type", latest().navigation.nextRoad)
            assertNull(latest().navigation.roundaboutExit)
        }
        // Unknown traffic side cannot choose the direction of a U-turn.
        for ((type, expected) in mapOf(4 to ManeuverKind.U_TURN_RIGHT,
            18 to ManeuverKind.START_ROUTE_U_TURN_RIGHT, 26 to ManeuverKind.U_TURN_WHEN_POSSIBLE_RIGHT)) {
            source.onFrame(buildRaw(0x5202) { u16(1, type); u8(3, type); u8(8, 1) })
            source.onFrame(buildRaw(0x5201) { u16List(13, listOf(type)) })
            assertEquals(expected, latest().navigation.maneuver)
            source.onFrame(buildRaw(0x5202) { u16(1, type); u8(8, 255) })
            assertEquals(ManeuverKind.UNKNOWN, latest().navigation.maneuver)
        }
        for (type in 28..46) {
            source.onFrame(buildRaw(0x5202) { u16(1, type); u8(3, type) })
            source.onFrame(buildRaw(0x5201) { u16List(13, listOf(type)) })
            assertEquals(ManeuverKind.ROUNDABOUT_NUMBERED, latest().navigation.maneuver)
            assertEquals(type - 27, latest().navigation.roundaboutExit)
        }
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

    @Test fun musicMetadataRetainsUnknownPlaybackUntilAStatusArrives() {
        source.start()
        source.onMedia(CarPlayNowPlaying(title = "dynamic line", artist = "Artist"))
        assertEquals("dynamic line", latest().media.title)
        assertEquals(PlaybackState.UNKNOWN, latest().media.playback)
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
