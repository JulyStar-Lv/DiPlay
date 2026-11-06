package com.shilapi.xcertplay.mazda.hudbench

import com.shilapi.display.api.*
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentSkipListSet

internal class HudBenchRunner(private val app: HudBenchApplication, private val status: (String) -> Unit) {
    private val stopped = AtomicBoolean()
    private val driverFailed = AtomicBoolean()
    private val clock = { System.nanoTime() / 1_000_000 }
    fun stop() { stopped.set(true) }
    private fun checkRunning() { check(!stopped.get()) { "任务已停止或真实投屏会话已接管" } }
    private fun await(at: Long) {
        while (clock() < at) { checkRunning(); Thread.sleep(minOf(50L, (at - clock()).coerceAtLeast(1))) }
        checkRunning()
    }
    private fun flush() { app.driver.flush().get(5, TimeUnit.SECONDS) }

    fun run(runId: String, mode: String, group: String, capture: Boolean) {
        require(UUID.fromString(runId).toString() == runId)
        require(mode in setOf("snapshot", "replay", "both"))
        val cases = HudBenchFixtures.select(group)
        val modes = if (mode == "both") listOf("snapshot", "replay") else listOf(mode)
        var acquired = false
        var evidence: HudBenchEvidence? = null
        var finalStatus = "completed"
        var reason: String? = null
        try {
            val base = requireNotNull(app.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
            val report = HudBenchEvidence(File(base, "hud-bench/$runId"), runId, mode, group).also { evidence = it }
            report.state.put("plannedCases", JSONArray(modes.flatMap { layer -> cases.map { "$layer-${it.id}" } }))
            report.save()
            check(app.hudEnabled) { "此APK未启用HUD，请使用 -PmzdHudEnabled=true 构建" }
            val factory = app.ownership.acquire(::stop).also { acquired = true }
            val latest = AtomicReference<DisplaySnapshot?>()
            val sourceFactory = observedFactory(factory) { snapshot ->
                latest.set(snapshot)
                report.event("snapshot", json("session" to snapshot.session.sourceInstanceId, "epoch" to snapshot.session.epoch,
                    "revision" to snapshot.revision, "receivedAtMillis" to snapshot.navigation.receivedAtMillis,
                    "connectedAtMillis" to JSONArray(snapshot.calls.map { it.connectedAtMillis ?: org.json.JSONObject.NULL }), "state" to semantic(snapshot)))
            }
            val outputTransactions = ConcurrentSkipListSet<Int>()
            app.evidence = { message ->
                report.event("driver", message)
                Regex("transaction=(22|24|27|37|42|43) accepted").find(message)?.groupValues?.get(1)?.toInt()?.let(outputTransactions::add)
                if (message.contains("cleanup failed") || message.contains(" disabled:") || message.contains("endpoint unavailable")) driverFailed.set(true)
            }
            val captures = HudBenchCapture(report, capture, stopped::get)
            check(captures.capture("baseline", "before", null)) { "基线截图失败，未开始发送；请检查电脑助手和Linux截图通道" }
            modes.forEach { layer -> cases.forEach { case ->
                checkRunning()
                latest.set(null)
                outputTransactions.clear()
                val caseId = "$layer-${case.id}"
                status("$caseId ${case.description}")
                val row = json("id" to caseId, "description" to case.description, "group" to case.group,
                    "status" to "running", "displayVerified" to false, "steps" to JSONArray())
                report.state.getJSONArray("cases").put(row); report.save()
                val origin = clock()
                var input: HudBenchInput? = null
                var caseError: Exception? = null
                try {
                    input = if (layer == "snapshot") SnapshotBenchInput(sourceFactory, clock, "bench-$runId-$caseId") else
                        ReplayBenchInput(sourceFactory, clock, { frame -> report.event("iap2", json(
                            "caseId" to caseId, "messageId" to frame.messageId,
                            "encodedFrameHex" to frame.encodedFrame().joinToString("") { "%02x".format(it.toInt() and 255) })) },
                            { error -> driverFailed.set(true); report.event("sourceFailure", error) })
                    val actions = (case.steps.map { Triple(it.atMs, 0, it) } + case.samples.map { Triple(it, 1, null) })
                        .sortedWith(compareBy({ it.first }, { it.second }))
                    actions.forEach { (at, kind, step) ->
                        await(origin + at)
                        if (kind == 0) {
                            val update = requireNotNull(step)
                            if (update.reconnect) input!!.reconnect()
                            report.event("input", json("caseId" to caseId, "label" to update.label,
                                "plannedAtMs" to at, "actualAtMs" to clock() - origin, "expected" to semantic(update.expected)))
                            input!!.apply(update, origin)
                            val actual = latest.get() ?: error("来源未发布快照")
                            val matches = sameJson(semantic(update.expected), semantic(actual))
                            row.getJSONArray("steps").put(json("label" to update.label, "contractMatches" to matches,
                                "expected" to semantic(update.expected), "actual" to semantic(actual)))
                            check(matches) { "$caseId 的来源快照不符合预期：${update.label}" }
                            flush()
                            check(!driverFailed.get()) { "车型驱动报告错误，停止后续项目，详见driver事件" }
                            report.save()
                        } else {
                            flush()
                            check(captures.capture(caseId, "during-$at", latest.get(), at, clock() - origin)) { "截图采集失败" }
                        }
                    }
                    await(origin + case.durationMs)
                } catch (error: Exception) { caseError = error }
                finally {
                    try {
                        input?.close(); flush()
                        // A worker barrier confirms only returned IPC; retain a separate post-clear image.
                        Thread.sleep(500)
                        if (!stopped.get()) check(captures.capture(caseId, "after-clear", null)) { "清除后截图失败" }
                        check(!driverFailed.get()) { "驱动调用或清除报告错误" }
                    } catch (error: Exception) { if (caseError == null) caseError = error else caseError!!.addSuppressed(error) }
                    row.put("status", if (caseError == null) "executed" else "failed")
                        .put("outputTransactionsAccepted", JSONArray(outputTransactions.toList()))
                        .put("ipcStatus", if (outputTransactions.isEmpty()) "no_output_observed" else "output_calls_returned")
                        .put("error", caseError?.toString() ?: org.json.JSONObject.NULL)
                        .put("endedAtMillis", System.currentTimeMillis())
                    report.save()
                }
                caseError?.let { throw it }
            } }
            val rows = report.state.getJSONArray("cases")
            finalStatus = when {
                (0 until rows.length()).any { rows.getJSONObject(it).getString("ipcStatus") == "no_output_observed" } -> "completed_with_inconclusive_transport"
                !capture -> "completed_without_captures"
                else -> "completed"
            }
        } catch (error: Exception) {
            finalStatus = if (stopped.get()) "stopped" else "failed"
            reason = error.toString()
        } finally {
            if (acquired) { app.evidence = null; app.ownership.release() }
            evidence?.let { report ->
                try {
                    report.finish(finalStatus, reason)
                    status("$finalStatus\n${report.root}/report.zip\n显示结果待报告图片检查")
                } catch (error: Exception) { status("归档失败，原始文件保留：${report.root}\n$error") }
            }
        }
        if (evidence == null) status("任务未启动：$reason")
    }
}
