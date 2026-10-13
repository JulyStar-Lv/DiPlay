package com.shilapi.xcertplay.mazda

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MzdWindowThemeTest {
    @Test fun projectionStaysInTheLaunchersTaskOnEitherCockpitHalf() {
        val context = RuntimeEnvironment.getApplication()
        val home = context.packageManager.getActivityInfo(ComponentName(context, DiPlayActivity::class.java), 0)
        val projection = context.packageManager.getActivityInfo(ComponentName(context, CarPlayHostActivity::class.java), 0)
        assertEquals(context.packageName, home.taskAffinity)
        assertEquals(home.taskAffinity, projection.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, projection.launchMode)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, home.launchMode)
    }

    @Test fun launcherAndProjectionSuppressTheBlackStartingWindow() {
        val context = RuntimeEnvironment.getApplication()
        for (activity in listOf(DiPlayActivity::class.java, CarPlayHostActivity::class.java)) {
            val info = context.packageManager.getActivityInfo(ComponentName(context, activity), 0)
            val theme = context.resources.newTheme().apply { applyStyle(info.theme, true) }
            val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowDisablePreview))
            try { assertTrue(activity.simpleName, attributes.getBoolean(0, false)) }
            finally { attributes.recycle() }
        }
    }
}
