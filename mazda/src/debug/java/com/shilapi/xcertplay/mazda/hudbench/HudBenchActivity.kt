package com.shilapi.xcertplay.mazda.hudbench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit finite debug entry. There is no receiver, boot trigger or production setting. */
class HudBenchActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()
    @Volatile private var runner: HudBenchRunner? = null
    private lateinit var output: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val gap = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(gap, gap, gap, gap) }
        output = TextView(this).apply { textSize = 17f; text = "HUD台架验证（仅debug）\n无需iPhone。截图模式需运行电脑端 hud_bench.py。\n本页按钮运行无截图模拟，报告不能证明显示通过。" }
        body.addView(output)
        listOf("两层验证" to "both", "通用快照模拟" to "snapshot", "CarPlay事件回放" to "replay").forEach { (title, mode) ->
            body.addView(Button(this).apply { text = title; setOnClickListener { start(UUID.randomUUID().toString(), mode, "all", false) } })
        }
        body.addView(Button(this).apply { text = "停止并清理"; setOnClickListener { runner?.stop() } })
        setContentView(ScrollView(this).apply { addView(body) })
        if (savedInstanceState == null) command(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); command(intent) }
    private fun command(intent: Intent) {
        if (intent.getBooleanExtra("stop", false)) { runner?.stop(); return }
        if (intent.getBooleanExtra("autorun", false)) start(intent.getStringExtra("run_id") ?: UUID.randomUUID().toString(),
            intent.getStringExtra("mode") ?: "both", intent.getStringExtra("suite") ?: "all", intent.getBooleanExtra("capture", true))
    }
    private fun start(runId: String, mode: String, group: String, capture: Boolean) {
        if (!running.compareAndSet(false, true)) { output.text = "已有任务运行，请先停止并等待归档"; return }
        val next = HudBenchRunner(application as HudBenchApplication) { message -> runOnUiThread { if (!isDestroyed) output.text = message } }
        runner = next
        worker.execute {
            try { next.run(runId, mode, group, capture) }
            catch (error: Exception) { runOnUiThread { if (!isDestroyed) output.text = "无法开始：$error" } }
            finally { runner = null; running.set(false) }
        }
    }
    override fun onDestroy() {
        runner?.stop()
        worker.shutdown() // let the running task close its endpoint and finalize evidence
        super.onDestroy()
    }
}
