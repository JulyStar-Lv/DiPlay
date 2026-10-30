package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.api.CallPhase
import com.shilapi.display.api.CallState
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Keep phone facts separate from the old BYD display card and its audio side effects. */
internal class CarPlayCallsReducer(private val clock: () -> Long) {
    private val calls = linkedMapOf<String, CallState>()
    fun snapshot(): List<CallState> = calls.values.toList()

    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != 0x4155) return false
        return runCatching {
            val body = Iap2BodyReader.of(frame)
            val number = body.optionalString(0)?.trim()?.ifEmpty { null }
            val name = body.optionalString(1)?.trim()?.ifEmpty { null }
            val status = body.optionalU8(2)
            val uuid = body.optionalString(4)?.takeIf { it.isNotBlank() }
            val id = uuid ?: number ?: calls.keys.singleOrNull() ?: return false
            val old = calls[id]
            if (status == 0 || status == 6) return calls.remove(id) != null
            if (old == null && (status == null || calls.size >= 16)) return false
            val phase = when (status) {
                null -> old?.phase ?: CallPhase.UNKNOWN
                1 -> CallPhase.DIALING
                2 -> CallPhase.RINGING
                3 -> CallPhase.CONNECTING
                4 -> CallPhase.ACTIVE
                5 -> CallPhase.HELD
                else -> CallPhase.UNKNOWN
            }
            val next = CallState(id, phase,
                name = if (body.has(1)) name else old?.name,
                number = if (body.has(0)) number else old?.number,
                connectedAtMillis = old?.connectedAtMillis ?: if (phase == CallPhase.ACTIVE || phase == CallPhase.HELD) clock() else null)
            calls[id] = next
            next != old
        }.getOrDefault(false)
    }
}
