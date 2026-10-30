package com.shilapi.xcertplay.mazda.hudbench

import android.graphics.BitmapFactory
import android.os.Process
import android.os.Build
import com.shilapi.display.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
internal fun atomicJson(file: File, value: JSONObject) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val temporary = File(file.parentFile, file.name + ".partial")
    FileOutputStream(temporary).use { out -> out.write(value.toString(2).toByteArray(Charsets.UTF_8)); out.fd.sync() }
    // Android AtomicFile's renameTo silently leaves an old destination on Windows/Robolectric.
    // Explicit replace reports failure instead of publishing stale status or capture requests.
    if (Build.VERSION.SDK_INT >= 26) {
        try { java.nio.file.Files.move(temporary.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } else android.system.Os.rename(temporary.path, file.path)
}
internal fun semantic(value: HudBenchValue) = json(
    "navigation" to json("phase" to value.navigation.phase.name, "maneuver" to value.navigation.maneuver.name,
        "drivingSide" to value.navigation.drivingSide.name, "roundaboutExit" to value.navigation.roundaboutExit,
        "distanceMeters" to value.navigation.distanceMeters, "remainingMeters" to value.navigation.remainingMeters,
        "remainingMillis" to value.navigation.remainingMillis, "currentRoad" to value.navigation.currentRoad,
        "nextRoad" to value.navigation.nextRoad,
        "lanes" to value.navigation.laneGuidance?.let { guidance -> JSONArray(guidance.lanes.map { lane ->
            json("index" to lane.index, "anglesDegrees" to JSONArray(lane.anglesDegrees),
                "recommended" to lane.recommended, "highlightedAngleDegrees" to lane.highlightedAngleDegrees)
        }) }),
    "media" to json("title" to value.media.title, "artist" to value.media.artist, "album" to value.media.album,
        "playback" to value.media.playback.name, "lyricsSupport" to value.media.lyricsSupport.name,
        "currentLyric" to value.media.currentLyric),
    "calls" to JSONArray(value.calls.map { json("id" to it.id, "phase" to it.phase.name, "name" to it.name, "number" to it.number) }))
internal fun semantic(value: DisplaySnapshot) = semantic(HudBenchValue(value.navigation, value.media, value.calls))
internal fun sameJson(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && sameJson(a.get(it), b.get(it)) }
    a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}

internal class HudBenchEvidence(val root: File, val runId: String, mode: String, group: String) {
    val state = json("schemaVersion" to 1, "runId" to runId, "mode" to mode, "group" to group,
        "androidUid" to Process.myUid(), "inputKind" to "synthetic", "status" to "running",
        "displayVerified" to false, "visualReview" to "pending_report_image_review",
        "unsupported" to JSONArray(listOf("artwork", "realLyrics", "auxiliaryMap", "callControl", "externalFocusInjection", "processKillRecovery")),
        "cases" to JSONArray(), "captures" to JSONArray())
    init { check(root.mkdirs()) { "报告目录已存在或无法创建" }; save() }
    @Synchronized fun event(kind: String, payload: Any?) {
        File(root, "events.ndjson").appendText(json("wallTimeMillis" to System.currentTimeMillis(),
            "monotonicMillis" to System.nanoTime() / 1_000_000, "kind" to kind, "payload" to payload).toString() + "\n")
    }
    @Synchronized fun save() = atomicJson(File(root, "report.json"), state)
    @Synchronized fun finish(status: String, reason: String?) {
        state.put("status", status).put("reason", reason ?: JSONObject.NULL)
        save()
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("json", "ndjson", "png") }.sortedBy { it.relativeTo(root).path }.toList()
        val manifest = JSONArray(files.map { json("path" to it.relativeTo(root).invariantSeparatorsPath, "bytes" to it.length(), "sha256" to sha256(it)) })
        val partial = File(root, "report.zip.partial")
        ZipOutputStream(partial.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
        }
        check(partial.renameTo(File(root, "report.zip"))) { "ZIP归档失败，原始证据已保留" }
        atomicJson(File(root, "finished.json"), json("runId" to runId, "status" to status,
            "archive" to "report.zip", "sha256" to sha256(File(root, "report.zip"))))
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
        val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size)
    } }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Capture handshake only. The PC helper executes a fixed Linux command; all HUD writes remain in this App. */
internal class HudBenchCapture(
    private val evidence: HudBenchEvidence,
    private val enabled: Boolean,
    private val stopped: () -> Boolean,
    private val timeoutMs: Long = 20_000,
) {
    fun capture(caseId: String, phase: String, snapshot: DisplaySnapshot?, plannedAtMs: Long? = null, actualAtMs: Long? = null): Boolean {
        val root = evidence.root
        val id = UUID.randomUUID().toString()
        val meta = json("captureId" to id, "runId" to evidence.runId, "caseId" to caseId, "phase" to phase,
            "requestedAtMillis" to System.currentTimeMillis(), "displayVerified" to false,
            "snapshot" to snapshot?.let(::semantic), "transport" to "adb-shell-helper",
            "plannedAtMs" to plannedAtMs, "actualAtMs" to actualAtMs,
            "lateByMs" to if (plannedAtMs != null && actualAtMs != null) (actualAtMs - plannedAtMs).coerceAtLeast(0) else null,
            "source" to "Linux LayerManagerControl screen 0")
        val request = File(root, "capture-request.json")
        val inbox = File(root, "inbox").apply { mkdirs() }
        val response = File(inbox, "$id.json")
        val incoming = File(inbox, "$id.png")
        try {
            if (!enabled) { meta.put("status", "disabled"); return true }
            check(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 256L * 1024 * 1024) { "证据超过256MiB，请导出后开始新批次" }
            atomicJson(request, json("version" to 1, "runId" to evidence.runId, "captureId" to id,
                "caseId" to caseId, "phase" to phase, "screenId" to 0))
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var result: JSONObject? = null
            while (System.nanoTime() < deadline && !stopped()) {
                if (response.isFile && response.length() <= 65536) {
                    result = runCatching { JSONObject(response.readText()) }.getOrNull()
                    if (result != null) break
                }
                Thread.sleep(50)
            }
            check(result != null) { "未收到截图助手响应或已停止" }
            check(result.getString("captureId") == id && result.getString("runId") == evidence.runId) { "截图响应身份不匹配" }
            meta.put("helper", result)
            check(result.getString("status") == "captured") { "助手采集失败，见helper.error" }
            val info = inspectPng(incoming)
            val destination = File(root, "screenshots/$id.png").apply { parentFile!!.mkdirs() }
            check(incoming.renameTo(destination))
            meta.put("image", "screenshots/$id.png").put("imageInfo", info).put("status", "captured")
            return true
        } catch (error: Exception) {
            meta.put("status", "failed").put("error", error.toString())
            return false
        } finally {
            request.delete(); response.delete(); incoming.delete()
            meta.put("completedAtMillis", System.currentTimeMillis())
            synchronized(evidence) { evidence.state.getJSONArray("captures").put(meta); evidence.save() }
            evidence.event("capture", meta)
        }
    }
}

internal fun inspectPng(file: File): JSONObject {
    require(file.length() in 24..(8L * 1024 * 1024)) { "PNG大小非法" }
    DataInputStream(file.inputStream().buffered()).use { input ->
        check(input.readLong() == -8552249625308161526L) { "PNG签名错误" }
        var first = true
        var imageData = false
        while (true) {
            val length = input.readInt()
            require(length in 0..(8 * 1024 * 1024))
            val type = ByteArray(4).also(input::readFully)
            val name = String(type, Charsets.US_ASCII)
            check(!first || (name == "IHDR" && length == 13)); first = false
            val crc = CRC32().apply { update(type) }
            var remaining = length
            val buffer = ByteArray(8192)
            while (remaining > 0) { val count = input.read(buffer, 0, minOf(buffer.size, remaining)); check(count > 0); crc.update(buffer, 0, count); remaining -= count }
            check(crc.value == (input.readInt().toLong() and 0xffffffffL)) { "PNG CRC错误" }
            if (name == "IDAT") imageData = true
            if (name == "IEND") { check(length == 0 && imageData && input.read() == -1); break }
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    check(options.outWidth in 1..8192 && options.outHeight in 1..8192 && options.outWidth.toLong() * options.outHeight <= 16_777_216)
    val bitmap = BitmapFactory.decodeFile(file.path) ?: error("PNG解码失败")
    bitmap.recycle()
    return json("bytes" to file.length(), "width" to options.outWidth, "height" to options.outHeight, "sha256" to sha256(file))
}
