package com.shilapi.xcertplay.mazda.hudbench

import android.content.pm.PackageManager
import android.util.Log
import com.shilapi.display.api.*
import com.shilapi.vehicle.mzd.MzdVehicleDisplayFactory
import com.shilapi.xcertplay.VehicleDisplayProvider
import com.shilapi.xcertplay.mazda.MzdApplication
import com.shilapi.xcertplay.mazda.mzdHudMediaSource
import java.util.concurrent.atomic.AtomicBoolean

/** Debug composition only. Production and bench share one physical worker and ownership rules. */
class HudBenchApplication : MzdApplication(), VehicleDisplayProvider {
    @Volatile internal var evidence: ((String) -> Unit)? = null
    @Suppress("DEPRECATION")
    private val hudMetadata by lazy {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
    }
    internal val hudEnabled: Boolean by lazy {
        hudMetadata?.getBoolean("com.shilapi.vehicle.mzd.HUD_ENABLED", false) == true
    }
    internal val driver by lazy { MzdVehicleDisplayFactory(this, mzdHudMediaSource(hudMetadata),
        automaticA2dpHandover = false, auxiliaryMap = true) {
        Log.i("DiPlay-MzdHUD", it)
        runCatching { evidence?.invoke(it) }.onFailure { error -> Log.w("DiPlay-MzdHUD", "Evidence write failed: ${error.javaClass.simpleName}") }
    } }
    internal val ownership by lazy { HudBenchOwnership(driver) }
    override val vehicleDisplayFactory: VehicleDisplayFactory by lazy {
        if (hudEnabled) ownership.realSourceFactory else VehicleDisplayFactory.NONE
    }
}

internal class HudBenchOwnership(private val driver: VehicleDisplayFactory) {
    private var realSources = 0
    private var stopBench: (() -> Unit)? = null
    val realSourceFactory = VehicleDisplayFactory { key ->
        synchronized(this) {
            // A real session takes precedence. Its open atomically retires the bench endpoint.
            stopBench?.invoke()
            realSources++
            val endpoint = try { driver.open(key) } catch (error: Exception) { realSources--; throw error }
            object : VehicleDisplayEndpoint by endpoint {
                private val closed = AtomicBoolean()
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        try { endpoint.close() } finally { synchronized(this@HudBenchOwnership) { realSources-- } }
                    }
                }
            }
        }
    }
    @Synchronized fun acquire(stop: () -> Unit): VehicleDisplayFactory {
        check(realSources == 0 && stopBench == null) { "已有真实投屏会话或台架任务，请先结束它" }
        stopBench = stop
        return VehicleDisplayFactory { session -> synchronized(this) {
            check(stopBench === stop && realSources == 0) { "真实投屏会话已接管，台架任务停止" }
            driver.open(session)
        } }
    }
    @Synchronized fun release() { stopBench = null }
}
