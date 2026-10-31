package com.conza.outpost.library

import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.data.InstalledDao
import com.conza.outpost.storage.ContentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Entry
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A readable ZIM file on disk. `id` is the file name without `.zim` and is used in URLs. */
data class Book(
    val id: String,
    val file: File,
    val title: String,
    val description: String,
    val language: String,
    val articleCount: Int,
    val sizeBytes: Long,
    val hasFullText: Boolean,
    val icon: ByteArray?,
    val mainPath: String?,
    val error: String? = null,
) {
    override fun equals(other: Any?) = other is Book && other.id == id && other.sizeBytes == sizeBytes && other.error == error
    override fun hashCode() = id.hashCode()
}

data class Hit(val bookId: String, val bookTitle: String, val title: String, val path: String, val snippet: String?)

/** Bytes for one ZIM entry, or a byte range of the .zim file for large uncompressed media. */
sealed interface ZimContent {
    val mime: String
    data class Bytes(override val mime: String, val data: ByteArray) : ZimContent
    data class FileSlice(override val mime: String, val file: File, val offset: Long, val size: Long) : ZimContent
}

/**
 * Replaces NOMAD's Kiwix container (kiwix-serve). Reads ZIM files directly with libzim:
 * article lookup, title suggestions, and Xapian full-text search.
 *
 * Any `.zim` in the zim/ folder is picked up, including files copied over from a NOMAD
 * server by hand, not only ones downloaded through the catalog.
 */
class ZimLibrary(
    private val storage: ContentStorage,
    private val dao: InstalledDao,
) {
    private val archives = ConcurrentHashMap<String, Archive>()
    private val bookCache = ConcurrentHashMap<String, Book>() // key: path + lastModified
    private val suggesters = ConcurrentHashMap<String, SuggestionSearcher>()
    private val searchers = ConcurrentHashMap<String, Searcher>()
    private val rescan = MutableStateFlow(0)
    private val paths = ConcurrentHashMap<String, File>() // book id → file, across storage volumes

    init { ZimNative.ensureLoaded() }

    /** Books on disk; re-emits when catalog installs change or [refresh] is called. */
    fun observeBooks(): Flow<List<Book>> =
        combine(dao.observeByKind(ResourceKind.ZIM.name), rescan) { rows, _ ->
            val catalogTitles = rows.associate { File(it.filePath).name to it.title }
            scan(catalogTitles)
        }.flowOn(Dispatchers.IO)

    fun refresh() { rescan.value++ }

    fun book(id: String): Book? = bookCache.values.firstOrNull { it.id == id }
        ?: scan(emptyMap()).firstOrNull { it.id == id }

    private fun scan(catalogTitles: Map<String, String>): List<Book> {
        val files = storage.allDirs(ResourceKind.ZIM)
            .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".zim", true) }.orEmpty().toList() }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
        files.forEach { paths[idFor(it)] = it }
        // Forget archives whose files were deleted.
        val present = files.map { idFor(it) }.toSet()
        archives.keys.filter { it !in present }.forEach { id -> close(id) }
        return files.map { f ->
            val key = "${f.absolutePath}:${f.lastModified()}:${f.length()}"
            bookCache.getOrPut(key) { load(f, catalogTitles[f.name]) }
        }.sortedBy { it.title.lowercase() }
    }

    private fun load(f: File, catalogTitle: String?): Book {
        val id = idFor(f)
        return try {
            val a = archive(id, f)
            Book(
                id = id,
                file = f,
                title = meta(a, "Title")?.takeIf { it.isNotBlank() } ?: catalogTitle ?: f.nameWithoutExtension,
                description = meta(a, "Description").orEmpty(),
                language = meta(a, "Language").orEmpty(),
                articleCount = runCatching { a.articleCount }.getOrDefault(0),
                sizeBytes = f.length(),
                hasFullText = runCatching { a.hasFulltextIndex() }.getOrDefault(false),
                icon = runCatching {
                    if (a.hasIllustration(48)) a.getIllustrationItem(48).data.data else null
                }.getOrNull(),
                mainPath = runCatching { if (a.hasMainEntry()) resolve(a.mainEntry).path else null }.getOrNull(),
            )
        } catch (t: Throwable) {
            Book(id, f, catalogTitle ?: f.nameWithoutExtension, "", "", 0, f.length(), false, null, null,
                error = "Can't open this file (${t.javaClass.simpleName}). It may be incomplete or corrupt.")
        }
    }

    private fun meta(a: Archive, key: String): String? = runCatching { a.getMetadata(key) }.getOrNull()

    private fun archive(id: String, file: File? = null): Archive =
        archives.getOrPut(id) {
            val f = file ?: paths[id] ?: File(storage.dir(ResourceKind.ZIM), "$id.zim")
            Archive(f.absolutePath)
        }

    private fun close(id: String) {
        suggesters.remove(id)?.let { runCatching { it.dispose() } }
        searchers.remove(id)?.let { runCatching { it.dispose() } }
        archives.remove(id)?.let { runCatching { it.dispose() } }
        bookCache.keys.removeAll { k -> k.contains("/$id.zim:") }
    }

    /** Release a book before its file is deleted. */
    fun release(file: File) = close(idFor(file))

    // ---------- reading ----------

    suspend fun content(bookId: String, path: String): ZimContent? = withContext(Dispatchers.IO) {
        val a = runCatching { archive(bookId) }.getOrNull() ?: return@withContext null
        val entry = runCatching { a.getEntryByPath(path) }.getOrNull() ?: return@withContext null
        val item = runCatching { resolve(entry).getItem(true) }.getOrNull() ?: return@withContext null
        val mime = item.mimetype.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val size = item.size
        if (size > LARGE_ITEM_BYTES) {
            // Videos/large media are usually stored uncompressed: stream them straight from the file.
            val direct = runCatching { item.directAccessInformation }.getOrNull()
            if (direct != null && direct.filename.isNotEmpty() && direct.offset > 0) {
                return@withContext ZimContent.FileSlice(mime, File(direct.filename), direct.offset, size)
            }
        }
        ZimContent.Bytes(mime, item.data.data)
    }

    fun mainUrl(book: Book): String? = book.mainPath?.let { ZimUrls.build(book.id, it) }

    fun randomUrl(bookId: String): String? = runCatching {
        val a = archive(bookId)
        repeat(10) {
            val e = a.randomEntry
            val mime = runCatching { e.getItem(true).mimetype }.getOrDefault("")
            if (mime.startsWith("text/html")) return ZimUrls.build(bookId, resolve(e).path)
        }
        null
    }.getOrNull()

    private fun resolve(entry: Entry): Entry {
        var e = entry
        var hops = 0
        while (e.isRedirect && hops++ < 10) e = e.redirectEntry
        return e
    }

    // ---------- search ----------

    /** Fast title-prefix suggestions (works on every ZIM, no full-text index needed). */
    suspend fun suggest(books: List<Book>, query: String, perBook: Int = 8): List<Hit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        books.filter { it.error == null }.flatMap { b ->
            runCatching {
                val s = suggesters.getOrPut(b.id) { SuggestionSearcher(archive(b.id)) }
                val iter = s.suggest(query.trim()).getResults(0, perBook)
                buildList {
                    while (iter.hasNext()) {
                        val item = iter.next()
                        add(Hit(b.id, b.title, item.title, item.path, if (item.hasSnippet()) item.snippet else null))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Full-text search with snippets (only for ZIMs that ship a Xapian index). */
    suspend fun fullText(books: List<Book>, query: String, perBook: Int = 15): List<Hit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        books.filter { it.error == null && it.hasFullText }.flatMap { b ->
            runCatching {
                val s = searchers.getOrPut(b.id) { Searcher(archive(b.id)) }
                val iter = s.search(Query(query.trim())).getResults(0, perBook)
                buildList {
                    // Iterator getters describe the item that next() is about to return.
                    while (iter.hasNext()) {
                        val title = iter.title
                        val path = iter.path
                        val snippet = runCatching { iter.snippet }.getOrNull()
                        iter.next()
                        add(Hit(b.id, b.title, title, path, snippet))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    companion object {
        private const val LARGE_ITEM_BYTES = 4L * 1024 * 1024
        fun idFor(f: File) = f.name.removeSuffix(".zim").removeSuffix(".ZIM")
    }
}

/** Loads only the libzim natives (libkiwix's server layer is excluded from the APK). */
object ZimNative {
    @Volatile private var loaded = false
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("c++_shared")
            System.loadLibrary("zim")
            System.loadLibrary("zim_wrapper")
            loaded = true
        }
    }
}
