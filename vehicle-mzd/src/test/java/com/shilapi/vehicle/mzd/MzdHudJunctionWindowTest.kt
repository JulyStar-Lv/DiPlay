package com.shilapi.vehicle.mzd

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import com.shilapi.display.android.*
import com.shilapi.display.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdHudJunctionWindowTest {
    private val left = NavigationJunction(JunctionKind.INTERSECTION, listOf(94, 11), -81)
    private val right = NavigationJunction(JunctionKind.INTERSECTION, listOf(2, 94, -122), 68)

    private fun withOutput(content: MapStreamContent = MapStreamContent.INSTRUCTION_CARD,
        action: (MzdHudMapOutput, VehicleMapLease) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", 5)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).apply { setName("MeterDisplay#0"); setFlags(Display.FLAG_PRESENTATION); setRotation(0) }
        val output = MzdHudMapOutput(context, {}, content)
        val lease = output.attach(object : MapSurfaceConsumer {
            override fun attach(surface: Surface) = Unit
            override fun detach(surface: Surface, released: () -> Unit) = released()
        })
        try { idle(); action(output, lease) } finally {
            lease.close(); idle(); ShadowDisplayManager.removeDisplay(id)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun window(output: MzdHudMapOutput): Presentation? = ReflectionHelpers.getField(output, "presentation")
    private fun diagram(output: MzdHudMapOutput): JunctionDiagramView =
        ReflectionHelpers.getField(requireNotNull(window(output)), "diagram")

    @Test fun completeGeometryDisplaysWithoutAuxiliaryVideoAndClearsWhenGeometryDisappears() = withOutput { output, _ ->
        output.navigationAllowed(true, left); idle()
        assertNotNull(window(output)) // no streamActive(true) and no phone video frame
        assertEquals(View.VISIBLE, diagram(output).visibility)
        output.navigationAllowed(true, NavigationJunction(JunctionKind.INTERSECTION)); idle()
        assertNull(window(output)) // no valid geometry and no active video fallback
        output.navigationAllowed(true, right); idle()
        assertNotNull(window(output))
        output.navigationAllowed(false); idle()
        assertNull(window(output))
    }

    @Test fun geometrySwitchesInPlaceAndMissingAnglesReturnToVideoWithoutReplacingItsSurface() = withOutput { output, lease ->
        lease.streamActive(true); output.navigationAllowed(true); idle()
        val original = requireNotNull(window(output))
        output.navigationAllowed(true, left); idle()
        assertSame(original, window(output))
        assertEquals(View.VISIBLE, diagram(output).visibility)
        output.navigationAllowed(true, right); idle()
        assertSame(original, window(output))
        val before = diagram(output)
        output.navigationAllowed(true, right.copy(exitAngleDegrees = null)); idle()
        assertSame(original, window(output))
        assertSame(before, diagram(output))
        assertEquals(View.GONE, diagram(output).visibility)
        output.navigationAllowed(true, right.copy(kind = JunctionKind.ROUNDABOUT)); idle()
        assertEquals(View.GONE, diagram(output).visibility)
    }

    @Test fun viewportStaysGreenOutsideTheCardToneFilterAndCannotCoverLeftHandGuidance() = withOutput { output, lease ->
        lease.streamActive(true); output.navigationAllowed(true, left); idle()
        val presentation = requireNotNull(window(output))
        val root = presentation.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        val tone: MzdHudCardToneController = ReflectionHelpers.getField(presentation, "cardTone")
        val sourceLayer: View = ReflectionHelpers.getField(tone, "sourceLayer")
        val targetLayer: View = ReflectionHelpers.getField(tone, "targetLayer")
        val overlay = diagram(output)
        assertSame(root, overlay.parent)
        assertNotSame(sourceLayer, overlay.parent)
        assertNotSame(targetLayer, overlay.parent)
        // Even an extreme source-card colour filter cannot affect the sibling diagram.
        val filters = requireNotNull(MzdHudCardTone.filters(
            MzdHudCardTone.Palette(MzdHudCardTone.Mode.LIGHT, 255, 170)))
        sourceLayer.setLayerType(View.LAYER_TYPE_SOFTWARE, filters.source)
        targetLayer.setLayerType(View.LAYER_TYPE_SOFTWARE, filters.target)
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 480)
        val image = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        root.draw(Canvas(image))
        val folder = File("build/reports/junction-diagram").apply { mkdirs() }
        File(folder, "mzd-left-800x480.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(folder, "mzd-left-178.png").outputStream().use {
            assertTrue(Bitmap.createBitmap(image, 584, 179, 178, 178).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertEquals("overlay=${overlay.left},${overlay.top},${overlay.width},${overlay.height} visible=${overlay.visibility}",
            MzdHudRoutePresentation.style.routeColor, image.getPixel(584 + 55, 179 + 73))
        assertEquals(Color.BLACK, image.getPixel(584 + 5, 179 + 5))
        assertEquals(Color.MAGENTA, image.getPixel(100, 200))
        output.navigationAllowed(true, right); idle()
        root.draw(Canvas(image))
        assertEquals(MzdHudRoutePresentation.style.routeColor, image.getPixel(584 + 135, 179 + 60))
        assertEquals(MzdHudRoutePresentation.style.roadColor, image.getPixel(584 + 47, 179 + 105))
        File(folder, "mzd-right-178.png").outputStream().use {
            assertTrue(Bitmap.createBitmap(image, 584, 179, 178, 178).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun outputCopiesAnglesAndLateOldLeaseCloseCannotDismissItsSuccessor() = withOutput { output, first ->
        val mutable = mutableListOf(94, 11)
        output.navigationAllowed(true, left.copy(branchAnglesDegrees = mutable))
        mutable[0] = -181
        idle()
        assertEquals(View.VISIBLE, diagram(output).visibility)
        val second = output.attach(object : MapSurfaceConsumer {
            override fun attach(surface: Surface) = Unit
            override fun detach(surface: Surface, released: () -> Unit) = released()
        })
        idle()
        val replacement = requireNotNull(window(output))
        first.close(); idle()
        assertSame(replacement, window(output))
        second.close(); idle()
        assertNull(window(output))
    }
}
