package com.conza.outpost.griddown

import java.io.ByteArrayOutputStream

/**
 * Just enough of the Meshtastic protobufs (mesh.proto / portnums.proto) to send and receive
 * text messages and positions over BLE, hand-rolled so we don't need protobuf or codegen.
 *
 * ToRadio { packet = 1; want_config_id = 3 }
 * FromRadio { id = 1; packet = 2; my_info = 3; node_info = 4; config_complete_id = 7 }
 * MeshPacket { from = 1 (fixed32); to = 2 (fixed32); channel = 3; decoded = 4; id = 6 (fixed32); want_ack = 10 }
 * Data { portnum = 1; payload = 2 }        TEXT_MESSAGE_APP = 1, POSITION_APP = 3, NODEINFO_APP = 4
 * Position { latitude_i = 1 (sfixed32, 1e-7°); longitude_i = 2 (sfixed32); altitude = 3 }
 * NodeInfo { num = 1; user = 2 }   User { id = 1; long_name = 2; short_name = 3 }   MyNodeInfo { my_node_num = 1 }
 */
object MeshProto {
    const val BROADCAST = 0xFFFFFFFFL
    const val TEXT_MESSAGE_APP = 1
    const val POSITION_APP = 3
    const val NODEINFO_APP = 4

    // ---- writer ----
    class Writer {
        private val out = ByteArrayOutputStream()
        private fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun uint(field: Int, v: Long) = apply { tag(field, 0); varint(v) }
        fun bool(field: Int, v: Boolean) = apply { if (v) uint(field, 1) }
        fun fixed32(field: Int, v: Long) = apply { tag(field, 5); for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun message(field: Int, w: Writer) = bytes(field, w.toByteArray())
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    // ---- reader ----
    /** Field number → raw values (varint as Long, fixed32 as Long, length-delimited as ByteArray). */
    fun fields(b: ByteArray): Map<Int, List<Any>> {
        val m = mutableMapOf<Int, MutableList<Any>>()
        var i = 0
        fun varint(): Long { var shift = 0; var r = 0L; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl shift); if (x < 0x80) return r; shift += 7; require(shift < 64) } }
        while (i < b.size) {
            val key = varint()
            val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            val v: Any = when (wire) {
                0 -> varint()
                1 -> { var r = 0L; for (k in 0 until 8) r = r or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; r }
                2 -> { val n = varint().toInt(); require(n >= 0 && i + n <= b.size) { "bad length" }; b.copyOfRange(i, i + n).also { i += n } }
                5 -> { var r = 0L; for (k in 0 until 4) r = r or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; r }
                else -> error("unsupported wire type $wire")
            }
            m.getOrPut(field) { mutableListOf() } += v
        }
        return m
    }

    private fun Map<Int, List<Any>>.long(f: Int) = this[f]?.firstOrNull() as? Long
    private fun Map<Int, List<Any>>.bytes(f: Int) = this[f]?.firstOrNull() as? ByteArray
    private fun Map<Int, List<Any>>.str(f: Int) = bytes(f)?.toString(Charsets.UTF_8)

    // ---- ToRadio ----
    fun wantConfig(id: Long): ByteArray = Writer().uint(3, id).toByteArray()

    fun text(text: String, packetId: Long, to: Long = BROADCAST, channel: Int = 0): ByteArray {
        val data = Writer().uint(1, TEXT_MESSAGE_APP.toLong()).string(2, text)
        val packet = Writer().fixed32(2, to).uint(3, channel.toLong()).message(4, data).fixed32(6, packetId).bool(10, to != BROADCAST)
        return Writer().message(1, packet).toByteArray()
    }

    fun position(latitude: Double, longitude: Double): ByteArray =
        Writer().fixed32(1, (latitude * 1e7).toLong() and 0xFFFFFFFFL).fixed32(2, (longitude * 1e7).toLong() and 0xFFFFFFFFL).toByteArray()

    // ---- FromRadio ----
    sealed interface FromRadio {
        data class Text(val from: Long, val to: Long, val id: Long, val text: String) : FromRadio
        data class Position(val from: Long, val lat: Double, val lon: Double) : FromRadio
        data class Node(val num: Long, val longName: String, val shortName: String) : FromRadio
        data class MyInfo(val nodeNum: Long) : FromRadio
        data class ConfigComplete(val id: Long) : FromRadio
        data object Other : FromRadio
    }

    private fun sfixed32(v: Long): Int = v.toInt()

    fun decodePosition(payload: ByteArray): Pair<Double, Double>? {
        val f = fields(payload)
        val lat = f.long(1)?.let { sfixed32(it) / 1e7 } ?: return null
        val lon = f.long(2)?.let { sfixed32(it) / 1e7 } ?: return null
        if (lat == 0.0 && lon == 0.0) return null // "no fix"
        return lat to lon
    }

    fun decodeFromRadio(b: ByteArray): FromRadio = runCatching {
        val f = fields(b)
        f.bytes(2)?.let { pkt ->
            val p = fields(pkt)
            val from = p.long(1) ?: 0; val to = p.long(2) ?: BROADCAST; val id = p.long(6) ?: 0
            val data = p.bytes(4)?.let(::fields) ?: return@runCatching FromRadio.Other
            val payload = data.bytes(2) ?: ByteArray(0)
            return@runCatching when (data.long(1)?.toInt()) {
                TEXT_MESSAGE_APP -> FromRadio.Text(from, to, id, payload.toString(Charsets.UTF_8))
                POSITION_APP -> decodePosition(payload)?.let { (la, lo) -> FromRadio.Position(from, la, lo) } ?: FromRadio.Other
                NODEINFO_APP -> fields(payload).let { u -> FromRadio.Node(from, u.str(2).orEmpty(), u.str(3).orEmpty()) }
                else -> FromRadio.Other
            }
        }
        f.bytes(3)?.let { return@runCatching FromRadio.MyInfo(fields(it).long(1) ?: 0) }
        f.bytes(4)?.let { ni ->
            val n = fields(ni); val user = n.bytes(2)?.let(::fields)
            return@runCatching FromRadio.Node(n.long(1) ?: 0, user?.str(2).orEmpty(), user?.str(3).orEmpty())
        }
        f.long(7)?.let { return@runCatching FromRadio.ConfigComplete(it) }
        FromRadio.Other
    }.getOrDefault(FromRadio.Other)

    /** Meshtastic shows nodes as "!a1b2c3d4" when no name is known. */
    fun nodeId(num: Long) = "!%08x".format(num and 0xFFFFFFFFL)
}
