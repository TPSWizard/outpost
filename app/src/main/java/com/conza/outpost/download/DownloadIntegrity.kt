package com.conza.outpost.download

import java.io.File
import java.security.MessageDigest

/** A publisher hash, when supplied, verifies bytes after a resumable transfer. */
object DownloadIntegrity {
    private val HASH = Regex("[0-9a-fA-F]{64}")

    fun validHash(value: String): Boolean = HASH.matches(value)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                digest.update(block, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
