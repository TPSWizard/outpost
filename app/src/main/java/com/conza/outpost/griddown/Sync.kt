package com.conza.outpost.griddown

import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.MapMarker
import com.conza.outpost.data.MeshMessage
import com.conza.outpost.data.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class SyncPin(val uuid: String, val name: String, val lat: Double, val lon: Double, val notes: String = "", val color: String = "#E4572E", val createdAt: Long = 0)
@Serializable data class SyncItem(
    val uuid: String, val category: String, val name: String, val quantity: Double, val unit: String = "",
    val litersPerUnit: Double? = null, val caloriesPerUnit: Double? = null, val dailyUse: Double? = null,
    val expiry: String? = null, val notes: String = "", val updatedAt: Long = 0,
)
@Serializable data class SyncNote(val title: String, val body: String, val updatedAt: Long = 0)
@Serializable data class SyncMessage(val uuid: String, val at: Long, val from: String, val text: String)

/** What one phone sends another over Nearby Connections. JSON, versioned, no personal data beyond what the user shares. */
@Serializable
data class SyncBundle(
    val version: Int = 1,
    val device: String,
    val sentAt: Long,
    val pins: List<SyncPin> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val notes: List<SyncNote> = emptyList(),
    val messages: List<SyncMessage> = emptyList(),
)

object Sync {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(b: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), b)
    fun decode(s: String): SyncBundle = json.decodeFromString(SyncBundle.serializer(), s)

    fun bundle(device: String, pins: List<MapMarker>, items: List<InventoryItem>, notes: List<Note>, messages: List<MeshMessage>, now: Long = System.currentTimeMillis()) = SyncBundle(
        device = device, sentAt = now,
        pins = pins.map { SyncPin(it.uuid.ifBlank { "pin-${it.id}-${it.createdAt}" }, it.name, it.lat, it.lon, it.notes, it.color, it.createdAt) },
        items = items.map { SyncItem(it.uuid, it.category, it.name, it.quantity, it.unit, it.litersPerUnit, it.caloriesPerUnit, it.dailyUse, it.expiry, it.notes, it.updatedAt) },
        notes = notes.map { SyncNote(it.title, it.body, it.updatedAt) },
        messages = messages.map { SyncMessage(it.uuid, it.at, it.from, it.text) },
    )

    data class Plan(
        val pins: List<MapMarker>,
        val newItems: List<InventoryItem>,
        val updatedItems: List<InventoryItem>,
        val notes: List<Note>,
        val messages: List<MeshMessage>,
    ) {
        val summary: String get() = listOfNotNull(
            pins.size.takeIf { it > 0 }?.let { "$it pin${if (it == 1) "" else "s"}" },
            (newItems.size + updatedItems.size).takeIf { it > 0 }?.let { "$it supply update${if (it == 1) "" else "s"}" },
            notes.size.takeIf { it > 0 }?.let { "$it note${if (it == 1) "" else "s"}" },
            messages.size.takeIf { it > 0 }?.let { "$it message${if (it == 1) "" else "s"}" },
        ).joinToString(", ").ifEmpty { "nothing new" }
    }

    /**
     * Merge rules: pins and messages are added once (by uuid). Supplies merge only between
     * family phones ([mergeSupplies]); the newer edit of the same item wins. Notes arrive as new
     * notes titled "From <device>: …" unless an identical note is already here.
     */
    fun plan(
        b: SyncBundle,
        localPins: List<MapMarker>,
        localItems: List<InventoryItem>,
        localNotes: List<Note>,
        localMessageUuids: Set<String>,
        mergeSupplies: Boolean,
    ): Plan {
        val pinIds = localPins.map { it.uuid }.toSet()
        val pins = b.pins.filter { it.uuid !in pinIds && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }.distinctBy { it.uuid }
            .map { MapMarker(name = it.name, lat = it.lat, lon = it.lon, color = it.color, notes = it.notes.ifBlank { "From ${b.device}" }, createdAt = it.createdAt, uuid = it.uuid) }
        val byUuid = localItems.associateBy { it.uuid }
        val newItems = mutableListOf<InventoryItem>(); val updated = mutableListOf<InventoryItem>()
        if (mergeSupplies) for (i in b.items.distinctBy { it.uuid }) {
            val mine = byUuid[i.uuid]
            val incoming = InventoryItem(mine?.id ?: 0, i.uuid, i.category, i.name, i.quantity, i.unit, i.litersPerUnit, i.caloriesPerUnit, i.dailyUse, i.expiry, i.notes, i.updatedAt)
            when {
                mine == null -> newItems += incoming
                i.updatedAt > mine.updatedAt -> updated += incoming
            }
        }
        val have = localNotes.map { it.body.trim() }.toSet()
        val notes = b.notes.filter { it.body.isNotBlank() && it.body.trim() !in have }
            .map { Note(title = "From ${b.device}: ${it.title.ifBlank { "Note" }}", body = it.body, updatedAt = it.updatedAt) }
        val messages = b.messages.filter { it.uuid !in localMessageUuids }.distinctBy { it.uuid }
            .map { MeshMessage(uuid = it.uuid, at = it.at, transport = "nearby", from = it.from, text = it.text, outgoing = false) }
        return Plan(pins, newItems, updated, notes, messages)
    }
}
