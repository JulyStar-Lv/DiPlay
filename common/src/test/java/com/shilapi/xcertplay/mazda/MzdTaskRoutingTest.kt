package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.Intent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.OwnScreenStart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [MzdOemReturnTest.MazdaResources::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MzdTaskRoutingTest {
    @Test fun eitherLauncherAreaRetainsItsHomeRootWhileOpeningTheBackgroundProjection() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        MzdOemReturnTest.MazdaResources.enabled = true
        var stops = 0
        CarPlayBackgroundSession.clear()
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        try {
            for (area in listOf("area_left", "area_right")) {
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .putExtra("task_display_area", area)
                val activityController = Robolectric.buildActivity(DiPlayActivity::class.java, launch).create()
                val activity = activityController.get()
                try {
                    assertFalse("Keep the launcher root for $area", activity.isFinishing)
                    val projection = shadowOf(activity).nextStartedActivity!!
                    assertEquals(CarPlayHostActivity::class.java.name, projection.component!!.className)
                    assertEquals(area, projection.getStringExtra("task_display_area"))
                    assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, projection.flags)
                    assertTrue(CarPlayBackgroundSession.hasSession())
                    assertEquals(0, stops)
                } finally { activityController.destroy() }
            }
        } finally {
            MzdIntegration.closeWindows()
            CarPlayBackgroundSession.clear()
        }
    }

    @Test fun pageSwitchesFollowTheMovedWindowInsteadOfTheOriginalLaunchArea() {
        MzdOemReturnTest.MazdaResources.enabled = true
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.intent = Intent().putExtra("task_display_area", "area_left")
            val decor = activity.window.decorView
            decor.layout(0, 0, 2560, 1440)
            val attached = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
            for (left in listOf(2560, 0)) {
                ReflectionHelpers.setField(attached, "mWindowLeft", left)
                for (target in listOf(DiPlayActivity::class.java, CarPlayHostActivity::class.java)) {
                    assertTrue(OwnScreenStart.start(activity, Intent(activity, target)))
                    assertEquals(if (left == 0) "area_left" else "area_right",
                        shadowOf(activity).nextStartedActivity!!.getStringExtra("task_display_area"))
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
