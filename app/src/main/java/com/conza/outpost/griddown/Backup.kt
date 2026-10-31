package com.conza.outpost.griddown

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.conza.outpost.MainActivity
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.container
import com.conza.outpost.storage.FileFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything the user typed (household, supplies, notes, chats, pins, checklists,
 * radio log, settings) to a single .zip on an SD card or USB drive via the system file picker.
 * Downloaded content (maps, ZIMs, models) isn't included: it's large and can be copied as files.
 */
object Backup {
    private const val DB = "outpost.db"
    private val PREFS = listOf("settings")

    suspend fun export(context: Context, dest: Uri): Int = withContext(Dispatchers.IO) {
        val c = context.container
        // Fold the write-ahead log into the main file so one copy is complete.
        c.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        var n = 0
        context.contentResolver.openOutputStream(dest)!!.use { os ->
            ZipOutputStream(os).use { zip ->
                fun add(name: String, f: File) { if (f.isFile) { zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++ } }
                add("db/$DB", context.getDatabasePath(DB))
                PREFS.forEach { add("prefs/$it.xml", File(context.applicationInfo.dataDir, "shared_prefs/$it.xml")) }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("Outpost backup. Restore from Toolkit → Backup & verify → Restore.\n".toByteArray()); zip.closeEntry()
            }
        }
        n
    }

    /** Replaces the database and settings with the backup's, then restarts the app. */
    suspend fun restore(context: Context, src: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        context.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (name.contains("..")) continue
                    if (name == "db/$DB" || (name.startsWith("prefs/") && name.removePrefix("prefs/").removeSuffix(".xml") in PREFS)) {
                        File(tmp, name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                        if (name == "db/$DB") hasDb = true
                    }
                }
            }
        }
        require(hasDb) { "That file isn't an Outpost backup" }
        require(File(tmp, "db/$DB").inputStream().use { val b = ByteArray(15); var n = 0; while (n < 15) { val r = it.read(b, n, 15 - n); if (r < 0) break; n += r }; String(b, 0, n) } == "SQLite format 3") { "The backup's database is damaged" }
        context.container.db.close()
        val dbFile = context.getDatabasePath(DB)
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        File(tmp, "db/$DB").copyTo(dbFile, overwrite = true)
        PREFS.forEach { p -> File(tmp, "prefs/$p.xml").takeIf { it.isFile }?.copyTo(File(context.applicationInfo.dataDir, "shared_prefs/$p.xml"), overwrite = true) }
        tmp.deleteRecursively()
        restart(context)
    }

    private fun restart(context: Context) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    data class Check(val title: String, val path: String, val ok: Boolean, val problem: String?)

    /** Checks every installed download: file present, expected size, and a valid header for its type. */
    suspend fun verify(context: Context): List<Check> = withContext(Dispatchers.IO) {
        val dao = context.container.db.installed()
        ResourceKind.entries.flatMap { dao.byKind(it.name) }
            .map { r ->
                val f = File(r.filePath)
                val kind = runCatching { ResourceKind.valueOf(r.kind) }.getOrNull()
                val problem = when {
                    !f.exists() -> "missing (SD card removed or file deleted)"
                    f.isFile && r.sizeBytes > 0 && f.length() != r.sizeBytes -> "size changed (%d MB, expected %d MB): possibly damaged".format(f.length() shr 20, r.sizeBytes shr 20)
                    f.isFile && kind != null && !FileFormats.looksValid(kind, f) -> "not a valid ${kind.name.lowercase()} file"
                    !f.canRead() -> "can't be read"
                    else -> null
                }
                Check(r.title, r.filePath, problem == null, problem)
            }
    }
}
