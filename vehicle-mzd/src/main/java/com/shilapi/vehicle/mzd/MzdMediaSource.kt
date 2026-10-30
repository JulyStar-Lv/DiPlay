package com.shilapi.vehicle.mzd

/**
 * Text compatibility values verified on the current J90K firmware.
 * The original identities of 27/29 are unknown; none is native CarPlay source 30.
 * The text layouts retain a 176 px container on stock resources. The separately verified Linux
 * SongNormal layout patch expands it to 216 px; selecting a value does not install that patch.
 */
enum class MzdMediaSource(internal val sourceType: Int) {
    /** Stock Bluetooth appearance, used only after native BT media has yielded. */
    BLUETOOTH_APPEARANCE_20(20),
    TEXT_COMPAT_27(27),
    TEXT_COMPAT_29(29);

    companion object {
        fun fromSourceType(value: Int): MzdMediaSource = entries.firstOrNull { it.sourceType == value }
            ?: throw IllegalArgumentException("J90K music text compatibility requires sourceType 20, 27 or 29")
    }
}
