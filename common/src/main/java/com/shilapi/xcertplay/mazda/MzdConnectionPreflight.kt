package com.shilapi.xcertplay.mazda

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Mazda's immediate Wi-Fi hint; other builds retain the upstream connection flow. */
internal object MzdConnectionPreflight {
    fun blocked(context: Context, wireless: Boolean, showMessage: (String) -> Unit): Boolean {
        if (!MzdIntegration.enabled(context) || !wireless ||
            AirPlayPersistence.loadWirelessHotspotMode(context) != WirelessHotspotMode.WIFI_P2P) return false
        val wifiOff = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull() == false
        if (wifiOff) showMessage(context.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect))
        return wifiOff
    }
}
