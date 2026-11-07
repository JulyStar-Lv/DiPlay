package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat

/** Synchronous MediaCodec adapter; configure, queues, flush and release all run on the decode worker. */
internal class PcmDcxCodec(report: (String) -> Unit) : PcmDcxPump.Port {
    private val codec = MediaCodecStartup.create(
        create = { MediaCodec.createByCodecName(PcmDcxPump.NAME) },
        configure = {
            val format = MediaFormat.createAudioFormat(PcmDcxPump.MIME, PcmDcxPump.RATE, 12).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, PcmDcxPump.EXPECTED.encoding)
                setInteger(MediaFormat.KEY_CHANNEL_MASK, PcmDcxPump.EXPECTED.mask)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PcmDcxPump.MAX_INPUT_BYTES)
            }
            it.configure(format, null, null, 0)
            runCatching { report("PCM DCX: negotiated input=${it.inputFormat}") }
            readFormat(it.inputFormat).validate()
        },
        start = { it.start() },
        release = { it.release() },
    )

    init { report("PCM DCX: configured name=${PcmDcxPump.NAME} inputLimitBytes=${PcmDcxPump.MAX_INPUT_BYTES}; vendor DRC configuration unverified") }

    override fun input(timeoutUs: Long): PcmDcxPump.Input? {
        val index = codec.dequeueInputBuffer(timeoutUs)
        if (index < 0) return null
        return PcmDcxPump.Input(index, checkNotNull(codec.getInputBuffer(index)))
    }

    override fun queue(input: PcmDcxPump.Input, size: Int, timeUs: Long, eos: Boolean) {
        require(size in 0..PcmDcxPump.MAX_INPUT_BYTES && size % PcmDcxPump.FRAME_BYTES == 0) {
            "PCM DCX: unsafe input size $size"
        }
        codec.queueInputBuffer(input.index, 0, size, timeUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    override fun output(timeoutUs: Long): PcmDcxPump.Output? {
        val info = MediaCodec.BufferInfo()
        val index = codec.dequeueOutputBuffer(info, timeoutUs)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            return PcmDcxPump.Output(ByteArray(0), readFormat(codec.outputFormat), 0)
        }
        if (index < 0) return null
        try {
            val format = readFormat(codec.getOutputFormat(index)).also { it.validate() }
            val bytes = if (info.size == 0) ByteArray(0) else {
                val buffer = checkNotNull(codec.getOutputBuffer(index))
                check(info.offset >= 0 && info.size >= 0 && info.offset <= buffer.capacity() - info.size)
                buffer.limit(info.offset + info.size)
                buffer.position(info.offset)
                ByteArray(info.size).also { buffer.get(it) }
            }
            return PcmDcxPump.Output(bytes, format, info.presentationTimeUs,
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
        } finally { codec.releaseOutputBuffer(index, false) }
    }

    override fun flush() { codec.flush(); readFormat(codec.inputFormat).validate() }
    override fun close() { try { codec.stop() } finally { codec.release() } }

    private fun readFormat(format: MediaFormat) = PcmDcxPump.Format(
        format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        format.getInteger(MediaFormat.KEY_PCM_ENCODING), format.getInteger(MediaFormat.KEY_CHANNEL_MASK),
    )
}
