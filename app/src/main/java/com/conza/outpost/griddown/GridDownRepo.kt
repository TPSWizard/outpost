package com.conza.outpost.griddown

import android.content.Context
import com.conza.outpost.Settings
import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.Medication
import com.conza.outpost.data.OutpostDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.time.LocalDate

/** Everything the Today screen, assistant, and printable cards need, computed from the database. */
data class GridSnapshot(
    val day: Int?,
    val climate: Climate,
    val members: List<HouseholdMember>,
    val meds: List<Medication>,
    val items: List<InventoryItem>,
    val statuses: List<CategoryStatus>,
    val runways: List<MedRunway>,
) {
    val people get() = members.count { !it.isPet }
    val pets get() = members.count { it.isPet }

    fun traits(year: Int = LocalDate.now().year): Set<String> = buildSet {
        if (pets > 0) add("pets")
        if (meds.isNotEmpty()) add("meds")
        if (members.any { !it.isPet && (Needs.age(it, year) ?: 99) < 2 }) add("infant")
        when (climate) { Climate.COLD -> add("cold"); Climate.HOT -> add("hot"); else -> {} }
    }

    val warnings: List<String> get() = statuses.filter { it.warning }.map { "${it.category.label}: ${it.summary}" } +
        runways.filter { it.warning }.map { "${it.med.name}: about %.0f days left".format(it.daysLeft) }
}

class GridDownRepo(private val context: Context, private val db: OutpostDatabase, private val settings: Settings) {
    val firstAid: FaFile by lazy { FirstAid.parse(context.assets.open("griddown/firstaid.json").bufferedReader().use { it.readText() }) }
    val checklists: ChecklistFile by lazy { Checklists.parse(context.assets.open("griddown/checklists.json").bufferedReader().use { it.readText() }) }

    fun climate(): Climate = runCatching { Climate.valueOf(settings.climate) }.getOrDefault(Climate.TEMPERATE)

    fun day(today: LocalDate = LocalDate.now()): Int? = settings.outageStartEpochDay?.let { Outage.day(it, today.toEpochDay()) }

    private fun build(members: List<HouseholdMember>, meds: List<Medication>, items: List<InventoryItem>): GridSnapshot {
        val climate = climate()
        val year = LocalDate.now().year
        return GridSnapshot(day(), climate, members, meds, items, Rationing.status(items, members, meds, climate, year), Rationing.medRunway(meds))
    }

    suspend fun snapshot(): GridSnapshot = build(db.household().members(), db.household().meds(), db.inventory().all())

    fun observe(): Flow<GridSnapshot> =
        combine(db.household().observeMembers(), db.household().observeMeds(), db.inventory().observe()) { m, meds, items -> build(m, meds, items) }

    /** Compact household context for the assistant's system prompt, or null if nothing is set up. */
    suspend fun contextBlock(): String? {
        val s = snapshot()
        if (s.members.isEmpty() && s.items.isEmpty() && s.day == null) return null
        return AnswerPolicy.contextBlock(s.day, s.people, s.pets, s.climate, s.statuses, s.runways)
    }

    /** The downloaded PDF for a manual id from the catalog (e.g. "atp-4-02-11"), if installed. */
    suspend fun manualFile(manualId: String): File? =
        db.installed().get(manualId)?.filePath?.let(::File)?.takeIf { it.isFile }
}
