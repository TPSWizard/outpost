package com.conza.outpost.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/** Pure-Kotlin text utilities for retrieval. No Android dependencies, so they're unit-tested. */
object TextTools {

    private val DROP_BLOCKS = Regex("(?is)<(script|style|noscript|svg|head|nav|footer|table class=\"(?:infobox|navbox)[^\"]*\")[^>]*>.*?</\\1>")
    private val BLOCK_TAGS = Regex("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|section|article|blockquote|dd|dt)[^>]*>")
    private val TAGS = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val CITE_MARKS = Regex("\\[(?:\\d+|edit|citation needed)]")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n\\s*\\n+")

    /** Readable plain text from an article's HTML. */
    fun htmlToText(html: String): String {
        var s = DROP_BLOCKS.replace(html, " ")
        s = BLOCK_TAGS.replace(s, "\n")
        s = TAGS.replace(s, "")
        s = ENTITY.replace(s) { m -> decodeEntity(m.groupValues[1]) ?: m.value }
        s = CITE_MARKS.replace(s, "")
        s = SPACES.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(s, "\n\n").trim()
    }

    private fun decodeEntity(e: String): String? = when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
        else -> when (e) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            "ndash" -> "–"; "mdash" -> "—"; "hellip" -> "…"; "deg" -> "°"; "times" -> "×"; "rsquo" -> "’"; "lsquo" -> "‘"
            "rdquo" -> "”"; "ldquo" -> "“"; "middot" -> "·"
            else -> null
        }
    }

    /**
     * Split text into passages of roughly [target] characters, breaking on paragraph and
     * sentence boundaries, with a little overlap so facts at a boundary aren't lost.
     */
    fun chunk(text: String, target: Int = 900, overlap: Int = 120): List<String> {
        val units = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { p ->
            if (p.length > target) p.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() } else listOf(p)
        }
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (u in units) {
            if (cur.isNotEmpty() && cur.length + u.length + 1 > target) {
                out += cur.toString().trim()
                val tail = cur.toString().trim().takeLast(overlap).substringAfter(' ', "")
                cur = StringBuilder()
                if (tail.isNotEmpty()) cur.append(tail).append(' ')
            }
            // Hard-split anything still longer than the target (e.g. text with no punctuation).
            var rest = u
            while (rest.length > target * 2) {
                out += rest.take(target)
                rest = rest.drop(target - overlap)
            }
            cur.append(rest).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private val STOP = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "how", "i", "if", "in",
        "is", "it", "its", "me", "my", "of", "on", "or", "should", "so", "that", "the", "their", "there", "this", "to",
        "was", "what", "when", "where", "which", "who", "why", "will", "with", "you", "your", "about", "would", "could",
        "tell", "explain", "please", "know", "get", "use", "used", "using", "we", "our", "they", "them", "any", "some",
    )

    fun terms(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }

    /** Keywords for a full-text query: drop filler words, keep at most [max] terms. */
    fun keywordQuery(question: String, max: Int = 6): String = terms(question).distinct().take(max).joinToString(" ")

    /** BM25-style score of each passage against the query, highest first. */
    fun rank(query: String, passages: List<String>): List<Pair<Int, Double>> =
        rankTerms(query, passages.map { terms(it) })

    /** [rank] for passages already split with [terms], so a stored index isn't re-tokenized per question. */
    fun rankTerms(query: String, docs: List<List<String>>): List<Pair<Int, Double>> {
        val q = terms(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return docs.indices.map { it to 0.0 }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { t -> docs.count { t in it } }
        val n = docs.size
        return docs.mapIndexed { i, d ->
            val tf = d.groupingBy { it }.eachCount()
            var score = 0.0
            for (t in q) {
                val f = tf[t] ?: continue
                val idf = ln(1 + (n - df[t]!! + 0.5) / (df[t]!! + 0.5))
                score += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * d.size / avg))
            }
            i to score
        }.sortedByDescending { it.second }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

    fun toFloats(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }

    /** A short title for a chat from the user's first message. */
    fun titleFrom(message: String): String {
        val t = message.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= 48) t else t.take(45).substringBeforeLast(' ') + "…"
    }
}
