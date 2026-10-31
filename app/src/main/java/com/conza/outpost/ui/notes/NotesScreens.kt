package com.conza.outpost.ui.notes

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.conza.outpost.ai.TextTools
import com.conza.outpost.container
import com.conza.outpost.data.Note
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.text.DateFormat
import java.util.Date

/** NOMAD's FlatNotes, rebuilt natively: markdown notes, plus imported documents the assistant can search. */
@Composable
fun NotesScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, onOpenDocument: (Long) -> Unit) {
    val c = LocalContext.current.container
    val ctx = LocalContext.current
    val notes by c.db.notes().observeAll().collectAsStateWithLifecycle(emptyList())
    val docs by c.db.retrieval().observeDocuments().collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Document"
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    if (mime == "application/pdf" || name.endsWith(".pdf", true)) error("PDF import isn't supported yet. Save it as text or HTML first.")
                    val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (raw.size > 20_000_000) error("That file is too large (20 MB limit).")
                    var text = String(raw, Charsets.UTF_8)
                    if (mime.contains("html") || name.endsWith(".html", true) || name.endsWith(".htm", true)) text = TextTools.htmlToText(text)
                    c.index.importDocument(name.substringBeforeLast('.'), text)
                    name
                }
            }
            snack.showSnackbar(result.fold({ "Imported $it. The assistant can now search it." }, { it.message ?: "Import failed" }))
        }
    }

    Scaffold(
        topBar = { BackTopBar("Notes & documents", onBack) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (tab == 0) onOpenNote(0) else importer.launch(arrayOf("text/*", "application/json", "application/xml"))
            }) { Icon(if (tab == 0) Icons.Filled.Add else Icons.Filled.UploadFile, if (tab == 0) "New note" else "Import document") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Notes (${notes.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Documents (${docs.size})") })
            }
            if (tab == 0) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                    placeholder = { Text("Search notes") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                val shown = notes.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
                if (notes.isEmpty()) EmptyState("No notes yet", "Write down plans, contacts, inventories, or anything you want the assistant to know.")
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(shown, key = { it.id }) { n ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenNote(n.id) }.padding(vertical = 10.dp)) {
                            Row {
                                if (n.pinned) Icon(Icons.Filled.PushPin, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(n.body.take(160).replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.updatedAt)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                if (docs.isEmpty()) EmptyState("No documents", "Import text, Markdown, or HTML files (manuals, checklists, exported web pages). " +
                    "They stay on this phone, and the assistant cites them in answers.")
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    items(docs, key = { it.id }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenDocument(d.id) }.padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, style = MaterialTheme.typography.titleMedium)
                                Text("${"%,d".format(d.text.length)} characters", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { scope.launch { c.index.deleteDocument(d.id) } }) { Icon(Icons.Filled.Delete, "Delete document") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun NoteEditorScreen(noteId: Long, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var id by remember { mutableLongStateOf(noteId) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(noteId == 0L) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var created by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(noteId) {
        if (noteId != 0L) c.db.notes().get(noteId)?.let { title = it.title; body = it.body; pinned = it.pinned; created = it.createdAt; preview = it.body.isNotBlank() }
        loaded = true
    }

    fun save(then: () -> Unit = {}) {
        if (!loaded) return then()
        if (title.isBlank() && body.isBlank()) return then()
        scope.launch {
            val note = Note(id = id, title = title.trim(), body = body, pinned = pinned, createdAt = created, updatedAt = System.currentTimeMillis())
            val newId = c.db.notes().upsert(note)
            if (id == 0L) id = newId
            c.index.indexNote(note.copy(id = if (id == 0L) newId else id))
            then()
        }
    }
    BackHandler { save(onBack) }

    Scaffold(topBar = {
        BackTopBar(if (noteId == 0L) "New note" else "Note", { save(onBack) }) {
            IconButton(onClick = { pinned = !pinned }) { Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Pin") }
            IconButton(onClick = { if (!preview) save(); preview = !preview }) { Icon(if (preview) Icons.Filled.Edit else Icons.Filled.Visibility, if (preview) "Edit" else "Preview") }
            if (id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val transparent = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            )
            TextField(title, { title = it }, Modifier.fillMaxWidth(), placeholder = { Text("Title") }, singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge, colors = transparent, readOnly = preview)
            if (preview) MarkdownView(body, Modifier.weight(1f).fillMaxWidth())
            else TextField(body, { body = it }, Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text("Write in Markdown: # headings, - lists, **bold**, [ ] checklists…") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), colors = transparent)
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this note?") },
        confirmButton = { TextButton(onClick = {
            scope.launch { c.db.notes().delete(id); c.index.removeNote(id); onBack() }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

private val markdown by lazy {
    val ext = listOf(TablesExtension.create())
    Parser.builder().extensions(ext).build() to HtmlRenderer.builder().extensions(ext).escapeHtml(true).build()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownView(md: String, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface.toArgb()
    val bg = MaterialTheme.colorScheme.surface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    fun hex(c: Int) = "#%06X".format(0xFFFFFF and c)
    val html = remember(md, fg, bg) {
        val (p, r) = markdown
        val checked = md.replace(Regex("(?m)^(\\s*[-*]) \\[x]", RegexOption.IGNORE_CASE), "$1 ☑").replace(Regex("(?m)^(\\s*[-*]) \\[ ]"), "$1 ☐")
        """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
           body{font-family:sans-serif;color:${hex(fg)};background:${hex(bg)};padding:4px 16px;line-height:1.5}
           a{color:${hex(accent)}} code,pre{background:rgba(127,127,127,.15);border-radius:4px;padding:2px 4px}
           pre{padding:8px;overflow-x:auto} table{border-collapse:collapse} td,th{border:1px solid rgba(127,127,127,.4);padding:4px 8px}
           blockquote{border-left:3px solid ${hex(accent)};margin-left:0;padding-left:12px;opacity:.85}
           </style></head><body>${r.render(p.parse(checked))}</body></html>"""
    }
    AndroidView(
        factory = { WebView(it).apply { settings.javaScriptEnabled = false; setBackgroundColor(bg) } },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        modifier = modifier,
    )
}

@Composable
fun DocumentScreen(id: Long, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val doc by produceState<com.conza.outpost.data.ImportedDocument?>(null, id) { value = c.db.retrieval().document(id) }
    Scaffold(topBar = { BackTopBar(doc?.title ?: "Document", onBack) }) { pad ->
        SelectionContainer {
            Text(doc?.text.orEmpty(), Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
