package com.conza.outpost.ai

import com.conza.outpost.data.Chunk
import java.io.File

/** Text of one PDF, page by page (index 0 = page 1). Android uses PDFBox-Android; tests use desktop PDFBox. */
fun interface PdfTextSource {
    fun pages(file: File): List<String>
}

/**
 * Page-tagged passages for PDFs, so the assistant can cite "FM 4-25.11, p. 65" and open the
 * viewer at that page. Pure logic; the extraction itself is behind [PdfTextSource].
 */
object PdfPassages {
    const val TYPE = "pdf"

    /** Stable id for a PDF path (chunks are deleted and replaced by path). */
    fun idFor(path: String): Long = path.hashCode().toLong() and 0xffffffffL

    fun chunks(path: String, title: String, pages: List<String>, target: Int = 800, overlap: Int = 100): List<Chunk> {
        var position = 0
        return pages.flatMapIndexed { index, raw ->
            val text = clean(raw)
            if (text.length < 40) emptyList() // blank, image-only, or a page number alone
            else TextTools.chunk(text, target, overlap).map { t ->
                Chunk(sourceType = TYPE, sourceId = idFor(path), sourceTitle = title, position = position++, text = t, sourceRef = path, page = index + 1)
            }
        }
    }

    /** Joins hyphenated line breaks and collapses whitespace that PDF text extraction leaves behind. */
    fun clean(s: String): String = s
        .replace(Regex("""(\w)-\r?\n(\w)"""), "$1$2")
        .replace(Regex("""[ \t ]+"""), " ")
        .replace(Regex("""\s*\r?\n\s*"""), "\n")
        .trim()

    /** The source shown under an answer for a PDF passage. */
    fun source(c: Chunk): Source = Source(TYPE, c.sourceTitle, "p. ${c.page ?: 1}", path = c.sourceRef, refId = (c.page ?: 1).toLong())

    /** Keyword-ranked best PDF passages for [query] (the hybrid search adds embeddings on top when a model is installed). */
    fun search(query: String, chunks: List<Chunk>, k: Int = 3): List<Pair<Chunk, Double>> {
        val pdf = chunks.filter { it.sourceType == TYPE }
        return TextTools.rank(query, pdf.map { "${it.sourceTitle}\n${it.text}" })
            .filter { it.second > 0 }
            .take(k)
            .map { (i, score) -> pdf[i] to score }
    }
}
