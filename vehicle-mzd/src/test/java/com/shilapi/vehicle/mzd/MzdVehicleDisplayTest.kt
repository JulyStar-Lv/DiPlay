package com.shilapi.vehicle.mzd

import com.shilapi.display.api.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdVehicleDisplayTest {
    private open class Transport : MzdTransport {
        override var alive = true
        val focus = mutableSetOf<MzdDomain>()
        val requested = mutableSetOf<MzdDomain>()
        val events = mutableListOf<String>()
        val mediaMessages = mutableListOf<JSONObject>()
        val navigationValues = mutableListOf<MzdNavigation>()
        var reject: MzdDomain? = null
        override fun request(domain: MzdDomain) {
            if (domain == reject) throw SecurityException()
            if (requested.add(domain)) { focus.add(domain); events.add("request:$domain") }
        }
        override fun focused(domain: MzdDomain) = alive && domain in focus
        override fun release(domain: MzdDomain) { if (requested.remove(domain)) events.add("release:$domain"); focus.remove(domain) }
        override fun navigationMode(active: Boolean) { events.add("mode:$active") }
        override fun navigation(value: MzdNavigation) { events.add("turn:${value.icon}:${value.distance}"); navigationValues.add(value) }
        override fun media(json: String) {
            val value = JSONObject(json)
            mediaMessages.add(value)
            events.add("media:${value.getInt("sourceType")}")
        }
        override fun phone(json: String) { events.add("phone:${JSONObject(json).getInt("lineStatus")}") }
        override fun callTime(seconds: Int) { events.add("time:$seconds") }
        override fun close() { events.add("closed"); alive = false }
    }
    private var now = 1_000L
    private val transports = mutableListOf<Transport>()
    private val factory = MzdVehicleDisplayFactory({}, { now }, { Transport().also { transports.add(it) } }, false)
    private val key = SourceSessionKey("test", 1)
    private fun snapshot(revision: Long = 1, session: SourceSessionKey = key) = DisplaySnapshot(session, revision,
        NavigationState(NavigationPhase.GUIDING, ManeuverKind.LEFT, distanceMeters = 150,
            remainingMeters = 5000, remainingMillis = 120_000, receivedAtMillis = 1_000),
        MediaState(title = "Title", artist = "Artist", playback = PlaybackState.PLAYING),
        listOf(CallState("a", CallPhase.ACTIVE, connectedAtMillis = 0)))

    @Test fun fullSnapshotIsAsynchronousThenWaitsForNativeNavigationMode() {
        val endpoint = factory.open(key)
        endpoint.submit(snapshot())
        assertTrue(transports.isEmpty())
        factory.pump()
        val client = transports.single()
        assertTrue(client.events.contains("mode:true"))
        assertFalse(client.events.contains("turn:19:150"))
        now = 1_500
        factory.pump()
        assertTrue(client.events.contains("turn:19:150"))
        assertTrue(client.events.contains("media:27"))
        assertTrue(client.events.contains("phone:3"))
        assertTrue(client.events.contains("time:1"))
        val size = client.events.size
        factory.pump()
        assertEquals(size, client.events.size)
    }

    @Test fun closingOldEndpointCannotClearNewOutputAndStaleRevisionsAreRejected() {
        val first = factory.open(key)
        first.submit(snapshot())
        factory.pump()
        val secondKey = key.copy(epoch = 2)
        val second = factory.open(secondKey)
        second.submit(snapshot(2, secondKey))
        factory.pump()
        assertTrue(transports.first().events.contains("media:0"))
        assertEquals("closed", transports.first().events.last())
        first.close()
        first.submit(snapshot(100))
        second.submit(DisplaySnapshot(secondKey, 1)) // cannot overwrite revision 2 with empty state
        val secondClient = transports.last()
        val before = secondClient.events.toList()
        factory.pump()
        assertEquals(before, secondClient.events)
        second.close()
        factory.pump()
        assertTrue(secondClient.events.contains("media:0"))
        assertEquals("closed", secondClient.events.last())
    }

    @Test fun entireLaneRowRefreshesThenClearsOnHideExpiryAndSessionClose() {
        val endpoint = factory.open(key)
        val guide = NavigationLaneGuidance(listOf(NavigationLane(0, listOf(-90), true),
            NavigationLane(1, listOf(0)), NavigationLane(2, listOf(0), true, 90)), now)
        val route = snapshot().copy(navigation = snapshot().navigation.copy(laneGuidance = guide))
        endpoint.submit(route); factory.pump()
        now += 500; factory.pump()
        val client = transports.single()
        assertEquals(listOf(16, 0, 33), client.navigationValues.last().lanes)
        endpoint.submit(route.copy(revision = 2, navigation = route.navigation.copy(laneGuidance = null)))
        factory.pump()
        assertTrue(client.navigationValues.last().lanes.isEmpty())
        endpoint.submit(route.copy(revision = 3)); factory.pump()
        assertEquals(3, client.navigationValues.last().lanes.size)
        now = 91_001
        endpoint.submit(route.copy(revision = 4, navigation = route.navigation.copy(receivedAtMillis = now)))
        factory.pump()
        assertTrue(client.navigationValues.last().lanes.isEmpty())
        assertFalse(client.events.contains("mode:false")) // lane expiry does not end a fresh route
        endpoint.close(); factory.pump()
        assertTrue(client.events.contains("mode:false"))
        assertTrue(client.navigationValues.last().lanes.isEmpty())
    }

    @Test fun submittedLaneListsAreFrozenBeforeWorkerUsesThem() {
        val endpoint = factory.open(key)
        val angles = mutableListOf(-90)
        val lanes = mutableListOf(NavigationLane(0, angles, true))
        endpoint.submit(snapshot().let { it.copy(navigation = it.navigation.copy(
            laneGuidance = NavigationLaneGuidance(lanes, now))) })
        angles.clear(); lanes.clear()
        factory.pump(); now += 500; factory.pump()
        assertEquals(listOf(16), transports.single().navigationValues.last().lanes)
    }

    @Test fun losingFocusStopsThatDomainWithoutReclaimOrClearingTheNewOwner() {
        val endpoint = factory.open(key)
        endpoint.submit(snapshot())
        factory.pump()
        now = 1_500; factory.pump()
        val client = transports.single()
        client.focus.remove(MzdDomain.NAVIGATION)
        client.focus.remove(MzdDomain.MEDIA)
        endpoint.submit(snapshot(2).copy(media = MediaState(title = "New", playback = PlaybackState.PLAYING)))
        val before = client.events.size
        factory.pump()
        assertEquals(before, client.events.size)
        endpoint.close(); factory.pump()
        assertFalse(client.events.drop(before).contains("mode:false"))
        assertFalse(client.events.drop(before).contains("media:0"))
    }

    @Test fun unsupportedDomainDoesNotStopOtherDomains() {
        val client = Transport().apply { reject = MzdDomain.NAVIGATION }
        val partial = MzdVehicleDisplayFactory({}, { now }, { client }, false)
        partial.open(key).submit(snapshot())
        partial.pump()
        assertTrue(client.alive)
        assertTrue(client.events.contains("media:27"))
        assertTrue(client.events.contains("phone:3"))
    }

    @Test fun discoveryRetriesAreBoundedAndDoNotBlockSubmit() {
        var attempts = 0
        val bad = MzdVehicleDisplayFactory({}, { now }, { attempts++; throw SecurityException() }, false)
        bad.open(key).submit(snapshot())
        repeat(10) { bad.pump(); now += 60_000 }
        assertEquals(3, attempts)
    }

    @Test fun diagnosticBarrierWaitsForTheWorkerAndIncludesEndpointCleanup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = object : Transport() {
            override fun navigationMode(active: Boolean) {
                if (active) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                super.navigationMode(active)
            }
        }
        val automatic = MzdVehicleDisplayFactory({}, { now }, { client }, true)
        val endpoint = automatic.open(key)
        try {
            endpoint.submit(snapshot())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val barrier = automatic.flush()
            assertFalse(barrier.isDone)
            endpoint.close()
            release.countDown()
            barrier.get(2, TimeUnit.SECONDS)
            assertTrue(client.events.contains("mode:false"))
            assertEquals("closed", client.events.last())
        } finally { release.countDown(); endpoint.close() }
    }

    @Test fun blockedOemCallDoesNotBlockNewSessionOrPhoneIngress() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = object : Transport() {
            override fun navigationMode(active: Boolean) {
                if (active) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                super.navigationMode(active)
            }
        }
        val slow = MzdVehicleDisplayFactory({}, { now }, { client }, false)
        val first = slow.open(key)
        first.submit(snapshot())
        val caller = Executors.newSingleThreadExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit { slow.pump() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.submit {
                first.submit(snapshot(2))
                slow.open(key.copy(epoch = 2)).close()
                first.close()
            }.get(1, TimeUnit.SECONDS)
            release.countDown()
            pending.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown(); caller.shutdownNow(); worker.shutdownNow()
        }
    }

    @Test fun missingDistanceStaleRouteAndUnknownManeuverHaveExplicitFallbacks() {
        val nav = snapshot().navigation
        assertNull(MzdHudMapping.navigation(nav.copy(distanceMeters = null), now))
        assertNull(MzdHudMapping.navigation(nav.copy(remainingMeters = null), now))
        assertNull(MzdHudMapping.navigation(nav, 91_001))
        assertEquals(0, MzdHudMapping.icon(nav.copy(maneuver = ManeuverKind.UNKNOWN)))
        val roundabout = nav.copy(maneuver = ManeuverKind.ROUNDABOUT_NUMBERED, drivingSide = DrivingSide.LEFT)
        assertEquals(75, MzdHudMapping.icon(roundabout.copy(roundaboutExit = 2)))
        assertEquals(70, MzdHudMapping.icon(roundabout.copy(roundaboutExit = 11)))
    }

    @Test fun navigationGateDiagnosticsMatchActualOutput() {
        val nav = snapshot().navigation
        val cases = listOf(
            nav.copy(phase = NavigationPhase.ENDED) to "not_guiding",
            nav.copy(receivedAtMillis = -90_000) to "stale",
            nav.copy(distanceMeters = null) to "missing_distance",
            nav.copy(remainingMeters = null) to "missing_remaining",
            nav.copy(remainingMillis = null) to "missing_time",
            nav.copy(distanceMeters = 131071) to "distance_range",
            nav.copy(remainingMeters = 9_999_001) to "remaining_range",
            nav.copy(remainingMeters = Long.MAX_VALUE) to "remaining_range",
            nav.copy(remainingMillis = -1) to "time_range",
            nav.copy(remainingMillis = 65535000) to "time_range",
        )
        for ((value, reason) in cases) {
            assertEquals(reason, MzdHudMapping.navigationBlockReason(value, now))
            assertNull(MzdHudMapping.navigation(value, now))
        }
        assertNull(MzdHudMapping.navigationBlockReason(nav, now))
        assertNotNull(MzdHudMapping.navigation(nav, now))
        val reports = mutableListOf<String>()
        val driver = MzdVehicleDisplayFactory(reports::add, { now }, { Transport() }, false)
        val endpoint = driver.open(key)
        endpoint.submit(snapshot().copy(navigation = nav.copy(distanceMeters = null)))
        repeat(3) { driver.pump() }
        assertEquals(1, reports.count { it == "hudnav stage=gate reason=missing_distance" })
        endpoint.submit(snapshot(2))
        driver.pump()
        now += 500
        driver.pump()
        assertTrue(reports.contains("hudnav stage=output action=sent icon=19 distance=150 remaining=5000 seconds=120"))
        now += 90_001
        driver.pump()
        assertTrue(reports.contains("hudnav stage=gate reason=stale"))
        assertTrue(reports.contains("hudnav stage=output action=clear"))
    }

    @Test fun longRemainingDistanceIsIndependentOfTheTurnDistanceSentinel() {
        val nav = snapshot().navigation
        for (meters in listOf(131071L, 1_018_000L, 9_999_000L)) {
            val mapped = MzdHudMapping.navigation(nav.copy(remainingMeters = meters, remainingMillis = 37_680_000), now)
            assertNotNull("Verified remaining distance $meters", mapped)
            assertEquals(meters.toInt(), mapped!!.remaining)
            assertEquals(37_680, mapped.seconds)
        }
        assertNull(MzdHudMapping.navigation(nav.copy(distanceMeters = 131071), now))
        assertNull(MzdHudMapping.navigation(nav.copy(remainingMeters = -1), now))
        assertNull(MzdHudMapping.navigation(nav.copy(remainingMeters = 9_999_001), now))
    }

    @Test fun sparsePhoneUpdatesKeepNavigationVisibleButSilenceStillExpires() {
        val endpoint = factory.open(key)
        val route = snapshot().copy(media = MediaState(), calls = emptyList())
        endpoint.submit(route); factory.pump()
        now = 1_500; factory.pump()
        val client = transports.single()
        now = 61_000; factory.pump()
        assertFalse(client.events.contains("mode:false"))
        endpoint.submit(route.copy(revision = 2, navigation = route.navigation.copy(receivedAtMillis = now)))
        factory.pump()
        now = 151_000; factory.pump() // Exactly 90 s since the genuine source update.
        assertFalse(client.events.contains("mode:false"))
        now++; factory.pump()
        assertTrue(client.events.contains("mode:false"))
        assertTrue(client.events.contains("release:NAVIGATION"))
    }

    @Test fun cancellationAndSessionCloseDoNotWaitForTheSparseUpdateTimeout() {
        for (closeSession in listOf(false, true)) {
            now = 1_000
            val endpoint = factory.open(key)
            endpoint.submit(snapshot()); factory.pump()
            now = 1_500; factory.pump()
            val client = transports.last()
            if (closeSession) endpoint.close() else endpoint.submit(snapshot(2).copy(navigation = NavigationState(NavigationPhase.ENDED)))
            factory.pump()
            assertTrue(client.events.contains("mode:false"))
            assertTrue(client.events.contains("release:NAVIGATION") || client.events.contains("closed"))
            endpoint.close(); factory.pump()
        }
    }

    @Test fun unchangedRouteReentryRepaintsReceiverWithPersistentValueCache() {
        var receiverCache: MzdNavigation? = MzdNavigation(19, 150, 5000, 120)
        var visible: MzdNavigation? = null
        val replay = MzdVehicleDisplayFactory({}, { now }, {
            object : Transport() {
                override fun navigationMode(active: Boolean) { visible = null; super.navigationMode(active) }
                override fun navigation(value: MzdNavigation) {
                    if (receiverCache != value) visible = value
                    receiverCache = value
                    super.navigation(value)
                }
            }
        }, false)
        val endpoint = replay.open(key)
        val route = snapshot().copy(media = MediaState(), calls = emptyList())
        endpoint.submit(route); replay.pump()
        now += 500; replay.pump()
        assertEquals(19, visible?.icon)
        endpoint.submit(route.copy(revision = 2, navigation = NavigationState()))
        replay.pump()
        endpoint.submit(route.copy(revision = 3))
        replay.pump()
        now += 500; replay.pump()
        assertEquals("Same TBT must be redrawn after mode reentry", 19, visible?.icon)
        endpoint.close(); replay.pump()
        val nextKey = key.copy(epoch = 2)
        val next = replay.open(nextKey)
        next.submit(route.copy(session = nextKey)); replay.pump()
        now += 500; replay.pump()
        assertEquals("Same TBT must be redrawn after endpoint replacement", 19, visible?.icon)
    }

    @Test fun callEndClearsCachedLyricThenRestoresLatestSongAfterOemRemoval() {
        val endpoint = factory.open(key)
        endpoint.submit(snapshot()); factory.pump()
        val client = transports.single()
        endpoint.submit(snapshot(2).copy(calls = emptyList())); factory.pump()
        assertEquals(0, client.mediaMessages.last().getInt("sourceType"))
        endpoint.submit(snapshot(3).copy(calls = emptyList(), media = MediaState(title = "Latest", playback = PlaybackState.PLAYING)))
        now += 499; factory.pump()
        assertEquals(0, client.mediaMessages.last().getInt("sourceType"))
        now += 1; factory.pump()
        assertEquals("Latest", client.mediaMessages.last().getString("songName"))
        assertEquals(27, client.mediaMessages.last().getInt("sourceType"))
        val count = client.mediaMessages.size
        factory.pump()
        assertEquals("Stable metadata must not restart the marquee every pump", count, client.mediaMessages.size)
        endpoint.close(); factory.pump()
    }

    @Test fun pendingMusicRestoreCannotWriteAfterFocusOrSessionLoss() {
        val endpoint = factory.open(key)
        endpoint.submit(snapshot()); factory.pump()
        val client = transports.single()
        client.focus.remove(MzdDomain.MEDIA)
        endpoint.submit(snapshot(2).copy(calls = emptyList())); factory.pump()
        val count = client.mediaMessages.size
        now += 500; factory.pump()
        assertEquals(count, client.mediaMessages.size)
        endpoint.close(); factory.pump()
        now += 5_000; factory.pump()
        assertEquals(count, client.mediaMessages.size)
    }

    @Test fun unchangedPlayingMediaHasBoundedHeartbeatAndStopsOnPauseOrFocusLoss() {
        val endpoint = factory.open(key)
        val musicOnly = snapshot().copy(navigation = NavigationState(), calls = emptyList())
        endpoint.submit(musicOnly); factory.pump()
        val client = transports.single()
        val first = client.mediaMessages.single().toString()
        now += 1_999; factory.pump()
        assertEquals(1, client.mediaMessages.size)
        now += 1; factory.pump()
        assertEquals(listOf(first, first), client.mediaMessages.map { it.toString() })
        endpoint.submit(musicOnly.copy(revision = 2, media = musicOnly.media.copy(playback = PlaybackState.PAUSED)))
        factory.pump()
        val pausedCount = client.mediaMessages.size
        now += 10_000; factory.pump()
        assertEquals(pausedCount, client.mediaMessages.size)
        client.focus.remove(MzdDomain.MEDIA)
        endpoint.submit(musicOnly.copy(revision = 3))
        now += 10_000; factory.pump()
        assertEquals(pausedCount, client.mediaMessages.size)
        endpoint.close(); factory.pump()
        assertEquals(pausedCount, client.mediaMessages.size)
    }

    @Test fun musicFallbackPreservesReadableTextArtistAndUtf8FieldBounds() {
        val short = JSONObject(MzdHudMapping.mediaJson(MediaState(title = "测试😀短曲", artist = "歌手", playback = PlaybackState.PLAYING)))
        assertEquals("测试 短曲", short.getString("songName"))
        assertEquals("测试 短曲 / 歌手", short.getJSONObject("lyricObj").getString("curLyric"))
        val long = JSONObject(MzdHudMapping.mediaJson(MediaState(title = "测试😀".repeat(40), artist = "测试歌手A", playback = PlaybackState.PLAYING)))
        val line = long.getJSONObject("lyricObj").getString("curLyric")
        assertTrue(line.startsWith("测试"))
        assertTrue(line.endsWith(" / 测试歌手A"))
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= 128)
        assertFalse(long.toString().contains("😀"))
        val emojiOnly = JSONObject(MzdHudMapping.mediaJson(MediaState(title = "😀", artist = "歌手")))
        assertEquals("歌手", emojiOnly.getJSONObject("lyricObj").getString("curLyric"))
    }

    @Test fun bluetoothHandoverChecksAreBoundedAndStopForCallsPauseAndClosedSession() {
        var checks = 0
        val client = object : Transport() {
            override fun handoverDisconnectedBluetooth(): Boolean { checks++; return false }
        }
        val driver = MzdVehicleDisplayFactory({}, { now }, { client }, false)
        val endpoint = driver.open(key)
        val music = snapshot().copy(navigation = NavigationState(), calls = emptyList())
        endpoint.submit(music); driver.pump()
        client.focus.remove(MzdDomain.MEDIA)
        driver.pump(); assertEquals(1, checks)
        now += 1_999; driver.pump(); assertEquals(1, checks)
        now += 1; driver.pump(); assertEquals(2, checks)
        endpoint.submit(music.copy(revision = 2, calls = snapshot().calls))
        now += 10_000; driver.pump(); assertEquals(2, checks)
        endpoint.submit(music.copy(revision = 3, media = music.media.copy(playback = PlaybackState.PAUSED)))
        now += 10_000; driver.pump(); assertEquals(2, checks)
        endpoint.submit(music.copy(revision = 4)); driver.pump(); assertEquals(2, checks)
        now += 500; driver.pump(); assertEquals(3, checks) // Wait for OEM call removal too.
        endpoint.close(); driver.pump(); now += 10_000; driver.pump(); assertEquals(3, checks)
        assertEquals(1, client.events.count { it == "request:MEDIA" })
        assertEquals(1, client.mediaMessages.size) // Never clears another owner's screen.
    }

    @Test fun phoneUsesSecondsAndMusicTextIsBoundedWithoutInventedLyrics() {
        assertEquals(65, MzdHudMapping.phone(CallState("a", CallPhase.ACTIVE, connectedAtMillis = 0), 65_000).seconds)
        assertEquals(1, MzdHudMapping.phone(CallState("b", CallPhase.RINGING), now).state)
        val json = JSONObject(MzdHudMapping.mediaJson(MediaState(title = "Song", artist = "Artist")))
        assertEquals("Song / Artist", json.getJSONObject("lyricObj").getString("curLyric"))
        assertEquals("A".repeat(127), MzdHudMapping.text("A".repeat(127) + "😀", 128))
        assertEquals("甲 ", MzdHudMapping.text("甲\n😀", 4))
    }

    @Test fun alternateTextSourceSurvivesHeartbeatAndCallRestorationThenClearsToZero() {
        val client = Transport()
        val alternate = MzdVehicleDisplayFactory({}, { now }, { client }, false, MzdMediaSource.TEXT_COMPAT_29)
        val endpoint = alternate.open(key)
        val song = snapshot().copy(navigation = NavigationState(), calls = emptyList())
        endpoint.submit(song); alternate.pump()
        assertEquals(29, client.mediaMessages.single().getInt("sourceType"))
        now += 2_000; alternate.pump()
        assertEquals(2, client.mediaMessages.size)
        assertEquals(client.mediaMessages.first().toString(), client.mediaMessages.last().toString())
        endpoint.submit(song.copy(revision = 2, calls = snapshot().calls)); alternate.pump()
        endpoint.submit(song.copy(revision = 3)); alternate.pump()
        assertEquals(0, client.mediaMessages.last().getInt("sourceType"))
        now += 500; alternate.pump()
        assertEquals(29, client.mediaMessages.last().getInt("sourceType"))
        assertEquals("Title / Artist", client.mediaMessages.last().getJSONObject("lyricObj").getString("curLyric"))
        endpoint.close(); alternate.pump()
        assertEquals(0, client.mediaMessages.last().getInt("sourceType"))
        assertEquals(0, client.mediaMessages.last().getInt("sourceID"))
        assertEquals(0, client.mediaMessages.last().getJSONObject("lyricObj").getInt("sourceID"))
        assertEquals("closed", client.events.last())
    }

    @Test fun bothTextSourcesKeepTheSame128ByteProtocolAndUseRealLyricsWhenAvailable() {
        for (source in MzdMediaSource.entries) {
            val song = MediaState(title = "长曲名".repeat(50), artist = "歌手", playback = PlaybackState.PLAYING)
            val fallback = JSONObject(MzdHudMapping.mediaJson(song, source))
            assertEquals(source.sourceType, fallback.getInt("sourceType"))
            assertEquals(1, fallback.getInt("sourceID"))
            assertEquals(1, fallback.getJSONObject("lyricObj").getInt("sourceID"))
            val line = fallback.getJSONObject("lyricObj").getString("curLyric")
            assertTrue(line.endsWith(" / 歌手"))
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 128)
            assertTrue(fallback.getString("songName").toByteArray(Charsets.UTF_8).size <= 128)
            val lyric = JSONObject(MzdHudMapping.mediaJson(song.copy(
                currentLyric = "真实歌词".repeat(30), lyricsSupport = LyricsSupport.CURRENT_LINE), source))
                .getJSONObject("lyricObj").getString("curLyric")
            assertTrue(lyric.startsWith("真实歌词"))
            assertTrue(lyric.toByteArray(Charsets.UTF_8).size <= 128)
            val cleared = JSONObject(MzdHudMapping.mediaJson(null, source))
            assertEquals(0, cleared.getInt("sourceType"))
            assertEquals(0, cleared.getJSONObject("lyricObj").getInt("lyricStatus"))
            assertEquals("", cleared.getJSONObject("lyricObj").getString("curLyric"))
        }
    }

    @Test fun automaticLeasePrecedesFocusAndLiveMetadataThenYieldsWithoutReclaim() {
        var prepared = false
        var begins = 0
        var ends = 0
        var yields = 0
        var recoveryAllowed = true
        val lease = object : MzdMediaHandover {
            override fun begin() { begins++ }
            override fun prepare(playing: Boolean, noCalls: Boolean, now: Long) = prepared
            override fun yield() { yields++; prepared = false }
            override fun end() { ends++ }
            override fun recover(now: Long, noCalls: Boolean) { recoveryAllowed = noCalls }
        }
        val client = Transport()
        val driver = MzdVehicleDisplayFactory({}, { now }, { client }, false,
            MzdMediaSource.BLUETOOTH_APPEARANCE_20, lease)
        val endpoint = driver.open(key)
        val song = snapshot().copy(navigation = NavigationState(), calls = emptyList())
        endpoint.submit(song); driver.pump()
        assertEquals(1, begins)
        assertFalse(client.events.contains("request:MEDIA"))
        assertTrue(client.mediaMessages.isEmpty())
        prepared = true; now += 2_000; driver.pump()
        assertEquals(20, client.mediaMessages.single().getInt("sourceType"))
        endpoint.submit(song.copy(revision = 2, media = song.media.copy(title = "Next")))
        driver.pump()
        assertEquals("Next / Artist", client.mediaMessages.last().getJSONObject("lyricObj").getString("curLyric"))
        endpoint.submit(song.copy(revision = 3, calls = snapshot().calls)); driver.pump()
        assertFalse(recoveryAllowed)
        endpoint.submit(song.copy(revision = 4)); now += 500; driver.pump()
        now += 500; driver.pump()
        client.focus.remove(MzdDomain.MEDIA); driver.pump()
        assertEquals(1, yields)
        repeat(10) { now += 2_000; driver.pump() }
        assertEquals(1, client.events.count { it == "request:MEDIA" })
        endpoint.close(); driver.pump()
        assertTrue(ends >= 1)
    }
}
