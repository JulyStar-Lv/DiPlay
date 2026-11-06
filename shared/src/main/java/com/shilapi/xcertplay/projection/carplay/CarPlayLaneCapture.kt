package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.display.api.NavigationLane

internal data class CarPlayLaneBlock(val index: Int, val lanes: List<NavigationLane>)

/** Strict decoding shared by diagnostics and display; missing angles never become straight.
 * Field layout checked against upstream DiPlay PR #1028 and the OEM CarPlayLaneInfo model.
 * Repeated signed angles are preserved, including branches sharing one lane.
 */
internal object CarPlayLaneCapture {
    fun parse(frame: Iap2Frame): CarPlayLaneBlock? = runCatching {
            require(frame.messageId == 0x5204)
            val body = Iap2BodyReader.of(frame)
            require(body.u16(0) == 42)
            val index = body.u16(1)
            val lanes = body.all(2)
            require(lanes.size <= 16)
            val indices = mutableSetOf<Int>()
            val decoded = lanes.map { parameter ->
                val lane = Iap2BodyReader.of(Iap2ParameterList.parse(parameter.payload))
                val laneIndex = lane.u16(0)
                require(laneIndex in 0..15 && indices.add(laneIndex))
                val status = lane.u8(1)
                val angles = lane.all(2).map {
                    require(it.payload.size == 2)
                    val unsigned = ((it.payload[0].toInt() and 255) shl 8) or (it.payload[1].toInt() and 255)
                    val signed = unsigned.toShort().toInt()
                    require(signed in -180..180)
                    signed
                }
                require(angles.size in 1..8)
                val highlight = lane.optionalI16(3)?.takeUnless { it == -181 }
                require(highlight == null || highlight in -180..180)
                // The highlighted branch can be absent from the repeated base-angle list.
                NavigationLane(laneIndex, angles, status == 2, highlight)
            }
            CarPlayLaneBlock(index, decoded.sortedBy { it.index })
        }.getOrNull()

    fun diagnostic(frame: Iap2Frame): String {
        // Keep the existing bounded diagnostic shape, including raw status for wire evidence.
        val value = parse(frame)?.let { block ->
            val statuses = Iap2BodyReader.of(frame).all(2).associate { parameter ->
                val lane = Iap2BodyReader.of(Iap2ParameterList.parse(parameter.payload))
                lane.u16(0) to lane.u8(1)
            }
            val layout = block.lanes.joinToString(";") {
                "${it.index}/${statuses[it.index]}/${it.anglesDegrees.joinToString("+")}/${it.highlightedAngleDegrees ?: "null"}"
            }.ifEmpty { "none" }
            "valid=true index=${block.index} count=${block.lanes.size} layout=$layout"
        } ?: "valid=false index=null count=0 layout=none"
        return "stage=lanes frame=5204 $value"
    }
}
