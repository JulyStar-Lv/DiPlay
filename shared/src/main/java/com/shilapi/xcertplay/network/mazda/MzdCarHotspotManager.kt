package com.shilapi.xcertplay.network.mazda

import com.shilapi.xcertplay.network.*

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Available only when the installed app actually holds both platform hotspot permissions. */
object MzdCarHotspot {
    /** Prepare the car-owned AP before constructing the original CarPlay runtime configuration. */
    fun prepare(context: Context, onDiagnostic: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false }): WirelessHotspotInfo =
        MzdCarHotspotManager(context, onDiagnostic, isCancelled).use {
            it.start(WirelessStartupPolicy.HOTSPOT_READY_MILLIS)
        }

    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT >= 30 &&
        context.checkSelfPermission("android.permission.NETWORK_SETTINGS") == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission("android.permission.TETHER_PRIVILEGED") == PackageManager.PERMISSION_GRANTED

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    internal fun credentials(config: SoftApConfiguration): MzdHotspotCredentials {
        val security = when (config.securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN -> ManualHotspotSecurity.OPEN
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> ManualHotspotSecurity.WPA2
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> ManualHotspotSecurity.WPA3_TRANSITION
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> ManualHotspotSecurity.WPA3
            else -> throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Car hotspot security is unsupported by CarPlay")
        }
        return MzdHotspotCredentials(config.ssid.orEmpty(), config.passphrase.orEmpty(), security)
    }

    @SuppressLint("PrivateApi", "NewApi")
    internal fun fiveGhzCopy(config: SoftApConfiguration): SoftApConfiguration {
        val type = SoftApConfiguration.Builder::class.java
        val builder = type.getConstructor(SoftApConfiguration::class.java).newInstance(config)
        type.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, 2)
        return type.getMethod("build").invoke(builder) as SoftApConfiguration
    }
}

/** Shares the car's hotspot with CarPlay; close releases observers, not the system-owned AP. */
@SuppressLint("PrivateApi", "NewApi")
internal class MzdCarHotspotManager(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) : WirelessHotspotManager {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java) ?: error("Wi-Fi service unavailable")
    private val tethering = app.getSystemService("tethering") ?: error("Tethering service unavailable")
    private val lock = Any()
    private val executor = Executor { it.run() }
    private var callback: Any? = null
    @Volatile private var closed = false
    @Volatile private var radio = MzdHotspotRadio()
    @Volatile private var startFailed = false
    @Volatile private var attachment: ManualHotspotManager? = null
    @Volatile private var preparing = false
    private var confirmedCredentials: MzdHotspotCredentials? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Mazda hotspot startup must run off the main thread" }
        check(MzdCarHotspot.permitted(app)) { "Mazda hotspot permissions are missing" }
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var acquired = false
        try {
            while (!acquired) {
                checkActive(deadline)
                acquired = startupLock.tryLock(minOf(100L, remaining(deadline)), TimeUnit.MILLISECONDS)
            }
            checkActive(deadline)
            preparing = true
            registerRadioObserver()
            val credentials = MzdHotspotStartup(port, { closed || isCancelled() }).prepare(deadline)
            checkActive(deadline)
            confirmedCredentials = credentials
            val delegate = ManualHotspotManager(app, credentials.ssid, credentials.passphrase,
                ManualHotspotBand.GHZ_5, 0, credentials.security, onDiagnostic,
                isCancelled = { closed || isCancelled() })
            synchronized(lock) {
                if (closed) { delegate.close(); throw InterruptedIOException("Car hotspot startup cancelled") }
                attachment = delegate
            }
            val info = delegate.start(remaining(deadline))
            validateReady()
            onDiagnostic("Mazda car hotspot ready: actual 5 GHz, channel=${info.channel}")
            return info
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw InterruptedIOException("Car hotspot startup interrupted")
        } catch (failure: Exception) {
            close()
            throw failure
        } finally {
            synchronized(lock) {
                preparing = false
                if (closed) unregisterRadioObserver()
            }
            if (acquired) startupLock.unlock()
        }
    }

    override fun validateReady() {
        if (closed || isCancelled() || !radio.fiveGhzReady() || CarHotspotStatus.isEnabled(app) == false) throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_NOT_READY, "The car's 5 GHz hotspot is no longer ready")
        check(MzdCarHotspot.credentials(port.read()) == confirmedCredentials) {
            "Car hotspot credentials changed before publication"
        }
        attachment?.validateReady() ?: error("Car hotspot interface unavailable")
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            attachment?.close()
            attachment = null
            // Keep observing while prepare unwinds so cancellation can restore the saved AP.
            if (!preparing) unregisterRadioObserver()
        }
    }

    private fun unregisterRadioObserver() {
        val observer = callback ?: return
        callback = null
        runCatching { WifiManager::class.java.getMethod("unregisterSoftApCallback", observerType).invoke(wifi, observer) }
    }

    private fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
    private fun checkActive(deadline: Long) {
        if (closed || isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Car hotspot startup cancelled")
        if (System.nanoTime() >= deadline) throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_NOT_READY, "Timed out waiting for the 5 GHz car hotspot")
    }

    private val port = object : MzdHotspotStartup.Port<SoftApConfiguration> {
        override fun read(): SoftApConfiguration = WifiManager::class.java.getMethod("getSoftApConfiguration")
            .invoke(wifi) as? SoftApConfiguration ?: error("Car hotspot configuration unavailable")
        override fun credentials(config: SoftApConfiguration) = MzdCarHotspot.credentials(config)
        override fun fiveGhz(config: SoftApConfiguration) = MzdCarHotspot.fiveGhzCopy(config)
        override fun write(config: SoftApConfiguration): Boolean {
            val validator = runCatching { WifiManager::class.java.getMethod("validateSoftApConfiguration", SoftApConfiguration::class.java) }.getOrNull()
            if (validator != null && validator.invoke(wifi, config) != true) return false
            return WifiManager::class.java.getMethod("setSoftApConfiguration", SoftApConfiguration::class.java)
                .invoke(wifi, config) == true
        }
        override fun radio(): MzdHotspotRadio {
            if (startFailed) throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Car could not start its hotspot")
            return radio
        }
        override fun start() {
            startFailed = false
            synchronized(lock) { radio = radio.copy(enabled = null, frequencies = emptyList()) }
            val type = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "onTetheringFailed" -> { startFailed = true; null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DiPlayTetheringCallback"
                    else -> null
                }
            }
            Class.forName("android.net.TetheringManager").getMethod("startTethering",
                Int::class.javaPrimitiveType, Executor::class.java, type).invoke(tethering, 0, executor, observer)
        }
        override fun stop() {
            startFailed = false
            // Stopping an already-off AP may emit no callback; keep recovery from waiting forever.
            if (CarHotspotStatus.isEnabled(app) == false) {
                synchronized(lock) { radio = radio.copy(enabled = false, frequencies = emptyList()) }
                return
            }
            synchronized(lock) { radio = radio.copy(enabled = null, frequencies = emptyList()) }
            Class.forName("android.net.TetheringManager").getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(tethering, 0)
        }
    }

    private val observerType get() = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
    private fun registerRadioObserver() = synchronized(lock) {
        check(!closed) { "Car hotspot manager closed" }
        val type = observerType
        val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DiPlayCarHotspotCallback"
                else -> {
                    synchronized(lock) {
                        if (callback === proxy) when (method.name) {
                            "onStateChanged" -> {
                                val state = args?.firstOrNull().let { value ->
                                    if (value is Int) value else runCatching {
                                        value?.javaClass?.getMethod("getState")?.invoke(value) as? Int
                                    }.getOrNull()
                                }
                                radio = radio.copy(enabled = when (state) { 13 -> true; 11 -> false; else -> null },
                                    frequencies = if (state == 13) radio.frequencies else emptyList())
                            }
                            "onInfoChanged" -> {
                                val infos = args?.firstOrNull().let { if (it is List<*>) it else listOfNotNull(it) }
                                radio = radio.copy(frequencies = infos.mapNotNull { info ->
                                    runCatching { info?.javaClass?.getMethod("getFrequency")?.invoke(info) as? Int }
                                        .getOrNull()?.takeIf { it > 0 }
                                })
                            }
                        }
                    }
                    null
                }
            }
        }
        callback = observer // close also unregisters after a partially successful registration.
        WifiManager::class.java.getMethod("registerSoftApCallback", Executor::class.java, type).invoke(wifi, executor, observer)
    }

    companion object { private val startupLock = ReentrantLock() }
}
