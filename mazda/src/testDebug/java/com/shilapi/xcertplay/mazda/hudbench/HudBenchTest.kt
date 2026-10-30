package com.shilapi.xcertplay.mazda.hudbench

import com.shilapi.display.api.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HudBenchTest {
    private class Recording : VehicleDisplayFactory {
        var latest: DisplaySnapshot? = null
        val closed = mutableListOf<SourceSessionKey>()
        override fun open(session: SourceSessionKey) = object : VehicleDisplayEndpoint {
            override val capabilities = DisplayCapabilities(navigation = true, mediaText = true, calls = true)
            private var ended = false
            override fun submit(snapshot: DisplaySnapshot) { if (!ended) latest = snapshot }
            override fun close() { ended = true; closed.add(session) }
        }
    }

    @Test fun bothLayersReachTheExpectedContractForEveryFixtureAndKeepCallTiming() {
        assertEquals(30, HudBenchFixtures.all().size)
        assertEquals(30, HudBenchFixtures.all().map { it.id }.distinct().size)
        for (case in HudBenchFixtures.all()) for (layer in listOf("snapshot", "replay")) {
            val output = Recording()
            val origin = 100_000L
            var now = origin
            val input: HudBenchInput = if (layer == "snapshot") SnapshotBenchInput(output, { now }, "test") else
                ReplayBenchInput(output, { now }, {}, { fail(it) })
            case.steps.forEach { step ->
                now = origin + step.atMs
                if (step.reconnect) input.reconnect()
                input.apply(step, origin)
                val actual = requireNotNull(output.latest)
                assertTrue("$layer ${case.id} ${step.label}\nexpected=${semantic(step.expected)}\nactual=${semantic(actual)}",
                    sameJson(semantic(step.expected), semantic(actual)))
                assertEquals(step.expected.calls.map { it.connectedAtMillis?.plus(origin) }, actual.calls.map { it.connectedAtMillis })
            }
            input.close()
            assertTrue(output.closed.isNotEmpty())
        }
    }

    @Test fun realSourceExcludesBenchAndAnIncomingRealSourceCancelsBench() {
        val output = Recording()
        val owner = HudBenchOwnership(output)
        val phone = owner.realSourceFactory.open(SourceSessionKey("real", 1))
        assertThrows(IllegalStateException::class.java) { owner.acquire {} }
        phone.close(); phone.close() // repeated close must not decrement the real source count twice
        var stopped = false
        val benchFactory = owner.acquire { stopped = true }
        val replacement = owner.realSourceFactory.open(SourceSessionKey("real", 2))
        assertTrue(stopped)
        assertThrows(IllegalStateException::class.java) { benchFactory.open(SourceSessionKey("late-bench", 1)) }
        owner.release()
        assertThrows(IllegalStateException::class.java) { owner.acquire {} }
        replacement.close()
        owner.acquire {}; owner.release()
    }

    @Test fun captureRejectsResponseFromAnotherRunAndKeepsFailureEvidence() {
        val parent = Files.createTempDirectory("hud-bench-test").toFile()
        val id = UUID.randomUUID().toString()
        val evidence = HudBenchEvidence(File(parent, id), id, "snapshot", "phone")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Boolean> { HudBenchCapture(evidence, true, { false }, 2500).capture("P01", "during", null) }
            val requestFile = File(evidence.root, "capture-request.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var ready: JSONObject? = null
            while (ready == null && System.nanoTime() < deadline) {
                ready = runCatching { JSONObject(requestFile.readText()) }.getOrNull()
                if (ready == null) Thread.sleep(10)
            }
            val request = requireNotNull(ready)
            atomicJson(File(evidence.root, "inbox/${request.getString("captureId")}.json"), json(
                "runId" to "wrong-run", "captureId" to request.getString("captureId"), "status" to "captured"))
            assertFalse(pending.get(3, TimeUnit.SECONDS))
            val capture = evidence.state.getJSONArray("captures").getJSONObject(0)
            assertEquals("failed", capture.getString("status"))
            assertFalse(capture.has("image"))
            assertFalse(requestFile.exists())
            evidence.finish("failed", "identity mismatch")
            assertEquals("failed", JSONObject(File(evidence.root, "report.json").readText()).getString("status"))
            ZipFile(File(evidence.root, "report.zip")).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                assertNotNull(zip.getEntry("report.json"))
                assertTrue(zip.entries().asSequence().none { it.name.endsWith(".png") })
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun disabledOrTimedOutCaptureNeverInventsAnImageOrVisualSuccess() {
        val parent = Files.createTempDirectory("hud-bench-test").toFile()
        val id = UUID.randomUUID().toString()
        val evidence = HudBenchEvidence(File(parent, id), id, "replay", "all")
        assertTrue(HudBenchCapture(evidence, false, { false }).capture("N01", "during", null))
        assertFalse(HudBenchCapture(evidence, true, { false }, 10).capture("N02", "during", null))
        assertEquals("disabled", evidence.state.getJSONArray("captures").getJSONObject(0).getString("status"))
        assertEquals("failed", evidence.state.getJSONArray("captures").getJSONObject(1).getString("status"))
        assertFalse(evidence.state.getBoolean("displayVerified"))
        assertThrows(IllegalStateException::class.java) { inspectPng(File(parent, "bad.png").apply { writeBytes(ByteArray(32)) }) }
    }

    @Test fun completePngHandshakeIsValidatedAndArchivedWithItsDigest() {
        val parent = Files.createTempDirectory("hud-bench-test").toFile()
        val id = UUID.randomUUID().toString()
        val evidence = HudBenchEvidence(File(parent, id), id, "snapshot", "phone")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Boolean> { HudBenchCapture(evidence, true, { false }, 5000).capture("P01", "during", null) }
            val requestFile = File(evidence.root, "capture-request.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var request: JSONObject? = null
            while (request == null && System.nanoTime() < deadline) {
                request = runCatching { JSONObject(requestFile.readText()) }.getOrNull()
                if (request == null) Thread.sleep(10)
            }
            val captureId = requireNotNull(request).getString("captureId")
            File(evidence.root, "inbox/$captureId.png").writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNg+M8AAAICAQB7CYF4AAAAAElFTkSuQmCC"))
            atomicJson(File(evidence.root, "inbox/$captureId.json"), json("runId" to id, "captureId" to captureId, "status" to "captured"))
            assertTrue(pending.get(5, TimeUnit.SECONDS))
            val capture = evidence.state.getJSONArray("captures").getJSONObject(0)
            assertEquals(1, capture.getJSONObject("imageInfo").getInt("width"))
            evidence.finish("completed", null)
            assertEquals("completed", JSONObject(File(evidence.root, "report.json").readText()).getString("status"))
            ZipFile(File(evidence.root, "report.zip")).use { zip ->
                assertNotNull(zip.getEntry(capture.getString("image")))
                assertFalse(JSONObject(zip.getInputStream(zip.getEntry("report.json")).reader().readText()).getBoolean("displayVerified"))
            }
            assertEquals(sha256(File(evidence.root, "report.zip")), JSONObject(File(evidence.root, "finished.json").readText()).getString("sha256"))
        } finally { executor.shutdownNow() }
    }
}
