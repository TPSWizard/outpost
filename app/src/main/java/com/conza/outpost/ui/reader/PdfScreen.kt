package com.conza.outpost.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.conza.outpost.container
import com.conza.outpost.library.ZimContent
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Many ZIMs (e.g. the Medical Library) are collections of PDFs, which a WebView can't show.
 * This extracts the PDF from the ZIM to the cache and renders it with Android's PdfRenderer.
 */
@Composable
fun PdfScreen(bookId: String?, path: String, onBack: () -> Unit, initialPage: Int = 1, title: String? = null) {
    val ctx = LocalContext.current
    val state by produceState<Result<File>?>(null, bookId, path) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                // bookId == null: a PDF the user imported (path is a file on disk).
                if (bookId == null) File(path).also { require(it.isFile) { "file is missing" } }
                else extract(ctx.cacheDir, ctx.container.library.content(bookId, path), bookId, path)
            }
        }
    }
    val shownTitle = title ?: path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace("%20", " ")

    Scaffold(topBar = {
        BackTopBar(shownTitle, onBack, subtitle = if (initialPage > 1) "Opened at page $initialPage" else null) {
            state?.getOrNull()?.let { f ->
                IconButton(onClick = { openExternally(ctx, f) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another app") }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> s.fold(
                    onSuccess = { PdfPages(it, initialPage) },
                    onFailure = { Text("Couldn't open this PDF: ${it.message}", Modifier.padding(16.dp)) },
                )
            }
        }
    }
}

private fun extract(cache: File, content: ZimContent?, bookId: String, path: String): File {
    content ?: error("not found in this library")
    val dir = File(cache, "pdf").apply { mkdirs() }
    val f = File(dir, "${(bookId + path).hashCode().toUInt()}.pdf")
    if (f.exists() && f.length() > 0) return f
    val tmp = File(dir, f.name + ".tmp")
    when (content) {
        is ZimContent.Bytes -> tmp.writeBytes(content.data)
        is ZimContent.FileSlice -> RandomAccessFile(content.file, "r").use { raf ->
            raf.seek(content.offset)
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var left = content.size
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
        }
    }
    tmp.renameTo(f)
    // Keep the cache bounded: drop the oldest extracted PDFs beyond ~500 MB.
    val all = dir.listFiles { x -> x.name.endsWith(".pdf") }.orEmpty().sortedByDescending { it.lastModified() }
    var total = 0L
    all.forEach { total += it.length(); if (total > 500L shl 20 && it != f) it.delete() }
    return f
}

private fun openExternally(ctx: android.content.Context, f: File) {
    val uri = runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f) }.getOrNull() ?: return
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open PDF with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun PdfPages(file: File, initialPage: Int = 1) {
    val renderer = remember(file) { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
    val lock = remember(file) { Mutex() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer.close() } } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (initialPage - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0)))

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3A3F3C))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(400, 2000)
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offsetX = if (scale == 1f) 0f else (offsetX + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
        ) {
            items(renderer.pageCount) { index ->
                val page by produceState<Bitmap?>(null, index, widthPx) {
                    value = withContext(Dispatchers.IO) {
                        lock.withLock {
                            renderer.openPage(index).use { p ->
                                val h = (widthPx.toFloat() * p.height / p.width).toInt()
                                Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                    bmp.eraseColor(AColor.WHITE)
                                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
                val bmp = page
                if (bmp == null) Box(Modifier.fillMaxWidth().aspectRatio(0.77f).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("Page ${index + 1}", color = Color.Gray)
                } else Image(bmp.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            item { Text("${renderer.pageCount} pages · pinch to zoom", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, modifier = Modifier.padding(8.dp)) }
        }
    }
}
