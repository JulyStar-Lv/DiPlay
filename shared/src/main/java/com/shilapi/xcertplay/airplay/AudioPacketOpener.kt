package com.shilapi.xcertplay.airplay

/** One receive thread's audio key and nonce scratch space; returned packets have independent ownership. */
internal class AudioPacketOpener(key: ByteArray) {
    private val cipher = AirPlayCrypto.PacketCipher(key)
    private val nonce = ByteArray(12)

    fun open(wire: ByteArray, headerBytes: Int = HEADER_BYTES): ByteArray {
        require(headerBytes >= HEADER_BYTES && headerBytes <= wire.size - TAG_BYTES - NONCE_BYTES) {
            "Audio packet is missing its header, tag or nonce"
        }
        val sealedEnd = wire.size - NONCE_BYTES
        wire.copyInto(nonce, 4, sealedEnd, wire.size)
        val rtp = ByteArray(sealedEnd - TAG_BYTES)
        wire.copyInto(rtp, 0, 0, headerBytes)
        cipher.cryptInto(
            encrypt = false,
            nonce = nonce,
            input = wire,
            inputOffset = headerBytes,
            inputLength = sealedEnd - headerBytes,
            aad = wire,
            aadOffset = 4,
            aadLength = HEADER_BYTES - 4,
            output = rtp,
            outputOffset = headerBytes,
        )
        return rtp
    }

    private companion object {
        const val HEADER_BYTES = 12
        const val TAG_BYTES = 16
        const val NONCE_BYTES = 8
    }
}
