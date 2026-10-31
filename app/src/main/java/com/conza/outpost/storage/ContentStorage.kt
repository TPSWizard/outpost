package com.conza.outpost.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.conza.outpost.catalog.ResourceKind
import java.io.File

/** A place content can live: internal shared storage or a removable SD card. */
data class StorageVolume(val index: Int, val root: File, val removable: Boolean, val freeBytes: Long, val totalBytes: Long) {
    val label: String get() = if (removable) "SD card" else "Phone storage"
}

/**
 * Where content lives on disk. Uses app-specific external storage, which needs no
 * permissions and is the largest volume on most phones. If an SD card is present the
 * user can send new downloads there; content already on either volume keeps working
 * because readers scan every volume.
 */
class ContentStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun volumes(): List<StorageVolume> =
        context.getExternalFilesDirs(null).mapIndexedNotNull { i, f ->
            if (f == null) return@mapIndexedNotNull null
            val removable = runCatching { Environment.isExternalStorageRemovable(f) }.getOrDefault(i > 0)
            val st = runCatching { StatFs(f.path) }.getOrNull()
            StorageVolume(i, f, removable, st?.availableBytes ?: 0, st?.totalBytes ?: 0)
        }

    var selectedVolume: Int
        get() = prefs.getInt("storage_volume", 0)
        set(v) = prefs.edit().putInt("storage_volume", v).apply()

    /** Root for new downloads. */
    val root: File
        get() = context.getExternalFilesDirs(null).getOrNull(selectedVolume)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir

    /** Every root that may hold content (selected volume first). */
    fun allRoots(): List<File> =
        (listOf(root) + context.getExternalFilesDirs(null).filterNotNull()).distinctBy { it.absolutePath }

    fun dir(kind: ResourceKind): File = File(root, kind.folder).apply { mkdirs() }

    /** All folders for [kind] across volumes, for readers that scan for files. */
    fun allDirs(kind: ResourceKind): List<File> = allRoots().map { File(it, kind.folder) }.filter { it.isDirectory }

    fun namedDir(name: String): File = File(root, name).apply { mkdirs() }

    fun fileFor(kind: ResourceKind, fileName: String): File = File(dir(kind), fileName)

    fun partialFileFor(kind: ResourceKind, fileName: String): File = File(dir(kind), "$fileName.part")

    fun freeBytes(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    fun totalBytes(): Long = runCatching { StatFs(root.path).totalBytes }.getOrDefault(0L)

    fun usedByContentBytes(): Long =
        allRoots().sumOf { r -> r.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}
