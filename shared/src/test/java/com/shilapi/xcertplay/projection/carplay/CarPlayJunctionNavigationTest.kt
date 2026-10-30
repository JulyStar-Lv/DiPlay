package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.*
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import org.junit.Assert.*
import org.junit.Test

/** Numeric-only replay of the 2026-10-30 bench capture; no roads or destination retained. */
class CarPlayJunctionNavigationTest {
    private val reducer = CarPlayNavigationReducer { 1_000L }
    private fun select(index: Int) = buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(index)) }

    private fun capturedLeft(index: Int = 2) = buildRaw(0x5202) {
        u16(0, 42); u16(1, index); u8(3, 1); u8(8, 0); u8(9, 0)
        u16(10, 94); u16(10, 11); u16(11, 65455) // ff af = -81 degrees, not 65455
    }

    private fun capturedRight() = buildRaw(0x5202) {
        u16(0, 42); u16(1, 3); u8(3, 2); u8(8, 0); u8(9, 0)
        u16(10, 2); u16(10, 94); u16(10, 65414); u16(11, 68)
    }

    @Test fun capturedLeftAndRightPreserveAllOtherRoadsAndKeepExitSeparate() {
        reducer.accept(capturedLeft()); reducer.accept(capturedRight())
        assertNull(reducer.state.junction) // future maneuvers are not the current junction
        reducer.accept(select(2))
        assertEquals(ManeuverKind.LEFT, reducer.state.maneuver)
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION, listOf(94, 11), -81), reducer.state.junction)
        reducer.accept(select(3))
        assertEquals(ManeuverKind.RIGHT, reducer.state.maneuver)
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION, listOf(2, 94, -122), 68), reducer.state.junction)
        assertNull(reducer.state.laneGuidance) // road branches must not create lane arrows
    }

    @Test fun typeOnlyAndAbsentGeometryNeverInventStraightOrDefaultExit() {
        reducer.accept(buildRaw(0x5202) { u16(1, 0); u8(3, 11); u8(9, 0) })
        reducer.accept(select(0))
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION), reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 1); u8(3, 1) })
        reducer.accept(select(1))
        assertNull(reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 1); i16(11, -90) })
        assertEquals(NavigationJunction(exitAngleDegrees = -90), reducer.state.junction)
    }

    @Test fun supportsPackedSignedAnglesAndRoundaboutWithoutRequiringExitInBranches() {
        // Packed encoding is a compatibility test, not an observed bench packet.
        reducer.accept(buildRaw(0x5202) {
            u16(1, 5); u8(9, 1)
            bytes(10, Iap2WireCodec.i16(-180) + Iap2WireCodec.i16(0) + Iap2WireCodec.i16(180))
            i16(10, -45); i16(11, 90)
        })
        reducer.accept(select(5))
        assertEquals(NavigationJunction(JunctionKind.ROUNDABOUT, listOf(-180, 0, 180, -45), 90), reducer.state.junction)
    }

    @Test fun sparsePatchesRetainFieldsButExplicitEmptyBranchesClearOnlyThoseBranches() {
        reducer.accept(capturedLeft()); reducer.accept(select(2))
        val before = reducer.state.junction
        reducer.accept(buildRaw(0x5202) { u16(1, 2); string(4, "Updated road") })
        reducer.accept(buildRaw(0x5201) { u32(10, 80) })
        assertEquals(before, reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 2); i16(11, -70) })
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION, listOf(94, 11), -70), reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 2); void(10) })
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION, emptyList(), -70), reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 2); u8(9, 250) })
        assertEquals(JunctionKind.UNKNOWN, reducer.state.junction!!.kind)
    }

    @Test fun changingIndexOrClearingSelectionDoesNotCarryPreviousJunctionGeometry() {
        reducer.accept(capturedLeft()); reducer.accept(select(2))
        reducer.accept(buildRaw(0x5201) { u16List(13, listOf(10)) })
        assertNull(reducer.state.junction)
        reducer.accept(buildRaw(0x5202) { u16(1, 10); u8(9, 0) })
        assertEquals(NavigationJunction(JunctionKind.INTERSECTION), reducer.state.junction)
        reducer.accept(select(2)); assertEquals(-81, reducer.state.junction!!.exitAngleDegrees)
        reducer.accept(buildRaw(0x5201) { u16List(13, emptyList()) })
        assertNull(reducer.state.junction)
    }

    @Test fun malformedOptionalGeometryCannotPartlyMutateManeuverOrCache() {
        reducer.accept(capturedLeft()); reducer.accept(select(2))
        val before = reducer.state
        val badFields: List<Iap2BodyBuilder.() -> Unit> = listOf(
            { bytes(10, byteArrayOf(0)) },
            { i16(10, -181) },
            { i16(10, 181) },
            { repeat(33) { i16(10, 0) } },
            { bytes(10, ByteArray(66)) },
            { i16(11, 1000) },
            { u8(11, 90) },
            { void(11) },
            { u16(9, 0) },
            { u8(9, 0); u8(9, 1) },
            { i16(11, -90); i16(11, 90) },
        )
        for (bad in badFields) {
            assertFalse(reducer.accept(buildRaw(0x5202) { u16(1, 2); u8(3, 2); string(4, "Invalid"); bad() }))
            assertFalse(reducer.lastFrameValid)
            assertEquals(before, reducer.state)
            reducer.accept(select(2))
            assertEquals(before, reducer.state) // rejected fields must not have reached the cache
        }
    }

    @Test fun futureCacheIsBoundedAndCannotEvictTheCurrentlySelectedJunction() {
        reducer.accept(capturedLeft(0)); reducer.accept(select(0))
        val before = reducer.state.junction
        for (index in 1..140) reducer.accept(buildRaw(0x5202) { u16(1, index); u8(9, 1) })
        reducer.accept(select(0))
        assertEquals(before, reducer.state.junction)
    }

    @Test fun endAndArrivalClearJunctionAndCachedGeometryBeforeAnotherRoute() {
        for (phase in listOf(0, 2)) {
            reducer.accept(capturedLeft()); reducer.accept(select(2))
            reducer.accept(buildRaw(0x5201) { u8(1, phase) })
            assertNull(reducer.state.junction)
            reducer.accept(select(2))
            assertNull(reducer.state.junction)
        }
    }

    @Test fun sourcePublishesJunctionDiagnosticsAndClearsGeometryOnReconnect() {
        val snapshots = mutableListOf<DisplaySnapshot>()
        val events = mutableListOf<String>()
        val source = CarPlayDisplaySource(VehicleDisplayFactory {
            object : VehicleDisplayEndpoint {
                override val capabilities = DisplayCapabilities(navigation = true)
                override fun submit(snapshot: DisplaySnapshot) { snapshots.add(snapshot) }
                override fun close() = Unit
            }
        }, { 1_000L }, onNavigationDiagnostic = events::add)
        source.onFrame(capturedLeft())
        source.start(); source.onFrame(select(2))
        val published = snapshots.last().navigation.junction!!
        assertEquals(-81, published.exitAngleDegrees)
        assertTrue(events.last().contains("junction=INTERSECTION junctionBranches=2 junctionExit=-81"))
        assertThrows(UnsupportedOperationException::class.java) {
            (published.branchAnglesDegrees as MutableList<Int>)[0] = -90
        }
        source.end(); source.start(); source.onFrame(select(2))
        assertNull(snapshots.last().navigation.junction)
        assertEquals(listOf(94, 11), published.branchAnglesDegrees)
        source.close()
    }
}
