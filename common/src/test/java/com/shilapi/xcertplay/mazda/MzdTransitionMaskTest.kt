package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
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
    private val checks = mutableListOf<(Boolean) -> Unit>()
    private var requests = 0
    private var rendered = 10L
    private var layoutRetries = 0

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

    @After fun tearDown() { mask.dispose(); activity.finish() }

    @Test fun immediatelyCoversEveryWidthWithoutCapturingOrDrawingOldControls() {
        mask.begin(2496)
        assertTrue(mask.isShowing)
        assertEquals(1f, mask.view.alpha, 0f)
        assertTrue(root.indexOfChild(output) < root.indexOfChild(mask.view))
        assertTrue(root.indexOfChild(mask.view) < root.indexOfChild(touches))
        for (width in listOf(2496, 2384, 2112, 1840, 1728, 2112, 2496)) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 1080)
            assertEquals(width, mask.view.width)
            assertEquals(1080, mask.view.height)
            mask.textureUpdated()
            idle(50)
        }
        assertTrue(checks.isEmpty())
        assertEquals(1f, mask.view.alpha, 0f)
    }

    @Test fun oldNarrowFramesAfterTheGuardCannotReleaseAnExpandingWindow() {
        mask.begin(2496)
        mask.settle(2496)
        mask.textureUpdated()
        idle(449)
        assertTrue(checks.isEmpty())
        idle(1)
        assertEquals(1, requests)
        present(false)
        present(false)
        present(true)
        idle(250)
        assertTrue("One matching presentation is not enough", mask.isShowing)
        present(false) // A transient good frame must not survive an intervening old/black frame.
        present(true)
        assertEquals(1f, mask.view.alpha, 0f)
        present(true)
        idle(250)
        assertFalse(mask.isShowing)
    }

    @Test fun areaNegotiationOverlapsThePanelAnimationInsteadOfAddingAnotherGuardAtItsEndpoint() {
        mask.begin(1728)
        mask.areaRequested(1728)
        idle(400)
        mask.textureUpdated()
        assertTrue("A prepared frame must not bypass the moving window", checks.isEmpty())
        mask.settle(1728)
        idle(49)
        assertEquals(0, requests)
        idle(1)
        assertEquals("Only the remaining 50 ms guard belongs after the 400 ms animation", 1, requests)
        mask.textureUpdated()
        assertEquals("A presentation callback must not wait for the next 50 ms poll", 1, checks.size)
        checks.last()(true)
        idle(16)
        mask.textureUpdated()
        assertEquals(2, checks.size)
        checks.last()(true)
        idle(200)
        assertFalse(mask.isShowing)
    }

    @Test fun aSlowPanelStillGatesReadyFramesAndAReversalRestartsTheAreaGuard() {
        mask.begin(1728); mask.areaRequested(1728)
        idle(800)
        mask.textureUpdated()
        assertTrue(checks.isEmpty())
        mask.settle(1728); idle(0)
        present(true)
        mask.begin(2496)
        mask.areaRequested(1728) // A late notification for the previous direction is not preparation.
        mask.settle(2496)
        val beforeGuard = checks.size
        mask.textureUpdated(); idle(449)
        assertEquals(beforeGuard, checks.size)
        idle(1)
        present(true); present(true); idle(200)
        assertFalse(mask.isShowing)
    }

    @Test fun theSameTextureBufferCannotCountAsTwoFramesOrLaunchConcurrentCopies() {
        mask.begin(); mask.settle(); idle(450)
        mask.textureUpdated(); idle(50)
        assertEquals(1, checks.size)
        mask.textureUpdated(); idle(100)
        assertEquals("One PixelCopy/capture at a time", 1, checks.size)
        checks.last()(true)
        idle(50) // The presentation that arrived while copying is another distinct buffer.
        assertEquals(2, checks.size)
        checks.last()(false)
        idle(300)
        assertEquals(2, checks.size)
        assertTrue(mask.isShowing)
        present(true)
        idle(300)
        assertTrue("Polling the same successful buffer must not release the mask", mask.isShowing)
        present(true); idle(250)
        assertFalse(mask.isShowing)
    }

    @Test fun reversalAndDisposalIgnoreOldAsyncResultsAndCancelAnOldFade() {
        mask.begin(1728); mask.settle(1728); idle(450)
        mask.textureUpdated(); idle(50)
        val stale = checks.last()
        mask.begin(2496)
        stale(true)
        mask.settle(1728); idle(500)
        assertEquals(1, checks.size)
        assertEquals(1f, mask.view.alpha, 0f)
        mask.settle(2496); idle(450)
        present(true); present(true); idle(50)
        mask.begin(1728)
        idle(300)
        assertTrue(mask.isShowing)
        assertEquals(1f, mask.view.alpha, 0f)
        mask.settle(1728); idle(450)
        mask.textureUpdated(); idle(50)
        val disposed = checks.last()
        mask.dispose(); disposed(true); idle(3000)
        assertNull(mask.view.parent)
        assertFalse(mask.isShowing)
    }

    @Test fun surfaceOutputAlsoRequiresTwoVerifiedPostGuardPresentationsAndRecoversFromStalls() {
        mask.dispose(); mask = create(texture = false)
        mask.begin(); mask.settle()
        rendered = 20L
        idle(1800)
        assertTrue(checks.isEmpty())
        assertTrue(mask.isShowing)
        assertEquals(1, layoutRetries)
        rendered = 1L // A recreated Surface/decoder restarted its counter during the transition.
        idle(50); checks.last()(false)
        rendered++; idle(50); checks.last()(true)
        idle(250)
        assertTrue(mask.isShowing)
        rendered++; idle(50); checks.last()(true)
        idle(250)
        assertFalse(mask.isShowing)
        val completed = requests
        idle(3000)
        assertEquals(completed, requests)
    }

    @Test fun aMissingOrWrongEndpointCannotBeReplacedByFramesOrATimer() {
        mask.begin(2496)
        mask.settle(1728)
        mask.textureUpdated(); idle(2800)
        assertTrue(mask.isShowing)
        assertEquals(1, layoutRetries)
        assertTrue(checks.isEmpty())
        mask.settle(2496); idle(450)
        present(true); present(true); idle(250)
        assertFalse(mask.isShowing)
    }

    @Test fun darkOrUnreadableFramesCannotKeepVideoAndTouchCoveredForever() {
        mask.begin(2496); mask.settle(2496); idle(450)
        repeat(3) { present(false) }
        idle(7_399)
        assertTrue("Retain normal protection while waiting for target pixels", mask.isShowing)
        idle(251)
        assertFalse("A dark valid screen or failed PixelCopy must not permanently block CarPlay", mask.isShowing)
        val completed = requests
        idle(5_000)
        assertEquals("Recovery cancels polling and refresh retries", completed, requests)
    }

    @Test fun reversalRestartsTheRecoveryDeadlineAndDisposalCancelsIt() {
        mask.begin(1728); mask.settle(1728); idle(7_000)
        mask.begin(2496); mask.settle(2496); idle(1_500)
        assertTrue("The previous transition's guard must not reveal a new transition", mask.isShowing)
        mask.dispose()
        val completed = requests
        idle(10_000)
        assertFalse(mask.isShowing)
        assertEquals(completed, requests)
    }

    private fun present(ready: Boolean) {
        val previous = checks.size
        mask.textureUpdated()
        idle(50)
        assertEquals(previous + 1, checks.size)
        checks.last()(ready)
    }

    private fun create(texture: Boolean) = MzdTransitionMask(root, output, Handler(Looper.getMainLooper()),
        checkFrame = { checks += it }, requestPicture = { requests++ },
        renderedFrames = { rendered }, textureOutput = texture, retryLayout = { layoutRetries++ })

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}
