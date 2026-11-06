package com.shilapi.xcertplay.mazda

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import com.shilapi.xcertplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers as R

/** Regression coverage for interrupted OEM panel motion and replacement video surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w5120dp-h1440dp-mdpi",
    shadows = [MzdOemReturnTest.MazdaResources::class, MzdVideoTransitionTest.VideoPixels::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MzdPanelLifecycleTest {
    private lateinit var host: CarPlayHostActivity
    private var window: Activity? = null
    private var measures = 0
    private var layouts = 0
    private val pixels get() = MzdVideoTransitionTest.VideoPixels

    @Before fun setUp() {
        MzdOemReturnTest.MazdaResources.enabled = true
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        R.getField<CarPlayVideoSurfaceOwner<Surface>>(host, "videoSurfaceOwner")
            .replace(surface, releaseOnDetach = false)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        R.setField(host, "controller", controller)
        R.setField(host, "sink", mock(AndroidMediaSink::class.java))
        pixels.fraction = 1f; pixels.copies = 0; pixels.result = PixelCopy.SUCCESS
        pixels.color = Color.CYAN; pixels.deferCopies = false; pixels.pending.clear()
        Settings.Global.putInt(host.contentResolver, "key_screen_show", 0)
        Settings.Global.putInt(host.contentResolver, "key_vpa_cui_show_left", 0)
    }
    @After fun tearDown() {
        window?.finish()
        R.callInstanceMethod<Void>(host, "discardLastPicture")
        R.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        R.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        R.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        MzdIntegration.closeWindows()
        ShadowChoreographer.setPaused(false)
        pixels.pending.clear()
    }
    private fun install(narrow: Boolean, sidePanel: Boolean = false): CarPlayViewAreas {
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow, sidePanel = sidePanel)
        R.setField(host, "sessionDisplay", CarPlaySessionDisplay(2496, 1080, 0, false, false, 2496, 1080, areas))
        return areas
    }
    private fun resize(width: Int) {
        R.callInstanceMethod<Void>(host, "scheduleDisplaySize",
            R.ClassParameter.from(Int::class.javaPrimitiveType, width),
            R.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
    }
    private fun update(output: View) {
        R.callInstanceMethod<Void>(host, "updateVideoLayout",
            R.ClassParameter.from(Int::class.javaPrimitiveType, output.width),
            R.ClassParameter.from(Int::class.javaPrimitiveType, output.height))
    }
    private fun layout(shell: View) {
        shell.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
        shell.layout(0, 0, 5120, 1440)
        R.callInstanceMethod<Void>(R.callInstanceMethod<Any>(shell, "getViewRootImpl"), "unscheduleTraversals")
    }
    private fun shell(narrow: Boolean, sidePanel: Boolean = false): MzdCarAppLayout {
        Settings.Global.putInt(host.contentResolver, "key_screen_show", if (narrow) 1 else 0)
        val areas = install(narrow, sidePanel)
        val output = TextureView(host)
        val canvas = MzdVideoCanvas(host).apply {
            addView(output, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r-l != or-ol || b-t != ob-ot) resize(r-l)
            }
        }
        val root = object : FrameLayout(host) {
            override fun onMeasure(w: Int, h: Int) { measures++; super.onMeasure(w, h) }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                layouts++; super.onLayout(changed, l, t, r, b)
            }
        }.apply {
            clipChildren = false
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
        }
        R.setField(host, "videoView", output)
        if (sidePanel) {
            val tab = View(host).apply { visibility = View.GONE }
            root.addView(tab, FrameLayout.LayoutParams(-1, -1))
            R.setField(host, "sidePanelTab", tab)
            R.setField(host, "sidePanelTabHintFor", areas)
        }
        val shell = MzdIntegration.wrap(host, root) as MzdCarAppLayout
        if (window == null) window = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        ShadowChoreographer.setPaused(true)
        window!!.setContentView(shell)
        val attach = R.getField<Any>(shell, "mAttachInfo")
        R.setField(attach, "mWindowVisibility", View.VISIBLE)
        shell.dispatchWindowVisibilityChanged(View.VISIBLE)
        layout(shell)
        update(output)
        layout(shell)
        shadowOf(Looper.getMainLooper()).idle()
        layout(shell)
        return shell
    }
    private fun move(shell: MzdCarAppLayout, narrow: Boolean): ValueAnimator {
        R.setField(shell, "vehiclePanel", narrow)
        R.callInstanceMethod<Void>(shell, "movePanel")
        return requireNotNull(R.getField<ValueAnimator?>(shell, "panelAnimator")).apply { pause() }
    }
    private fun mask(): MzdTransitionMask = R.getField(
        R.getField<MzdVideoCoordinator>(host, "mazdaVideo"), "mask")

    @Test fun surfaceLossDuringFadeMustKeepTheWideTargetCovered() {
        pixels.fraction = 1728f / 2496f
        val shell = shell(narrow = true)
        val output = R.getField<View>(host, "videoView")
        assertEquals("The narrow viewport already has full-size video buffers", 2496, output.width)
        val texture = SurfaceTexture(0)
        try {
            R.setField(host, "currentSurfaceTexture", texture)
            val listener = R.getField<TextureView.SurfaceTextureListener>(host, "textureListener")
            val animator = move(shell, narrow = false)
            layout(shell)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            animator.end()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            pixels.fraction = 1f
            repeat(2) {
                listener.onSurfaceTextureUpdated(texture)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            val mask = mask()
            assertTrue("The fade has started after two target presentations", R.getField(mask, "fading"))
            assertTrue("Destroy the Surface during, not after, its fade", mask.isShowing)
            assertNotNull(mask.view.drawable)
            listener.onSurfaceTextureDestroyed(texture)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_500))
            assertTrue("A lost video surface cannot be exposed by the pending mask fade", mask.isShowing)
            assertEquals(1f, mask.view.alpha, 0f)
            assertNotNull("Keep the blurred picture through Surface replacement", mask.view.drawable)
            val replacement = mock(Surface::class.java)
            `when`(replacement.isValid).thenReturn(true)
            R.getField<CarPlayVideoSurfaceOwner<Surface>>(host, "videoSurfaceOwner")
                .replace(replacement, releaseOnDetach = false)
            R.setField(host, "currentSurfaceTexture", texture)
            R.getField<MzdVideoCoordinator>(host, "mazdaVideo").surfaceChanged()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            listener.onSurfaceTextureUpdated(texture)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertTrue("One presentation from the replacement cannot release the blur", mask.isShowing)
            listener.onSurfaceTextureUpdated(texture)
            ShadowChoreographer.setPaused(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertFalse("Two valid replacement presentations release the blur: " +
                "ready=${R.getField<Int>(mask, "readyFrames")} fading=${R.getField<Boolean>(mask, "fading")} " +
                "alpha=${mask.view.alpha}", mask.isShowing)
            assertEquals(2496, output.width)
        } finally { texture.release() }
    }

    @Test fun sidePanelTabMustBePreparedBeforeThe400msEndpoint() {
        for (fromNarrow in listOf(false, true)) {
            val shell = shell(narrow = fromNarrow, sidePanel = true)
            val tab = R.getField<View>(host, "sidePanelTab")
            assertEquals(View.VISIBLE, tab.visibility)
            val animator = move(shell, narrow = !fromNarrow)
            assertEquals(400L, animator.duration)
            layout(shell)
            val preparedLeft = (tab.layoutParams as FrameLayout.LayoutParams).leftMargin
            val preparedMeasures = measures
            val preparedLayouts = layouts
            for (time in listOf(350L, 383L, 400L)) {
                animator.currentPlayTime = time
                assertFalse(shell.isLayoutRequested)
            }
            animator.end()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("The tab cannot change layout parameters at the endpoint", preparedLeft,
                (tab.layoutParams as FrameLayout.LayoutParams).leftMargin)
            assertFalse("The final frame cannot request a responsive host traversal", shell.isLayoutRequested)
            assertEquals(preparedMeasures, measures)
            assertEquals(preparedLayouts, layouts)
        }
    }

    @Test fun hiddenWindowChangingTargetMustNotKeepThePreviousBlurForEightSeconds() {
        val shell = shell(narrow = false)
        val animator = move(shell, narrow = true)
        layout(shell)
        animator.currentPlayTime = 200
        // The OEM reverses while our window is hidden. Visibility snaps to the provider state.
        Settings.Global.putInt(host.contentResolver, "key_screen_show", 0)
        shell.dispatchWindowVisibilityChanged(View.INVISIBLE)
        layout(shell)
        shell.dispatchWindowVisibilityChanged(View.VISIBLE)
        layout(shell)
        assertEquals(2496, shell.getChildAt(0).width)
        resize(2496)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        pixels.fraction = 1f
        repeat(3) {
            mask().textureUpdated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse("A valid returned wide panel must not retain the cancelled narrow target mask", mask().isShowing)
    }

    @Test fun positionOnlyChangeAt383msMustNotCancelTheFinalDraw() {
        val shell = shell(narrow = false)
        val animator = move(shell, narrow = true)
        layout(shell)
        animator.currentPlayTime = 383
        shell.translationY = 2f // Same-size window/decor position change.
        val listener = R.getField<ViewTreeObserver.OnPreDrawListener>(shell, "windowPositionListener")
        val preparedMeasures = measures
        val preparedLayouts = layouts
        assertTrue("A position-only update at the tail must not cancel an otherwise ready draw", listener.onPreDraw())
        assertFalse(shell.isLayoutRequested)
        val output = R.getField<View>(host, "videoView")
        val origin = IntArray(2)
        output.getLocationOnScreen(origin)
        assertArrayEquals("Keep the video canvas at the same physical coordinates", intArrayOf(64, 192), origin)
        assertEquals(preparedMeasures, measures)
        assertEquals(preparedLayouts, layouts)
        animator.end()
        assertTrue(listener.onPreDraw())
        assertFalse(shell.isLayoutRequested)
    }

    @Test fun preparedWideAndNarrowPanelsKeepOpaqueBlurAtLargeFontScale() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (narrow in listOf(true, false)) {
            val shell = shell(narrow = !narrow)
            val animator = move(shell, narrow)
            layout(shell)
            animator.end()
            shadowOf(Looper.getMainLooper()).idle()
            val root = shell.getChildAt(0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                for (x in 0 until root.width step 8) assertNotEquals(Color.BLACK, bitmap.getPixel(x, 540))
                val file = File("build/reports/mazda-ui/panel-lifecycle-${root.width}-large-font.png")
                requireNotNull(file.parentFile).mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
