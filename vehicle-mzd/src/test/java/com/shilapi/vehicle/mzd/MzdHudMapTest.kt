package com.shilapi.vehicle.mzd

import android.view.Surface
import com.shilapi.display.android.*
import com.shilapi.display.api.*
import org.junit.Assert.*
import org.junit.Test

class MzdHudMapTest {
    @Test fun phoneCardGetsTheWholeViewportRatherThanTheMapMarkerRegion() {
        for (content in listOf(MapStreamContent.INSTRUCTION_CARD, MapStreamContent.MAP_WITH_INSTRUCTION_CARD)) {
            val card = MzdHudMapProfile.request(content)
            assertEquals(content, card.content)
            assertEquals(listOf(0, 0, 0, 0), listOf(card.insetLeft, card.insetTop, card.insetRight, card.insetBottom))
            assertEquals(178f to 178f, MzdHudMapProfile.fittedSize(card.width, card.height))
        }
        assertTrue(MzdHudMapProfile.request(MapStreamContent.MAP).insetTop > 0)
    }
    @Test fun onlyMeasuredNonPrimaryUnrotatedLayoutIsAccepted() {
        assertTrue(MzdHudMapProfile.accepts("MeterDisplay#0", 800, 480, 0, 7, true))
        assertFalse(MzdHudMapProfile.accepts("MeterDisplay#0", 800, 480, 0, 0, true))
        assertFalse(MzdHudMapProfile.accepts("MeterDisplay#0", 800, 400, 0, 7, true))
        assertFalse(MzdHudMapProfile.accepts("MeterDisplay#0", 800, 480, Surface.ROTATION_90, 7, true))
        assertFalse(MzdHudMapProfile.accepts("other", 800, 480, 0, 7, true))
        assertFalse(MzdHudMapProfile.accepts("MeterDisplay#0", 800, 480, 0, 7, false))
    }

    @Test fun unexpectedWideMapIsContainedWithoutCroppingOrStretching() {
        val (width, height) = MzdHudMapProfile.fittedSize(1280, 720)
        assertEquals(178f, width, 0.001f)
        assertEquals(100.125f, height, 0.001f)
        assertEquals(1280f / 720, width / height, 0.001f)
        assertEquals(178f to 178f, MzdHudMapProfile.fittedSize(352, 352))
    }

    private class Gate : MzdMapGate {
        var allowed = false
        var junction: NavigationJunction? = null
        override val request = MzdHudMapProfile.request
        override fun navigationAllowed(allowed: Boolean) { this.allowed = allowed; junction = null }
        override fun navigationAllowed(allowed: Boolean, junction: NavigationJunction?) {
            this.allowed = allowed; this.junction = junction
        }
        override fun attach(consumer: MapSurfaceConsumer): VehicleMapLease = error("No Android surface in gate tests")
    }
    private class Transport : MzdTransport {
        override val alive = true
        var focused = true
        override fun focused(domain: MzdDomain) = focused
        override fun request(domain: MzdDomain) = Unit
        override fun release(domain: MzdDomain) = Unit
        override fun navigationMode(active: Boolean) = Unit
        override fun navigation(value: MzdNavigation) = Unit
        override fun media(json: String) = Unit
        override fun phone(json: String) = Unit
        override fun callTime(seconds: Int) = Unit
        override fun close() = Unit
    }

    @Test fun mapFollowsConfirmedNavigationFocusExpiryAndSessionOwnership() {
        var now = 1000L
        val gate = Gate()
        val transport = Transport()
        val factory = MzdVehicleDisplayFactory({}, { now }, { transport }, false, mapOutput = gate)
        fun snapshot(key: SourceSessionKey, revision: Long) = DisplaySnapshot(key, revision,
            NavigationState(NavigationPhase.GUIDING, ManeuverKind.LEFT, distanceMeters = 50,
                remainingMeters = 2000, remainingMillis = 120000, receivedAtMillis = now))
        val key = SourceSessionKey("phone", 1)
        val first = factory.open(key)
        first.submit(snapshot(key, 1))
        factory.pump()
        assertFalse(gate.allowed) // OEM mode has not settled
        now += 400
        factory.pump()
        assertTrue(gate.allowed)
        transport.focused = false
        factory.pump()
        assertFalse(gate.allowed)
        transport.focused = true
        factory.pump()
        now += 400
        factory.pump()
        assertTrue(gate.allowed)
        now += 90001
        factory.pump()
        assertFalse(gate.allowed)
        first.submit(snapshot(key, 2))
        factory.pump()
        now += 400
        factory.pump()
        assertTrue(gate.allowed)
        val secondKey = SourceSessionKey("phone", 2)
        val second = factory.open(secondKey)
        assertFalse(gate.allowed)
        second.submit(snapshot(secondKey, 1))
        factory.pump()
        now += 400
        factory.pump()
        assertTrue(gate.allowed)
        first.close()
        assertTrue(gate.allowed) // late close cannot hide successor
        second.close()
        assertFalse(gate.allowed)
    }

    @Test fun junctionUsesTheSameNavigationLeaseAndClearsOnIndexEndExpiryAndOwnershipChanges() {
        var now = 1000L
        val gate = Gate()
        val transport = Transport()
        val factory = MzdVehicleDisplayFactory({}, { now }, { transport }, false, mapOutput = gate)
        val key = SourceSessionKey("phone", 1)
        val endpoint = factory.open(key)
        val angles = mutableListOf(94, 11)
        val state = NavigationState(NavigationPhase.GUIDING, ManeuverKind.LEFT,
            distanceMeters = 50, remainingMeters = 2000, remainingMillis = 120000,
            receivedAtMillis = now, junction = NavigationJunction(JunctionKind.INTERSECTION, angles, -81))
        endpoint.submit(DisplaySnapshot(key, 1, state)); angles[0] = -180
        factory.pump(); assertNull(gate.junction)
        now += 400; factory.pump()
        assertEquals(listOf(94, 11), gate.junction!!.branchAnglesDegrees)
        transport.focused = false; factory.pump(); assertNull(gate.junction)
        transport.focused = true; factory.pump(); now += 400; factory.pump()
        assertNotNull(gate.junction)
        endpoint.submit(DisplaySnapshot(key, 2, state.copy(junction = null)))
        factory.pump(); assertNull(gate.junction) // next maneuver has no road geometry
        endpoint.submit(DisplaySnapshot(key, 3, state))
        factory.pump(); assertNotNull(gate.junction)
        endpoint.submit(DisplaySnapshot(key, 4, NavigationState(phase = NavigationPhase.ENDED)))
        factory.pump(); assertNull(gate.junction)
        endpoint.submit(DisplaySnapshot(key, 5, state))
        factory.pump(); now += 400; factory.pump(); assertNotNull(gate.junction)
        now += 90001; factory.pump(); assertNull(gate.junction)
        val replacement = factory.open(SourceSessionKey("phone", 2))
        assertNull(gate.junction)
        endpoint.close(); assertNull(gate.junction)
        replacement.close()
    }
}
