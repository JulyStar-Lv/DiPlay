// SPDX-License-Identifier: GPL-3.0-or-later
// Navigation service reference: Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
package com.shilapi.xcertplay.airplay

import java.io.File

/** Navigation-only VSP receiver. Legs stay opaque until a real coordinate representation is verified. */
internal class VehicleRouteSharing(private val captureDirectory: File?, private val log: (String) -> Unit) {
    private val values = mutableMapOf<Long, Any?>().apply {
        characteristics.forEach { c -> c.initial?.let { put(c.iid, it) } }
    }
    private val followed = mutableSetOf<Long>()
    private var captureCount = 0
    private var capturedBytes = 0L

    @Synchronized fun receive(bytes: ByteArray): List<ByteArray> {
        capture("rx", bytes)
        val outer = OpackCodec.decode(bytes) as? Map<*, *> ?: return emptyList()
        if (outer["pluginID"] != 0L) return emptyList()
        val body = outer["pluginData"] as? ByteArray ?: return emptyList()
        val request = OpackCodec.decode(body) as? Map<*, *> ?: return emptyList()
        val transaction = request["transactionID"]
        val data = request["values"]
        val answers = mutableListOf<Map<String, Any?>>()
        fun answer(command: String, vararg fields: Pair<String, Any?>) {
            answers.add(linkedMapOf("command" to command, "transactionID" to transaction, *fields))
        }
        when (request["command"]) {
            "configRequest" -> {
                answer("configResponse", "values" to mapOf("accessories" to accessories()))
                log("Route sharing: navigation configuration requested")
            }
            "registerRequest" -> {
                val iids = data as? List<*> ?: return emptyList()
                require(iids.size <= 64)
                val present = linkedMapOf<Long, Any?>()
                val errors = linkedMapOf<Long, Long>()
                for (iid in iids) {
                    if (iid !is Long) continue
                    if (characteristics.any { it.iid == iid }) followed.add(iid)
                    if (values.containsKey(iid)) present[iid] = values[iid] else errors[iid] = NO_VALUE
                }
                answer("registerResponse", "values" to present, "errors" to errors)
            }
            "writeRequest" -> {
                val written = data as? Map<*, *> ?: return emptyList()
                require(written.size <= 64)
                val errors = linkedMapOf<Long, Long>()
                val changes = linkedMapOf<Long, Any?>()
                for ((key, value) in written) {
                    val iid = key as? Long ?: continue
                    val c = characteristics.find { it.iid == iid }
                    if (c == null || c.initial != null || !c.accepts(value)) {
                        errors[iid] = NO_VALUE
                        continue
                    }
                    values[iid] = value
                    errors[iid] = 0
                    if (iid in followed) changes[iid] = value
                    // Do not place coordinates, destinations or raw values in routine diagnostics.
                    log("Route sharing: phone wrote iid=$iid field=${c.name}" +
                        if (iid == 260L) " legs=${(value as? List<*>)?.size ?: 0}" else "")
                }
                answer("writeResponse", "errors" to errors)
                if (changes.isNotEmpty()) answers.add(mapOf("command" to "updateNotify", "values" to changes))
            }
            "controlRequest" -> {
                val controls = data as? Map<*, *> ?: return emptyList()
                require(controls.size <= 64)
                answer("controlResponse", "values" to emptyMap<Long, Any?>(),
                    "errors" to controls.keys.filterIsInstance<Long>().associateWith { -6735L })
            }
            else -> log("Route sharing: unsupported command")
        }
        return answers.map { response -> wrap(response).also { capture("tx", it) } }
    }

    private fun capture(direction: String, bytes: ByteArray) {
        val dir = captureDirectory ?: return
        if (captureCount >= 512 || capturedBytes + bytes.size > 32 * 1024 * 1024) return
        try {
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "%04d-%s.opack".format(captureCount++, direction)).writeBytes(bytes)
            capturedBytes += bytes.size
        } catch (error: Exception) { log("Route sharing: capture failed ${error.javaClass.simpleName}") }
    }

    private data class Characteristic(val iid: Long, val kind: Long, val name: String, val format: Int,
        val max: Long? = null, val initial: Any? = null, val mutable: Boolean = true) {
        fun accepts(value: Any?): Boolean = when (format) {
            0 -> value == null || value is Boolean
            1, 3 -> (value == null && format == 3) || (value is Long && value >= 0 && value <= (max ?: 0xffffffffL))
            10 -> value == null || (value is String && value.toByteArray(Charsets.UTF_8).size <= 65535)
            12 -> value == null || value is Map<*, *>
            13 -> value == null || value is List<*>
            else -> false
        }
        fun describe(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "iid" to iid, "type" to kind, "format" to format, "mutable" to mutable,
            "writable" to (initial == null), "oemWritable" to (initial != null), "priority" to 1L,
        ).apply {
            if (format == 1 || format == 3) {
                put("minimumValue", 0L); put("maximumValue", max ?: 0xffffffffL); put("stepValue", 1L)
            }
            if (format in 10..13) put("maximumLength", 65535L)
            if (initial == null && format != 1) put("supportsInvalid", true)
            initial?.let { put("initialValue", it) }
        }
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"
        const val HIGH_UUID = "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"
        const val NORMAL_UUID = "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"
        private const val NO_VALUE = -6727L
        private val characteristics = listOf(
            Characteristic(258, 0x45000103, "applicationEnabled", 0),
            Characteristic(259, 0x45000112, "currentLegIndex", 3),
            Characteristic(260, 0x45000109, "legs", 13),
            Characteristic(261, 0x4500010d, "sharingState", 1, 4),
            Characteristic(262, 0x45000104, "userEnabled", 0),
            Characteristic(263, 0x4500010e, "userVisibleReason", 10, initial = "HUD route display", mutable = false),
            Characteristic(264, 0x45000105, "vehicleEnabled", 0, initial = true),
            Characteristic(266, 0x45000108, "destination", 12),
            Characteristic(267, 0x45000106, "geodeticSystem", 1, 2),
            Characteristic(268, 0x45000107, "origin", 12),
            Characteristic(269, 0x45000110, "rerouteReason", 1, 6),
            Characteristic(270, 0x45000101, "routeState", 1, 255),
            Characteristic(271, 0x45000102, "userVisibleApplicationName", 10),
            Characteristic(273, 0x4500010c, "routeSource", 1, 5, 0L),
            Characteristic(274, 0x4500010a, "userVisibleNavigationSystemName", 10, initial = "DiPlay", mutable = false),
            Characteristic(275, 0x45000111, "userVisibleVehicleBrand", 10, initial = "DiPlay", mutable = false),
        )
        private fun accessories(): List<Map<String, Any?>> {
            fun service(iid: Long, kind: Long, range: LongRange) = mapOf("iid" to iid, "type" to kind,
                "characteristics" to characteristics.filter { it.iid in range }.map { it.describe() })
            return listOf(mapOf("iid" to 256L, "type" to 0x0e000002L, "version" to "1.0", "services" to listOf(
                service(257, 0x1e000104, 258L..264L), service(265, 0x1e000102, 266L..271L),
                service(272, 0x1e000103, 273L..275L))))
        }
        fun info(): Map<String, Any?> = mapOf("protocolVersion" to "1.0", "pluginCount" to 1L,
            "pluginMapping" to mapOf("DiPlay" to 0L), "pluginConfigs" to listOf(
                mapOf("name" to "DiPlay", "pluginID" to 0L, "accessories" to accessories())))
        internal fun wrap(message: Map<String, Any?>): ByteArray = OpackCodec.encode(
            mapOf("pluginID" to 0L, "pluginData" to OpackCodec.encode(message)))
    }
}
