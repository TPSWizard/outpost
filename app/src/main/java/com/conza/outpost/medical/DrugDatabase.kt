package com.conza.outpost.medical

import android.database.sqlite.SQLiteDatabase
import com.conza.outpost.storage.ContentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DrugHit(val id: Long, val brand: String?, val generic: String?, val labelCount: Int, val productType: String?) {
    val title: String get() = brand?.takeIf { it.isNotBlank() } ?: generic ?: "Unnamed product"
    val subtitle: String get() = if (!brand.isNullOrBlank() && !generic.isNullOrBlank() && !brand.equals(generic, true)) generic else ""
}

data class DrugLabel(
    val id: Long,
    val setId: String,
    val brand: String?,
    val generic: String?,
    val manufacturer: String?,
    val route: String?,
    val productType: String?,
    val effective: String?,
    val sections: List<Pair<String, String>>, // heading → text, in display order, non-empty only
    val boxedWarning: String?,
    val interactionsText: String,
) {
    val title: String get() = brand?.takeIf { it.isNotBlank() } ?: generic ?: "Unnamed product"
}

data class DrugDbInfo(val exportDate: String?, val records: Long, val sizeBytes: Long)

/**
 * Offline FDA drug-label reference (NOMAD's Drug Reference v1). The database is built on the
 * phone from openFDA's bulk export by [DrugIngestWorker] and lives next to other content.
 * Search uses SQLite FTS4 over names and indications ("what it treats").
 */
class DrugDatabase(private val storage: ContentStorage) {
    @Volatile private var db: SQLiteDatabase? = null

    val dir: File get() = storage.namedDir(DIR)
    val file: File get() = File(dir, FILE)
    val buildingFile: File get() = File(dir, BUILDING)

    /** Finds the finished database on any volume. */
    private fun locate(): File? = storage.allRoots().map { File(File(it, DIR), FILE) }.firstOrNull { it.exists() }

    val installed: Boolean get() = locate() != null

    @Synchronized
    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val f = locate() ?: return null
        return runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()?.also { db = it }
    }

    @Synchronized
    fun close() { db?.close(); db = null }

    suspend fun info(): DrugDbInfo? = withContext(Dispatchers.IO) {
        val d = open() ?: return@withContext null
        val export = meta(d, "export_date")
        val n = d.rawQuery("SELECT COUNT(*) FROM labels", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        DrugDbInfo(export, n, locate()?.length() ?: 0)
    }

    private fun meta(d: SQLiteDatabase, key: String): String? =
        d.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    suspend fun search(query: String, byIndication: Boolean, limit: Int = 60): List<DrugHit> = withContext(Dispatchers.IO) {
        val d = open() ?: return@withContext emptyList()
        val match = ftsQuery(query, if (byIndication) "indications" else "name") ?: return@withContext emptyList()
        val first = query.trim().lowercase().replace("'", "")
        val sql = """
            SELECT MIN(l.id), l.brand, l.generic, COUNT(*) AS n, MAX(l.product_type)
            FROM labels l
            WHERE l.id IN (SELECT docid FROM labels_fts WHERE labels_fts MATCH ?)
            GROUP BY lower(coalesce(l.brand,'')), lower(coalesce(l.generic,''))
            ORDER BY (lower(coalesce(l.generic,'')) LIKE ? OR lower(coalesce(l.brand,'')) LIKE ?) DESC, n DESC
            LIMIT $limit
        """.trimIndent()
        d.rawQuery(sql, arrayOf(match, "$first%", "$first%")).use { c ->
            buildList {
                while (c.moveToNext()) add(DrugHit(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4)))
            }
        }
    }

    suspend fun label(id: Long): DrugLabel? = withContext(Dispatchers.IO) {
        val d = open() ?: return@withContext null
        d.rawQuery("SELECT * FROM labels WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return@withContext null
            fun s(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
            val sections = listOf(
                "Purpose" to s("purpose"),
                "Active ingredients" to s("active"),
                "Uses / indications" to s("indications"),
                "Dosage and administration" to s("dosage"),
                "Do not use (contraindications)" to s("contraindications"),
                "Drug interactions" to s("interactions"),
                "Warnings" to s("warnings"),
                "When using this product" to s("when_using"),
                "Stop use and ask a doctor if" to s("stop_use"),
            ).mapNotNull { (h, t) -> t?.let { h to cleanSection(it) } }
            DrugLabel(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                setId = s("set_id").orEmpty(),
                brand = s("brand"), generic = s("generic"), manufacturer = s("manufacturer"),
                route = s("route"), productType = s("product_type"), effective = s("effective"),
                sections = sections,
                boxedWarning = s("boxed")?.let(::cleanSection),
                interactionsText = listOfNotNull(s("interactions"), s("contraindications"), s("warnings")).joinToString("\n"),
            )
        }
    }

    /** Other labels (manufacturers/forms) for the same brand + generic pair. */
    suspend fun variants(hit: DrugLabel, limit: Int = 30): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val d = open() ?: return@withContext emptyList()
        d.rawQuery(
            "SELECT id, coalesce(manufacturer,'Unknown manufacturer') || ' · ' || coalesce(route,'') FROM labels " +
                "WHERE lower(coalesce(brand,'')) = lower(?) AND lower(coalesce(generic,'')) = lower(?) LIMIT $limit",
            arrayOf(hit.brand.orEmpty(), hit.generic.orEmpty()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
    }

    fun delete() {
        close()
        storage.allRoots().forEach { r -> File(r, DIR).deleteRecursively() }
    }

    companion object {
        const val DIR = "medical"
        const val FILE = "drug-labels.db"
        const val BUILDING = "drug-labels.building.db"
        const val RESOURCE_ID = "openfda-drug-labels"

        /** FTS4 query: every word must match as a prefix in [column]. Null if nothing searchable. */
        fun ftsQuery(input: String, column: String): String? {
            val words = input.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.take(8)
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$column:$it*" }
        }

        /** Labels often start with an all-caps heading that repeats the section name; drop it. */
        fun cleanSection(t: String): String {
            val trimmed = t.trim()
            val m = Regex("^([A-Z][A-Z &/(),-]{3,60})[:\\s]+(?=[A-Z][a-z]|\\d|•|-)").find(trimmed)
            return (if (m != null) trimmed.substring(m.range.last + 1) else trimmed).trim()
        }

        const val SCHEMA = """
            CREATE TABLE IF NOT EXISTS labels(
              id INTEGER PRIMARY KEY, set_id TEXT UNIQUE NOT NULL,
              brand TEXT, generic TEXT, manufacturer TEXT, route TEXT, product_type TEXT,
              purpose TEXT, active TEXT, indications TEXT, dosage TEXT, warnings TEXT, boxed TEXT,
              interactions TEXT, contraindications TEXT, when_using TEXT, stop_use TEXT, effective TEXT);
            CREATE VIRTUAL TABLE IF NOT EXISTS labels_fts USING fts4(content="", name, indications);
            CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
        """
    }
}
