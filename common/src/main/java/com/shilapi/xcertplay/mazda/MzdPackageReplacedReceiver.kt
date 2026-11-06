package com.shilapi.xcertplay.mazda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Registered by Mazda only; recovery must run before Launcher can reject the first app click. */
class MzdPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && MzdIntegration.enabled(context)) {
            MzdIntegration.refreshLauncherAfterUpdate(context)
        }
    }
}
