package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.CarPlayHostActivity
import java.util.WeakHashMap
import java.lang.ref.WeakReference

internal object MzdIntegration {
    private class PanelWindow(layout: MzdCarAppLayout) {
        val layout = WeakReference(layout)
        var started = false
        var resumedOrder = 0L
    }
    private val windows = WeakHashMap<Activity, PanelWindow>()
    private val applications = WeakHashMap<Application, Unit>()
    private var resumedOrder = 0L
    private val windowLifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            windows[activity]?.apply { started = true; resumedOrder = ++this@MzdIntegration.resumedOrder }
            updateWindowLayers()
        }
        override fun onActivityStopped(activity: Activity) {
            windows[activity]?.started = false
            updateWindowLayers()
        }
        override fun onActivityDestroyed(activity: Activity) {
            windows.remove(activity)
            updateWindowLayers()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    fun enabled(context: Context) = context.resources.getBoolean(R.bool.config_mazda)

    fun wrap(activity: Activity, content: View): View {
        if (!enabled(activity)) return content
        val application = activity.application
        if (applications.put(application, Unit) == null) application.registerActivityLifecycleCallbacks(windowLifecycle)
        return MzdCarAppLayout(activity).apply {
            setPanelContent(content)
            val previous = windows[activity]
            windows[activity] = PanelWindow(this).also {
                it.started = previous?.started ?: false
                it.resumedOrder = previous?.resumedOrder ?: 0L
            }
            updateWindowLayers()
        }
    }

    internal fun updateWindowLayers() {
        // Both Activities can remain in the OEM task for screen transfers. Their
        // independent animators can differ by a frame and expose the home's white background.
        // Hide only the covered page, retaining the projection Surface and background session.
        windows.forEach { (activity, window) ->
            val covered = activity !is CarPlayHostActivity && cockpit(activity) && windows.any { (other, above) ->
                other is CarPlayHostActivity && !other.isFinishing && cockpit(other) && above.started &&
                    above.resumedOrder > window.resumedOrder && homeArea(other) == homeArea(activity)
            }
            window.layout.get()?.setCoveredByProjection(covered)
        }
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
