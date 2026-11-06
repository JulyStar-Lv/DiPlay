package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.os.Build

/** Only declare layouts that this firmware routes directly to its Dolby output. */
object SpatialAudioSupport {
    fun hasPcmDcxDecoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.name == PcmDcxPump.NAME &&
                info.supportedTypes.any { it.equals(PcmDcxPump.MIME, ignoreCase = true) }
        }
    }.getOrDefault(false)

    fun formats(context: Context): List<Int> {
        if (Build.VERSION.SDK_INT < 32 || !SoftwareApacDecoder.available) return emptyList()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BUS && it.address.contains("dolby", ignoreCase = true)
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        return listOf(65 to 0xfc, 68 to 0xb58fc).filter { (_, mask) ->
            devices.any { mask in it.channelMasks && 48000 in it.sampleRates } &&
                AudioTrack.isDirectPlaybackSupported(AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build(), attributes)
        }.map { it.first }
    }
}
