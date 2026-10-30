package com.shilapi.display.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.api.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudMapLayersTest {
    private val session = SourceSessionKey("layer-test", 1)
    private var now = 1000L
    private fun scene(revision: Long = 1, route: String = "route-a", phase: NavigationPhase = NavigationPhase.GUIDING,
        origin: RouteSceneOrigin = RouteSceneOrigin.NAVIGATION_SOURCE) = RouteScene(session, revision, route, phase,
        RouteLine(listOf(RoutePoint(0.0, -100.0), RoutePoint(0.0, 200.0))), emptyList(),
        RoutePoint(0.0, revision.toDouble()), 0.0, 178.0, now, origin)
    private fun event(revision: Long = 1, route: String = "route-a", junction: String = "j1",
        origin: RouteSceneOrigin = RouteSceneOrigin.NAVIGATION_SOURCE, time: Long = now,
        key: SourceSessionKey = session) = JunctionImageEvent(key, route, junction, revision, time, origin)
    private fun png(width: Int = 80, height: Int = 40, color: Int = Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray()
        }
    }
    private fun image() = requireNotNull(RasterJunctionImage.decode(png()))

    @Test fun hiddenJunctionRestoresTheLatestMapWhileBaseUpdatesUnderIt() {
        val layers = HudMapLayers(session, { now })
        assertFalse(layers.showJunction(event(), image()))
        assertTrue(layers.submit(scene()))
        val raster = image()
        assertTrue(layers.showJunction(event(), raster))
        now += 100
        val latest = scene(2)
        assertTrue(layers.submit(latest))
        assertSame(latest, layers.frame().map)
        assertSame(raster, layers.frame().junction)
        assertTrue(layers.hideJunction(event(2)))
        assertSame(latest, layers.frame().map)
        assertNull(layers.frame().junction)
    }

    @Test fun lateWrongIdentityOrOriginEventsNeverReplaceTheLayer() {
        val layers = HudMapLayers(session, { now })
        layers.submit(scene())
        val raster = image()
        assertTrue(layers.showJunction(event(2), raster))
        for (invalid in listOf(event(1), event(2), event(3, route = "other"),
            event(3, key = SourceSessionKey("other", 1)), event(3, origin = RouteSceneOrigin.SAMPLE_REPLAY),
            event(3, time = now + 1))) assertFalse(layers.showJunction(invalid, image()))
        assertSame(raster, layers.frame().junction)
        assertTrue(layers.showJunction(event(3, junction = "j2"), raster))
        assertFalse(layers.hideJunction(event(4, junction = "j1")))
        assertEquals("j2", layers.frame().junctionEvent?.junctionId)
    }

    @Test fun hideBeforeShowIsATombstoneAndBothUseTheirOwnRevisionSequence() {
        val layers = HudMapLayers(session, { now })
        layers.submit(scene(100))
        assertTrue(layers.hideJunction(event(2)))
        assertFalse(layers.showJunction(event(1), image()))
        assertFalse(layers.showJunction(event(2), image()))
        assertTrue(layers.showJunction(event(3), image()))
        assertTrue(layers.submit(scene(101)))
        assertNotNull(layers.frame().junction)
    }

    @Test fun routeReplacementAndEndClearTheLayerAndRejectPreviousRouteEvents() {
        val layers = HudMapLayers(session, { now })
        layers.submit(scene()); layers.showJunction(event(), image())
        layers.submit(scene(2, route = "route-b"))
        assertNull(layers.frame().junction)
        assertFalse(layers.showJunction(event(2), image()))
        assertTrue(layers.showJunction(event(2, route = "route-b"), image()))
        layers.submit(scene(3, route = "route-b", phase = NavigationPhase.ENDED))
        assertNull(layers.frame().map); assertNull(layers.frame().junction)
        assertFalse(layers.showJunction(event(3, route = "route-b"), image()))
    }

    @Test fun mapTimeoutCannotBeRevivedByAJunctionOrRetainAnOverlayOnAFreshMap() {
        val layers = HudMapLayers(session, { now })
        layers.submit(scene()); layers.showJunction(event(), image())
        now += 3001
        assertFalse(layers.showJunction(event(2), image()))
        assertTrue(layers.submit(scene(2))) // no intervening frame() required to retire the stale layer
        assertNull(layers.frame().junction)
        layers.showJunction(event(3), image())
        now += 3001
        assertNull(layers.frame().map); assertNull(layers.frame().junction)
    }

    @Test fun junctionTimeoutAndCloseClearAndNeverExtendTheMapLease() {
        val layers = HudMapLayers(session, { now }, maximumJunctionAgeMillis = 1500)
        layers.submit(scene()); layers.showJunction(event(), image())
        now += 1501
        layers.submit(scene(2))
        assertNotNull(layers.frame().map); assertNull(layers.frame().junction)
        assertFalse(layers.showJunction(event(2, time = 1000), image()))
        assertTrue(layers.showJunction(event(3), image()))
        layers.close()
        assertNull(layers.frame().map); assertNull(layers.frame().junction)
        assertFalse(layers.submit(scene(3))); assertFalse(layers.showJunction(event(4), image()))
    }

    @Test fun samplesNeedOptInAndAnOriginChangeClearsTheLayer() {
        val sample = scene(origin = RouteSceneOrigin.SAMPLE_REPLAY)
        assertFalse(HudMapLayers(session, { now }).submit(sample))
        val layers = HudMapLayers(session, { now }, allowSampleReplay = true)
        layers.submit(sample)
        assertFalse(layers.showJunction(event(), image()))
        assertTrue(layers.showJunction(event(origin = RouteSceneOrigin.SAMPLE_REPLAY), image()))
        layers.submit(scene(2))
        assertNull(layers.frame().junction)
    }

    @Test fun decodingRejectsUnknownDataMismatchedLayersAndUnboundedAllocations() {
        assertNull(RasterJunctionImage.decode(byteArrayOf()))
        assertNull(RasterJunctionImage.decode(byteArrayOf(1, 2, 3)))
        assertNull(RasterJunctionImage.decode(ByteArray(4 * 1024 * 1024 + 1)))
        assertNull(RasterJunctionImage.decode(png(), png(81, 40)))
        assertNull(RasterJunctionImage.decode(png(2049, 1)))
        assertNull(RasterJunctionImage.decode(png(2048, 1025)))
        assertNull(RasterJunctionImage.decode(png(1024, 1025), png(1024, 1025)))
        val valid = requireNotNull(RasterJunctionImage.decode(png(), png(color = Color.TRANSPARENT)))
        assertEquals(80, valid.width); assertEquals(40, valid.height)
        assertFalse(valid.road.isMutable)
    }

    @Test fun rasterLayersContainInBlackViewportAndDoNotPaintOutsideIt() {
        val arrow = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        arrow.setPixel(40, 20, Color.GREEN)
        val bytes = ByteArrayOutputStream().use { arrow.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        val raster = requireNotNull(RasterJunctionImage.decode(png(), bytes))
        val bitmap = Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val renderer = HudMapRenderer()
        renderer.draw(Canvas(bitmap), HudMapFrame(scene(), raster, event()), 178, 178)
        assertEquals(Color.BLACK, bitmap.getPixel(89, 20)) // letterbox, not stretched
        assertEquals(Color.RED, bitmap.getPixel(20, 89))
        assertTrue(Color.green(bitmap.getPixel(90, 90)) > 100)
        assertEquals(Color.RED, bitmap.getPixel(89, 130)) // no receiver's white car on a source raster
        assertEquals(Color.BLUE, bitmap.getPixel(190, 89))
        renderer.draw(Canvas(bitmap), HudMapFrame(null, raster, event()), 178, 178)
        assertEquals(Color.BLACK, bitmap.getPixel(89, 89)) // no active base, no orphan image
    }

    @Test fun replayLabelRemainsVisibleAboveAnOpaqueJunction() {
        val bitmap = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        HudMapRenderer().draw(Canvas(bitmap), HudMapFrame(scene(origin = RouteSceneOrigin.SAMPLE_REPLAY),
            image(), event(origin = RouteSceneOrigin.SAMPLE_REPLAY)))
        assertTrue((0 until 18).any { y -> (0 until 32).any { x -> Color.red(bitmap.getPixel(x, y)) > 200 &&
            Color.green(bitmap.getPixel(x, y)) > 100 } })
    }
}
