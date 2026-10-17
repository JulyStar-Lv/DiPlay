package com.shilapi.xcertplay.mazda

import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.provider.Settings
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.CarPlayViewAreas
import com.shilapi.xcertplay.CarPlayVideoSurfaceOwner
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w5120dp-h1440dp-mdpi",
    shadows = [MzdOemReturnTest.MazdaResources::class, MzdVideoTransitionTest.VideoPixels::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MzdVideoTransitionTest {
    private lateinit var host: CarPlayHostActivity

    @Before fun setUp() {
        MzdOemReturnTest.MazdaResources.enabled = true
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        ReflectionHelpers.getField<CarPlayVideoSurfaceOwner<Surface>>(host, "videoSurfaceOwner")
            .replace(surface, releaseOnDetach = false)
        VideoPixels.fraction = 1f
        VideoPixels.copies = 0
        VideoPixels.result = PixelCopy.SUCCESS
        VideoPixels.color = Color.CYAN
        VideoPixels.deferCopies = false
        VideoPixels.pending.clear()
    }

    @After fun tearDown() {
        VideoPixels.pending.clear()
        ReflectionHelpers.callInstanceMethod<Void>(host, "discardLastPicture")
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        MzdOemReturnTest.MazdaResources.enabled = true
    }

    @Test fun hostMasksBeforeItsFirstResizeAndBlocksTouchesUntilVerifiedTargetFrames() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false)
        install(2496, 1080, areas)
        val output = TextureView(host)
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
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")
        assertTrue("The mask must precede the first changed-width layout", mask.isShowing)
        assertEquals(2496, output.width)
        verify(controller).sendTouch(emptyList())
        verify(controller).showViewArea(requireNotNull(areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, null)))
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
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertEquals("An old wide frame after the guard still cannot confirm the narrow target", 1f, mask.view.alpha, 0f)
            VideoPixels.fraction = 1728f / 2496f
            repeat(2) {
                listener.onSurfaceTextureUpdated(texture)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            }
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

    @Test fun rightHandCanvasKeepsVideoSizeAndTouchCoordinatesAtBothStreamScales() {
        for ((canvasWidth, canvasHeight) in listOf(2496 to 1080, 1248 to 540, 1920 to 830, 2700 to 1168))
            for (dock in CarPlayDock.entries) {
            val areas = MzdViewAreas.build(canvasWidth, canvasHeight, dock, narrow = true)
            val narrow = requireNotNull(areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, dock.edge))
            assertEquals(canvasWidth - areas.areas[narrow].width, areas.areas[narrow].originX)
            install(canvasWidth, canvasHeight, areas)
            for (surfaceOutput in listOf(false, true)) {
                var changes = 0
                val output = object : FrameLayout(host) {
                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh); changes++
                    }
                }
                val canvas = MzdVideoCanvas(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
                if (surfaceOutput) output.addView(View(host), FrameLayout.LayoutParams(-1, -1))
                ReflectionHelpers.setField(host, "videoView", output)
                for (width in listOf(1728, 2496, 1728, 2496)) {
                    canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                    canvas.layout(0, 0, width, 1080)
                    assertEquals(2496, output.width)
                    assertEquals(1080, output.height)
                    assertEquals(width - 2496, output.left)
                    assertEquals("Narrow startup and repeated switches retain their video buffers", 1, changes)
                    val picture = content(width, 1080)
                    val sidePanelScreen = ReflectionHelpers.callInstanceMethod<RectF>(host, "sidePanelScreenRect",
                        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, output.width),
                        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, output.height))
                    val screen = areas.layoutArea(areas.current)
                    assertEquals("Side-panel hit testing uses window coordinates, not the full-size surface",
                        picture.left + screen.originX * picture.width / canvasWidth, sidePanelScreen.left, .001f)
                    assertEquals(picture.left + (screen.originX + screen.width) * picture.width / canvasWidth,
                        sidePanelScreen.right, .001f)
                    val atActiveLeft = picture.left + areas.areas[narrow].originX * picture.width / canvasWidth
                    assertEquals((width - 1728).toFloat(), atActiveLeft, .001f)
                    val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                        atActiveLeft + 864f, 540f, 0)
                    try {
                        val contact = CarPlayTouchMapper.contacts(event, picture).single()
                        val area = areas.areas[narrow]
                        assertEquals((area.originX + (area.width - MzdVideoLayout.rightEdgeGuard(canvasWidth, area)) / 2.0) /
                            canvasWidth, contact.x, .0001)
                        assertEquals(.5, contact.y, .0001)
                    } finally { event.recycle() }
                }
            }
        }
    }

    @Test fun narrowStartupDeclaresTheRightAreaDespiteAFullSizeVideoSurface() {
        ReflectionHelpers.setField(host, "airPlayIdentity", com.shilapi.xcertplay.airplay.AirPlayIdentity.generate())
        val output = TextureView(host)
        val canvas = MzdVideoCanvas(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        canvas.measure(View.MeasureSpec.makeMeasureSpec(1728, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        canvas.layout(0, 0, 1728, 1080)
        ReflectionHelpers.setField(host, "videoView", output)
        val actual = ReflectionHelpers.callInstanceMethod<Any>(host, "currentActivitySize")
        assertEquals(1728, ReflectionHelpers.getField<Int>(actual, "width"))
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val full = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(2496, 1080)
        val config = host.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(host, full) as com.shilapi.xcertplay.airplay.AirPlayConfig
        val area = requireNotNull(config.main.viewAreas)[config.main.initialViewArea]
        assertEquals(config.main.widthPixels - area.width, area.originX)
        assertTrue(area.originX > 0)
    }

    @Test fun surfaceOutputRetainsItsSizeWhenTheActiveAreaAndItsEdgeGuardChange() {
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false)
        install(2496, 1080, areas)
        var sizes = 0
        val surface = object : SurfaceView(host) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh); sizes++
            }
        }
        val output = FrameLayout(host).apply { addView(surface, FrameLayout.LayoutParams(-1, -1)) }
        val canvas = MzdVideoCanvas(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        ReflectionHelpers.setField(host, "videoView", output)
        ReflectionHelpers.setField(host, "fallbackVideoView", surface)
        for (width in listOf(2496, 1728, 2496, 1728)) {
            areas.use(requireNotNull(areas.index(if (width == 1728) CarPlayViewAreas.Kind.SPLIT_SCREEN
                else CarPlayViewAreas.Kind.FULL_SCREEN, null)))
            repeat(2) {
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                canvas.layout(0, 0, width, 1080)
                ReflectionHelpers.callInstanceMethod<Void>(host, "updateVideoLayout",
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
            }
            assertEquals(2496, surface.width)
            assertEquals(1080, surface.height)
            assertEquals("Area changes must not resize the SurfaceView, including its final frame", 1, sizes)
            assertFalse(canvas.isLayoutRequested)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLateBlurFromTheCancelledDirectionCannotReplaceTheCurrentPictureOrRequestLayout() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        install(2496, 1080, MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false))
        val output = TextureView(host)
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        ReflectionHelpers.setField(host, "videoView", output)
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(2496, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 2496, 1080)
        }
        layout()
        VideoPixels.deferCopies = true
        VideoPixels.color = Color.RED
        host.beginMazdaPanelTransition(1728)
        VideoPixels.fraction = 1728f / 2496
        VideoPixels.color = Color.BLUE
        host.beginMazdaPanelTransition(2496)
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(
            ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")
        layout()
        assertEquals(2, VideoPixels.pending.size)
        VideoPixels.pending[1]()
        VideoPixels.pending[0]()
        assertFalse("An asynchronous picture must not schedule a traversal near the endpoint", root.isLayoutRequested)
        val picture = mask.view.drawable as android.graphics.drawable.BitmapDrawable
        assertEquals(Color.BLUE, picture.bitmap.getPixel(32, picture.bitmap.height / 2))
        mask.dispose()
    }

    @Test fun rejectedWideAreaStaysMaskedAndTheSameSizeCanRetryWithoutReconnecting() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(false)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = true)
        install(2496, 1080, areas)
        val output = TextureView(host)
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
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")
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
        verify(controller, times(3)).showViewArea(full)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        repeat(2) {
            mask.textureUpdated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(mask.isShowing)
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
    }

    @Test fun bothEndpointsFinishUsingTheAreaRequestedBeforeTheFirstResizeWithoutAnother450msPause() {
        AirPlayPersistence.saveAdaptPipResolution(host, true)
        for ((from, to) in listOf(2496 to 1728, 1728 to 2496)) {
            val controller = mock(CarPlayController::class.java)
            `when`(controller.showViewArea(anyInt())).thenReturn(true)
            ReflectionHelpers.setField(host, "controller", controller)
            ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
            val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = from == 1728)
            ReflectionHelpers.setField(host, "sessionDisplay",
                CarPlaySessionDisplay(2496, 1080, 0, false, false, from, 1080, areas))
            val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
            ReflectionHelpers.setField(host, "activeDisplaySize",
                sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(from, 1080))
            val output = TextureView(host)
            val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
            host.setContentView(root)
            fun layout(width: Int) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1080)
            }
            layout(from)
            ReflectionHelpers.setField(host, "videoView", output)
            VideoPixels.copies = 0
            VideoPixels.fraction = to / 2496f
            host.beginMazdaPanelTransition(to)
            val mask = ReflectionHelpers.getField<MzdTransitionMask>(
                ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")
            val target = requireNotNull(areas.index(if (to == 1728) CarPlayViewAreas.Kind.SPLIT_SCREEN
                else CarPlayViewAreas.Kind.FULL_SCREEN, null))
            inOrder(controller).apply {
                verify(controller).sendTouch(emptyList())
                verify(controller).showViewArea(target)
            }
            assertEquals("Negotiation precedes any viewport resize", from, output.width)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            mask.textureUpdated()
            assertEquals("Only the blur snapshot may be copied before the endpoint", 1, VideoPixels.copies)
            layout(to)
            ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, to),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            repeat(2) {
                mask.textureUpdated()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertFalse("The endpoint must not add another 450 ms guard for $from -> $to", mask.isShowing)
            assertEquals(3, VideoPixels.copies)
            verifyNoMoreInteractions(controller)
            assertEquals("Expanding a session started narrow must retain its connection", 0,
                ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fixedCanvasAndMovingCropNeverResizeVideoAtIntermediateOrFinalFrames() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val bitmap = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        // Attach the real host views to a window without starting CarPlay's transport services.
        val window = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        try {
            ShadowChoreographer.setPaused(true)
            for ((from, to) in listOf(2496 to 1728, 1728 to 2496)) {
                Settings.Global.putInt(host.contentResolver, "key_screen_show", if (from == 1728) 1 else 0)
                val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = from == 1728)
                install(2496, 1080, areas)
                var sizes = 0
                var measures = 0
                var layouts = 0
                val output = object : TextureView(host) {
                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        sizes++
                    }
                }
                val canvas = MzdVideoCanvas(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
                val root = object : FrameLayout(host) {
                    override fun onMeasure(w: Int, h: Int) { measures++; super.onMeasure(w, h) }
                    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                        layouts++; super.onLayout(changed, l, t, r, b)
                    }
                }.apply { clipChildren = false; addView(canvas, FrameLayout.LayoutParams(-1, -1)) }
                val shell = MzdIntegration.wrap(host, root) as MzdCarAppLayout
                window.setContentView(shell)
                fun layout() {
                    shell.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
                    shell.layout(0, 0, 5120, 1440)
                    ReflectionHelpers.callInstanceMethod<Void>(
                        ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl"), "unscheduleTraversals")
                }
                val attach = ReflectionHelpers.getField<Any>(shell, "mAttachInfo")
                ReflectionHelpers.setField(attach, "mWindowVisibility", View.VISIBLE)
                shell.dispatchWindowVisibilityChanged(View.VISIBLE)
                layout()
                assertEquals(from, root.width)
                assertEquals(2496, output.width)
                val initialSizes = sizes
                ReflectionHelpers.setField(host, "videoView", output)
                ReflectionHelpers.setField(shell, "vehiclePanel", to == 1728)
                ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
                val animator = requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
                animator.pause()
                layout()
                assertEquals("Prepare responsive chrome before moving its crop", to, root.width)
                assertEquals(2496, output.width)
                assertEquals("Even the first frame must retain the video buffers", initialSizes, sizes)
                val preparedSizes = sizes
                val preparedMeasures = measures
                val preparedLayouts = layouts
                val integration = ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo")
                val mask = ReflectionHelpers.getField<MzdTransitionMask>(integration, "mask")
                mask.view.setBackgroundColor(Color.CYAN) // Distinguish the moving panel from its swept strip.
                val samples = listOf(0L to 64, 50L to 93, 100L to 176, 200L to 448,
                    300L to 720, 350L to 803, 383L to 829, 400L to 832)
                val origin = IntArray(2)
                shell.getLocationOnScreen(origin)
                for ((time, shrinkingLeft) in samples) {
                    animator.currentPlayTime = time
                    val expected = if (to == 1728) shrinkingLeft else 896 - shrinkingLeft
                    val mapped = RectF(ReflectionHelpers.getField<android.graphics.Rect>(shell, "layoutBounds"))
                    mapped.offset(origin[0].toFloat(), origin[1].toFloat())
                    assertEquals("Physical edge at $time ms for $from -> $to", expected.toFloat(), mapped.left, 0.01f)
                    assertEquals(2560f, mapped.right, 0.01f)
                    assertEquals(192f, mapped.top, 0.01f)
                    assertEquals(1272f, mapped.bottom, 0.01f)
                    assertEquals("The video canvas stays at the same physical position", 64,
                        root.left + canvas.left + output.left + origin[0])
                    assertEquals(1f, root.scaleX, 0f)
                    assertFalse("A layer frame must not request another host traversal", shell.isLayoutRequested)
                    assertEquals(preparedMeasures, measures)
                    assertEquals(preparedLayouts, layouts)
                    assertEquals(preparedSizes, sizes)
                    bitmap.eraseColor(Color.MAGENTA)
                    shell.draw(Canvas(bitmap).apply { translate(origin[0].toFloat(), origin[1].toFloat()) })
                    for (x in 80 until 2544 step 16) assertEquals("Coverage at $time ms, x=$x",
                        if (x < expected) MzdTransitionMask.BACKGROUND_COLOR else Color.CYAN, bitmap.getPixel(x, 800))
                    assertEquals(Color.MAGENTA, bitmap.getPixel(32, 800))
                }
                animator.end()
                assertEquals(1f, root.scaleX, 0f)
                assertEquals(preparedMeasures, measures)
                assertEquals(preparedLayouts, layouts)
                assertEquals(preparedSizes, sizes)
                assertFalse(integration.isPanelMoving)
            }
            val output = ReflectionHelpers.getField<View>(host, "videoView")
            val root = output.parent.parent as FrameLayout
            val shell = root.parent as MzdCarAppLayout
            fun layout() {
                shell.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
                shell.layout(0, 0, 5120, 1440)
                ReflectionHelpers.callInstanceMethod<Void>(
                    ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl"), "unscheduleTraversals")
            }
            fun move(narrow: Boolean): ValueAnimator? {
                ReflectionHelpers.setField(shell, "vehiclePanel", narrow)
                ReflectionHelpers.callInstanceMethod<Void>(shell, "movePanel")
                return ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator")?.apply { pause() }
            }
            fun edge(): Float {
                return ReflectionHelpers.getField<android.graphics.Rect>(shell, "layoutBounds").left.toFloat()
            }
            val shrinking = requireNotNull(move(true))
            layout()
            shrinking.currentPlayTime = 200
            val before = edge()
            val reversed = requireNotNull(move(false))
            layout()
            assertEquals("A reversal keeps the displayed edge", before, edge(), 0.01f)
            assertEquals(200L, reversed.duration)
            reversed.end()
            assertEquals(1f, root.scaleX, 0f)
            requireNotNull(move(true)).currentPlayTime = 0
            layout()
            assertEquals(1728, root.width)
            assertEquals(2496, output.width)
            assertNull("Reversing before any movement needs no second animation", move(false))
            layout()
            assertEquals(2496, output.width)
            assertEquals(1f, root.scaleX, 0f)
            val integration = ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo")
            assertFalse(integration.isPanelMoving)
            val mask = ReflectionHelpers.getField<MzdTransitionMask>(integration, "mask")
            ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
            VideoPixels.fraction = 1f
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            repeat(2) { mask.textureUpdated(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertFalse("An immediate reversal must not leave the previous target's mask until recovery", mask.isShowing)
        } finally {
            bitmap.recycle()
            window.finish()
            MzdIntegration.closeWindows()
            Settings.Global.putInt(host.contentResolver, "key_screen_show", 0)
            ShadowChoreographer.setPaused(false)
        }
    }

    @Test fun preparedTargetFramesCannotReleaseASlowMovingLayerAndTheOppositeEndCannotFinishIt() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        install(2496, 1080, MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = false))
        val output = TextureView(host)
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        host.setContentView(root)
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 1080)
        }
        layout(2496)
        ReflectionHelpers.setField(host, "videoView", output)
        host.beginMazdaPanelTransition(1728, deferUntilPanelEnd = true)
        layout(1728)
        val integration = ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo")
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(integration, "mask")
        ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1728),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        VideoPixels.fraction = 1728f / 2496f
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        host.endMazdaPanelTransition(2496)
        repeat(2) { mask.textureUpdated(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        assertTrue(integration.isPanelMoving)
        assertEquals(1, VideoPixels.copies)
        host.endMazdaPanelTransition(1728)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(2) { mask.textureUpdated(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(mask.isShowing)
        assertEquals(3, VideoPixels.copies)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun hostKeepsTheParkedPictureCoveredAndOnlyInspectsNewTargetAreaPresentations() {
        ReflectionHelpers.setField(host, "sink", mock(AndroidMediaSink::class.java))
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        ReflectionHelpers.setField(host, "controller", controller)
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = true)
        install(2496, 1080, areas)
        VideoPixels.fraction = 1728f / 2496f
        val output = object : TextureView(host) {
            override fun getBitmap(width: Int, height: Int): Bitmap =
                throw AssertionError("TextureView.getBitmap would inspect the transformed/cropped viewport")
        }
        val root = FrameLayout(host).apply { addView(output, FrameLayout.LayoutParams(-1, -1)) }
        val parkedPicture = android.widget.ImageView(host).apply { setBackgroundColor(Color.MAGENTA) }
        val touches = View(host)
        root.addView(parkedPicture, FrameLayout.LayoutParams(-1, -1))
        root.addView(touches, FrameLayout.LayoutParams(-1, -1))
        ReflectionHelpers.setField(host, "pictureHold", parkedPicture)
        host.setContentView(root)
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 1080)
        }
        layout(1728)
        ReflectionHelpers.setField(host, "videoView", output)
        host.beginMazdaPanelTransition(2496)
        val mask = ReflectionHelpers.getField<MzdTransitionMask>(ReflectionHelpers.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")
        assertEquals("Request one small raw snapshot for the blurred fallback", 1, VideoPixels.copies)
        assertTrue(root.indexOfChild(parkedPicture) < root.indexOfChild(mask.view))
        assertTrue(root.indexOfChild(mask.view) < root.indexOfChild(touches))
        layout(2496)
        val rendered = Bitmap.createBitmap(2496, 1080, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(rendered))
            assertEquals(MzdTransitionMask.BACKGROUND_COLOR, rendered.getPixel(1248, 540))
        } finally { rendered.recycle() }
        ReflectionHelpers.callInstanceMethod<Void>(host, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        repeat(2) {
            mask.textureUpdated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        assertEquals("Two old narrow frames must still be covered at a wide endpoint", 1f, mask.view.alpha, 0f)
        VideoPixels.fraction = 1f
        VideoPixels.result = PixelCopy.ERROR_SOURCE_NO_DATA
        repeat(2) {
            mask.textureUpdated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        assertEquals("A failed raw copy must not reveal the output", 1f, mask.view.alpha, 0f)
        VideoPixels.result = PixelCopy.SUCCESS
        repeat(2) {
            mask.textureUpdated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(mask.isShowing)
        mask.dispose()
    }

    @Implements(PixelCopy::class)
    class VideoPixels {
        companion object {
            var fraction = 1f
            var copies = 0
            var result = PixelCopy.SUCCESS
            var color = Color.CYAN
            var deferCopies = false
            val pending = mutableListOf<() -> Unit>()

            @JvmStatic @Implementation
            fun request(source: Surface, bitmap: Bitmap, listener: PixelCopy.OnPixelCopyFinishedListener, handler: Handler) {
                copies++
                assertTrue(source.isValid)
                assertEquals(MzdTransitionFrame.CAPTURE_WIDTH, bitmap.width)
                val pixels = IntArray(bitmap.width * bitmap.height) {
                    if (it % bitmap.width >= bitmap.width * (1 - fraction)) color else Color.BLACK
                }
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val copied = result
                if (deferCopies) pending += { listener.onPixelCopyFinished(copied) }
                else handler.post { listener.onPixelCopyFinished(copied) }
            }
        }
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
