package com.shilapi.display.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteSceneRendererTest {
    private fun scene(origin: RouteSceneOrigin = RouteSceneOrigin.NAVIGATION_SOURCE) = RouteScene(
        SourceSessionKey("renderer-test", 1), 1, "synthetic-test", NavigationPhase.GUIDING,
        RouteLine(listOf(RoutePoint(0.0, -100.0), RoutePoint(0.0, 200.0))),
        listOf(RouteLine(listOf(RoutePoint(-200.0, 60.0), RoutePoint(200.0, 60.0)))),
        RoutePoint(0.0, 0.0), 0.0, 178.0, 1000, origin)

    @Test fun routeIsTenPixelsWideAboveGrayRoadsWithAStationaryWhiteMarker() {
        val image = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        RouteSceneRenderer().draw(Canvas(image), scene())
        val cyan = Color.rgb(0, 222, 255)
        assertEquals(cyan, image.getPixel(89, 72))
        assertEquals(Color.rgb(87, 96, 104), image.getPixel(30, 72))
        assertEquals(Color.BLACK, image.getPixel(95, 72)) // route outline masks the road
        assertEquals(10, (0 until 178).count { image.getPixel(it, 30) == cyan })
        assertEquals(Color.WHITE, image.getPixel(89, 126))
        assertEquals(Color.BLACK, image.getPixel(5, 5))
    }

    @Test fun expiryAndSessionCloseClearEveryPixelAndReplayAlwaysHasItsVisibleLabel() {
        var now = 1000L
        val live = scene()
        val store = RouteSceneStore(live.session, { now })
        val renderer = RouteSceneRenderer()
        val image = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        fun black() = (0 until 178).all { y -> (0 until 178).all { x -> image.getPixel(x, y) == Color.BLACK } }
        assertTrue(store.submit(live))
        renderer.draw(Canvas(image), store.current()); assertFalse(black())
        now = 4001
        renderer.draw(Canvas(image), store.current()); assertTrue(black())
        renderer.draw(Canvas(image), scene(RouteSceneOrigin.SAMPLE_REPLAY))
        assertTrue((0 until 18).any { y -> (0 until 32).any { x -> image.getPixel(x, y) != Color.BLACK } })
        store.close()
        renderer.draw(Canvas(image), store.current()); assertTrue(black())
    }

    @Test fun explicitViewportAndClearNeverPaintOutsideTheCallersBounds() {
        val image = Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.RED)
        val renderer = RouteSceneRenderer()
        renderer.draw(Canvas(image), scene(), 178, 178)
        assertEquals(Color.RED, image.getPixel(190, 20))
        renderer.draw(Canvas(image), null, 178, 178)
        assertEquals(Color.BLACK, image.getPixel(20, 20))
        assertEquals(Color.RED, image.getPixel(20, 190))
    }
}
