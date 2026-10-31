package com.conza.outpost.ai

import com.conza.outpost.Settings
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.data.Chunk
import com.conza.outpost.data.ImportedDocument
import com.conza.outpost.data.Note
import com.conza.outpost.data.OutpostDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class IndexStatus(val pending: Int = 0, val running: Boolean = false, val embeddingModel: String? = null, val error: String? = null)

data class RetrievedPassage(val chunk: Chunk, val score: Double)

/**
 * Replaces NOMAD's Qdrant + nomic-embed-text pipeline for notes and imported documents.
 *
 * Passages are stored in SQLite. If the Nomic search model is installed they get embeddings
 * (computed in the background on-device) and search is semantic + keyword hybrid; otherwise
 * search falls back to keyword (BM25) ranking, so it always works.
 */
class RetrievalIndex(
    private val db: OutpostDatabase,
    private val llm: LocalLlm,
    @Suppress("unused") private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val dao = db.retrieval()
    private val mutex = Mutex()
    private var embedJob: Job? = null
    private val _status = MutableStateFlow(IndexStatus())
    val status: StateFlow<IndexStatus> = _status

    init {
        scope.launch {
            // First run after upgrading: index notes that predate the index.
            if (dao.allChunks().isEmpty()) db.notes().all().forEach { indexNote(it, embedNow = false) }
            scheduleEmbedding()
        }
    }

    suspend fun indexNote(note: Note, embedNow: Boolean = true) = mutex.withLock {
        dao.deleteChunks("note", note.id)
        val text = listOf(note.title, note.body).filter { it.isNotBlank() }.joinToString("\n\n")
        if (text.isNotBlank()) dao.insertChunks(TextTools.chunk(text, 700, 100).mapIndexed { i, t ->
            Chunk(sourceType = "note", sourceId = note.id, sourceTitle = note.title.ifBlank { "Untitled note" }, position = i, text = t)
        })
    }.also { if (embedNow) scheduleEmbedding() }

    suspend fun removeNote(id: Long) = mutex.withLock { dao.deleteChunks("note", id) }

    suspend fun importDocument(title: String, text: String): Long {
        val id = mutex.withLock {
            val docId = dao.insertDocument(ImportedDocument(title = title, text = text))
            dao.insertChunks(TextTools.chunk(text, 800, 120).mapIndexed { i, t ->
                Chunk(sourceType = "doc", sourceId = docId, sourceTitle = title, position = i, text = t)
            })
            docId
        }
        scheduleEmbedding()
        return id
    }

    suspend fun deleteDocument(id: Long) = mutex.withLock {
        dao.deleteChunks("doc", id)
        dao.deleteDocument(id)
    }

    private suspend fun embeddingModel(): File? =
        db.installed().byKind(ResourceKind.MODEL.name)
            .firstOrNull { it.resourceId.contains("embed", ignoreCase = true) && File(it.filePath).exists() }
            ?.let { File(it.filePath) }

    /** Embed any passages that don't have a vector for the installed search model yet. */
    fun scheduleEmbedding() {
        if (embedJob?.isActive == true) return
        embedJob = scope.launch {
            val model = embeddingModel() ?: run { _status.value = IndexStatus(); return@launch }
            if (!llm.available) { _status.value = IndexStatus(error = llm.unavailableReason); return@launch }
            val tag = model.name
            try {
                llm.ensureEmbedModel(model)
                while (true) {
                    val batch = dao.stale(tag, 16)
                    if (batch.isEmpty()) break
                    _status.value = IndexStatus(dao.countStale(tag), true, tag)
                    for (c in batch) {
                        val v = llm.embed("search_document: " + c.text) ?: continue
                        dao.setEmbedding(c.id, TextTools.toBytes(v), tag)
                    }
                }
                _status.value = IndexStatus(0, false, tag)
            } catch (t: Throwable) {
                _status.value = IndexStatus(error = t.message)
            }
        }
    }

    /** Best passages from notes and documents for [query]. */
    suspend fun search(query: String, k: Int = 4): List<RetrievedPassage> {
        val chunks = dao.allChunks()
        if (chunks.isEmpty()) return emptyList()
        val keyword = TextTools.rank(query, chunks.map { "${it.sourceTitle}\n${it.text}" }).toMap()
        val maxKw = keyword.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        val model = embeddingModel()
        val qv = if (model != null && llm.available) runCatching {
            llm.ensureEmbedModel(model); llm.embed("search_query: $query")
        }.getOrNull() else null

        val scored = chunks.mapIndexed { i, c ->
            val kw = (keyword[i] ?: 0.0) / maxKw
            val sem = if (qv != null && c.embedding != null && c.embeddingModel == model?.name)
                TextTools.cosine(qv, TextTools.toFloats(c.embedding)).toDouble() else null
            // Hybrid: semantic similarity dominates when available; keywords break ties and catch exact terms.
            val score = if (sem != null) 0.7 * sem + 0.3 * kw else kw
            RetrievedPassage(c, score)
        }
        val minScore = if (qv != null) 0.35 else 0.05
        return scored.filter { it.score >= minScore }.sortedByDescending { it.score }.take(k)
    }
}
