package com.shilapi.display.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.display.api.JunctionKind
import com.shilapi.display.api.NavigationJunction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JunctionDiagramRendererTest {
    private val left = NavigationJunction(JunctionKind.INTERSECTION, listOf(94, 11), -81)
    private val right = NavigationJunction(JunctionKind.INTERSECTION, listOf(2, 94, -122), 68)
    private val style = RouteMapStyle()
    private fun draw(value: NavigationJunction?, size: Int = 178) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            JunctionDiagramRenderer(style).draw(Canvas(it), value)
        }

    @Test fun capturedLeftKeepsBothOtherRoadsGrayAndHighlightsOnlyTheActualExit() {
        val image = draw(left)
        assertEquals(style.routeColor, image.getPixel(55, 73))
        assertEquals(style.roadColor, image.getPixel(134, 81))
        assertEquals(style.roadColor, image.getPixel(98, 34))
        assertEquals(Color.WHITE, image.getPixel(89, 142))
        assertEquals(Color.BLACK, image.getPixel(5, 5))
        assertEquals(10, (0 until 178).count { image.getPixel(it, 110) == style.routeColor })
        save("apple-left-178", image)
    }

    @Test fun capturedRightPreservesTheNegativeBranchAndDoesNotMirrorTheExit() {
        val image = draw(right)
        assertEquals(style.routeColor, image.getPixel(135, 60))
        assertEquals(style.roadColor, image.getPixel(47, 105))
        assertEquals(style.roadColor, image.getPixel(91, 29))
        assertEquals(Color.BLACK, image.getPixel(25, 60))
        save("apple-right-178", image)
    }

    @Test fun missingMalformedAndUnverifiedKindsNeverCreateRoadsFromDefaults() {
        val invalid = listOf(null, NavigationJunction(JunctionKind.INTERSECTION),
            left.copy(branchAnglesDegrees = null), left.copy(exitAngleDegrees = null),
            left.copy(kind = JunctionKind.UNKNOWN), left.copy(kind = JunctionKind.ROUNDABOUT),
            left.copy(branchAnglesDegrees = listOf(-181)), left.copy(exitAngleDegrees = 181),
            left.copy(branchAnglesDegrees = List(33) { 0 }))
        for (value in invalid) {
            assertFalse(JunctionDiagramRenderer.supports(value))
            val image = draw(value)
            assertTrue((0 until 178).all { y -> (0 until 178).all { x -> image.getPixel(x, y) == Color.BLACK } })
        }
        assertTrue(JunctionDiagramRenderer.supports(left.copy(branchAnglesDegrees = emptyList())))
    }

    @Test fun rendererScalesAndClipsToItsOwnViewportEvenWhenClearing() {
        val image = Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.MAGENTA)
        val renderer = JunctionDiagramRenderer()
        renderer.draw(Canvas(image), left, 178, 178)
        assertEquals(Color.MAGENTA, image.getPixel(190, 20))
        assertEquals(Color.MAGENTA, image.getPixel(20, 190))
        renderer.draw(Canvas(image), null, 178, 178)
        assertEquals(Color.BLACK, image.getPixel(89, 110))
        assertEquals(Color.MAGENTA, image.getPixel(190, 190))
        val twice = draw(left, 356)
        assertEquals(style.routeColor, twice.getPixel(110, 146))
        assertEquals(20, (0 until 356).count { twice.getPixel(it, 220) == style.routeColor })
        save("apple-left-356", twice)
    }

    private fun save(name: String, image: Bitmap) {
        val folder = File("build/reports/junction-diagram").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
