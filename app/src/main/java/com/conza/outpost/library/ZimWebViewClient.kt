package com.conza.outpost.library

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels

/**
 * Serves ZIM entries to the WebView in place of NOMAD's kiwix-serve container.
 *
 * - Requests to [ZimUrls.HOST] are answered from the ZIM file (on WebView's IO thread).
 * - Requests to any other host are blocked: pages stay offline and nothing phones home.
 * - Tapping a link to the real web hands it to the system browser instead.
 */
class ZimWebViewClient(
    private val library: ZimLibrary,
    private val onExternalLink: (Uri) -> Unit,
    /** Documents the WebView can't display (PDF) are handed to a native viewer. */
    private val onDocument: (bookId: String, path: String) -> Unit = { _, _ -> },
    private val onPageChanged: (url: String, title: String?) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!ZimUrls.isZimUrl(url)) {
            return if (request.url.scheme == "data" || request.url.scheme == "blob") null else blocked()
        }
        val target = ZimUrls.parse(url) ?: return notFound()
        val content = runBlocking {
            target.query?.let { q -> library.content(target.bookId, "${target.path}?$q") }
                ?: library.content(target.bookId, target.path)
        } ?: return notFound()
        return toResponse(content)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (ZimUrls.isZimUrl(url)) {
            val t = ZimUrls.parse(url) ?: return false
            if (t.path.endsWith(".pdf", ignoreCase = true)) { onDocument(t.bookId, t.path); return true }
            return false
        }
        if (request.url.scheme in setOf("http", "https", "mailto", "tel", "geo")) onExternalLink(request.url)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageChanged(url, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(url, view.title)
    }

    private fun toResponse(c: ZimContent): WebResourceResponse {
        val isText = c.mime.startsWith("text/") || c.mime.endsWith("javascript") || c.mime.endsWith("json") || c.mime.endsWith("xml")
        val stream: InputStream = when (c) {
            is ZimContent.Bytes -> ByteArrayInputStream(c.data)
            is ZimContent.FileSlice -> sliceStream(c)
        }
        return WebResourceResponse(c.mime, if (isText) "utf-8" else null, 200, "OK", HEADERS, stream)
    }

    private fun sliceStream(c: ZimContent.FileSlice): InputStream {
        val raf = RandomAccessFile(c.file, "r")
        raf.seek(c.offset)
        val limited = object : FilterInputStream(Channels.newInputStream(raf.channel)) {
            private var remaining = c.size
            override fun read(): Int = if (remaining <= 0) -1 else super.read().also { if (it >= 0) remaining-- }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
            override fun close() { super.close(); raf.close() }
        }
        return limited
    }

    private fun notFound() = WebResourceResponse(
        "text/html", "utf-8", 404, "Not Found", HEADERS,
        ByteArrayInputStream("<html><body style='font-family:sans-serif;padding:2em'><h3>Not in this offline library</h3><p>This page isn't included in the downloaded file.</p></body></html>".toByteArray()),
    )

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Offline", HEADERS, ByteArrayInputStream(ByteArray(0)))

    companion object {
        private val HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=3600")
    }
}
