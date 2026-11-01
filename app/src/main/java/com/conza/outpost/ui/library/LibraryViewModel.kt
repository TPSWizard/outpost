package com.conza.outpost.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.conza.outpost.container
import com.conza.outpost.library.Book
import com.conza.outpost.library.Hit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val titleHits: List<Hit> = emptyList(),
    val fullTextHits: List<Hit>? = null, // null = not run yet
    val searching: Boolean = false,
)

@OptIn(FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val library = app.container.library

    val books: StateFlow<List<Book>?> = library.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private val queryFlow = MutableStateFlow("")
    private var fullTextJob: Job? = null


    fun onQuery(q: String) {
        fullTextJob?.cancel()
        _search.value = _search.value.copy(query = q, fullTextHits = null)
        queryFlow.value = q
    }

    fun runFullText() {
        val q = _search.value.query
        if (q.isBlank()) return
        fullTextJob?.cancel()
        fullTextJob = viewModelScope.launch {
            _search.value = _search.value.copy(searching = true)
            val hits = library.fullText(books.value.orEmpty(), q)
            _search.value = _search.value.copy(fullTextHits = hits, searching = false)
        }
    }

    fun rescan() { library.refresh(); scanPdfs() }

    // ---- Imported books: your own PDFs and ZIM files ----
    private val storage = app.container.storage
    private val _pdfs = MutableStateFlow<List<java.io.File>>(emptyList())
    val pdfs: StateFlow<List<java.io.File>> = _pdfs.asStateFlow()
    /** File name → catalog title, for field manuals downloaded through Content. */
    private val _pdfTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val pdfTitles: StateFlow<Map<String, String>> = _pdfTitles.asStateFlow()
    val importing = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun scanPdfs() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _pdfs.value = storage.allRoots().map { java.io.File(it, BOOKS) }.filter { it.isDirectory }
            .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }.orEmpty().toList() }
            .sortedBy { it.name.lowercase() }
        _pdfTitles.value = app().container.db.installed().byKind(com.conza.outpost.catalog.ResourceKind.DOC.name)
            .associate { java.io.File(it.filePath).name to it.title }
        app().container.syncPdfIndex()
    }

    private fun app() = getApplication<Application>()

    /** Copies a PDF or .zim the user picked into Outpost's storage. ZIMs join the Library; PDFs open in the viewer. */
    fun import(uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "imported"
        val dest = when {
            name.endsWith(".zim", true) -> java.io.File(storage.dir(com.conza.outpost.catalog.ResourceKind.ZIM), name)
            name.endsWith(".pdf", true) -> java.io.File(storage.namedDir(BOOKS), name)
            else -> { message.value = "Outpost can import .pdf and .zim files. \"$name\" isn't one of those."; return@launch }
        }
        importing.value = name
        val result = runCatching {
            val tmp = java.io.File(dest.parentFile, dest.name + ".part")
            ctx.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (dest.name.endsWith(".zim", true) && !com.conza.outpost.storage.FileFormats.looksValid(com.conza.outpost.catalog.ResourceKind.ZIM, tmp)) {
                tmp.delete(); error("that file isn't a valid ZIM")
            }
            dest.delete(); tmp.renameTo(dest)
        }
        importing.value = null
        message.value = result.fold({ "Added $name to your Library." }, { "Couldn't import $name: ${it.message}" })
        rescan()
    }

    fun deletePdf(f: java.io.File) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val db = app().container.db.installed()
        db.byKind(com.conza.outpost.catalog.ResourceKind.DOC.name).firstOrNull { it.filePath == f.absolutePath }?.let { db.delete(it.resourceId) }
        f.delete(); scanPdfs()
    }

    // Runs last so every property above is initialized before background work can touch it.
    init {
        scanPdfs()
        // Pick up libraries the moment a download finishes (and files copied in by hand on resume).
        viewModelScope.launch {
            app.container.downloader.observe()
                .map { m -> m.values.count { it.state == androidx.work.WorkInfo.State.SUCCEEDED } }
                .distinctUntilChanged()
                .collect { rescan() }
        }
        viewModelScope.launch {
            queryFlow.debounce(220).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) {
                    _search.value = SearchState(query = q)
                    return@collectLatest
                }
                _search.value = _search.value.copy(searching = true)
                val hits = library.suggest(books.value.orEmpty(), q)
                _search.value = _search.value.copy(titleHits = hits, searching = false)
            }
        }
    }

    companion object { const val BOOKS = "books" }
}
