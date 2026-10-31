package com.conza.outpost.griddown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Deterministic first-aid checklists (never AI-generated at runtime). Each step cites a page
 * in a bundled field manual: [FaSource.manual] is a catalog id (e.g. "atp-4-02-11") and
 * [FaSource.page] the 1-based PDF page, so "Open manual" lands on the right page.
 */
@Serializable
data class FaSource(val manual: String, val page: Int)

@Serializable
data class FaStep(val text: String, val detail: String? = null, val source: FaSource? = null, val urgent: Boolean = false)

@Serializable
data class FaNode(
    val id: String,
    /** A yes/no question; null for a step list. */
    val question: String? = null,
    val yes: String? = null,
    val no: String? = null,
    val title: String = "",
    val steps: List<FaStep> = emptyList(),
    /** Next node after the steps, or null to finish. */
    val next: String? = null,
)

@Serializable
data class FaTopic(
    val id: String,
    val title: String,
    val summary: String,
    val start: String,
    val nodes: List<FaNode>,
    val keywords: List<String> = emptyList(),
) {
    fun node(id: String) = nodes.firstOrNull { it.id == id }
}

@Serializable
data class FaFile(val version: String, val manuals: Map<String, String>, val topics: List<FaTopic>)

object FirstAid {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): FaFile = json.decodeFromString(FaFile.serializer(), text)

    /** Problems with a checklist file: broken links, unreachable nodes, steps without sources. Empty = valid. */
    fun validate(f: FaFile): List<String> = buildList {
        for (t in f.topics) {
            val ids = t.nodes.map { it.id }.toSet()
            if (t.start !in ids) add("${t.id}: start '${t.start}' missing")
            if (ids.size != t.nodes.size) add("${t.id}: duplicate node ids")
            for (n in t.nodes) {
                if (n.question != null) {
                    if (n.yes == null || n.no == null) add("${t.id}/${n.id}: question needs yes and no")
                    if (n.steps.isNotEmpty()) add("${t.id}/${n.id}: question nodes must not have steps")
                } else if (n.steps.isEmpty()) add("${t.id}/${n.id}: step node has no steps")
                listOfNotNull(n.yes, n.no, n.next).forEach { if (it !in ids) add("${t.id}/${n.id}: link to missing '$it'") }
                n.steps.forEach { s ->
                    if (s.source == null) add("${t.id}/${n.id}: step without a source: ${s.text.take(40)}")
                    else if (s.source.manual !in f.manuals) add("${t.id}/${n.id}: unknown manual ${s.source.manual}")
                }
            }
            // Reachability + no cycles (checklists must always end).
            val seen = mutableSetOf<String>()
            fun walk(id: String, path: Set<String>) {
                if (id in path) { add("${t.id}: cycle at $id"); return }
                seen += id
                val n = t.node(id) ?: return
                listOfNotNull(n.yes, n.no, n.next).forEach { walk(it, path + id) }
            }
            if (t.start in ids) walk(t.start, emptySet())
            (ids - seen).forEach { add("${t.id}: node '$it' is unreachable") }
        }
    }

    /** Picks the checklist that best matches a question (used by the assistant's answer policy). */
    fun match(f: FaFile, question: String): FaTopic? {
        val q = question.lowercase()
        return f.topics.map { t -> t to t.keywords.count { k -> Regex("\\b${Regex.escape(k)}").containsMatchIn(q) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}
