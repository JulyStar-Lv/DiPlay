package com.shilapi.xcertplay.mazda

import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.CarPlayViewAreas
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w5120dp-h1440dp-mdpi",
    shadows = [MzdOemReturnTest.MazdaResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MzdVideoTransitionTest {
    private lateinit var host: CarPlayHostActivity

    @Before fun setUp() {
        MzdOemReturnTest.MazdaResources.enabled = true
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun tearDown() {
        ReflectionHelpers.callInstanceMethod<Void>(host, "discardLastPicture")
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        MzdOemReturnTest.MazdaResources.enabled = true
    }

    @Test fun hostShowsTheMaskBeforeItsFirstResizeAndBlocksTouchesUntilAPostTransitionTextureFrame() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false)
        install(2496, 1080, areas)
        val output = object : TextureView(host) {
            override fun isAvailable() = true
            override fun getBitmap(width: Int, height: Int) =
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff467c95.toInt()) }
        }
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        val shell = MzdIntegration.wrap(host, root) as MzdCarAppLayout
        host.setContentView(shell)
        ReflectionHelpers.setField(host, "videoView", output)
        fun layout() {
            shell.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 5120, 1440)
        }
        layout()
        assertEquals(2496, output.width)
        ReflectionHelpers.setField(shell, "vehiclePanel", true)
        ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(host, "mazdaTransitionMask")
        assertTrue("The mask must precede the first changed-width layout", mask.isShowing)
        assertEquals(2496, output.width)
        verify(controller).sendTouch(emptyList())
        ReflectionHelpers.getField<android.animation.ValueAnimator?>(shell, "panelAnimator")?.end()
        layout()
        assertEquals(1728, mask.view.width)
        assertEquals(1080, mask.view.height)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 500f, 500f, 0)
        try {
            assertTrue(ReflectionHelpers.callInstanceMethod(host, "onHostTouch",
                ReflectionHelpers.ClassParameter.from(View::class.java, output),
                ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event)))
        } finally { event.recycle() }
        verifyNoMoreInteractions(controller)
        ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1728),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        shadowOf(Looper.getMainLooper()).idle()
        val texture = SurfaceTexture(0)
        try {
            ReflectionHelpers.setField(host, "currentSurfaceTexture", texture)
            val listener = ReflectionHelpers.getField<TextureView.SurfaceTextureListener>(host, "textureListener")
            listener.onSurfaceTextureUpdated(texture)
            assertEquals(1f, mask.view.alpha, 0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            listener.onSurfaceTextureUpdated(texture)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertFalse(mask.isShowing)
        } finally {
            texture.release()
            MzdIntegration.closeWindows()
        }
    }

    @Test fun bothDirectionsAndReversalsCoverThePanelWithoutDistortionOrTouchDrift() {
        // Include old frames before the area command, new frames after it, and a mid-animation reversal.
        val widths = listOf(2496, 2384, 2112, 1840, 1728, 1840, 2112, 1840, 1728, 2112, 2496)
        for (dock in CarPlayDock.entries) for ((canvasWidth, canvasHeight) in listOf(2496 to 1080, 1248 to 540)) {
            val areas = MzdViewAreas.build(canvasWidth, canvasHeight, dock, narrow = false)
            install(canvasWidth, canvasHeight, areas)
            for (narrow in listOf(false, true)) {
                areas.use(requireNotNull(areas.index(
                    if (narrow) CarPlayViewAreas.Kind.SPLIT_SCREEN else CarPlayViewAreas.Kind.FULL_SCREEN,
                    dock.edge)))
                val area = areas.layoutArea(areas.current)
                for (width in widths) {
                    val content = content(width, 1080)
                    val sx = content.width / canvasWidth
                    val sy = content.height / canvasHeight
                    assertEquals("The picture must not stretch as the panel moves", sx, sy, 0.0001f)
                    val areaLeft = content.left + area.originX * sx
                    val areaTop = content.top + area.originY * sy
                    assertTrue(areaLeft <= 0.001f)
                    assertTrue(areaTop <= 0.001f)
                    assertTrue("No unused stream pixels may enter on the right", areaLeft + area.width * sx >= width - .001f)
                    assertTrue("Keep the right-edge band and its filter neighbour out of the viewport",
                        areaLeft + (area.width - MzdVideoLayout.rightEdgeGuard(canvasWidth, area)) * sx >= width - .001f)
                    assertTrue("No top/bottom letterbox", areaTop + area.height * sy >= 1080 - .001f)
                    assertTrue(content.contains(0f, 0f))
                    assertTrue(content.contains(width.toFloat(), 1080f))

                    // A visible stream point must still map to the same normalized iPhone contact.
                    val x = width / 2f
                    val y = 540f
                    val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
                    try {
                        val contact = CarPlayTouchMapper.contacts(event, content).single()
                        assertEquals((x - content.left) / sx / canvasWidth.toDouble(), contact.x, 0.0001)
                        assertEquals((y - content.top) / sy / canvasHeight.toDouble(), contact.y, 0.0001)
                    } finally { event.recycle() }
                }
            }
        }
    }

    @Test fun oemEndpointsSwitchAreasImmediatelyAndCancelPendingIntermediateSizes() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false)
        install(2496, 1080, areas)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        ReflectionHelpers.setField(host, "activeDisplaySize",
            sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(2496, 1080))
        fun resize(width: Int) {
            ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, width),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
            shadowOf(Looper.getMainLooper()).idle()
        }
        resize(2112)
        verifyNoInteractions(controller)
        resize(1728)
        val narrow = requireNotNull(areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, null))
        verify(controller).showViewArea(narrow)
        assertEquals(narrow, areas.current)
        resize(2112)
        resize(2496)
        val full = requireNotNull(areas.index(CarPlayViewAreas.Kind.FULL_SCREEN, null))
        verify(controller).showViewArea(full)
        assertEquals(full, areas.current)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        verifyNoMoreInteractions(controller)
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(host, "handshakeResetInProgress"))
    }

    @Test fun otherVariantsAndCameraWindowsRetainTheirOriginalFit() {
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false)
        install(2496, 1080, areas)
        MzdOemReturnTest.MazdaResources.enabled = false
        assertEquals(CarPlayVideoLayout.fit(2496, 1080, 1728, 1080), content(1728, 1080))
        MzdOemReturnTest.MazdaResources.enabled = true
        assertEquals(CarPlayVideoLayout.fit(2496, 1080, 700, 990), content(700, 990))
    }

    @Test fun rejectedWideAreaStaysMaskedAndTheSameSizeCanRetryWithoutReconnecting() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(false)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = true)
        install(2496, 1080, areas)
        val output = object : TextureView(host) {
            override fun isAvailable() = true
            override fun getBitmap(width: Int, height: Int) =
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        host.setContentView(root)
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 1080)
        }
        layout(1728)
        ReflectionHelpers.setField(host, "videoView", output)
        host.beginMazdaPanelTransition(2496)
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(host, "mazdaTransitionMask")
        layout(2496)
        ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        mask.textureUpdated()
        assertEquals(CarPlayViewAreas.Kind.SPLIT_SCREEN, areas.kindOf(areas.current))
        assertEquals("A frame at the new window width is not an accepted target area", 1f, mask.view.alpha, 0f)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1900))
        assertEquals(CarPlayViewAreas.Kind.FULL_SCREEN, areas.kindOf(areas.current))
        val full = requireNotNull(areas.index(CarPlayViewAreas.Kind.FULL_SCREEN, null))
        verify(controller, times(2)).showViewArea(full)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        mask.textureUpdated()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(mask.isShowing)
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun hostCapturesTheOldNarrowActiveAreaBeforeExpandingInsteadOfTheWholeBlackPaddedCanvas() {
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = true)
        install(2496, 1080, areas)
        val output = object : TextureView(host) {
            override fun isAvailable() = true
            override fun getBitmap(width: Int, height: Int): Bitmap {
                assertEquals(1248, width)
                assertEquals(540, height)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.BLACK)
                    Canvas(it).drawRect(0f, 0f, 863f, height.toFloat(), Paint().apply { color = Color.CYAN })
                }
            }
        }
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        val parkedPicture = android.widget.ImageView(host).apply { setBackgroundColor(Color.MAGENTA) }
        val touches = View(host)
        root.addView(parkedPicture, FrameLayout.LayoutParams(-1, -1))
        root.addView(touches, FrameLayout.LayoutParams(-1, -1))
        ReflectionHelpers.setField(host, "pictureHold", parkedPicture)
        host.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(1728, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1728, 1080)
        ReflectionHelpers.setField(host, "videoView", output)
        val backdrop = host.beginMazdaPanelTransition(2496)
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(host, "mazdaTransitionMask")
        val picture = requireNotNull(mask.snapshot)
        assertSame("The shell must reuse the cropped active picture, not software-capture the TextureView", picture, backdrop)
        assertEquals(Color.CYAN, picture.getPixel(picture.width - 1, picture.height / 2))
        assertTrue(mask.isShowing)
        assertTrue("The return picture must stay below the blur", root.indexOfChild(parkedPicture) < root.indexOfChild(mask.view))
        assertTrue("Touch and settings controls must stay above the blur", root.indexOfChild(mask.view) < root.indexOfChild(touches))
        root.measure(View.MeasureSpec.makeMeasureSpec(2496, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 2496, 1080)
        val rendered = Bitmap.createBitmap(2496, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(rendered))
        assertTrue("The clear parked picture must not cover the transition", Color.red(rendered.getPixel(1248, 540)) < 100)
        mask.dispose()
    }

    private fun install(width: Int, height: Int, areas: CarPlayViewAreas) {
        ReflectionHelpers.setField(host, "sessionDisplay",
            CarPlaySessionDisplay(width, height, 0, false, false, 2496, 1080, areas))
    }

    private fun content(width: Int, height: Int): CarPlayVideoLayout =
        ReflectionHelpers.callInstanceMethod(host, "contentRect",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, width),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, height))
}
