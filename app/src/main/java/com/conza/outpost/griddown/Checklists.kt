package com.conza.outpost.griddown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DailyTask(
    val id: String,
    /** WATER | FOOD | HEALTH | SECURITY | COMMS | MORALE */
    val category: String,
    /** Phases (1–4) when this task appears. */
    val phases: List<Int>,
    /** "daily" tasks reset each day; "once" tasks stay done for the rest of the phase. */
    val frequency: String = "daily",
    val text: String,
    val detail: String = "",
    /** Only show when the household has this trait: "pets", "meds", "infant", "cold", "hot". */
    val requires: String? = null,
)

@Serializable
data class ChecklistFile(val version: String, val tasks: List<DailyTask>)

object Checklists {
    val CATEGORIES = listOf("WATER" to "Water", "FOOD" to "Food", "HEALTH" to "Health", "SECURITY" to "Security", "COMMS" to "Communication", "MORALE" to "Morale")

    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): ChecklistFile = json.decodeFromString(ChecklistFile.serializer(), text)

    /** The day key a completion is stored under: today for daily tasks, the phase's first day for once-per-phase tasks. */
    fun doneKey(task: DailyTask, day: Int): Int = if (task.frequency == "once") Outage.phase(day).days.first else day

    fun forDay(file: ChecklistFile, day: Int, traits: Set<String>): List<DailyTask> {
        val phase = Outage.phase(day).number
        return file.tasks.filter { phase in it.phases && (it.requires == null || it.requires in traits) }
    }

    fun validate(file: ChecklistFile): List<String> = buildList {
        val cats = CATEGORIES.map { it.first }.toSet()
        if (file.tasks.map { it.id }.toSet().size != file.tasks.size) add("duplicate task ids")
        file.tasks.forEach { t ->
            if (t.category !in cats) add("${t.id}: unknown category ${t.category}")
            if (t.phases.isEmpty() || t.phases.any { it !in 1..4 }) add("${t.id}: bad phases")
            if (t.frequency !in setOf("daily", "once")) add("${t.id}: bad frequency")
        }
        for (p in 1..4) for (c in cats) if (file.tasks.none { p in it.phases && it.category == c && it.requires == null }) add("phase $p has no $c task")
    }
}
