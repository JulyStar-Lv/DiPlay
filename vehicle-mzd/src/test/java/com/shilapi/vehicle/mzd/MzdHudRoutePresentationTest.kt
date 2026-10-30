package com.shilapi.vehicle.mzd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.android.RouteSceneRenderer
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
class MzdHudRoutePresentationTest {
    private fun scene(distance: Long?) = RouteScene(SourceSessionKey("profile-test", 1), 1, "sample",
        NavigationPhase.GUIDING, RouteLine(listOf(RoutePoint(0.0, -100.0), RoutePoint(0.0, 1000.0))),
        emptyList(), RoutePoint(0.0, 0.0), 0.0, 712.0, 1000, RouteSceneOrigin.NAVIGATION_SOURCE, distance)

    @Test fun nativeDistanceBoundariesSelectRelativeViewsWithAFixedLowerMarker() {
        val profile = MzdHudRoutePresentation
        for ((distance, level) in listOf(0L to 19, 300L to 19, 301L to 18, 600L to 18, 601L to 17))
            assertEquals(level, profile.zoomLevel(distance))
        assertNull(profile.zoomLevel(null)); assertNull(profile.zoomLevel(-1))
        assertEquals(712.0, profile.camera(scene(null)).viewSpanMeters, 0.0)
        assertEquals(712.0, profile.camera(scene(900)).viewSpanMeters, 0.0)
        assertEquals(356.0, profile.camera(scene(450)).viewSpanMeters, 0.0)
        assertEquals(178.0, profile.camera(scene(150)).viewSpanMeters, 0.0)
        assertEquals(0.8, profile.camera(scene(150)).markerYFraction, 0.0)
        assertEquals(178, profile.renderWidth); assertEquals(178, profile.renderHeight)
        assertEquals(10, profile.framesPerSecond)
    }

    @Test fun greenRouteIsTenPixelsAtNativeSizeWithWhiteCarAtEightyPercent() {
        val bitmap = Bitmap.createBitmap(178, 178, Bitmap.Config.ARGB_8888)
        RouteSceneRenderer(MzdHudRoutePresentation).draw(Canvas(bitmap), scene(150))
        assertEquals(10, (0 until 178).count { bitmap.getPixel(it, 30) == 0xff00ffa4.toInt() })
        assertEquals(Color.WHITE, bitmap.getPixel(89, 140))
        val camera = MzdHudRoutePresentation.camera(scene(150))
        assertEquals(142.4, RouteSceneProjector.layout(scene(150), 178, 178, camera.viewSpanMeters,
            camera.markerYFraction).marker.y, 0.001)
    }
}
