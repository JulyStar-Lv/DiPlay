package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** A frame with the iPhone's frame time and its arrival time (System.nanoTime); see [ScreenStream.Listener]. */
    fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes)
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    /** Gain belongs to this buffered packet, including when a decoder returns its PCM later. */
    fun onBufferedAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int, gain: Float) =
        onAudioRtp(id, format, rtp, sample)
    /** Requests a format handoff without blocking; true once all submitted buffered audio has played. */
    fun onBufferedAudioDrain(id: AudioStreamId): Boolean = true
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
    /** The iPhone asked the car to duck its other audio for a navigation prompt (true), or to restore it. */
    fun onAudioDuck(ducked: Boolean) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
/**
 * Delivers a screen stream's data to [sink] only while [isCurrent]: a replaced stream's thread may still
 * be delivering when its successor starts. The check runs before each delivery, so a callback already
 * past it can still hand over one item; the successor's configuration and keyframe follow it. The iPhone
 * connects after SETUP returns the port, which is after the stream is registered as current.
 */
internal fun currentScreenListener(type: Int, sink: MediaSink, isCurrent: () -> Boolean) = object : ScreenStream.Listener {
    override fun onCodec(codec: VideoCodec) { if (isCurrent()) sink.onVideoCodec(type, codec) }
    override fun onConfig(codecData: ByteArray) { if (isCurrent()) sink.onVideoConfig(type, codecData) }
    override fun onFrame(naluBytes: ByteArray) { if (isCurrent()) sink.onVideoFrame(type, naluBytes) }
    override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (isCurrent()) sink.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
    }
}

class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
    private val routeCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    ) {
        val position = FeedbackPositionMonitor(format.rtpClockRate)
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    private data class VehicleKey(val session: AirPlaySession, val uuid: String)
    private data class VehicleChannel(val id: Long, val channel: VehicleStateChannel)
    private val vehicleChannels = ConcurrentHashMap<VehicleKey, VehicleChannel>()
    private val vehicleRoutes = ConcurrentHashMap<AirPlaySession, VehicleRouteSharing>()
    private val bufferedStreams = ConcurrentHashMap<AirPlaySession, BufferedStream>()
    private val microphoneScheduler by lazy {
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "carplay-mic-start").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(MICROPHONE_SCHEDULER_IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    /** The session whose last duck command started a navigation prompt. */
    private val duckingSession = AtomicReference<AirPlaySession?>()

    // The sink has one type-103 renderer, even while old control sessions remain connected.
    // Never hold this lock when closing/controlling a stream: sink callbacks can reenter us.
    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: AirPlaySession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val retiredBufferedSessions = Collections.newSetFromMap(WeakHashMap<AirPlaySession, Boolean>())
    private class BufferedTransition(val session: AirPlaySession, val candidate: BufferedStream?) {
        var cancelled = false // guarded by bufferedOwnershipLock
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: AirPlaySession): Boolean {
        // A reentrant sink callback cannot wait for itself. Preserve its retirement barrier
        // until cleanup completes; the volatile flag requires no reverse stream-lock order.
        bufferedTransition?.takeIf { it.candidate == null && it.retiring?.stream?.outputCleanupComplete == true }
            ?.let { bufferedTransition = null }
        return !session.isClosed && session !in retiredBufferedSessions && bufferedTransition == null
    }
    private val nextDataStreamId = AtomicLong(FIRST_REMOTE_CONTROL_STREAM_ID)
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val peer = session.remoteAddress ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(key, peer, session::logDebug)
        sink.setVideoDiagnosticHandler(type) {
            if (type == STREAM_TYPE_MAIN_SCREEN && streams[streamKey] === screen && it == "first frame rendered") {
                session.videoFrameRendered()
            }
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (streams[streamKey] === screen) {
                // The cluster asks for a keyframe of its own screen; the plain command is the main screen's.
                val command = if (type == STREAM_TYPE_ALT_SCREEN) {
                    mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID))
                } else {
                    mapOf("type" to "forceKeyFrame")
                }
                val sent = session.sendCommand(command)
                session.logDebug("Video recovery: requested keyframe sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener by currentScreenListener(type, sink, isCurrent = { streams[streamKey] === screen }) {
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onScreenStreamActive(type, false)
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        streams.remove(streamKey)?.close()
        audioMeta.remove(streamKey)
        audioCaptures.remove(streamKey)?.close()
        if (pendingMicrophone.remove(streamKey) != null) sink.onMicrophoneStopped(streamId)
        sink.onAudioStopped(streamId)

        val key = outputKey(session, stream) ?: return null
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (microphone != null) pendingMicrophone[streamKey] = microphone
        if (type == STREAM_TYPE_MAIN_AUDIO && audioType != "media") {
            session.logDebug(
                "Audio: setup type=$type audioType=$audioType input=${stream["input"] ?: "none"} " +
                    "micPort=${if ((stream["dataPort"] as? Number)?.toInt()?.let { it > 0 } == true) "yes" else "no"} " +
                    "microphone=${if (microphone != null) "on" else "off"} keys=${stream.keys.sorted().joinToString(",")}" +
                    // An app call (WeChat, #718) carried vocoderInfo; the report showed only its name.
                    (stream["vocoderInfo"]?.let { " vocoderInfo=${it.toString().take(200)}" } ?: ""),
            )
        }

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val clockProbe = RtpClockProbe()
        val microphoneStart = microphone?.let { config -> MicrophoneStart { sink.onMicrophoneStarted(streamId, config) } }
        val redundancy = stream["supportsRTPPacketRedundancy"] == true
        val audio = AudioStream(key, type, redundancy, session::logDebug)
        session.logDebug("Audio: UDP recovery type=$type audioType=$audioType redundancy=$redundancy")
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    microphoneStart?.onDownlink()
                }

                override fun onRtp(rtp: ByteArray, sample: Int) {
                    meta.position.observeRtp(sample)
                    clockProbe.observe(sample, System.nanoTime())?.let { hz ->
                        session.logDebug(
                            "Audio: rtp clock type=$type audioType=$audioType " +
                                "audioFormat=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                                "measuredHz=$hz microphoneClockHz=${microphone?.rtpClockRate ?: "none"}",
                        )
                    }
                    sink.onAudioRtp(streamId, format, rtp, sample)
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    capture?.record(wire, rtp, sample, error)
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        if (microphoneStart != null && MicrophoneStart.startsWithoutDownlink(audioType)) {
            microphoneStart.scheduleSilentStart(microphoneScheduler, MicrophoneStart.SILENT_START_MILLIS) { startNow ->
                // Atomic with TEARDOWN, which removes the pending entry before it stops the microphone.
                var started = false
                pendingMicrophone.computeIfPresent(streamKey) { _, pending ->
                    if (pending === microphone) started = startNow()
                    pending
                }
                if (started) {
                    session.logDebug(
                        "Audio: microphone started without downlink audio type=$type audioType=$audioType " +
                            "afterMs=${MicrophoneStart.SILENT_START_MILLIS}",
                    )
                }
            }
        }
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        ).apply {
            if (redundancy) put("supportsRTPPacketRedundancy", true)
        }
    }

    override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) {
            Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, "media")
        val apap = stream["packetFormatAPAP"] == true
        val index = (stream["audioFormatIndex"] as? Number)?.toInt()
        if (apap && (session.spatialAudioFormats.isEmpty() ||
                index !in session.spatialAudioFormats + 23 || stream["audioType"] != "media" ||
                stream["isMedia"]?.let { it != true } == true || (stream["spf"] as? Number)?.toInt() != 1024 ||
                stream["audioMode"]?.let { it != "default" } == true)) {
            session.logDebug("Spatial audio: unsupported extended SETUP index=$index; declined")
            return null
        }
        val spatial = apap && index in session.spatialAudioFormats
        if ((!spatial && format.codec != AudioCodecKind.AAC_LC) ||
            (spatial && (stream["audioFormat"] as? Number)?.toLong() != 0L) ||
            (apap && index == 23 && (stream["audioFormat"] as? Number)?.toLong() != 0x800000L)) {
            Log.w(TAG, "buffered audio format ${stream["audioFormat"]} is not AAC-LC; declined")
            return null
        }
        if (stream["ct"]?.let { it !is Number || it.toInt() != if (spatial) 0 else 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true) {
            Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
            return null
        }
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, if (apap) format.copy(sampleRate = 48000, rtpClockRate = 48000) else format,
                sink, session::logDebug, expectedPeer = peer, apap = apap, spatialFormats = session.spatialAudioFormats,
                timelineDiagnostics = session.bufferedTimelineExperiment in 3..8)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        // Validate before retiring a healthy owner. Detach its registry entries first, but
        // keep a transition barrier until close has finished its final sink callback.
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            if (!mayInstallBufferedLocked(session)) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    if (oldSession !== session) retiredBufferedSessions.add(oldSession)
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val retired = runCatching { previous?.stream?.close() }.isSuccess &&
            previous?.stream?.outputCleanupComplete != false
        val installed = synchronized(bufferedOwnershipLock) {
            val usable = retired && bufferedTransition === transition && !transition.cancelled &&
                !session.isClosed && session !in retiredBufferedSessions
            if (usable) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            if (bufferedTransition === transition) {
                bufferedTransition = if (retired) null else BufferedTransition(session, null).also { it.retiring = previous }
            }
            usable
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream client=${stream["clientID"]} rate=${format.sampleRate} port=${buffered.port}")
        return linkedMapOf(
            "type" to type,
            "dataPort" to buffered.port,
            "audioBufferSize" to BufferedAudioStream.AUDIO_BUFFER_BYTES,
        )
    }

    override fun onBufferedAudioControl(
        session: AirPlaySession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        fun mediaTime(valueKey: String, scaleKey: String): Long? {
            val value = (body[valueKey] as? Number)?.toLong() ?: return null
            val scale = (body[scaleKey] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            // Resume requests can use nanoseconds. Multiply before dividing without Long overflow.
            return BigInteger.valueOf(value).multiply(BigInteger.valueOf(buffered.rtpClockRate.toLong()))
                .divide(BigInteger.valueOf(scale)).toLong() and 0xffffffffL
        }
        val rtpTime = (body["rtpTime"] as? Number)?.toLong()
            ?: mediaTime("mediaTimeValue", "mediaTimeScale")
        val rate = when ((body["rate"] as? Number)?.toDouble()) {
            0.0 -> 0
            1.0 -> 1
            else -> null
        }
        val response = when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> {
                if (rate == null || rate !in 0..1) null else {
                    val anchor = buffered.setRate(rtpTime, rate, session.syncedNtp())
                    if (session.bufferedTimelineExperiment in listOf(1, 6, 7, 8) && method == "SETRATEANCHORTIME") {
                        session.logDebug("Buffered audio: timeline experiment=${session.bufferedTimelineExperiment} SETRATEANCHORTIME ack=empty rate=$rate")
                        null
                    } else if (session.bufferedTimelineExperiment in 2..8 && method == "SETRATE" && rate == 1 &&
                        anchor != null && anchor.containsKey("mediaTimeValue")) {
                        val value = (body["mediaTimeValue"] as? Number)?.toLong()
                        val scale = (body["mediaTimeScale"] as? Number)?.toLong()?.takeIf { it > 0 }
                        if (value != null && scale != null) {
                            session.logDebug("Buffered audio: timeline experiment=2 SETRATE reply=original-media-time scale=$scale")
                            anchor + mapOf("mediaTimeValue" to value, "mediaTimeScale" to scale)
                        } else anchor
                    } else anchor
                }
            }
            "GETANCHOR" -> buffered.anchor()
            "FLUSHBUFFERED" -> {
                val until = (body["flushUntilTS"] as? Number)?.toLong()
                    ?: mediaTime("flushUntilMediaTimeValue", "flushUntilMediaTimeScale")
                val from = (body["flushFromTS"] as? Number)?.toLong()
                    ?: mediaTime("flushFromMediaTimeValue", "flushFromMediaTimeScale")
                val untilSequence = (body["flushUntilSeq"] as? Number)?.toInt()
                val fromSequence = (body["flushFromSeq"] as? Number)?.toInt()
                // An unsupported lower bound must not turn a bounded request into a prefix reset.
                val hasFrom = body.containsKey("flushFromTS") || body.containsKey("flushFromMediaTimeValue") ||
                    body.containsKey("flushFromSeq")
                if (untilSequence != null && (!hasFrom || fromSequence != null)) {
                    buffered.flush(until, from, untilSequence, fromSequence)
                } else if (until != null && (!hasFrom || from != null)) buffered.flush(until, from)
                null
            }
            "SETMAGICCOOKIE" -> {
                val id = (body["magicCookieID"] as? Number)?.toLong()
                val cookie = body["magicCookie"] as? ByteArray
                if (id != null && cookie != null && cookie.size in 1..ApapPacket.MAX_COOKIE_BYTES) {
                    runCatching { buffered.storeMagicCookie(id, cookie) }
                        .onFailure { session.logDebug("Spatial audio: cookie limit exceeded") }
                }
                null
            }
            else -> null
        }
        // OEM SETRATE includes both fields (libAirPlay 0x927e0..0x9285c). This session uses NTP.
        val extendedResponse = if (session.bufferedTimelineExperiment in 7..8 && response?.containsKey("mediaTimeValue") == true)
            response + mapOf("networkTimeTimelineID" to 0L, "networkTimeFlags" to 0L) else response
        // Keep a media-only APAP request in that representation on the wire; output still uses RTP.
        return if (session.bufferedTimelineExperiment == 8 && method == "SETRATE" && rate == 1 &&
            body["rtpTime"] == null && body["mediaTimeValue"] is Number &&
            (body["mediaTimeScale"] as? Number)?.toLong()?.let { it > 0 } == true &&
            extendedResponse?.containsKey("mediaTimeValue") == true)
            extendedResponse - "rtpTime" else extendedResponse
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid == VehicleRouteSharing.HIGH_UUID || uuid == VehicleRouteSharing.NORMAL_UUID) {
            return vehicleDataStream(session, uuid, stream)
        }
        if (session.videoInCar) videoDataStream(session, uuid, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        val peer = session.remoteAddress ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            expectedPeer = peer,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    private fun vehicleDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!session.routeSharingNegotiated || session.isClosed) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        val peer = session.remoteAddress ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32)
        val state = vehicleRoutes.computeIfAbsent(session) {
            VehicleRouteSharing(routeCaptureDirectory?.let { File(it, java.util.UUID.randomUUID().toString()) }, session::logDebug)
        }
        val streamKey = VehicleKey(session, uuid)
        val id = nextDataStreamId.getAndIncrement()
        val channel = VehicleStateChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY), peer,
            receive = { bytes ->
                if (session.isClosed || vehicleChannels[streamKey]?.id != id) emptyList() else state.receive(bytes)
            }, log = session::logDebug)
        val candidate = VehicleChannel(id, channel)
        vehicleChannels.put(streamKey, candidate)?.channel?.close()
        val port = try {
            channel.listen(session.localAddress ?: InetAddress.getByName(if (peer is Inet6Address) "::" else "0.0.0.0"))
        } catch (error: Exception) {
            vehicleChannels.remove(streamKey, candidate)
            channel.close()
            throw error
        }
        if (session.isClosed || vehicleChannels[streamKey] !== candidate) {
            vehicleChannels.remove(streamKey, candidate)
            channel.close()
            return null
        }
        session.logDebug("Route sharing: channel listening priority=${if (uuid == VehicleRouteSharing.HIGH_UUID) "high" else "normal"} streamID=$id")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to id, "dataPort" to port).apply {
            stream["streamConnectionID"]?.let { put("streamConnectionID", unsignedPlistInteger(it)) }
        }
    }

    override fun onDataStreamTeardown(session: AirPlaySession, streamId: Long) {
        if (streamId == 1L) {
            clearPendingIapTunnel(session)
            streams.remove(StreamKey(session, STREAM_TYPE_DATA))?.close()
        } else if (streamId == VIDEO_SETTINGS_STREAM_ID) videoSettingsChannels.remove(session)?.close()
        vehicleChannels.entries.filter { it.key.session === session && it.value.id == streamId }.forEach { entry ->
            if (vehicleChannels.remove(entry.key, entry.value)) entry.value.channel.close()
        }
        if (vehicleChannels.keys.none { it.session === session }) vehicleRoutes.remove(session)
    }

    private fun closeVehicleChannels(session: AirPlaySession) {
        vehicleChannels.keys.filter { it.session === session }.forEach { vehicleChannels.remove(it)?.channel?.close() }
        vehicleRoutes.remove(session)
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && (stream["controlType"] as? Number)?.toInt() == 1) {
            val streamId = nextDataStreamId.getAndIncrement()
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to streamId)
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        val peer = session.remoteAddress ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VideoSettingsChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY), peer) { session.logDebug(it) }
        val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
        videoSettingsChannels.put(session, channel)?.close()
        session.logTrace("video settings stream listening port=$port")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to VIDEO_SETTINGS_STREAM_ID, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.values.toList()
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            // sampleTime counts on the stream's RTP clock. Counted at the 48 kHz Opus decode rate,
            // a 16 kHz call stream ran 3x ahead of the audio that had arrived, 14.5 s ahead after
            // 20 s, in a call whose far end the driver could not understand (#473).
            val clockRate = meta.format.rtpClockRate
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to clockRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * clockRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
                meta.position.onFeedback(meta.type, entry["streamConnectionID"], sampleTime, nowNs)
                    ?.let(session::logDebug)
            }
            entry
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        // WT_CarPlay's feedback packs AirTunesTime directly; its timing UDP adds the NTP epoch
        // separately. Compare that single field against stage 2 before changing other clocks.
        val responseStreams = if (session.bufferedTimelineExperiment in 4..8) streams.map { entry ->
            val timestamp = entry["timestamp"] as? BigInteger
            val response = if (timestamp == null) entry else entry + ("timestamp" to
                timestamp.subtract(BigInteger.valueOf(2_208_988_800L).shiftLeft(32)))
            // OEM feedback writes its sample clock as `sr` (double), not `sampleRate`.
            if (session.bufferedTimelineExperiment in 5..8)
                response + ("sr" to (entry.getValue("sampleRate") as Number).toDouble()) else response
        } else streams
        if (session.bufferedTimelineExperiment in 3..8) responseStreams.forEach { entry ->
            session.logDebug("Buffered timeline: reply type=${entry["type"]} id=${entry["streamConnectionID"]} " +
                "sr=${entry["sr"]} sampleRate=${entry["sampleRate"]} sampleTime=${entry["sampleTime"]} " +
                "timestamp=${entry["timestamp"]} timestampRawNs=${entry["timestampRawNs"]} " +
                "epoch=${if (session.bufferedTimelineExperiment in 4..8) 1970 else 1900}")
        }
        return linkedMapOf("streams" to responseStreams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) {
            clearPendingIapTunnel(session)
            closeVehicleChannels(session)
            videoSettingsChannels.remove(session)?.close()
        }
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        tornDown.forEach { key ->
            val streamId = AudioStreamId(key.type, key.audioType)
            if (pendingMicrophone.remove(key) != null) sink.onMicrophoneStopped(streamId)
            audioMeta.remove(key)
            audioCaptures.remove(key)?.close()
            // BufferedAudioStream owns serialized sink cleanup; stopping it first from here
            // could interleave with its in-flight delivery and recreate a stale renderer.
            if (key.type != BufferedAudioStream.STREAM_TYPE) sink.onAudioStopped(streamId)
            streams.remove(key)?.close()
        }
        if (isScreenStreamType(type)) sink.onScreenStreamActive(type, false)
    }

    override fun onAudioDuck(session: AirPlaySession, ducked: Boolean) {
        if (ducked) duckingSession.set(session) else duckingSession.compareAndSet(session, null)
        sink.onAudioDuck(ducked)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        // A phone that disconnects during a prompt never sends unduckAudio.
        if (duckingSession.compareAndSet(session, null)) sink.onAudioDuck(false)
        clearPendingIapTunnel(session)
        retireBuffered(session)
        videoSettingsChannels.remove(session)?.close()
        closeVehicleChannels(session)
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach { sink.onScreenStreamActive(it.type, false) }
        sessionStreams.forEach { streams.remove(it)?.close() }
        audioMeta.clear()
        pendingMicrophone.clear()
        audioCaptures.values.forEach(AudioPacketCapture::close)
        audioCaptures.clear()
    }

    /** Removes ownership before a close that may block on delivery or invoke the sink. */
    private fun detachBufferedLocked(session: AirPlaySession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: AirPlaySession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            bufferedTransition?.takeIf { it.session === session }?.let {
                it.cancelled = true
                pending = it.candidate
                // The SETUP thread still owns the barrier while it closes the earlier owner.
            }
            detachBufferedLocked(session).also {
                if (it != null && bufferedTransition == null) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (retirement != null && bufferedTransition === retirement &&
                    previous?.stream?.outputCleanupComplete == true) bufferedTransition = null
            }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (!wantsMicrophone(format.audioType, stream)) return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            MicrophoneConfig.pcmFrameMillis(format.sampleRate, format.channels)
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            opusClockRate = MicrophoneConfig.opusClockRate(formatBits),
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    internal companion object {
        /**
         * Calls and Siri always record. App audio ("default", e.g. a WhatsApp voice note) also carries
         * guidance prompts, so it records only when the iPhone asks for input: an explicit `input` flag,
         * or, without one, the uplink port the iPhone sends for a recording stream.
         */
        fun wantsMicrophone(audioType: String, stream: Map<String, Any?>): Boolean = when (audioType) {
            "telephony", "speechrecognition" -> true
            "default", "compatibility" -> when (val input = stream["input"]) {
                is Boolean -> input
                is Number -> input.toInt() != 0
                else -> (stream["dataPort"] as? Number)?.toInt()?.let { it in 1..65535 } == true
            }
            else -> false
        }

        private const val TAG = "xcertplay-usb"
        private const val MICROPHONE_SCHEDULER_IDLE_SECONDS = 5L
        private const val STREAM_TYPE_MAIN_SCREEN = 110
        private const val STREAM_TYPE_ALT_SCREEN = 111
        private const val STREAM_TYPE_MAIN_AUDIO = 100
        private const val STREAM_TYPE_DATA = 130
        private const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        private const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val VIDEO_SETTINGS_STREAM_ID = 2L
        private const val FIRST_REMOTE_CONTROL_STREAM_ID = 3L
        private const val OPUS_24K = 0x20000000L
        private const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
