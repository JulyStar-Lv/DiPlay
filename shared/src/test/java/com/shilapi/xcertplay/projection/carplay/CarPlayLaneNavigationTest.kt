package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import org.junit.Assert.*
import org.junit.Test

class CarPlayLaneNavigationTest {
    private var now = 1_000L
    private val reducer = CarPlayNavigationReducer { now }
    private fun block(index: Int, count: Int = 4) = buildRaw(0x5204) {
        u16(0, 42); u16(1, index)
        repeat(count) { lane -> group(2) {
            u16(0, lane); u8(1, if (lane == count - 1) 2 else 0)
            i16(2, if (lane == 0) -90 else 0)
            if (lane == count - 1) i16(3, 90)
        } }
    }
    private fun showing(index: Int, show: Boolean = true, maneuver: Int = 7) = buildRaw(0x5201) {
        u8(1, 1); u16List(13, listOf(maneuver))
        u16(16, index); u16(17, 8); u8(18, if (show) 1 else 0)
        u32(10, 150); u32(7, 5000); u64(6, 120)
    }

    @Test fun preloadedGroupsWaitForCurrentIndexAndShowingThenPublishAllLanesInOrder() {
        assertFalse(reducer.accept(block(3)))
        reducer.accept(block(4, 8))
        assertNull(reducer.state.laneGuidance)
        reducer.accept(showing(3, false))
        assertNull(reducer.state.laneGuidance)
        reducer.accept(showing(3))
        assertEquals(listOf(0, 1, 2, 3), reducer.state.laneGuidance!!.lanes.map { it.index })
        assertEquals(listOf(false, false, false, true), reducer.state.laneGuidance!!.lanes.map { it.recommended })
        assertEquals(listOf(0), reducer.state.laneGuidance!!.lanes.last().anglesDegrees)
        assertEquals(90, reducer.state.laneGuidance!!.lanes.last().highlightedAngleDegrees)
        reducer.accept(showing(4))
        assertEquals(8, reducer.state.laneGuidance!!.lanes.size)
        reducer.accept(showing(3, false))
        assertNull(reducer.state.laneGuidance)
    }

    @Test fun selectedBlockCanArriveAfterShowingAndFutureBlockCannotReplaceIt() {
        reducer.accept(showing(2))
        assertNull(reducer.state.laneGuidance)
        assertTrue(reducer.accept(block(2, 2)))
        val shown = reducer.state.laneGuidance
        assertEquals(2, shown!!.lanes.size)
        assertFalse(reducer.accept(block(3, 8)))
        assertEquals(shown, reducer.state.laneGuidance)
    }

    @Test fun freshRouteAuthorizationKeepsPreloadedGeometryAlive() {
        reducer.accept(block(1))
        now += 300_000
        reducer.accept(showing(1))
        assertEquals(now, reducer.state.laneGuidance!!.receivedAtMillis)
        now += 59_000
        reducer.accept(buildRaw(0x5201) { u32(10, 80) })
        assertEquals(now, reducer.state.laneGuidance!!.receivedAtMillis)
    }

    @Test fun boundedFutureCacheNeverEvictsTheCurrentlyVisibleGroup() {
        reducer.accept(block(0)); reducer.accept(showing(0))
        val visible = reducer.state.laneGuidance
        now += 1000
        for (index in 1..16) reducer.accept(block(index, 8))
        assertEquals(visible, reducer.state.laneGuidance) // future data cannot renew its lease
        reducer.accept(buildRaw(0x5201) { u32(10, 80) })
        assertEquals(4, reducer.state.laneGuidance!!.lanes.size)
    }

    @Test fun sparseManeuverSwitchExplicitHideAndEmptyBlockClearOldRow() {
        reducer.accept(block(1)); reducer.accept(showing(1))
        reducer.accept(buildRaw(0x5201) { u16List(13, listOf(8)) })
        assertNull(reducer.state.laneGuidance)
        reducer.accept(showing(1)); assertNotNull(reducer.state.laneGuidance)
        reducer.accept(block(1, 0)); assertTrue(reducer.state.laneGuidance!!.lanes.isEmpty())
        reducer.accept(block(1)); reducer.accept(showing(1)); assertNotNull(reducer.state.laneGuidance)
        reducer.accept(buildRaw(0x5201) { u16(17, 0) })
        assertNull(reducer.state.laneGuidance)
    }

    @Test fun endAndArrivalDiscardCacheAndLateOldRouteData() {
        for (phase in listOf(0, 2)) {
            val route = CarPlayNavigationReducer { now }
            route.accept(block(1)); route.accept(showing(1))
            route.accept(buildRaw(0x5201) { u8(1, phase) })
            assertNull(route.state.laneGuidance)
            route.accept(block(1)) // a delayed old-route packet
            route.accept(showing(1))
            assertNull(route.state.laneGuidance)
            route.accept(block(1)); assertNotNull(route.state.laneGuidance)
        }
    }

    @Test fun malformedRoutePatchAndBadAnglesDoNotPartlyMutateSelection() {
        reducer.accept(block(1)); reducer.accept(showing(1))
        val before = reducer.state
        assertFalse(reducer.accept(buildRaw(0x5201) { u16(16, 2); u8(18, 3) }))
        assertEquals(before, reducer.state)
        assertFalse(reducer.accept(buildRaw(0x5204) {
            u16(0, 42); u16(1, 1); group(2) { u16(0, 0); u8(1, 2) }
        }))
        assertEquals(before, reducer.state)
    }

    @Test fun missingHighlightSentinelAndRepeatedAnglesRemainFaithful() {
        val parsed = CarPlayLaneCapture.parse(buildRaw(0x5204) {
            u16(0, 42); u16(1, 1)
            group(2) { u16(0, 0); u8(1, 0); i16(2, -90); i16(2, 0); i16(3, -181) }
        })!!
        assertEquals(listOf(-90, 0), parsed.lanes.single().anglesDegrees)
        assertNull(parsed.lanes.single().highlightedAngleDegrees)
    }

    @Test fun productionSourcePublishesLanesAndDoesNotLeakThemAcrossReconnect() {
        val snapshots = mutableListOf<DisplaySnapshot>()
        val source = CarPlayDisplaySource(VehicleDisplayFactory {
            object : VehicleDisplayEndpoint {
                override val capabilities = DisplayCapabilities(navigation = true, lanes = true)
                override fun submit(snapshot: DisplaySnapshot) { snapshots.add(snapshot) }
                override fun close() = Unit
            }
        }, { now })
        source.start()
        source.onFrame(block(0)); source.onFrame(showing(0))
        assertEquals(4, snapshots.last().navigation.laneGuidance!!.lanes.size)
        source.end(); source.start(); source.onFrame(showing(0))
        assertNull(snapshots.last().navigation.laneGuidance)
    }
}
