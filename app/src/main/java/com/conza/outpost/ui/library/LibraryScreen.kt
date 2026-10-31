package com.conza.outpost.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conza.outpost.library.Book
import com.conza.outpost.library.Hit
import com.conza.outpost.ui.content.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (bookId: String, path: String?) -> Unit,
    onBrowseContent: () -> Unit,
    onOpenPdfFile: (String) -> Unit = {},
    initialQuery: String? = null,
    vm: LibraryViewModel = viewModel(),
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val pdfs by vm.pdfs.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    androidx.compose.runtime.LaunchedEffect(initialQuery) { if (!initialQuery.isNullOrBlank()) vm.onQuery(initialQuery) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.import(uri) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { vm.rescan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("application/pdf", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Filled.Add, "Add your own PDF or ZIM")
                    }
                    IconButton(onClick = vm::rescan) { Icon(Icons.Filled.Refresh, "Rescan for files") }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val list = books
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() && pdfs.isEmpty() -> EmptyLibrary(onBrowseContent)
                else -> {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = vm::onQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        placeholder = { Text("Search ${list.size} offline ${if (list.size == 1) "library" else "libraries"}") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (search.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
                        },
                        singleLine = true,
                    )
                    if (search.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    importing?.let { Text("Importing $it…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall) }
                    if (search.query.isBlank()) BookList(list, onOpen, pdfs, onOpenPdfFile, vm::deletePdf)
                    else SearchResults(search, anyFullText = list.any { it.hasFullText }, onFullText = vm::runFullText, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onBrowseContent: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text("No offline libraries yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Download Wikipedia, medical references, or survival guides from Content → Library. " +
                "You can also copy .zim files from a NOMAD server into the zim folder shown on the About tab.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onBrowseContent) { Text("Browse libraries") }
    }
}

@Composable
private fun BookList(
    books: List<Book>,
    onOpen: (String, String?) -> Unit,
    pdfs: List<java.io.File>,
    onOpenPdf: (String) -> Unit,
    onDeletePdf: (java.io.File) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pdfs.isNotEmpty()) {
            item(key = "pdf-h") { Text("My PDFs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(pdfs, key = { "pdf:" + it.absolutePath }) { f ->
                Row(Modifier.fillMaxWidth().clickable { onOpenPdf(f.absolutePath) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension.replace('_', ' '), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(f.length()), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onDeletePdf(f) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            if (books.isNotEmpty()) item(key = "zim-h") { Text("Offline libraries", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        }
        items(books, key = { it.id }) { b ->
            Card(
                Modifier.fillMaxWidth().clickable(enabled = b.error == null) { onOpen(b.id, null) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookIcon(b)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (b.error != null) {
                            Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            if (b.description.isNotBlank()) {
                                Text(b.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                buildString {
                                    append("%,d articles · ".format(b.articleCount))
                                    append(formatBytes(b.sizeBytes))
                                    if (b.hasFullText) append(" · full-text search")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookIcon(b: Book) {
    val bmp = remember(b.id, b.sizeBytes) {
        b.icon?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            b.error != null -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
            bmp != null -> Image(bmp, null, Modifier.size(36.dp))
            else -> Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SearchResults(
    s: SearchState,
    anyFullText: Boolean,
    onFullText: () -> Unit,
    onOpen: (String, String?) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (anyFullText && s.fullTextHits == null) {
            item {
                AssistChip(
                    onClick = onFullText,
                    label = { Text("Search inside articles for \"${s.query}\"") },
                    leadingIcon = { Icon(Icons.Filled.ManageSearch, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        s.fullTextHits?.let { hits ->
            item { SectionLabel(if (hits.isEmpty()) "No full-text matches" else "Inside articles") }
            items(hits, key = { "ft:${it.bookId}:${it.path}" }) { HitRow(it, onOpen) }
            item { Spacer(Modifier.size(12.dp)) }
        }
        if (!s.searching && s.titleHits.isEmpty() && s.fullTextHits == null) {
            item { Text("No titles match. Try full-text search or fewer words.", Modifier.padding(8.dp)) }
        }
        if (s.titleHits.isNotEmpty()) item { SectionLabel("Titles") }
        items(s.titleHits, key = { "t:${it.bookId}:${it.path}" }) { HitRow(it, onOpen) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun HitRow(hit: Hit, onOpen: (String, String?) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(hit.bookId, hit.path) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(hit.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        hit.snippet?.takeIf { it.isNotBlank() }?.let {
            Text(
                remember(it) { runCatching { AnnotatedString.fromHtml(it) }.getOrElse { _ -> AnnotatedString(it) } },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(hit.bookTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
