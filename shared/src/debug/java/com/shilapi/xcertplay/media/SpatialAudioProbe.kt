package com.shilapi.xcertplay.media

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Debug-only digital loopback. No AudioTrack is created and nothing reaches the speakers. */
object SpatialAudioProbe {
    fun run(directory: File) {
        val report = JSONObject().put("status", "running").put("rate", 48000)
            .put("channels", 12).put("encoding", "s16le").put("speakerPlayback", false)
        val status = File(directory, "status.json")
        fun save() { status.writeText(report.toString(2)) }
        save()
        val logs = ArrayList<String>()
        try {
            val input = File(directory, "input.pcm")
            require(input.length() in 24..(32L * 1024 * 1024) && input.length() % 24 == 0L)
            val output = File(directory, "dcx-output.pcm")
            var outputBytes = 0L
            val inputDigest = MessageDigest.getInstance("SHA-256")
            val outputDigest = MessageDigest.getInstance("SHA-256")
            output.outputStream().buffered().use { stream ->
                PcmDcxPump(PcmDcxCodec { if (logs.size < 64) logs += it },
                    { !Thread.currentThread().isInterrupted }, { bytes ->
                        outputBytes += bytes.size
                        check(outputBytes <= 64L * 1024 * 1024) { "PCM probe output limit" }
                        stream.write(bytes); outputDigest.update(bytes)
                    }, { if (logs.size < 64) logs += it }).use { pump ->
                    DataInputStream(input.inputStream().buffered()).use { source ->
                        var offset = 0L
                        while (offset < input.length()) {
                            val bytes = ByteArray(minOf(6144L, input.length() - offset).toInt())
                            source.readFully(bytes)
                            inputDigest.update(bytes)
                            pump.submit(bytes, offset / 24 * 1_000_000L / 48000)
                            offset += bytes.size
                        }
                    }
                    pump.finish()
                }
            }
            check(outputBytes > 0) { "PCM DCX produced no output" }
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            report.put("status", "ok").put("inputBytes", input.length()).put("outputBytes", outputBytes)
                .put("inputSha256", hex(inputDigest.digest())).put("outputSha256", hex(outputDigest.digest()))
                .put("codec", PcmDcxPump.NAME).put("maxInputBytes", PcmDcxPump.MAX_INPUT_BYTES)
        } catch (error: Exception) {
            report.put("status", "error").put("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            report.put("logs", org.json.JSONArray(logs)); save()
        }
    }
}
