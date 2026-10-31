package com.conza.outpost.ui.tools

import android.annotation.SuppressLint
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.container
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.content.formatMb
import java.io.ByteArrayInputStream
import java.io.File

/** NOMAD's CyberChef / IT-Tools containers, as downloadable offline web apps. */
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalContext.current.container
    val catalog by c.catalog.catalog.collectAsStateWithLifecycle()
    val installed by c.db.installed().observeByKind(ResourceKind.TOOL.name).collectAsStateWithLifecycle(emptyList())
    val downloads by c.downloader.observe().collectAsStateWithLifecycle(emptyMap())
    LaunchedEffect(Unit) { if (c.catalog.catalog.value == null) c.catalog.load() }
    val tools = catalog?.tools?.flatMap { s -> s.groups.flatMap { it.items } }.orEmpty()

    Scaffold(topBar = { BackTopBar("Data tools", onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tools, key = { it.id }) { t ->
                val inst = installed.firstOrNull { it.resourceId == t.id && File(it.filePath).isDirectory }
                val dl = downloads[t.id]
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.description, style = MaterialTheme.typography.bodySmall)
                        when {
                            inst != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onOpen(t.id) }) { Text("Open") }
                                if (inst.version != t.version) OutlinedButton(onClick = { c.downloader.enqueue(t, c.settings.wifiOnly) }) { Text("Update") }
                            }
                            dl?.active == true -> {
                                Text(if (dl.state == WorkInfo.State.RUNNING) "Downloading…" else "Queued", style = MaterialTheme.typography.labelSmall)
                                dl.fraction?.let { f -> LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            else -> {
                                dl?.error?.let { Text("Failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                                Button(onClick = { c.downloader.enqueue(t, c.settings.wifiOnly) }) { Text("Download · ${formatMb(t.sizeMb)}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ToolViewerScreen(toolId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val installed by c.db.installed().observeByKind(ResourceKind.TOOL.name).collectAsStateWithLifecycle(null)
    val row = installed?.firstOrNull { it.resourceId == toolId }
    val entry = c.catalog.catalog.value?.item(toolId)?.extra?.get("entry") ?: "index.html"
    val dir = row?.let { File(it.filePath) }

    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }

    // Load only once the WebView has its real size: web apps like CyberChef lay out their panes
    // from the window size at startup, and a 0×0 first load leaves them collapsed.
    LaunchedEffect(dir) {
        val d = dir ?: return@LaunchedEffect
        webView.webViewClient = DirectoryWebViewClient("$toolId.tools.outpost.local", d, entry)
        val start = "https://$toolId.tools.outpost.local/" + if (entry == "index.html") "" else entry
        fun go() { if (webView.url == null && webView.width > 0 && webView.height > 0) webView.loadUrl(start) }
        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> go() }
        go()
    }

    // Modern web apps need a reasonably current Android System WebView (Chromium 87+).
    val webViewMajor = remember {
        if (android.os.Build.VERSION.SDK_INT < 26) null
        else runCatching { WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() }.getOrNull()
    }

    Scaffold(topBar = { BackTopBar(row?.title ?: toolId, onBack) }) { pad ->
        if (dir == null) Text(if (installed == null) "" else "This tool isn't installed.", Modifier.padding(pad).padding(16.dp))
        else Column(Modifier.padding(pad).fillMaxSize()) {
            if (webViewMajor != null && webViewMajor < 90) Text(
                "This device's Android System WebView is version $webViewMajor, which is too old for some tools (IT-Tools shows a blank page). " +
                    "Update \"Android System WebView\" from the Play Store, then reopen the tool.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp),
            )
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Serves a static web app from a folder at its own fake https origin, with single-page-app
 * fallback to the entry page. Every other host is blocked so tools stay fully offline.
 */
class DirectoryWebViewClient(private val host: String, private val root: File, private val entry: String) : WebViewClient() {
    private val canonicalRoot = root.canonicalPath + File.separator

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme == "data" || url.scheme == "blob") return null
        if (url.host != host) return WebResourceResponse("text/plain", "utf-8", 403, "Offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        var path = url.path.orEmpty().trimStart('/').ifEmpty { entry }
        var f = File(root, path)
        if (!f.canonicalPath.startsWith(canonicalRoot)) return notFound()
        if (f.isDirectory) f = File(f, "index.html")
        if (!f.isFile) {
            if (path.substringAfterLast('/').contains('.')) return notFound()
            f = File(root, entry); path = entry // client-side route
        }
        val ext = f.extension.lowercase()
        val mime = when (ext) {
            "js", "mjs" -> "text/javascript"; "wasm" -> "application/wasm"; "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"; "css" -> "text/css"; "html", "htm" -> "text/html"; "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val text = mime.startsWith("text/") || mime.endsWith("json")
        return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), f.inputStream())
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != host

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
