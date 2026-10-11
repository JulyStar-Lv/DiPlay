package com.shilapi.xcertplay.mazda

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.mazda.MzdCarHotspot
import java.util.concurrent.atomic.AtomicInteger

/** One preparation at a time; cancellation prevents a retired host from starting CarPlay. */
internal class MzdHotspotConnection(
    context: Context,
    private val prepareHotspot: ((String) -> Unit, () -> Boolean) -> WirelessHotspotInfo =
        { diagnostic, cancelled -> MzdCarHotspot.prepare(context.applicationContext, diagnostic, cancelled) },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var pending = false

    fun start(onDiagnostic: (String) -> Unit, onReady: (WirelessHotspotInfo) -> Unit,
        onFailure: (Throwable) -> Unit) {
        if (pending) return
        pending = true
        val token = generation.get()
        Thread({
            val result = runCatching { prepareHotspot(onDiagnostic) { token != generation.get() } }
            handler.post {
                if (token != generation.get()) return@post
                pending = false
                result.fold(onReady, onFailure)
            }
        }, "MazdaHotspotPrepare").start()
    }

    fun cancel() {
        generation.incrementAndGet()
        pending = false
    }
}
