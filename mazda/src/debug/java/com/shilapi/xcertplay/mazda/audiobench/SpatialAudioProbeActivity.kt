package com.shilapi.xcertplay.mazda.audiobench

import android.app.Activity
import android.os.Bundle
import com.shilapi.xcertplay.media.SpatialAudioProbe

/** ADB entry for a silent digital measurement, separate from the CarPlay session. */
class SpatialAudioProbeActivity : Activity() {
    private var worker: Thread? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = checkNotNull(getExternalFilesDir("audio-probe")).apply { mkdirs() }
        if (intent.getStringExtra("mode") == "prepare") { finish(); return }
        worker = Thread({
            SpatialAudioProbe.run(directory)
            runOnUiThread { finish() }
        }, "pcm-dcx-probe").apply { start() }
    }
    override fun onDestroy() { worker?.interrupt(); super.onDestroy() }
}
