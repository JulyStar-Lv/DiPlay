package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class MzdTransitionMaskTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var output: View
    private lateinit var touches: View
    private lateinit var mask: MzdTransitionMask
    private val callbacks = mutableListOf<(Bitmap?) -> Unit>()
    private var requests = 0
    private var rendered = 10L

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        output = View(activity)
        touches = View(activity)
        root = FrameLayout(activity).apply {
            addView(output, FrameLayout.LayoutParams(-1, -1))
            addView(touches, FrameLayout.LayoutParams(-1, -1))
        }
        activity.setContentView(root)
        mask = create(texture = true)
    }

    @After fun tearDown() {
        mask.dispose()
        activity.finish()
    }

    @Test fun maskIsImmediatelyOpaqueAndFillsEveryAnimatedPanelSizeBeforeASnapshotArrives() {
        mask.begin()
        assertTrue(mask.isShowing)
        assertEquals(1f, mask.view.alpha, 0f)
        assertNotNull(mask.view.background)
        assertNull(mask.picture.drawable)
        assertEquals(ImageView.ScaleType.FIT_XY, mask.picture.scaleType)
        assertEquals(ImageView.ScaleType.FIT_XY, mask.blur.scaleType)
        assertTrue(root.indexOfChild(output) < root.indexOfChild(mask.view))
        assertTrue(root.indexOfChild(mask.view) < root.indexOfChild(touches))
        for (width in listOf(2496, 2384, 2112, 1840, 1728, 2112, 2496)) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 1080)
            assertEquals(width, mask.view.width)
            assertEquals(1080, mask.view.height)
            mask.textureUpdated()
            assertEquals("Frames during the window animation must not reveal the live video", 1f, mask.view.alpha, 0f)
        }
        val picture = Bitmap.createBitmap(24, 15, Bitmap.Config.ARGB_8888)
        callbacks.single()(picture)
        assertSame(picture, (mask.picture.drawable as BitmapDrawable).bitmap)
        assertNotNull(mask.blur.drawable)
    }

    @Test fun endpointWaitsForTheIphoneAnimationAndThenANewTexturePresentation() {
        mask.begin()
        mask.settle()
        mask.textureUpdated()
        idle(449)
        mask.textureUpdated()
        assertEquals(1f, mask.view.alpha, 0f)
        assertEquals(0, requests)
        idle(1)
        assertEquals(1, requests)
        assertTrue(mask.isShowing)
        mask.textureUpdated()
        idle(250)
        assertFalse(mask.isShowing)
    }

    @Test fun aRapidReversalCancelsThePreviousReleaseAndStaleCapturesCannotReturnAfterDisposal() {
        mask.begin()
        val oldCapture = callbacks.single()
        mask.settle()
        idle(450)
        mask.textureUpdated()
        idle(50)
        mask.begin()
        idle(400)
        mask.textureUpdated()
        assertTrue(mask.isShowing)
        assertEquals(1f, mask.view.alpha, 0f)
        oldCapture(Bitmap.createBitmap(24, 15, Bitmap.Config.ARGB_8888))
        assertNull(mask.picture.drawable)
        val newCapture = callbacks.last()
        assertNotSame("A reversal must replace a snapshot that has not arrived", oldCapture, newCapture)
        val replacement = Bitmap.createBitmap(24, 15, Bitmap.Config.ARGB_8888)
        newCapture(replacement)
        assertSame(replacement, (mask.picture.drawable as BitmapDrawable).bitmap)
        mask.settle()
        idle(450)
        mask.textureUpdated()
        idle(250)
        assertFalse(mask.isShowing)
        mask.dispose()
        oldCapture(Bitmap.createBitmap(24, 15, Bitmap.Config.ARGB_8888))
        assertNull(mask.view.parent)
        assertNull(mask.picture.drawable)
    }

    @Test fun surfaceFramesMustAdvanceAfterTheGuardAndBothMissingFramesAndMissingEndpointsTimeOut() {
        mask.dispose()
        mask = create(texture = false)
        mask.begin()
        mask.settle()
        rendered = 20L // Frames during the iPhone's animation cannot complete the transition.
        idle(450)
        idle(50)
        assertTrue(mask.isShowing)
        rendered++
        idle(300)
        assertFalse(mask.isShowing)
        mask.begin()
        mask.settle()
        idle(1800)
        assertFalse("A stalled stream cannot keep the mask stuck", mask.isShowing)
        mask.begin()
        idle(2800)
        assertFalse("Moving/detaching the task may cancel its endpoint notification", mask.isShowing)
    }

    @Test fun anOldEndpointCannotArmTheMaskForANewDirection() {
        mask.begin(1728)
        mask.settle(1728)
        idle(100)
        mask.begin(2496)
        mask.settle(1728)
        idle(450)
        mask.textureUpdated()
        assertEquals(0, requests)
        assertEquals(1f, mask.view.alpha, 0f)
        mask.settle(2496)
        idle(450)
        assertEquals(1, requests)
        mask.textureUpdated()
        idle(250)
        assertFalse(mask.isShowing)
    }

    private fun create(texture: Boolean) = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
        capture = { callbacks += it }, requestPicture = { requests++ },
        renderedFrames = { rendered }, textureOutput = texture)

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}
