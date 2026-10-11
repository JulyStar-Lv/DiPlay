package com.shilapi.xcertplay.mazda

import android.app.Activity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdCockpitTest {
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
