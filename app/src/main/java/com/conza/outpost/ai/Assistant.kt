package com.conza.outpost.ai

import android.content.Context
import com.conza.outpost.AppContainer
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.data.ChatMessage
import com.conza.outpost.data.ChatSession
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.library.ZimContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where a passage in an answer came from; tapping it opens the article, note, or document. */
@Serializable
data class Source(
    val type: String, // "zim" | "note" | "doc" | "pdf" (path = file, refId = page)
    val title: String,
    val subtitle: String = "",
    val bookId: String? = null,
    val path: String? = null,
    val refId: Long? = null,
)

object Sources {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Source.serializer())
    fun encode(list: List<Source>): String? = if (list.isEmpty()) null else json.encodeToString(ser, list)
    fun decode(s: String?): List<Source> = if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(ser, s) }.getOrDefault(emptyList())
}

sealed interface Readiness {
    data class Local(val modelTitle: String, val file: File) : Readiness
    data class Remote(val url: String, val model: String) : Readiness
    data object NeedsModel : Readiness
    data class Unsupported(val reason: String) : Readiness
}

data class Generation(
    val sessionId: Long,
    val messageId: Long,
    val phase: String,
    val tokensPerSecond: Double? = null,
    /** The answer so far, streamed live (written to the database when finished). */
    val text: String = "",
)

/**
 * The assistant pipeline (NOMAD's chat_service + rag_service, rebuilt for a phone):
 * retrieve passages from the offline library (Kiwix full-text search), notes and documents;
 * build a cited prompt; stream the answer from the on-device model or a remote server.
 */
class Assistant(private val context: Context, private val c: AppContainer) {
    private val remote = RemoteLlm(c.http)
    private val chats = c.db.chats()
    private val _generation = MutableStateFlow<Generation?>(null)
    val generation: StateFlow<Generation?> = _generation

    val busy: Boolean get() = _generation.value.let { it != null && it.phase != "Done" }
    private var job: Job? = null

    suspend fun installedChatModels(): List<InstalledResource> =
        c.db.installed().byKind(ResourceKind.MODEL.name)
            .filter { !it.resourceId.contains("embed", ignoreCase = true) && File(it.filePath).exists() }

    suspend fun readiness(): Readiness {
        val s = c.settings
        if (s.aiBackend == "remote") return Readiness.Remote(s.remoteUrl, s.remoteModel)
        if (!c.llm.checkAvailable()) return Readiness.Unsupported(c.llm.unavailableReason ?: "On-device AI unavailable")
        val models = installedChatModels()
        val pick = models.firstOrNull { it.resourceId == s.chatModelId } ?: models.firstOrNull() ?: return Readiness.NeedsModel
        return Readiness.Local(pick.title, File(pick.filePath))
    }

    suspend fun newSession(): Long = chats.insertSession(ChatSession(title = "New chat"))

    fun stop() {
        c.llm.stop()
        job?.cancel()
    }

    /** Sends [text] in [sessionId] (or a new chat). Returns the session id immediately; the answer streams into the DB. */
    suspend fun send(sessionId: Long?, text: String): Long {
        val sid = sessionId ?: newSession()
        val history = chats.messages(sid)
        if (history.isEmpty()) chats.updateSession(sid, TextTools.titleFrom(text))
        chats.insertMessage(ChatMessage(sessionId = sid, role = "user", content = text))
        val answerId = chats.insertMessage(ChatMessage(sessionId = sid, role = "assistant", content = ""))
        chats.updateSession(sid, chats.session(sid)?.title ?: TextTools.titleFrom(text))

        job?.cancel()
        job = c.appScope.launch {
            try {
                answer(sid, answerId, history, text)
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    if (t is kotlinx.coroutines.CancellationException) {
                        val partial = _generation.value?.takeIf { it.messageId == answerId }?.text.orEmpty()
                        chats.updateMessage(answerId, partial.trim().ifBlank { "(stopped)" }, null)
                    } else {
                        chats.updateMessage(answerId, "⚠️ ${t.message ?: t.javaClass.simpleName}", null)
                    }
                }
            } finally {
                if (_generation.value?.phase != "Done") _generation.value = null
            }
        }
        return sid
    }

    private suspend fun answer(sid: Long, answerId: Long, history: List<ChatMessage>, question: String) {
        val s = c.settings
        val readiness = readiness()
        if (readiness is Readiness.NeedsModel) error("No AI model installed. Download one from Content → AI models, or connect to a server in Settings.")
        if (readiness is Readiness.Unsupported) error(readiness.reason)

        // 1. Retrieve.
        val passages = if (s.useSources) {
            _generation.value = Generation(sid, answerId, "Searching your library and notes…")
            retrieve(question)
        } else emptyList()

        // 2. Load model (first use only).
        if (readiness is Readiness.Local) {
            _generation.value = Generation(sid, answerId, "Loading ${readiness.modelTitle}…")
            c.llm.ensureChatModel(readiness.file, s.contextSize)
        }
        _generation.value = Generation(sid, answerId, "Thinking…")

        // 3. Generate, shrinking the prompt if it doesn't fit.
        val out = StringBuilder()
        var lastFlush = 0L
        val started = System.currentTimeMillis()
        var pieces = 0
        val onToken: (String) -> Boolean = { piece ->
            out.append(piece)
            pieces++
            val now = System.currentTimeMillis()
            if (now - lastFlush > 80) {
                lastFlush = now
                val secs = (now - started) / 1000.0
                _generation.value = Generation(sid, answerId, "Writing…", if (secs > 0.5) pieces / secs else null, out.toString())
            }
            job?.isActive != false
        }

        val short = s.answerMode != "explain"
        val maxTokens = if (short) 200 else 768
        val system = systemPrompt(short, runCatching { c.gridDown.contextBlock() }.getOrNull())
        // Kept out of the system prompt so the cached prompt prefix stays the same between questions.
        val routed = com.conza.outpost.griddown.AnswerPolicy.route(question, runCatching { c.gridDown.firstAid }.getOrNull())
        val prompt = if (routed.isEmpty()) question else question + "\n\n(A verified checklist or calculator for this is shown to me above your answer. " +
            "Agree with it, point me to it for steps and doses, and never give different numbers.)"
        var historyTurns = if (short) 4 else 6
        var keep = passages.size
        var stats: GenerationStats? = null
        while (stats == null) {
            val used = passages.take(keep)
            val messages = buildMessages(system, history.takeLast(historyTurns), prompt, used)
            out.clear()
            try {
                stats = when (readiness) {
                    is Readiness.Local -> c.llm.generate(messages, maxTokens, s.temperature, onToken)
                    is Readiness.Remote -> remote.chat(s.remoteApi, s.remoteUrl, s.remoteModel, s.remoteApiKey, messages, s.temperature, onToken)
                    else -> error("unreachable")
                }
            } catch (e: PromptTooLongException) {
                when {
                    historyTurns > 0 -> historyTurns = (historyTurns - 2).coerceAtLeast(0)
                    keep > 1 -> keep--
                    else -> throw IllegalStateException("Your message is too long for this model's context window. Try a shorter question or a larger context size in Settings.")
                }
            }
        }
        val used = passages.take(keep)
        val (text, actions) = com.conza.outpost.griddown.AssistantTools.parse(out.toString())
        chats.updateMessage(answerId, text.ifBlank { if (actions.isNotEmpty()) "Here's what I can do:" else "(no answer)" }, Sources.encode(used.map { it.source }))
        if (actions.isNotEmpty()) chats.updateActionState(answerId, com.conza.outpost.griddown.AssistantTools.encodeStates(actions.map { com.conza.outpost.griddown.ActionState(it) }))
        // Unload in Survival mode once the answer is done if the user has left the Assistant.
        if (s.survivalMode && !assistantVisible) runCatching { c.llm.unloadAll() }
        chats.updateSession(sid, chats.session(sid)?.title ?: "Chat")
        _generation.value = Generation(sid, answerId, "Done", stats.tokensPerSecond, out.toString())
    }

    data class Passage(val source: Source, val text: String, val score: Double)

    /** Library (ZIM full-text) + notes/documents, merged and ranked against the question. */
    suspend fun retrieve(question: String, max: Int = 4): List<Passage> {
        val q = TextTools.keywordQuery(question).ifBlank { question }
        val found = mutableListOf<Passage>()

        // Offline library: Xapian full-text search where available, else title matches.
        runCatching {
            val books = c.library.observeBooks().first().filter { it.error == null }
            var hits = c.library.fullText(books, q, perBook = 3)
            if (hits.isEmpty()) hits = c.library.suggest(books, q, perBook = 2)
            for (h in hits.take(8)) {
                val content = c.library.content(h.bookId, h.path) as? ZimContent.Bytes ?: continue
                if (!content.mime.contains("html")) continue
                val text = TextTools.htmlToText(String(content.data, Charsets.UTF_8))
                val chunks = TextTools.chunk(text, 900, 100)
                val best = TextTools.rank(question, chunks).firstOrNull() ?: continue
                found += Passage(Source("zim", h.title, h.bookTitle, h.bookId, h.path), chunks[best.first], best.second)
            }
        }

        // Notes and imported documents.
        runCatching {
            for (r in c.index.search(question, 4)) {
                val src = if (r.chunk.sourceType == PdfPassages.TYPE) PdfPassages.source(r.chunk)
                    else Source(r.chunk.sourceType, r.chunk.sourceTitle, if (r.chunk.sourceType == "note") "Note" else "Document", refId = r.chunk.sourceId)
                // Put notes on the same scale as library passages so neither swamps the other.
                found += Passage(src, r.chunk.text, TextTools.rank(question, listOf(r.chunk.text)).first().second + r.score * 5)
            }
        }

        // One passage per article, best first.
        return found.groupBy { it.source.title + it.source.bookId + it.source.refId }
            .map { (_, ps) -> ps.maxBy { it.score } }
            .sortedByDescending { it.score }
            .filter { it.score > 0.0 }
            .take(max)
    }

    /** Set by the Assistant screen; lets Survival mode free the model's memory when nobody is looking. */
    @Volatile var assistantVisible = false

    fun systemPrompt(short: Boolean, context: String?): String = buildString {
        append(SYSTEM_PROMPT)
        append(if (short) " Answer in at most 80 words: the key steps or facts only." else " Explain fully, with reasons, in clear steps.")
        append(" Never invent drug doses; refer to the label or a pharmacist.")
        if (context != null) append("\n\n").append(context)
        append("\n\n").append(com.conza.outpost.griddown.AssistantTools.TOOL_PROMPT)
    }

    private fun buildMessages(system: String, history: List<ChatMessage>, question: String, passages: List<Passage>): List<Pair<String, String>> {
        val msgs = mutableListOf("system" to system)
        history.filter { it.content.isNotBlank() && !it.content.startsWith("⚠️") }.forEach { msgs += it.role to it.content }
        val user = if (passages.isEmpty()) question else buildString {
            append("Sources from my offline library and notes:\n\n")
            passages.forEachIndexed { i, p ->
                append("[${i + 1}] ${p.source.title}")
                if (p.source.type == PdfPassages.TYPE) append(", ${p.source.subtitle}")
                if (p.source.subtitle.isNotBlank() && p.source.type != PdfPassages.TYPE) append(" (${p.source.subtitle})")
                append("\n").append(p.text.take(1200)).append("\n\n")
            }
            append("Question: ").append(question)
        }
        msgs += "user" to user
        return msgs
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are Outpost, an offline assistant running entirely on the user's phone with no internet access. " +
                "Answer clearly and concisely. When sources are provided, base your answer on them and cite them inline " +
                "like [1] or [2]. If the sources don't contain the answer, say so briefly, then answer from general knowledge. " +
                "For medical, safety, or emergency questions, give practical steps and say when professional help is needed. " +
                "Use plain text; short bullet lists are fine."
    }
}
