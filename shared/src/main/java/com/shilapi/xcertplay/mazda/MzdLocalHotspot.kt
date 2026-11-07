package com.shilapi.xcertplay.mazda

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.network.HotspotJoinElement
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.LocalOnlyHotspotRadioInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import java.io.IOException
import java.math.BigInteger
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.Executor

/** Upstream owns each reservation; Mazda retains it across retries and serializes AP transitions. */
object MzdLocalHotspot {
    data class Credentials(val ssid: String, val password: String)

    // A captured iPhone completed discovery at ~32 s; do not tear down at the default 30 s.
    const val FIRST_TCP_TIMEOUT_MILLIS = 45_000L

    private var monitoredContext: Context? = null
    private var stateObserver: LocalOnlyHotspotRadioInfo? = null
    private var sharedSession: MzdLocalHotspotSession? = null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun session(context: Context, diagnostic: (String) -> Unit): MzdLocalHotspotSession = synchronized(this) {
        val app = context.applicationContext
        if (monitoredContext !== app) {
            sharedSession?.close()
            stateObserver?.close()
            val lifecycle = MzdLocalHotspotLifecycle()
            val wifi = app.getSystemService(WifiManager::class.java) ?: error("WifiManager is unavailable")
            val observer = LocalOnlyHotspotRadioInfo(wifi, lifecycle::stateChanged)
            observer.start()
            observer.unavailableReason?.let { diagnostic("Mazda local hotspot: shutdown observation unavailable ($it)") }
            // One application-context observer survives replacement of the controller and reservation.
            monitoredContext = app
            stateObserver = observer
            sharedSession = MzdLocalHotspotSession({ validateInterface(it, observer) }) { credentials, report ->
                newManager(app, credentials, lifecycle, report)
            }
        }
        sharedSession!!
    }

    fun deriveCredentials(appName: String, firstInstallTime: Long): Credentials {
        val hash = MessageDigest.getInstance("SHA-256").digest(firstInstallTime.toString().toByteArray(Charsets.UTF_8))
        val password = BigInteger(1, hash).mod(BigInteger("1000000000000")).toString().padStart(12, '0')
        val suffix = "-5G-${password.take(4)}"
        // SSIDs have a 32-byte UTF-8 limit, including the fixed suffix.
        val prefix = appName.fold("") { name, character ->
            if ((name + character + suffix).toByteArray(Charsets.UTF_8).size <= 32) name + character else name
        }.ifBlank { "DiPlay" }
        return Credentials(prefix + suffix, password)
    }

    @Suppress("DEPRECATION")
    fun credentials(context: Context, appName: String): Credentials = synchronized(this) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        val prefs = context.getSharedPreferences("mazda_local_hotspot", Context.MODE_PRIVATE)
        val name = prefs.getString("ssid", null)
        val password = prefs.getString("password", null)
        if (prefs.getLong("first_install_time", -1) == installed && name != null && password != null) {
            return@synchronized Credentials(name, password)
        }
        deriveCredentials(appName, installed).also {
            prefs.edit().putLong("first_install_time", installed).putString("ssid", it.ssid)
                .putString("password", it.password).apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun manager(context: Context, appName: String, diagnostic: (String) -> Unit): WirelessHotspotManager {
        return synchronized(this) {
            session(context, diagnostic).manager(credentials(context, appName), diagnostic)
        }
    }

    fun release() = synchronized(this) { sharedSession?.close() }

    private fun validateInterface(info: WirelessHotspotInfo, observer: LocalOnlyHotspotRadioInfo) {
        val network = info.interfaceName?.let(NetworkInterface::getByName)
        // Android can hide the hardware address even while the reservation and AP are valid.
        val bssid = runCatching { network?.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 0xff) } }
            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
        val radio = observer.forBssid(info.bssid)
        if (network?.isUp != true || info.hostAddress !in network.inetAddresses.toList() ||
            bssid?.let { !it.equals(info.bssid, ignoreCase = true) } == true ||
            radio?.let { it.frequencyMHz != info.frequencyMHz } == true
        ) throw IOException("Mazda local hotspot interface or radio changed")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun newManager(context: Context, credentials: Credentials, lifecycle: MzdLocalHotspotLifecycle,
        diagnostic: (String) -> Unit,
    ): WirelessHotspotManager {
        return MzdLocalHotspotManager(lifecycle, diagnostic) { lease ->
            LocalOnlyHotspotManager(context, diagnostic, customRequest = { wifi, callback, main ->
                val builder = SoftApConfiguration.Builder()
                SoftApConfiguration.Builder::class.java.getMethod("setSsid", String::class.java)
                    .invoke(builder, credentials.ssid)
                SoftApConfiguration.Builder::class.java.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(builder, credentials.password, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                SoftApConfiguration.Builder::class.java.getMethod("setBand", Int::class.javaPrimitiveType)
                    .invoke(builder, SoftApConfiguration.BAND_5GHZ)
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching {
                        // Reuse the upstream 5 GHz element for this app-owned, temporary hotspot.
                        val element = HotspotJoinElement.apple5Ghz
                        val payload = element.payload.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val ie = Class.forName("android.net.wifi.ScanResult\$InformationElement")
                            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
                            .newInstance(element.id, element.idExt, payload)
                        builder.javaClass.getMethod("setVendorElements", List::class.java).invoke(builder, listOf(ie))
                    }.onSuccess { diagnostic("Mazda local hotspot: Apple Device IE configured; broadcast not yet verified") }
                        .onFailure { diagnostic("Mazda local hotspot: Apple Device IE unavailable (${it.javaClass.simpleName}); continuing without it") }
                }
                val method = if (Build.VERSION.SDK_INT >= 36) "startLocalOnlyHotspotWithConfiguration" else "startLocalOnlyHotspot"
                val tracked = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        try { callback.onStarted(reservation) } finally { lifecycle.resolved(lease) }
                    }
                    override fun onFailed(reason: Int) {
                        lifecycle.resolved(lease, reason)
                        callback.onFailed(reason)
                    }
                    override fun onStopped() {
                        lifecycle.stopped(lease)
                        lifecycle.resolved(lease)
                        callback.onStopped()
                    }
                }
                lifecycle.begin(lease)
                try {
                    WifiManager::class.java.getMethod(method, SoftApConfiguration::class.java, Executor::class.java,
                        WifiManager.LocalOnlyHotspotCallback::class.java)
                        .invoke(wifi, builder.build(), Executor { main.post(it) }, tracked)
                } catch (failure: Exception) {
                    lifecycle.resolved(lease, -1)
                    throw failure
                }
            })
        }
    }
}
