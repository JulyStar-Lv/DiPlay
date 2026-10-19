package com.shilapi.xcertplay.mazda

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MzdWindowThemeTest {
    @Test fun apkUpdateCanRefreshLauncherBeforeTheFirstAppListLaunch() {
        val context = RuntimeEnvironment.getApplication()
        val matches = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)
        val info = matches.single {
            it.activityInfo.name == MzdPackageReplacedReceiver::class.java.name
        }.activityInfo
        assertTrue(info.enabled)
        assertFalse("Only the system/package owner should invoke recovery", info.exported)
    }

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

    @Test fun cockpitActivitiesOccludeAppListWithoutRequestingTheWallpaperWindow() {
        val context = RuntimeEnvironment.getApplication()
        for (activity in listOf(DiPlayActivity::class.java, CarPlayHostActivity::class.java)) {
            val info = context.packageManager.getActivityInfo(ComponentName(context, activity), 0)
            val theme = context.resources.newTheme().apply { applyStyle(info.theme, true) }
            val attributes = theme.obtainStyledAttributes(intArrayOf(
                android.R.attr.windowIsTranslucent, android.R.attr.windowShowWallpaper))
            try {
                assertFalse("${activity.simpleName} must occlude lower Activities", attributes.getBoolean(0, true))
                assertFalse("OEM task background owns the wallpaper", attributes.getBoolean(1, true))
            } finally { attributes.recycle() }
        }
    }
}
