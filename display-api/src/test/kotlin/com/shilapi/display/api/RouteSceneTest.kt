package com.shilapi.display.api

import org.junit.Assert.*
import org.junit.Test

class RouteSceneTest {
    private val session = SourceSessionKey("route-source", 1)
    private fun line(vararg points: Pair<Double, Double>) = RouteLine(points.map { RoutePoint(it.first, it.second) })
    private fun scene(revision: Long = 1, at: Long = 1000, phase: NavigationPhase = NavigationPhase.GUIDING,
        heading: Double = 0.0, origin: RouteSceneOrigin = RouteSceneOrigin.NAVIGATION_SOURCE,
        key: SourceSessionKey = session, route: RouteLine = line(0.0 to 0.0, 0.0 to 50.0),
        roads: List<RouteLine> = listOf(route), position: RoutePoint = RoutePoint(0.0, 0.0), scale: Double = 1.0,
        routeId: String = "selected-route") = RouteScene(key, revision, routeId, phase, route, roads,
            position, heading, scale * 178, at, origin)

    @Test fun allLayersUseTheSameHeadingUpTransformAndFixedMarker() {
        val east = line(0.0 to 0.0, 50.0 to 0.0)
        val value = scene(heading = 90.0, route = east)
        val frame = RouteSceneProjector.layout(value, 178, 178)
        assertEquals(frame.route, frame.roads)
        assertEquals(89.0, frame.marker.x, 0.001)
        assertEquals(131.72, frame.marker.y, 0.001)
        assertEquals(frame.marker.x, frame.route.single().end.x, 0.001)
        assertEquals(frame.marker.y - 50, frame.route.single().end.y, 0.001)
        val larger = RouteSceneProjector.layout(value, 356, 356)
        assertEquals(frame.route.single().end.x * 2, larger.route.single().end.x, 0.001)
        assertEquals(frame.route.single().end.y * 2, larger.route.single().end.y, 0.001)
        val moved = RouteSceneProjector.layout(scene(position = RoutePoint(0.0, 20.0)), 178, 178)
        assertEquals(frame.marker, moved.marker)
        assertEquals(151.72, moved.route.single().start.y, 0.001)
        val zoom = RouteSceneProjector.layout(scene(scale = 0.5), 178, 178)
        assertEquals(100.0, zoom.route.single().start.y - zoom.route.single().end.y, 0.001)
    }

    @Test fun roadsAreClippedWithoutInventingConnectionsAcrossAnOffscreenExcursion() {
        val crossing = line(-1000.0 to 50.0, 1000.0 to 50.0)
        val frame = RouteSceneProjector.layout(scene(route = crossing), 178, 178)
        assertEquals(0.0, frame.route.single().start.x, 0.001)
        assertEquals(178.0, frame.route.single().end.x, 0.001)
        val outside = line(200.0 to 0.0, 200.0 to 100.0)
        assertTrue(RouteSceneProjector.layout(scene(route = outside), 178, 178).route.isEmpty())
        val excursion = line(0.0 to 0.0, 500.0 to 500.0, 500.0 to -500.0, 10.0 to 0.0)
        val parts = RouteSceneProjector.layout(scene(route = excursion), 178, 178).route
        assertEquals(2, parts.size)
        assertNotEquals(parts[0].end, parts[1].start)
    }

    @Test fun boundedFullSnapshotsCannotBeMutatedByTheirProducer() {
        val sourcePoints = mutableListOf(RoutePoint(0.0, 0.0), RoutePoint(1.0, 1.0))
        val route = RouteLine(sourcePoints)
        val roads = mutableListOf(route)
        val value = scene(route = route, roads = roads)
        sourcePoints.clear(); roads.clear()
        assertEquals(2, value.route.points.size)
        assertEquals(1, value.roads.size)
        assertThrows(UnsupportedOperationException::class.java) { (value.roads as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { RoutePoint(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { RouteLine(List(257) { RoutePoint(0.0, 0.0) }) }
        assertThrows(IllegalArgumentException::class.java) { scene(roads = List(33) { route }) }
        assertThrows(IllegalArgumentException::class.java) { scene(heading = 360.0) }
        assertThrows(IllegalArgumentException::class.java) { scene(scale = 0.0) }
    }

    @Test fun oldSessionsAndOutOfOrderOrExpiredFramesCannotRenewTheLease() {
        var now = 1000L
        val store = RouteSceneStore(session, { now })
        assertTrue(store.submit(scene()))
        assertFalse(store.submit(scene(revision = 2, key = SourceSessionKey("route-source", 2))))
        assertFalse(store.submit(scene(revision = 1)))
        assertFalse(store.submit(scene(revision = 2, at = now + 1)))
        now = 4001
        assertNull(store.current())
        assertFalse(store.submit(scene(revision = 2)))
        assertTrue(store.submit(scene(revision = 2, at = now, routeId = "rerouted")))
        assertEquals("rerouted", store.current()!!.sourceRouteId)
        assertTrue(store.submit(scene(revision = 3, at = now, phase = NavigationPhase.ENDED)))
        assertNull(store.current())
        assertFalse(store.submit(scene(revision = 2, at = now)))
        store.close()
        assertFalse(store.submit(scene(revision = 4, at = now)))
    }

    @Test fun samplesRequireExplicitOptInAndLifecycleClearsCannotLeaveOldRoads() {
        val sample = scene(origin = RouteSceneOrigin.SAMPLE_REPLAY)
        assertFalse(RouteSceneStore(session, { 1000 }).submit(sample))
        val store = RouteSceneStore(session, { 1000 }, allowSampleReplay = true)
        assertTrue(store.submit(sample))
        for ((index, phase) in listOf(NavigationPhase.NONE, NavigationPhase.ARRIVED, NavigationPhase.UNKNOWN).withIndex()) {
            assertTrue(store.submit(scene(revision = 2L + index, phase = phase)))
            assertNull(store.current())
        }
    }
}
