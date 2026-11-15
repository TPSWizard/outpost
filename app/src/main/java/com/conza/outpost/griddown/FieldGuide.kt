package com.conza.outpost.griddown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Short, bundled reference cards. No download or AI model is required to read them. */
@Serializable
data class FieldSource(val title: String, val url: String)

@Serializable
data class FieldCard(
    val id: String,
    val section: String,
    val title: String,
    val summary: String,
    val useWhen: String,
    val equipment: List<String>,
    val steps: List<String>,
    val caution: String,
    val getHelpWhen: String,
    val sources: List<FieldSource>,
)

@Serializable
data class FieldGuideFile(val version: String, val notice: String, val cards: List<FieldCard>)

object FieldGuide {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): FieldGuideFile = json.decodeFromString(FieldGuideFile.serializer(), text)

    /** These urgent questions use a reviewed card instead of unconstrained model output. */
    fun safetyCardId(question: String): String? {
        val q = question.lowercase()
        return when {
            Regex("\\b(water|well|creek|stream)\\b").containsMatchIn(q) &&
                Regex("\\b(drink|safe|treat|boil|filter|purif|disinfect|contaminat|bleach)" ).containsMatchIn(q) -> "water-safety"
            Regex("\\b(fallout|nuclear blast|radiation emergency|radioactive)\\b").containsMatchIn(q) -> "fallout"
            Regex("\\b(chemical release|toxic gas|gas leak|hazmat|chemical emergency)\\b").containsMatchIn(q) -> "chemical-release"
            Regex("\\b(drone|uas|quadcopter)\\b").containsMatchIn(q) -> "unsafe-drone"
            Regex("\\b(explosion|blast|bombardment|shelling)\\b").containsMatchIn(q) -> "blast-debris"
            Regex("\\b(intruder|break.in|breaking into|home invasion|active shooter|armed attack|shooting|violent threat)\\b").containsMatchIn(q) -> "violent-threat"
            Regex("\\b(canning|can food|preserv(e|ing) food|botulism)\\b").containsMatchIn(q) -> "food-storage"
            Regex("\\b(mushroom|forag|wild plant|wild berr)" ).containsMatchIn(q) -> "wild-food"
            Regex("\\b(lost in|lost outdoors|lost in the woods|lost on a trail)\\b").containsMatchIn(q) -> "lost-outdoors"
            Regex("\\b(lightning|thunderstorm)\\b").containsMatchIn(q) -> "weather-exposure"
            Regex("\\b(generator|carbon monoxide|camp stove indoors)\\b").containsMatchIn(q) -> "fire-safety"
            Regex("\\b(bleeding|severe burn|broken bone|fracture|hypothermia|heat stroke|choking|cpr)\\b").containsMatchIn(q) -> "first-aid"
            else -> null
        }
    }

    fun validate(file: FieldGuideFile): List<String> = buildList {
        val ids = mutableSetOf<String>()
        if (file.notice.isBlank()) add("Guide notice is missing")
        for (card in file.cards) {
            if (!ids.add(card.id)) add("Duplicate card: ${card.id}")
            if (card.id.isBlank() || card.section.isBlank() || card.title.isBlank() || card.summary.isBlank() ||
                card.useWhen.isBlank() || card.equipment.isEmpty() || card.getHelpWhen.isBlank() ||
                card.steps.isEmpty() || card.steps.any(String::isBlank) || card.caution.isBlank()
            ) add("${card.id}: incomplete card")
            if (card.sources.isEmpty() || card.sources.any { it.title.isBlank() || !it.url.startsWith("https://") })
                add("${card.id}: missing or invalid source")
        }
    }
}
