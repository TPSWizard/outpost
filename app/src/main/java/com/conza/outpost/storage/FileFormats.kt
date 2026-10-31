package com.conza.outpost.storage

import com.conza.outpost.catalog.ResourceKind
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cheap integrity checks so a captive-portal HTML page never gets "installed" as a map. */
object FileFormats {
    private const val ZIM_MAGIC = 72173914 // 0x044D495A, little-endian, per the ZIM spec

    fun looksValid(kind: ResourceKind, file: File): Boolean = when (kind) {
        ResourceKind.MAP -> PmtilesHeader.read(file) != null
        ResourceKind.ZIM -> readZimMagic(file) == ZIM_MAGIC
        ResourceKind.MODEL -> startsWith(file, "GGUF".toByteArray())
        ResourceKind.TOOL -> startsWith(file, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK\3\4
    }

    private fun startsWith(file: File, magic: ByteArray): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(magic.size)
            raf.readFully(b)
            b.contentEquals(magic)
        }
    }.getOrDefault(false)

    /**
     * Extract a tool .zip into [dest], dropping a single shared top-level folder (e.g. "dist/")
     * so the entry page sits at the root. Guards against zip-slip paths.
     */
    fun extractZip(zip: File, dest: File) {
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
        val tops = names.map { it.substringBefore('/') }.toSet()
        val strip = if (tops.size == 1 && names.all { it.contains('/') }) tops.first() + "/" else ""
        dest.deleteRecursively()
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        java.util.zip.ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val rel = e.name.removePrefix(strip)
                if (rel.isEmpty()) continue
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun readZimMagic(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(4)
            raf.readFully(b)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }.getOrNull()
}

/**
 * Minimal PMTiles v3 header reader (fixed 127-byte little-endian header).
 * Used to validate downloads and to frame the camera on an installed region.
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
data class PmtilesHeader(
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    companion object {
        const val SIZE = 127

        fun read(file: File): PmtilesHeader? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < SIZE) return null
                val b = ByteArray(SIZE)
                raf.readFully(b)
                parse(b)
            }
        }.getOrNull()

        fun parse(bytes: ByteArray): PmtilesHeader? {
            if (bytes.size < SIZE) return null
            val magic = String(bytes, 0, 7, Charsets.US_ASCII)
            if (magic != "PMTiles" || bytes[7].toInt() != 3) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(off: Int) = buf.getInt(off) / 10_000_000.0
            return PmtilesHeader(
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
                minLon = e7(102),
                minLat = e7(106),
                maxLon = e7(110),
                maxLat = e7(114),
                centerZoom = bytes[118].toInt() and 0xFF,
                centerLon = e7(119),
                centerLat = e7(123),
            )
        }
    }
}
