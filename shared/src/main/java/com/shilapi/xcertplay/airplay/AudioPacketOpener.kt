package com.shilapi.xcertplay.airplay

/** One receive thread's audio key and nonce scratch space; returned packets have independent ownership. */
internal class AudioPacketOpener(key: ByteArray) {
    private val cipher = AirPlayCrypto.PacketCipher(key)
    private val nonce = ByteArray(12)

    fun open(wire: ByteArray): ByteArray {
        require(wire.size >= HEADER_BYTES + TAG_BYTES + NONCE_BYTES) { "Audio packet is missing its tag or nonce" }
        val sealedEnd = wire.size - NONCE_BYTES
        wire.copyInto(nonce, 4, sealedEnd, wire.size)
        val rtp = ByteArray(sealedEnd - TAG_BYTES)
        wire.copyInto(rtp, 0, 0, HEADER_BYTES)
        cipher.cryptInto(
            encrypt = false,
            nonce = nonce,
            input = wire,
            inputOffset = HEADER_BYTES,
            inputLength = sealedEnd - HEADER_BYTES,
            aad = wire,
            aadOffset = 4,
            aadLength = HEADER_BYTES - 4,
            output = rtp,
            outputOffset = HEADER_BYTES,
        )
        return rtp
    }

    private companion object {
        const val HEADER_BYTES = 12
        const val TAG_BYTES = 16
        const val NONCE_BYTES = 8
    }
}
