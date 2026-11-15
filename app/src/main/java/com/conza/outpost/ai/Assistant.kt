package com.conza.outpost.ai

import android.content.Context
import com.conza.outpost.AppContainer
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.data.ChatMessage
import com.conza.outpost.data.ChatSession
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.library.ZimContent
import com.conza.outpost.griddown.FieldGuide
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
    val type: String, // "zim" | "note" | "doc" | "pdf" | "text" | "fieldguide" (path = card id)
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
    private val guide by lazy { context.assets.open("griddown/field-guide.json").bufferedReader().use { FieldGuide.parse(it.readText()) } }
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
        // A reviewed card is safer than a small model improvising during an urgent situation.
        FieldGuide.safetyCardId(question)?.let { cardId ->
            guide.cards.firstOrNull { it.id == cardId }?.let { card ->
                val answer = buildString {
                    append(card.title).append(" [1]\n")
                    card.steps.forEach { append("• ").append(it).append('\n') }
                    append("Important: ").append(card.caution)
                    append("\nOpen the cited field card for sources and limits. Follow current local emergency instructions.")
                }
                chats.updateMessage(answerId, answer, Sources.encode(listOf(Source("fieldguide", card.title, "Bundled offline card", path = card.id))))
                chats.updateSession(sid, chats.session(sid)?.title ?: "Chat")
                _generation.value = Generation(sid, answerId, "Done", text = answer)
                return
            }
        }
        val s = c.settings
        val readiness = readiness()
        if (readiness is Readiness.NeedsModel) error("No AI model installed. Download one from Content → AI models, or connect to a server in Settings.")
        if (readiness is Readiness.Unsupported) error(readiness.reason)

        val short = s.answerMode != "explain"
        val (maxPassages, passageChars) = passageBudget(short)

        // 1. Retrieve.
        val passages = if (s.useSources) {
            _generation.value = Generation(sid, answerId, "Searching your library and notes…")
            retrieve(question, maxPassages, fast = short)
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

        val maxTokens = if (short) 144 else 768
        val system = systemPrompt(short, runCatching { c.gridDown.contextBlock() }.getOrNull(),
            tools = com.conza.outpost.griddown.AssistantTools.wantsAction(question))
        // Kept out of the system prompt so the cached prompt prefix stays the same between questions.
        val routed = com.conza.outpost.griddown.AnswerPolicy.route(question, runCatching { c.gridDown.firstAid }.getOrNull())
        val prompt = if (routed.isEmpty()) question else question + "\n\n(A verified checklist or calculator is available above. " +
            "Answer directly from the supplied passages when possible; use the verified tool for exact steps or doses.)"
        var historyTurns = if (short) 4 else 6
        var keep = passages.size
        var stats: GenerationStats? = null
        while (stats == null) {
            val used = passages.take(keep)
            val messages = buildMessages(system, history.takeLast(historyTurns), prompt, used, passageChars)
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
    suspend fun retrieve(question: String, max: Int = 4, fast: Boolean = false): List<Passage> {
        val q = TextTools.keywordQuery(question).ifBlank { question }
        val bundled = BundledKnowledge.find(guide, question, if (fast) 1 else 2).map { (card, score) ->
            Passage(Source("fieldguide", card.title, "Bundled offline card", path = card.id), BundledKnowledge.text(card), score)
        }
        val found = mutableListOf<Passage>()

        // Local text and PDF passages first. Quick answers often have enough evidence here,
        // avoiding a full-text pass over every downloaded ZIM before the model starts.
        runCatching {
            for (r in c.index.search(question, 4, semantic = !fast)) {
                val src = when (r.chunk.sourceType) {
                    PdfPassages.TYPE -> PdfPassages.source(r.chunk)
                    TextPassages.TYPE -> TextPassages.source(r.chunk)
                    else -> Source(r.chunk.sourceType, r.chunk.sourceTitle, if (r.chunk.sourceType == "note") "Note" else "Document", refId = r.chunk.sourceId)
                }
                // Put notes on the same scale as library passages so neither swamps the other.
                found += Passage(src, r.chunk.text, TextTools.rank(question, listOf(r.chunk.text)).first().second + r.score * 5)
            }
        }

        // Search Kiwix when local passages are insufficient, or for a more detailed answer.
        val distinctLocalSources = found.map { it.source.title + it.source.bookId + it.source.refId }.distinct().size
        if (!fast || bundled.size + distinctLocalSources < max) runCatching {
            val books = c.library.observeBooks().first().filter { it.error == null }
            var hits = c.library.fullText(books, q, perBook = if (fast) 2 else 3)
            if (hits.isEmpty()) hits = c.library.suggest(books, q, perBook = 2)
            for (h in hits.take(if (fast) 4 else 8)) {
                val content = c.library.content(h.bookId, h.path) as? ZimContent.Bytes ?: continue
                if (!content.mime.contains("html")) continue
                val text = TextTools.htmlToText(String(content.data, Charsets.UTF_8))
                val chunks = TextTools.chunk(text, 900, 100)
                val best = TextTools.rank(question, chunks).firstOrNull() ?: continue
                found += Passage(Source("zim", h.title, h.bookTitle, h.bookId, h.path), chunks[best.first], best.second)
            }
        }

        // One passage per article, best first.
        val ranked = found.groupBy { it.source.title + it.source.bookId + it.source.refId }
            .map { (_, ps) -> ps.maxBy { it.score } }
            .sortedByDescending { it.score }
            .filter { it.score > 0.0 }
        return bundled.take(max) + ranked.take((max - bundled.size).coerceAtLeast(0))
    }

    /** Set by the Assistant screen; lets Survival mode free the model's memory when nobody is looking. */
    @Volatile var assistantVisible = false

    /**
     * The system prompt. [tools] adds the ACTION instructions; they are left out for plain questions
     * because every token here is re-read by the phone on each answer (the KV cache only covers a
     * prefix that is identical to the previous turn's).
     */
    fun systemPrompt(short: Boolean, context: String?, tools: Boolean = true): String = buildString {
        append(SYSTEM_PROMPT)
        append(if (short) " Answer in at most 80 words: the key steps or facts only." else " Explain fully, with reasons, in clear steps.")
        append(" Never invent drug doses; refer to the label or a pharmacist.")
        if (context != null) append("\n\n").append(context)
        if (tools) append("\n\n").append(com.conza.outpost.griddown.AssistantTools.TOOL_PROMPT)
    }

    private fun buildMessages(
        system: String, history: List<ChatMessage>, question: String, passages: List<Passage>, passageChars: Int = PASSAGE_CHARS_LONG,
    ): List<Pair<String, String>> {
        val msgs = mutableListOf("system" to system)
        history.filter { it.content.isNotBlank() && !it.content.startsWith("⚠️") }.forEach { msgs += it.role to it.content }
        val user = if (passages.isEmpty()) question else buildString {
            append("Sources from my offline library and notes:\n\n")
            passages.forEachIndexed { i, p ->
                append("[${i + 1}] ${p.source.title}")
                if (p.source.type == PdfPassages.TYPE) append(", ${p.source.subtitle}")
                if (p.source.subtitle.isNotBlank() && p.source.type != PdfPassages.TYPE) append(" (${p.source.subtitle})")
                append("\n").append(p.text.take(passageChars)).append("\n\n")
            }
            append("Question: ").append(question)
        }
        msgs += "user" to user
        return msgs
    }

    companion object {
        const val PASSAGE_CHARS_SHORT = 700
        const val PASSAGE_CHARS_LONG = 1000

        /**
         * How many retrieved passages go into the prompt and how long each may be. Prompt processing
         * on a phone runs at tens of tokens per second, so four 1,200-character passages (~1,200 tokens)
         * were most of the wait before the first word; a short answer rarely needs more than two.
         */
        fun passageBudget(short: Boolean): Pair<Int, Int> =
            if (short) 2 to PASSAGE_CHARS_SHORT else 4 to PASSAGE_CHARS_LONG

        const val SYSTEM_PROMPT =
            "You are Outpost, a practical assistant for offline and emergency use. " +
                "Answer the question directly and concisely; do not merely list places to look. When sources are provided, use their relevant facts and cite them inline " +
                "like [1] or [2]. Do not invent citations. For an ordinary question with no relevant source, answer from model knowledge and label it unverified. " +
                "If a source only partly answers, explain the supported part and the uncertainty. " +
                "For medical, safety, or emergency questions, do not improvise risky procedures or specific doses from general knowledge; " +
                "direct the user to verified checklists, current local instructions, or professional help. " +
                "Use plain text; short bullet lists are fine."
    }
}
