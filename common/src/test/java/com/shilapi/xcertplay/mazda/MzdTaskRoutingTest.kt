package com.shilapi.xcertplay.mazda

import android.content.Intent
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
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
}
