package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.Context
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

    fun cockpit(context: Context) = enabled(context) && context is Activity &&
        MzdCarAppLayout.isCockpitWindow(context)
}
