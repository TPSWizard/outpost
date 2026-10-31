package com.conza.outpost.medical

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.conza.outpost.container
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.download.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Builds the offline drug database from openFDA's bulk drug-label export, one part at a time
 * (≈130 MB zips, 14 parts, ~260k labels). Replaces NOMAD's download_drug_data + ingest jobs.
 *
 * Resumable: finished parts are recorded in the database being built, so an interrupted build
 * picks up at the next part. The finished file only replaces the old one at the very end.
 */
class DrugIngestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val c get() = applicationContext.container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val drugs = c.drugs
        setForeground(info("Preparing drug reference…", 0, 0))
        try {
            val manifest = fetchManifest()
            val building = drugs.buildingFile
            val db = SQLiteDatabase.openOrCreateDatabase(building, null)
            try {
                DrugDatabase.SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
                db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=OFF")
                val prevExport = meta(db, "export_date")
                if (prevExport != null && prevExport != manifest.exportDate) {
                    db.close(); building.delete()
                    return@withContext Result.retry()
                }
                setMeta(db, "export_date", manifest.exportDate)
                val done = meta(db, "done_parts")?.split(",")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

                manifest.parts.forEachIndexed { i, url ->
                    coroutineContext.ensureActive()
                    if (url in done) return@forEachIndexed
                    val zip = File(drugs.dir, "part-${i + 1}.zip")
                    download(url, zip, i, manifest.parts.size)
                    ingest(db, zip, i, manifest.parts.size)
                    zip.delete()
                    done += url
                    setMeta(db, "done_parts", done.joinToString(","))
                }
                setMeta(db, "complete", "1")
            } finally {
                if (db.isOpen) db.close()
            }

            // Swap in the new database.
            drugs.close()
            drugs.file.delete()
            if (!drugs.buildingFile.renameTo(drugs.file)) error("Couldn't finalize the drug database")
            c.db.installed().upsert(
                InstalledResource(
                    resourceId = DrugDatabase.RESOURCE_ID, kind = "DATA", version = manifest.exportDate,
                    title = "FDA drug labels", filePath = drugs.file.absolutePath, sizeBytes = drugs.file.length(),
                    installedAt = System.currentTimeMillis(),
                )
            )
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 6) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network error")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private data class Manifest(val exportDate: String, val parts: List<String>, val totalRecords: Long)

    private fun fetchManifest(): Manifest {
        val req = Request.Builder().url("https://api.fda.gov/download.json").build()
        c.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("openFDA manifest HTTP ${r.code}")
            val label = Json.parseToJsonElement(r.body!!.string()).jsonObject["results"]!!.jsonObject["drug"]!!
                .jsonObject["label"]!!.jsonObject
            return Manifest(
                exportDate = label["export_date"]!!.jsonPrimitive.content,
                parts = label["partitions"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content },
                totalRecords = label["total_records"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            )
        }
    }

    private suspend fun download(url: String, dest: File, part: Int, parts: Int) {
        var attempt = 0
        while (true) {
            try {
                val have = if (dest.exists()) dest.length() else 0L
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                c.http.newCall(req).execute().use { r ->
                    if (r.code == 416) return
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val append = r.code == 206
                    val body = r.body ?: throw IOException("Empty body")
                    val total = (if (append) have else 0L) + body.contentLength()
                    var written = if (append) have else 0L
                    var last = 0L
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - last > 1000) {
                                last = now
                                val pct = if (total > 0) (written * 100 / total).toInt() else 0
                                report("Downloading part ${part + 1} of $parts", part, parts, pct, 0)
                            }
                        }
                    }
                    if (total > 0 && written < total) throw IOException("Connection closed early")
                }
                return
            } catch (e: IOException) {
                if (++attempt >= 5) throw e
                kotlinx.coroutines.delay(5000L * attempt)
            }
        }
    }

    private suspend fun ingest(db: SQLiteDatabase, zip: File, part: Int, parts: Int) {
        val insert = db.compileStatement(
            "INSERT OR IGNORE INTO labels(set_id,brand,generic,manufacturer,route,product_type,purpose,active,indications," +
                "dosage,warnings,boxed,interactions,contraindications,when_using,stop_use,effective) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        val fts = db.compileStatement("INSERT INTO labels_fts(docid, name, indications) VALUES (?,?,?)")
        var count = 0
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { zin ->
            zin.nextEntry ?: throw IOException("Empty archive")
            val reader = JsonReader(InputStreamReader(zin, Charsets.UTF_8))
            reader.beginObject()
            db.beginTransaction()
            try {
                while (reader.hasNext()) {
                    if (reader.nextName() != "results") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val r = readRecord(reader)
                        if (r.setId.isNullOrBlank() || (r.brand == null && r.generic == null)) continue
                        insert.clearBindings()
                        val values = listOf(r.setId, r.brand, r.generic, r.manufacturer, r.route, r.productType,
                            r.sec("purpose"), r.sec("active_ingredient"), r.sec("indications_and_usage"),
                            r.sec("dosage_and_administration"), r.sec("warnings"), r.sec("boxed_warning"),
                            r.sec("drug_interactions"), r.sec("contraindications"), r.sec("when_using"),
                            r.sec("stop_use"), r.effective)
                        values.forEachIndexed { i, v -> if (v == null) insert.bindNull(i + 1) else insert.bindString(i + 1, v) }
                        val rowId = insert.executeInsert()
                        if (rowId > 0) {
                            fts.clearBindings()
                            fts.bindLong(1, rowId)
                            fts.bindString(2, listOfNotNull(r.brand, r.generic, r.sec("active_ingredient")?.take(300)).joinToString(" "))
                            fts.bindString(3, listOfNotNull(r.sec("indications_and_usage"), r.sec("purpose")).joinToString(" ").take(4000))
                            fts.executeInsert()
                        }
                        if (++count % 1000 == 0) {
                            db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction()
                            coroutineContext.ensureActive()
                            report("Building part ${part + 1} of $parts", part, parts, 100, count)
                        }
                    }
                    reader.endArray()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private class Rec {
        var setId: String? = null
        var brand: String? = null
        var generic: String? = null
        var manufacturer: String? = null
        var route: String? = null
        var productType: String? = null
        var effective: String? = null
        val sections = HashMap<String, String>()
        fun sec(k: String) = sections[k]
    }

    private val wanted = setOf(
        "purpose", "active_ingredient", "indications_and_usage", "dosage_and_administration", "warnings", "boxed_warning",
        "drug_interactions", "contraindications", "when_using", "stop_use",
    )

    private fun readRecord(reader: JsonReader): Rec {
        val r = Rec()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "set_id" -> r.setId = reader.nextString()
                "effective_time" -> r.effective = reader.nextString()
                "openfda" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "brand_name" -> r.brand = strings(reader).firstOrNull()?.take(255)
                            "generic_name" -> r.generic = strings(reader).joinToString(", ").take(512).ifBlank { null }
                            "manufacturer_name" -> r.manufacturer = strings(reader).firstOrNull()?.take(255)
                            "route" -> r.route = strings(reader).joinToString(", ").take(255).ifBlank { null }
                            "product_type" -> r.productType = strings(reader).firstOrNull()?.take(64)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                in wanted -> r.sections[name] = strings(reader).joinToString("\n").trim().take(20_000).ifBlank { null } ?: ""
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        r.sections.entries.removeAll { it.value.isBlank() }
        return r
    }

    private fun strings(reader: JsonReader): List<String> {
        if (reader.peek() == JsonToken.STRING) return listOf(reader.nextString())
        if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return emptyList() }
        val out = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) if (reader.peek() == JsonToken.STRING) out += reader.nextString() else reader.skipValue()
        reader.endArray()
        return out
    }

    private fun meta(db: SQLiteDatabase, k: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMeta(db: SQLiteDatabase, k: String, v: String) =
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", arrayOf(k, v))

    private suspend fun report(text: String, part: Int, parts: Int, pct: Int, records: Int) {
        setProgress(workDataOf(KEY_PART to part + 1, KEY_PARTS to parts, KEY_PCT to pct, KEY_RECORDS to records, KEY_TEXT to text))
        setForeground(info(text, part * 100 + pct, parts * 100))
    }

    private fun info(text: String, progress: Int, max: Int): ForegroundInfo {
        DownloadWorker.ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Drug reference")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(max, progress, max == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4242, n)
    }

    companion object {
        const val UNIQUE = "drug-ingest"
        const val KEY_PART = "part"
        const val KEY_PARTS = "parts"
        const val KEY_PCT = "pct"
        const val KEY_RECORDS = "records"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"

        fun start(context: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<DrugIngestWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}
