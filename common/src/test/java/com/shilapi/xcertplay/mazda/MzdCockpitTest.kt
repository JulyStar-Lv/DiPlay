package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.CarPlayViewAreas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdCockpitTest {
    class CockpitWindowActivity : Activity() {
        var exposesLowerActivities = false
        override fun setTranslucent(translucent: Boolean): Boolean {
            exposesLowerActivities = translucent
            return true
        }
    }

    @Config(qualifiers = "w5120dp-h1440dp-mdpi")
    @Test fun transparentCockpitMarginsNeverEnableActivityStackTranslucency() {
        val controller = Robolectric.buildActivity(CockpitWindowActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Home/Host can reapply these colors after a UI-mode or configuration change.
            // A transparent Drawable must never also reveal the Activity stack under the crop.
            repeat(3) {
                assertTrue(MzdCarAppLayout.applyWindowBackground(activity))
                assertEquals(Color.TRANSPARENT, (activity.window.decorView.background as ColorDrawable).color)
                assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
                assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
                assertFalse(activity.window.isNavigationBarContrastEnforced)
                assertFalse("AppList must not be made visible behind the cockpit margins",
                    activity.exposesLowerActivities)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun windowVisibility(view: View, visibility: Int) {
        // Robolectric leaves AttachInfo at GONE even after ActivityController.visible().
        // Android updates it before dispatching this callback from ViewRootImpl.
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", visibility)
        view.dispatchWindowVisibilityChanged(visibility)
    }

    @Config(qualifiers = "w5120dp-h1440dp-mdpi", shadows = [MzdOemReturnTest.MazdaResources::class])
    @Test fun passengerStartupAndSameSizeTaskMovesKeepContentInsideItsOwnArea() {
        MzdOemReturnTest.MazdaResources.enabled = true
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val child = View(activity)
        val shell = MzdIntegration.wrap(activity, child) as MzdCarAppLayout
        val parent = android.widget.FrameLayout(activity).apply {
            addView(shell, android.widget.FrameLayout.LayoutParams(2560, 1374).apply {
                leftMargin = 2560
                topMargin = 66
            })
        }
        fun settle() {
            repeat(3) {
                parent.measure(View.MeasureSpec.makeMeasureSpec(5120, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, 5120, 1440)
                if (!parent.viewTreeObserver.dispatchOnPreDraw()) return
            }
            fail("The panel must settle after the task's screen position changes")
        }
        fun visiblePanel(expected: Rect) {
            val origin = IntArray(2)
            child.getLocationOnScreen(origin)
            assertEquals(expected, Rect(origin[0], origin[1], origin[0] + child.width,
                origin[1] + child.height))
            val visible = Rect()
            assertTrue("The content must have a visible area, rather than lie outside its task",
                child.getGlobalVisibleRect(visible))
            assertEquals(1080, child.height)
        }
        fun navigate(area: String) {
            activity.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                .putExtra("reason", "TinnoveCarSystemBarButton").putExtra("task_display_area", area))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            // These are driver-only settings, even when the app starts on the passenger half.
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 1)
            Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", 1)
            activity.setContentView(parent)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shell.isAttachedToWindow)
            settle()
            visiblePanel(Rect(2592, 192, 5088, 1272))
            assertEquals(2496, child.width)
            assertEquals("Match the OEM passenger panel's equal horizontal margins", 32, child.left)
            assertEquals(32, shell.width - child.right)

            // Reparent the same view without detaching it or changing its measured size.
            (shell.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin = 0
            parent.requestLayout()
            settle()
            visiblePanel(Rect(832, 192, 2560, 1272))
            assertEquals(1728, child.width)
            navigate("area_right")
            assertFalse("Passenger navigation must not close the driver window", activity.isFinishing)

            (shell.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin = 2560
            parent.requestLayout()
            settle()
            visiblePanel(Rect(2592, 192, 5088, 1272))
            assertEquals(2496, child.width)
            navigate("area_left")
            assertFalse("Driver navigation must not close the passenger window", activity.isFinishing)
            navigate("area_right")
            assertTrue("Passenger navigation must close its own window", activity.isFinishing)
        } finally {
            MzdIntegration.closeWindows()
            activity.finish()
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", 0)
        }
    }

    @Config(qualifiers = "w2560dp-h1440dp-mdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun sidebarResizeFollowsAppListCurveInBothDirectionsAndReversesFromCurrentProgress() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val child = View(activity)
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(child) }
        activity.setContentView(shell)
        fun layout() {
            shell.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 2560, 1440)
            val origin = IntArray(2)
            child.getLocationOnScreen(origin)
            assertEquals(192, origin[1])
            assertEquals(2560, origin[0] + child.width)
            assertEquals(1272, origin[1] + child.height)
            assertEquals(1080, child.height)
        }
        fun animator(): ValueAnimator = requireNotNull(ReflectionHelpers.getField(shell, "panelAnimator"))
        fun signal(key: String, value: Int) {
            // Layout is driven explicitly below. Release the real ViewRoot's queued traversal
            // barrier so the paused Choreographer does not block the provider's Handler message.
            val viewRoot = ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Void>(viewRoot, "unscheduleTraversals")
            Settings.Global.putInt(activity.contentResolver, key, value)
            // Model the provider notification explicitly; ShadowSettings does not publish every write.
            activity.contentResolver.notifyChange(Settings.Global.getUriFor(key), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ReflectionHelpers.callInstanceMethod<Boolean>(shell, "readVehiclePanel"),
                ReflectionHelpers.getField<Boolean>(shell, "vehiclePanel"))
        }
        try {
            // Deliver Settings notifications without Robolectric automatically finishing all frames.
            ShadowChoreographer.setPaused(true)
            layout()
            windowVisibility(shell, View.VISIBLE)
            assertEquals(64, child.left)
            assertTrue(shell.isLaidOut)
            assertTrue(shell.isShown)
            assertEquals(View.VISIBLE, shell.windowVisibility)
            signal("key_screen_show", 1)
            val shrinking = animator()
            shrinking.pause()
            assertEquals(400L, shrinking.duration)
            shrinking.currentPlayTime = 0
            layout()
            assertEquals("Keep covering AppList while the OEM sidebar starts entering", 64, child.left)
            // Independent samples of the APK's 400 ms MotionScene interpolator 0 over 768 px.
            // Check both directions, not just the midpoint (where several wrong curves agree).
            val appListLeft = listOf(64, 93, 176, 301, 448, 595, 720, 803, 832)
            appListLeft.forEachIndexed { frame, expected ->
                shrinking.currentPlayTime = frame * 50L
                layout()
                assertEquals("Launcher AppList shrinking at ${frame * 50} ms", expected, child.left)
            }
            // The two OEM signals share one transition; a second true signal must not restart it.
            signal("key_vpa_cui_show_left", 1)
            assertSame(shrinking, animator())
            shrinking.currentPlayTime = 400
            layout()
            assertEquals(832, child.left)
            signal("key_screen_show", 0)
            assertSame(shrinking, animator())
            signal("key_vpa_cui_show_left", 0)
            val expanding = animator()
            assertNotSame(shrinking, expanding)
            expanding.pause()
            appListLeft.forEachIndexed { frame, expected ->
                expanding.currentPlayTime = frame * 50L
                layout()
                assertEquals("Launcher AppList expanding at ${frame * 50} ms", 896 - expected, child.left)
            }
            expanding.currentPlayTime = 100
            layout()
            assertEquals(720, child.left)
            val reversalLeft = child.left
            // Reverse a live animation from its displayed edge, without snapping to either endpoint.
            signal("key_screen_show", 1)
            val reversed = animator()
            assertNotSame(expanding, reversed)
            reversed.pause()
            reversed.currentPlayTime = 0
            layout()
            assertEquals(reversalLeft, child.left)
            assertEquals("Use remaining MotionLayout progress, not eased pixel distance", 100L, reversed.duration)
            reversed.currentPlayTime = 50
            layout()
            assertEquals("Continue the end of the same eased curve on reversal", 803, child.left)
            reversed.currentPlayTime = reversed.duration
            layout()
            assertEquals(832, child.left)
            // Removing the window cancels its animation and callbacks.
            signal("key_screen_show", 0)
            val retiring = animator()
            assertTrue(retiring.isRunning)
            activity.setContentView(View(activity))
            assertFalse(retiring.isRunning)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
        } finally {
            activity.finish()
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", 0)
            ShadowChoreographer.setPaused(false)
        }
    }

    @Config(qualifiers = "w2560dp-h1440dp-mdpi")
    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun hiddenWindowJumpsToSidebarStateWithoutReplayingAnAnimationOnReturn() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val child = View(activity)
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(child) }
        activity.setContentView(shell)
        fun layout() {
            shell.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, 2560, 1440)
        }
        try {
            ShadowChoreographer.setPaused(true)
            layout()
            windowVisibility(shell, View.VISIBLE)
            assertTrue(shell.isLaidOut)
            assertTrue(shell.isShown)
            assertEquals(View.VISIBLE, shell.windowVisibility)
            val viewRoot = ReflectionHelpers.callInstanceMethod<Any>(shell, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Void>(viewRoot, "unscheduleTraversals")
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 1)
            activity.contentResolver.notifyChange(Settings.Global.getUriFor("key_screen_show"), null)
            shadowOf(Looper.getMainLooper()).idle()
            val running = requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
            running.pause()
            running.currentPlayTime = 100
            layout()
            assertEquals(176, child.left)

            windowVisibility(shell, View.GONE)
            assertFalse(running.isRunning)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
            layout()
            assertEquals("Finish the current state when the window disappears", 832, child.left)
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            // Returning re-reads the provider, including changes made while not drawing.
            windowVisibility(shell, View.VISIBLE)
            layout()
            assertEquals(64, child.left)
            assertNull(ReflectionHelpers.getField<ValueAnimator?>(shell, "panelAnimator"))
        } finally {
            activity.finish()
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
            Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", 0)
            ShadowChoreographer.setPaused(false)
        }
    }

    @Test fun oemPanelCoordinatesFollowBothSidebarSignalsAndOrdinaryWindowsStayWhole() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(MzdIntegration.enabled(activity))
        val child = View(activity)
        fun bounds(screen: Int, assistant: Int, width: Int = 2560, height: Int = 1440): List<Int> {
            Settings.Global.putInt(activity.contentResolver, "key_screen_show", screen)
            Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", assistant)
            val shell = MzdCarAppLayout(activity).apply { setPanelContent(child) }
            shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, width, height)
            val result = listOf(child.left, child.top, child.right, child.bottom)
            shell.removeAllViews()
            return result
        }
        assertEquals(listOf(64, 192, 2560, 1272), bounds(0, 0))
        assertEquals(listOf(832, 192, 2560, 1272), bounds(1, 0))
        assertEquals(listOf(832, 192, 2560, 1272), bounds(0, 1))
        // Launcher CommonUtil.getScreenStatus uses max(abs(cui), abs(screen)).
        assertEquals(listOf(832, 192, 2560, 1272), bounds(0, -1))
        assertEquals(listOf(832, 192, 2560, 1272), bounds(0, 2))
        assertEquals(listOf(832, 192, 2560, 1272), bounds(-1, 0))
        assertEquals(listOf(0, 0, 1280, 720), bounds(1, 1, 1280, 720))
        Settings.Global.putInt(activity.contentResolver, "key_screen_show", 0)
        Settings.Global.putInt(activity.contentResolver, "key_vpa_cui_show_left", 0)
        activity.finish()
    }

    @Config(qualifiers = "w2560dp-h1440dp-mdpi")
    @Test fun insetAndroidDecorStillPlacesThePanelAtPhysicalOemCoordinates() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(MzdCarAppLayout.isCockpitWindow(activity))
        val child = View(activity)
        val shell = MzdCarAppLayout(activity).apply { setPanelContent(child) }
        val parent = android.widget.FrameLayout(activity).apply {
            addView(shell, android.widget.FrameLayout.LayoutParams(2560, 1374).apply { topMargin = 66 })
        }
        activity.setContentView(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 2560, 1440)
        val origin = IntArray(2)
        child.getLocationOnScreen(origin)
        assertArrayEquals(intArrayOf(64, 192), origin)
        assertEquals(2496, child.width)
        assertEquals(1080, child.height)
        activity.finish()
    }

    @Test fun narrowStartupAndRestorationUseTheSameCanvasAtEachResolutionAndDock() {
        for (dock in CarPlayDock.entries) for ((width, height) in listOf(2496 to 1080, 1248 to 540)) {
            val areas = MzdViewAreas.build(width, height, dock, narrow = true)
            assertEquals(CarPlayViewAreas.Kind.SPLIT_SCREEN, areas.kindOf(areas.current))
            assertEquals(width * 1728 / 2496 and 1.inv(), areas.layoutArea(areas.current).width)
            assertEquals(height, areas.layoutArea(areas.current).height)
            val full = requireNotNull(areas.indexFor(2496, 1080, false))
            assertEquals(width, areas.layoutArea(full).width)
            assertEquals(areas.areas[areas.current].dockEdge, areas.areas[full].dockEdge)
            areas.use(full)
            val narrow = requireNotNull(areas.indexFor(1728, 1080, true))
            assertEquals(CarPlayViewAreas.Kind.SPLIT_SCREEN, areas.kindOf(narrow))
        }
    }
}
