package com.conza.outpost.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.conza.outpost.container
import com.conza.outpost.library.Hit
import com.conza.outpost.library.ZimUrls
import com.conza.outpost.library.ZimWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val TEXT_ZOOMS = listOf(85, 100, 115, 130, 150)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    initialPath: String?,
    onClose: () -> Unit,
    onOpenDocument: (bookId: String, path: String) -> Unit = { _, _ -> },
    onSearchLibraries: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val library = container.library
    val prefs = container.settings

    var book by remember { mutableStateOf<com.conza.outpost.library.Book?>(null) }
    var bookLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        book = withContext(Dispatchers.IO) { library.book(bookId) }
        bookLoaded = true
    }

    var currentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(prefs.textZoom) }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var externalLink by remember { mutableStateOf<android.net.Uri?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true // many ZIMs (zimit-packaged sites) need it
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            settings.textZoom = zoom
            webViewClient = ZimWebViewClient(
                library = library,
                onExternalLink = { uri -> externalLink = uri },
                onDocument = onOpenDocument,
                onPageChanged = { url, title ->
                    currentUrl = url
                    if (title != null) pageTitle = title
                    canGoBack = canGoBack()
                },
            )
            // Links that the page turns into downloads (PDFs served without a .pdf path).
            setDownloadListener { url, _, _, mime, _ ->
                val t = ZimUrls.parse(url)
                if (t != null && (mime == "application/pdf" || t.path.endsWith(".pdf", true))) onOpenDocument(t.bookId, t.path)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) pageTitle = title }
            }
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }

    // First load: restored URL, a specific article, or the book's home page.
    LaunchedEffect(book) {
        val b = book ?: return@LaunchedEffect
        if (webView.url != null) return@LaunchedEffect
        val start = currentUrl
            ?: initialPath?.let { ZimUrls.build(b.id, it) }
            ?: library.mainUrl(b)
        if (start != null) {
            // Wait for a real size so responsive pages lay out correctly on first load.
            fun go() { if (webView.url == null && webView.width > 0) webView.loadUrl(start) }
            webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> go() }
            go()
        }
    }

    LaunchedEffect(query, searching) {
        if (!searching || query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(200)
        book?.let { hits = library.suggest(listOf(it), query, perBook = 25) }
    }

    BackHandler {
        when {
            searching -> { searching = false; query = "" }
            webView.canGoBack() -> webView.goBack()
            else -> onClose()
        }
    }

    externalLink?.let { uri ->
        val topic = remember(uri) { topicFrom(uri) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { externalLink = null },
            title = { Text("This link goes to the internet") },
            text = {
                Text("It points to ${uri.host ?: "a website"}, which isn't part of your offline library." +
                    (if (topic.isNotBlank()) " You can search your downloaded libraries for \"$topic\" instead." else ""))
            },
            confirmButton = {
                if (topic.isNotBlank()) androidx.compose.material3.TextButton(onClick = { externalLink = null; onSearchLibraries(topic) }) { Text("Search offline") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    externalLink = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open in browser") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (searching) { searching = false; query = "" } else onClose() }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search ${book?.title ?: "this library"}") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        Column {
                            Text(pageTitle ?: book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pageTitle != null && book != null && pageTitle != book?.title) {
                                Text(book!!.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search this library") }
                        IconButton(onClick = { book?.let { b -> library.mainUrl(b)?.let(webView::loadUrl) } }) {
                            Icon(Icons.Filled.Home, "Library home page")
                        }
                        IconButton(onClick = { library.randomUrl(bookId)?.let(webView::loadUrl) }) {
                            Icon(Icons.Filled.Casino, "Random article")
                        }
                        IconButton(onClick = {
                            zoom = TEXT_ZOOMS[(TEXT_ZOOMS.indexOf(zoom).coerceAtLeast(0) + 1) % TEXT_ZOOMS.size]
                            webView.settings.textZoom = zoom
                            prefs.textZoom = zoom
                        }) { Icon(Icons.Filled.FormatSize, "Text size ($zoom%)") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (bookLoaded && book == null) Text("This library isn't available. It may have been deleted.", Modifier.padding(24.dp))
            if (searching && hits.isNotEmpty()) {
                Surface(tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    LazyColumn {
                        items(hits, key = { it.path }) { h ->
                            Text(
                                h.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        webView.loadUrl(ZimUrls.build(h.bookId, h.path))
                                        searching = false
                                        query = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** "https://www.cdc.gov/tb/topic/basics/default.htm" → "tb basics"; "/wiki/Tuberculosis" → "Tuberculosis". */
internal fun topicFrom(uri: android.net.Uri): String {
    val skip = setOf("wiki", "index", "default", "topic", "topics", "en", "www", "html", "htm", "php", "aspx", "home", "page")
    val parts = uri.pathSegments.map { it.substringBeforeLast('.').replace(Regex("[-_+]"), " ").trim() }
        .filter { it.isNotBlank() && it.lowercase() !in skip && !it.all { c -> c.isDigit() } }
    return (parts.lastOrNull() ?: uri.host?.removePrefix("www.")?.substringBefore('.') ?: "").take(60)
}
