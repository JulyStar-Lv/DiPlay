package com.shilapi.xcertplay.mazda

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayTransport

/** Saved MANUAL remains the driver's car-hotspot choice; LOCAL_ONLY is a runtime strategy only. */
internal object MzdWirelessConnection {
    const val MANUAL_FALLBACK = "mazda_manual_hotspot_fallback"
    private const val CONNECTION_PREFERENCES = "mazda_connection"
    private const val SKIP_WIFI_CHECK = "skip_wifi_check"

    fun skipFutureWifiChecks(context: Context) {
        context.getSharedPreferences(CONNECTION_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(SKIP_WIFI_CHECK, true).apply()
    }

    fun hasManualDetails(context: Context): Boolean =
        AirPlayPersistence.loadManualHotspotSsid(context).isNotEmpty() ||
            AirPlayPersistence.loadManualHotspotPassphrase(context).isNotEmpty()

    fun clearsManualDetails(context: Context, ssid: String, password: String): Boolean =
        MzdIntegration.enabled(context) && ssid.isEmpty() && password.isEmpty()

    fun automatic(context: Context, mode: WirelessHotspotMode): Boolean =
        MzdIntegration.enabled(context) && mode != WirelessHotspotMode.EXISTING_WIFI && !hasManualDetails(context)

    fun runtimeMode(context: Context, mode: WirelessHotspotMode,
        ssid: String = AirPlayPersistence.loadManualHotspotSsid(context),
        password: String = AirPlayPersistence.loadManualHotspotPassphrase(context),
    ): WirelessHotspotMode =
        if (MzdIntegration.enabled(context) && mode != WirelessHotspotMode.EXISTING_WIFI) {
            if (ssid.isEmpty() && password.isEmpty()) WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
            else WirelessHotspotMode.MANUAL
        } else mode

    fun wifiWarning(context: Context, mode: WirelessHotspotMode): String? =
        if (MzdIntegration.enabled(context) && mode != WirelessHotspotMode.EXISTING_WIFI &&
            !context.getSharedPreferences(CONNECTION_PREFERENCES, Context.MODE_PRIVATE).getBoolean(SKIP_WIFI_CHECK, false) &&
            context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        ) context.getString(R.string.mazda_wifi_off_message) else null

    fun firstTcpTimeoutMillis(context: Context, mode: WirelessHotspotMode): Long =
        if (MzdIntegration.enabled(context) && mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
            MzdLocalHotspot.FIRST_TCP_TIMEOUT_MILLIS else WirelessStartupPolicy.FIRST_TCP_MILLIS

    fun fallbackIntent(context: Context): Intent = Intent(context, DiPlayActivity::class.java)
        .putExtra("page", "connection").putExtra(MANUAL_FALLBACK, true)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    fun releaseLocalHotspot(context: Context) {
        if (MzdIntegration.enabled(context)) MzdLocalHotspot.release()
    }

    fun hotspotFactory(context: Context, mode: WirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
        transport: CarPlayTransport = CarPlayTransport.WIRELESS, onFailure: () -> Unit,
    ): ((Context, (String) -> Unit) -> WirelessHotspotManager)? {
        if (!MzdIntegration.enabled(context) || Build.VERSION.SDK_INT < 30) return null
        if (mode != WirelessHotspotMode.LOCAL_ONLY_HOTSPOT || transport != CarPlayTransport.WIRELESS) {
            releaseLocalHotspot(context)
            return null
        }
        return { app, diagnostic ->
            val delegate = MzdLocalHotspot.manager(app, context.getString(R.string.app_name), diagnostic)
            object : WirelessHotspotManager by delegate {
                @Volatile private var closed = false

                override fun close() {
                    closed = true
                    delegate.close()
                }

                override fun start(timeoutMillis: Long): WirelessHotspotInfo = try {
                    delegate.start(timeoutMillis)
                } catch (failure: Exception) {
                    delegate.close()
                    if (!closed) onFailure()
                    throw failure
                }
            }
        }
    }
}
