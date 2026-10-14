package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.mazda.MzdCarHotspot
import java.util.WeakHashMap

internal object MzdIntegration {
    private val windows = WeakHashMap<Activity, Unit>()

    fun enabled(context: Context) = context.resources.getBoolean(R.bool.config_mazda)

    fun automaticHotspot(context: Context) = enabled(context) &&
        AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL &&
        MzdCarHotspot.permitted(context)

    fun wrap(activity: Activity, content: View): View {
        if (!enabled(activity)) return content
        windows[activity] = Unit
        return MzdCarAppLayout(activity).apply { setPanelContent(content) }
    }

    fun closeWindows() {
        // Finish every UI window, including the launcher root in the shared Mazda task.
        // Do not use finishAndRemoveTask: its service onTaskRemoved callback stops CarPlay.
        windows.keys.toList().forEach {
            if (!it.isFinishing) {
                (it as? CarPlayHostActivity)?.keepMazdaReturnPicture()
                it.finish()
            }
        }
        windows.clear()
    }

    fun exitApp(activity: Activity) {
        // Finish our UI to reveal the existing Launcher page (including AppList).
        // Refresh only a stale opposite-area record; do not navigate this area HOME.
        refreshOtherLauncherArea(activity)
        closeWindows()
    }

    fun refreshOtherLauncherArea(activity: Activity) {
        refreshLauncherArea(activity, if (homeArea(activity) == "area_left") "area_right" else "area_left")
    }

    /** Updating the APK kills the old UI without giving it an opportunity to navigate HOME. */
    fun refreshLauncherAfterUpdate(context: Context) {
        listOf("area_left", "area_right").forEach { refreshLauncherArea(context, it) }
    }

    private fun refreshLauncherArea(context: Context, area: String) {
        val key = if (area == "area_right") "copilot_display_status" else "main_display_status"
        val status = runCatching { Settings.Global.getString(context.contentResolver, key) }
            .getOrNull()?.trim()?.split(Regex("\\s+")) ?: return
        if (status.size < 4 || status[0] != key || status[1] != "1" || status[2] != context.packageName) return
        runCatching {
            context.sendBroadcast(Intent("wt.intent.action.gohome").setPackage("com.tinnove.launcher")
                .putExtra("launcher_display_area", area))
            Log.i("DiPlayUi", "Launcher refresh requested for stale DiPlay record in $area")
        }.onFailure { Log.w("DiPlayUi", "OEM area refresh unavailable: ${it.javaClass.simpleName}") }
    }

    internal fun homeArea(activity: Activity): String {
        val decor = activity.window.decorView
        if (decor.isLaidOut && decor.width > 0) {
            val origin = IntArray(2)
            decor.getLocationOnScreen(origin)
            // Both halves use Android display 0. Follow the live task position after flying screens,
            // rather than its original task_display_area launch extra.
            return if (decor.width < 5120 && origin[0] >= 2560) "area_right" else "area_left"
        }
        if (Build.VERSION.SDK_INT >= 30 && activity.windowManager.currentWindowMetrics.bounds.left >= 2560)
            return "area_right"
        return if (activity.intent?.getStringExtra("task_display_area") == "area_right") "area_right" else "area_left"
    }

    fun cockpit(context: Context) = enabled(context) && context is Activity &&
        MzdCarAppLayout.isCockpitWindow(context)
}
