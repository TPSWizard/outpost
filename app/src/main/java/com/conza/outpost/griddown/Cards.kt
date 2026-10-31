package com.conza.outpost.griddown

import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.Medication
import com.conza.outpost.data.PlanItem
import java.time.LocalDate

/** A printable card: a title and sections of short lines. Rendered to PDF by the app; pure so it's testable. */
data class PrintCard(val title: String, val subtitle: String, val sections: List<Pair<String, List<String>>>)

object Cards {
    /** Suggested clock times for N doses a day. The label or prescriber's instructions always win. */
    fun doseTimes(perDay: Double): List<String> = when (perDay.toInt()) {
        0 -> listOf("As needed")
        1 -> listOf("8 AM")
        2 -> listOf("8 AM", "8 PM")
        3 -> listOf("8 AM", "2 PM", "8 PM")
        4 -> listOf("8 AM", "12 PM", "4 PM", "8 PM")
        6 -> listOf("6 AM", "10 AM", "2 PM", "6 PM", "10 PM", "2 AM")
        else -> listOf("${perDay.toInt()} times a day, evenly spaced")
    }

    private fun f(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

    fun familyPlan(members: List<HouseholdMember>, plan: List<PlanItem>, year: Int = LocalDate.now().year): PrintCard = PrintCard(
        "Family emergency plan", "Keep a copy in every go-bag and by the door",
        listOf(
            "Household" to members.map { m ->
                if (m.isPet) "${m.name} (${m.petType.ifBlank { "pet" }})"
                else "${m.name}" + (Needs.age(m, year)?.let { ", age $it" } ?: "") + (if (m.medicalNotes.isNotBlank()) " — ${m.medicalNotes}" else "")
            }.ifEmpty { listOf("(add your household in Outpost)") },
            "Contacts" to plan.filter { it.type == "contact" }.map { "${it.name}: ${it.detail}" }.ifEmpty { listOf("Out-of-area contact: ______________________", "Neighbor: ______________________") },
            "Rally points" to plan.filter { it.type == "rally" }.map { p ->
                "${p.name}" + (if (p.detail.isNotBlank()) " — ${p.detail}" else "") + (if (p.lat != null && p.lon != null) " (%.5f, %.5f)".format(p.lat, p.lon) else "")
            }.ifEmpty { listOf("1. Near home: ______________________", "2. Outside the neighborhood: ______________________") },
            "If we're separated" to listOf(
                "Go to rally point 1. If you can't, go to rally point 2.",
                "Check in on the radio at the top of each hour for 5 minutes (see Radio card).",
                "Leave a dated note at home saying where you went.",
            ),
        ),
    )

    fun medSchedule(members: List<HouseholdMember>, meds: List<Medication>): PrintCard = PrintCard(
        "Medication schedule", "Follow the label; ask a pharmacist before changing doses",
        meds.groupBy { it.memberId }.map { (id, list) ->
            (members.firstOrNull { it.id == id }?.name ?: "Unassigned") to list.map { m ->
                "${m.name}${if (m.critical) " (CRITICAL)" else ""}: ${f(m.dosesPerDay)}×/day at ${doseTimes(m.dosesPerDay).joinToString(", ")}" +
                    (if (m.dosesPerDay > 0) " · ${f(m.onHand)} on hand ≈ ${(m.onHand / m.dosesPerDay).toInt()} days" else "") +
                    (if (m.notes.isNotBlank()) " · ${m.notes}" else "")
            }
        }.ifEmpty { listOf("Medications" to listOf("(none recorded)")) } +
            listOf("Dose log" to listOf("Date / time / who / dose / given by", "________________________________________", "________________________________________")),
    )

    fun quickRef(firstAid: FaFile?, bleachPercent: Double, altitudeM: Int): PrintCard {
        val bleach = listOf(1.0 to "1 L", Needs.GALLON_L to "1 gal", 5 * Needs.GALLON_L to "5 gal").map { (l, label) ->
            val clear = WaterDisinfection.bleachDose(l, bleachPercent, false)
            val cloudy = WaterDisinfection.bleachDose(l, bleachPercent, true)
            "$label: ${clear.friendly} (cloudy: ${cloudy.friendly})"
        }
        // Each situation in a checklist, with its first (most important) action and the manual page.
        val fa = firstAid?.topics.orEmpty().map { t ->
            t.title to t.nodes.filter { it.steps.isNotEmpty() }.map { n ->
                val s = n.steps.first()
                "${n.title}: ${s.text}" + (s.source?.let { " [${it.manual} p.${it.page}]" } ?: "")
            }
        }
        return PrintCard(
            "Water & first-aid quick reference", "Not a substitute for a clinician",
            listOf(
                "Bleach ($bleachPercent% unscented) — stir, wait 30 min, faint chlorine smell" to bleach,
                "Boiling" to listOf("Rolling boil ${WaterDisinfection.boilMinutes(altitudeM.toDouble())} min at your altitude (3 min above 2,000 m). Cool covered."),
                "Food" to listOf("Fridge: 4 h with door closed. Full freezer: 48 h (half full 24 h).", "Throw out perishables above 40 °F / 4 °C for 2+ hours."),
            ) + fa,
        )
    }

    /** Everything someone needs if this phone is dead: written while the battery lasts. */
    fun phoneDies(s: GridSnapshot, plan: List<PlanItem>, bleachPercent: Double): PrintCard {
        val year = LocalDate.now().year
        return PrintCard(
            "If this phone dies", "Printed ${LocalDate.now()}" + (s.day?.let { " · day $it of the outage" } ?: ""),
            listOf(
                "People" to s.members.map { m -> m.name + (Needs.age(m, year)?.let { " ($it)" } ?: "") + (if (m.medicalNotes.isNotBlank()) ": ${m.medicalNotes}" else "") },
                "Critical medications" to s.runways.filter { it.med.critical }.map { r -> "${r.med.name}: ${f(r.med.dosesPerDay)}×/day, ~${r.daysLeft.toInt()} days left" }
                    .ifEmpty { s.runways.take(5).map { r -> "${r.med.name}: ${f(r.med.dosesPerDay)}×/day, ~${r.daysLeft.toInt()} days left" } },
                "Supplies (days left)" to s.statuses.filter { it.daysLeft != null }.map { "${it.category.label}: ~${it.daysLeft!!.toInt()} days — ${it.summary}" },
                "Daily needs" to Needs.household(s.members, s.climate, year).let {
                    listOf("Water %.1f L (%.1f gal) · Food %,.0f kcal".format(it.waterL, it.waterL / Needs.GALLON_L, it.peopleKcal))
                },
                "Contacts & rally points" to plan.map { (if (it.type == "rally") "Rally: " else "") + it.name + (if (it.detail.isNotBlank()) " — ${it.detail}" else "") },
                "Water" to listOf("Bleach: ${WaterDisinfection.bleachDose(Needs.GALLON_L, bleachPercent, false).friendly} per gallon ($bleachPercent%), double if cloudy, wait 30 min. Or boil 1 min."),
                "Radio" to listOf("GMRS/FRS ch 20 (462.675 MHz) · Ham 146.520 MHz · NOAA weather 162.400–162.550 MHz · CB ch 9 emergency."),
            ).filter { it.second.isNotEmpty() },
        )
    }
}
