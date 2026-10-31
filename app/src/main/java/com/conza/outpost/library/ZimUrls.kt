package com.conza.outpost.library

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * ZIM content is served to the WebView from a fake origin that never touches the network:
 *   https://zim.outpost.local/<bookId>/<path inside the ZIM>
 * Using https gives pages a secure context (needed by some zimit-packaged sites), and keeping
 * the book id as the first segment means relative links inside an article resolve correctly.
 *
 * Pure Kotlin (no android.net.Uri) so it can be unit-tested on the JVM.
 */
object ZimUrls {
    const val HOST = "zim.outpost.local"
    private const val PREFIX = "https://$HOST/"

    fun build(bookId: String, path: String): String =
        PREFIX + encodeSegment(bookId) + "/" + path.split('/').joinToString("/") { encodeSegment(it) }

    data class Target(val bookId: String, val path: String, val query: String?)

    fun parse(url: String): Target? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val raw = uri.rawPath?.removePrefix("/") ?: return null
        val slash = raw.indexOf('/')
        if (slash <= 0) return null
        val book = decode(raw.substring(0, slash))
        val path = decode(raw.substring(slash + 1))
        if (path.isEmpty()) return null
        return Target(book, path, uri.rawQuery?.let(::decode))
    }

    fun isZimUrl(url: String): Boolean = url.startsWith(PREFIX, ignoreCase = true)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@"

    fun encodeSegment(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code < 128 && c in UNRESERVED) append(c)
            else append('%').append("%02X".format(b.toInt() and 0xFF))
        }
    }

    /** Percent-decoding that, unlike URLDecoder, leaves '+' alone (it is literal in paths). */
    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
