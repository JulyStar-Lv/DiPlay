package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.CarPlayViewAreas
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [MzdOemReturnTest.MazdaResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MzdReturnPictureTest {
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private val display = CarPlaySessionDisplay(2496, 1080, 0, false, false, 2496, 1080)
    private var stops = 0

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        MzdOemReturnTest.MazdaResources.enabled = true
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        sink = mock(AndroidMediaSink::class.java)
    }

    @After fun tearDown() {
        hosts.forEach {
            ReflectionHelpers.getField<Handler>(it, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(it, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(it, "airPlayCommandExecutor").shutdownNow()
        }
        MzdIntegration.closeWindows()
        CarPlayBackgroundSession.clear()
        MzdOemReturnTest.MazdaResources.enabled = true
    }

    @Test fun oemReturnCapturesABoundedPictureAndReopenedHostWaitsForItsOwnTextureFrame() {
        val old = host()
        val output = video(old)
        store(old)
        CarPlayBackgroundSession.active = true
        MzdIntegration.closeWindows()
        assertTrue(old.isFinishing)
        val kept = CarPlayBackgroundSession.snapshot()!!.returnPicture!!
        assertEquals(1248, kept.width)
        assertEquals(540, kept.height)
        invoke(old, "discardLastPicture")
        assertSame(kept, CarPlayBackgroundSession.snapshot()!!.returnPicture)

        val reopened = host()
        val touch = View(reopened)
        (video(reopened).parent as FrameLayout).addView(touch)
        assertEquals(true, invoke(reopened, "adoptBackgroundSession"))
        val hold = ReflectionHelpers.getField<ImageView>(reopened, "pictureHold")
        assertEquals(View.VISIBLE, hold.visibility)
        assertSame(kept, (hold.drawable as BitmapDrawable).bitmap)
        val viewport = hold.parent as FrameLayout
        assertTrue(viewport.indexOfChild(hold) < viewport.indexOfChild(touch))
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()!!.sink)
        assertEquals(0, stops)

        val current = SurfaceTexture(0)
        val stale = SurfaceTexture(0)
        try {
            ReflectionHelpers.setField(reopened, "currentSurfaceTexture", current)
            val listener = ReflectionHelpers.getField<TextureView.SurfaceTextureListener>(reopened, "textureListener")
            listener.onSurfaceTextureUpdated(stale)
            assertTrue(ReflectionHelpers.getField(reopened, "pictureHoldAwaitTexture"))
            listener.onSurfaceTextureUpdated(current)
            assertFalse(ReflectionHelpers.getField(reopened, "pictureHoldAwaitTexture"))
            assertEquals(0, stops)
        } finally {
            current.release()
            stale.release()
        }
        assertEquals(1, output.captures)
    }

    @Test fun pictureFollowsOnlyItsSessionAndCannotBeOverwrittenByAStaleOwner() {
        val first = host()
        store(first)
        CarPlayBackgroundSession.active = true
        first.keepMazdaReturnPicture()
        val kept = CarPlayBackgroundSession.snapshot()!!.returnPicture!!
        val next = host()
        store(next)
        assertSame(kept, CarPlayBackgroundSession.snapshot()!!.returnPicture)
        CarPlayBackgroundSession.keepReturnPicture(first, sink, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        assertSame(kept, CarPlayBackgroundSession.snapshot()!!.returnPicture)
        val replacement = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(replacement, sink, 2496, 1080, next, display) { it() }
        assertNull(CarPlayBackgroundSession.snapshot()!!.returnPicture)
        CarPlayBackgroundSession.clear()
        assertNull(CarPlayBackgroundSession.snapshot())
    }

    @Test fun inactiveAndOtherVariantSessionsDoNotCapturePictures() {
        val activity = host()
        store(activity)
        activity.keepMazdaReturnPicture()
        assertNull(CarPlayBackgroundSession.snapshot()!!.returnPicture)
        CarPlayBackgroundSession.active = true
        MzdOemReturnTest.MazdaResources.enabled = false
        activity.keepMazdaReturnPicture()
        assertNull(CarPlayBackgroundSession.snapshot()!!.returnPicture)
        assertEquals(0, video(activity).captures)
    }

    @Test fun surfaceOutputAlsoRestoresTheSessionPictureInsideItsVideoViewport() {
        val old = host()
        store(old)
        CarPlayBackgroundSession.active = true
        old.keepMazdaReturnPicture()
        val kept = CarPlayBackgroundSession.snapshot()!!.returnPicture!!
        val reopened = host()
        val viewport = video(reopened).parent as FrameLayout
        viewport.removeAllViews()
        val surface = SurfaceView(reopened)
        viewport.addView(surface, FrameLayout.LayoutParams(1728, 1080))
        ReflectionHelpers.setField(reopened, "videoView", viewport)
        ReflectionHelpers.setField(reopened, "fallbackVideoView", surface)
        assertEquals(true, invoke(reopened, "adoptBackgroundSession"))
        val hold = ReflectionHelpers.getField<ImageView>(reopened, "pictureHold")
        assertSame(viewport, hold.parent)
        assertSame(kept, (hold.drawable as BitmapDrawable).bitmap)
        assertEquals(View.VISIBLE, hold.visibility)
        assertFalse(ReflectionHelpers.getField(reopened, "pictureHoldAwaitTexture"))
        assertEquals(surface.layoutParams.width, hold.layoutParams.width)
        assertEquals(surface.layoutParams.height, hold.layoutParams.height)
    }

    @Test fun stoppingSessionRejectsCapturesUntilTeardownCompletes() {
        val activity = host()
        var complete: (() -> Unit)? = null
        CarPlayBackgroundSession.store(controller, sink, 2496, 1080, activity, display) { complete = it }
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.stop()
        try {
            activity.keepMazdaReturnPicture()
            assertNull(CarPlayBackgroundSession.snapshot()!!.returnPicture)
        } finally {
            CarPlayBackgroundSession.clear()
            complete!!.invoke()
        }
        assertFalse(CarPlayBackgroundSession.isStopping())
    }

    @Test @Config(qualifiers = "w5120dp-h1440dp-mdpi")
    fun reopenedFullPanelResynchronizesTheRetainedNarrowAreaEvenWhenItsSizeHasNotChanged() {
        assertReopenedPanelResynchronizes(2496, retainedNarrow = true)
    }

    @Test @Config(qualifiers = "w5120dp-h1440dp-mdpi")
    fun reopenedNarrowPanelResynchronizesTheRetainedFullAreaEvenWhenItsSizeHasNotChanged() {
        assertReopenedPanelResynchronizes(1728, retainedNarrow = false)
    }

    private fun assertReopenedPanelResynchronizes(panelWidth: Int, retainedNarrow: Boolean) {
        val old = host()
        val areas = MzdViewAreas.build(2496, 1080, CarPlayDock.AUTOMATIC, narrow = retainedNarrow)
        // The snapshot keeps the startup window size, while the same area list changes as
        // the driver opens the OEM sidebar and then leaves DiPlay for the launcher.
        CarPlayBackgroundSession.store(controller, sink, panelWidth, 1080, old,
            display.copy(rotation = invoke(old, "displayRotation") as Int,
                windowWidth = panelWidth, viewAreas = areas)) { stops++; it() }
        val retained = areas.current
        `when`(controller.showViewArea(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true)

        val reopened = host(panelWidth)
        ReflectionHelpers.setField(reopened, "hideTopBar", display.hideTopBar)
        ReflectionHelpers.setField(reopened, "hideBottomBar", display.hideBottomBar)
        assertEquals(true, invoke(reopened, "adoptBackgroundSession"))
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val active = ReflectionHelpers.getField<Any>(reopened, "activeDisplaySize")
        assertEquals(retained, areas.current)
        assertFalse("Same window layout must exercise the same-size shortcut",
            ReflectionHelpers.callInstanceMethod<Boolean>(reopened, "displayLayoutChanged",
                ReflectionHelpers.ClassParameter.from(sizeClass, active)))
        assertNull(ReflectionHelpers.getField<MzdVideoCoordinator?>(reopened, "mazdaVideo"))
        // Both panel shapes have the same full-size TextureView and decoder buffers.
        assertEquals(2496, video(reopened).width)
        assertEquals(panelWidth, (video(reopened).parent as MzdVideoCanvas).width)
        ReflectionHelpers.callInstanceMethod<Void>(reopened, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

        val target = requireNotNull(areas.index(if (panelWidth == 1728)
            CarPlayViewAreas.Kind.SPLIT_SCREEN else CarPlayViewAreas.Kind.FULL_SCREEN, null))
        assertEquals(target, areas.current)
        ReflectionHelpers.callInstanceMethod<Void>(reopened, "scheduleDisplaySize",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2496),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1080))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        verify(controller, times(1)).showViewArea(target)
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()!!.sink)
        assertEquals(0, stops)
        assertEquals(0, ReflectionHelpers.getField<Int>(reopened, "restartGeneration"))
    }

    private fun host(panelWidth: Int? = null) = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().apply {
        hosts.add(this)
        setTheme(R.style.Theme_Xcertplay)
        val output = CapturedTexture(this)
        val viewport = if (panelWidth == null) FrameLayout(this) else MzdVideoCanvas(this)
        viewport.addView(output, FrameLayout.LayoutParams(-1, -1))
        setContentView(MzdIntegration.wrap(this, viewport))
        if (panelWidth == null) output.layout(0, 0, 2496, 1080)
        else {
            viewport.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            viewport.layout(0, 0, panelWidth, 1080)
        }
        ReflectionHelpers.setField(this, "videoView", output)
        ReflectionHelpers.setField(this, "sink", sink)
    }

    private fun video(activity: CarPlayHostActivity) = ReflectionHelpers.getField<CapturedTexture>(activity, "videoView")
    private fun store(owner: CarPlayHostActivity) = CarPlayBackgroundSession.store(controller, sink,
        2496, 1080, owner, display) { stops++; it() }
    private fun invoke(activity: CarPlayHostActivity, method: String): Any? = ReflectionHelpers.callInstanceMethod(activity, method)

    private class CapturedTexture(context: android.content.Context) : TextureView(context) {
        var captures = 0
        override fun isAvailable() = true
        override fun getBitmap(width: Int, height: Int): Bitmap {
            captures++
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
