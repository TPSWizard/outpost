package com.conza.outpost.ai

import com.conza.outpost.griddown.FieldCard
import com.conza.outpost.griddown.FieldGuideFile

/** Finds small, reviewed passages that ship inside the APK. */
object BundledKnowledge {
    fun text(card: FieldCard): String = buildString {
        append(card.title).append(". ").append(card.summary).append('\n')
        append("Use when: ").append(card.useWhen).append('\n')
        card.steps.forEach { append("• ").append(it).append('\n') }
        append("Limit: ").append(card.caution).append('\n')
        append("Get help: ").append(card.getHelpWhen)
    }

    fun find(guide: FieldGuideFile, question: String, max: Int = 2): List<Pair<FieldCard, Double>> {
        val queryTerms = TextTools.terms(question).toSet()
        if (queryTerms.isEmpty()) return emptyList()
        val texts = guide.cards.map(::text)
        val ranked = TextTools.rank(question, texts)
        val minimumMatches = if (queryTerms.size > 1) 2 else 1
        return ranked.asSequence()
            .filter { (index, score) -> score > 0 && TextTools.terms(texts[index]).toSet().intersect(queryTerms).size >= minimumMatches }
            .take(max)
            .map { (index, score) -> guide.cards[index] to score }
            .toList()
    }
}
