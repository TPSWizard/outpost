package com.conza.outpost.griddown

import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.Medication
import kotlin.math.pow

enum class Climate(val label: String) { HOT("Hot"), TEMPERATE("Mild"), COLD("Cold") }

/**
 * Daily needs. Pure functions; every number has a public source:
 * - Water: FEMA/Ready.gov "at least one gallon (3.8 L) per person per day"; hot climates can
 *   double it; children, nursing mothers, and sick people need more.
 * - Calories: USDA Dietary Guidelines 2020–2025 (moderately active), by age band.
 * - Pets: about 60 mL water per kg per day; dog/cat energy from the standard 70·kg^0.75 formula.
 */
object Needs {
    const val GALLON_L = 3.785

    fun age(m: HouseholdMember, year: Int): Int? = m.birthYear?.let { (year - it).coerceAtLeast(0) }

    fun waterLitersPerDay(m: HouseholdMember, climate: Climate, year: Int): Double {
        if (m.isPet) {
            val kg = m.weightKg ?: if (m.petType == "cat") 4.0 else 20.0
            val base = 0.06 * kg
            return base * if (climate == Climate.HOT) 1.5 else 1.0
        }
        var l = GALLON_L
        if (climate == Climate.HOT) l *= 2.0
        if (m.pregnantOrNursing) l *= 1.25
        return l
    }

    fun caloriesPerDay(m: HouseholdMember, climate: Climate, year: Int): Double {
        if (m.isPet) {
            val kg = m.weightKg ?: if (m.petType == "cat") 4.0 else 20.0
            val rer = 70 * kg.pow(0.75)
            return rer * if (m.petType == "cat") 1.2 else 1.6
        }
        val a = age(m, year) ?: 30
        var kcal = when {
            a < 2 -> 900.0
            a <= 3 -> 1100.0
            a <= 8 -> 1500.0
            a <= 13 -> 2000.0
            a <= 18 -> 2400.0
            a <= 50 -> 2300.0
            else -> 2000.0
        }
        kcal *= when (m.activity) { "low" -> 0.9; "high" -> 1.2; else -> 1.0 }
        if (m.pregnantOrNursing) kcal += 400
        if (climate == Climate.COLD) kcal *= 1.1
        return kcal
    }

    data class Household(val waterL: Double, val peopleKcal: Double, val petKcal: Double, val people: Int, val pets: Int)

    fun household(members: List<HouseholdMember>, climate: Climate, year: Int) = Household(
        waterL = members.sumOf { waterLitersPerDay(it, climate, year) },
        peopleKcal = members.filter { !it.isPet }.sumOf { caloriesPerDay(it, climate, year) },
        petKcal = members.filter { it.isPet }.sumOf { caloriesPerDay(it, climate, year) },
        people = members.count { !it.isPet },
        pets = members.count { it.isPet },
    )
}

enum class SupplyCategory(val label: String) {
    WATER("Water"), FOOD("Food"), MEDICINE("Medicine"), FUEL("Fuel"), BATTERIES("Batteries"), OTHER("Other");
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } ?: OTHER }
}

data class CategoryStatus(
    val category: SupplyCategory,
    /** null = not enough information to estimate. */
    val daysLeft: Double?,
    val summary: String,
    val warning: Boolean,
)

data class MedRunway(val med: Medication, val daysLeft: Double) { val warning get() = daysLeft < Rationing.WARN_DAYS }

/** "How many days will our supplies last?" */
object Rationing {
    const val WARN_DAYS = 14.0

    /** Liters per unit from a free-text unit, when the item doesn't say. */
    fun litersPerUnit(item: InventoryItem): Double? = item.litersPerUnit ?: when (item.unit.trim().lowercase().removeSuffix("s")) {
        "l", "liter", "litre" -> 1.0
        "ml" -> 0.001
        "gal", "gallon" -> Needs.GALLON_L
        "qt", "quart" -> 0.946
        "oz", "fl oz" -> 0.0296
        "case of 24 x 0.5 l", "case" -> null
        else -> null
    }

    fun medRunway(meds: List<Medication>): List<MedRunway> =
        meds.filter { it.dosesPerDay > 0 }.map { MedRunway(it, it.onHand / it.dosesPerDay) }.sortedBy { it.daysLeft }

    fun status(
        items: List<InventoryItem>,
        members: List<HouseholdMember>,
        meds: List<Medication>,
        climate: Climate,
        year: Int,
    ): List<CategoryStatus> {
        val need = Needs.household(members, climate, year)
        val byCat = items.groupBy { SupplyCategory.of(it.category) }
        return SupplyCategory.entries.map { cat ->
            val list = byCat[cat].orEmpty()
            when (cat) {
                SupplyCategory.WATER -> {
                    val liters = list.sumOf { it.quantity * (litersPerUnit(it) ?: 0.0) }
                    val unknown = list.count { litersPerUnit(it) == null }
                    val days = if (need.waterL > 0 && list.isNotEmpty()) liters / need.waterL else null
                    CategoryStatus(cat, days,
                        "%.0f L stored · household needs %.1f L/day".format(liters, need.waterL) +
                            if (unknown > 0) " · $unknown item(s) missing a volume" else "",
                        days != null && days < WARN_DAYS)
                }
                SupplyCategory.FOOD -> {
                    val kcal = list.sumOf { it.quantity * (it.caloriesPerUnit ?: 0.0) }
                    val unknown = list.count { it.caloriesPerUnit == null }
                    val days = if (need.peopleKcal > 0 && list.isNotEmpty()) kcal / need.peopleKcal else null
                    CategoryStatus(cat, days,
                        "%,.0f kcal stored · people need %,.0f kcal/day".format(kcal, need.peopleKcal) +
                            if (unknown > 0) " · $unknown item(s) missing calories" else "",
                        days != null && days < WARN_DAYS)
                }
                SupplyCategory.MEDICINE -> {
                    val runway = medRunway(meds)
                    val critical = runway.filter { it.med.critical }
                    val days = (critical.ifEmpty { runway }).minOfOrNull { it.daysLeft }
                        ?: list.filter { (it.dailyUse ?: 0.0) > 0 }.minOfOrNull { it.quantity / it.dailyUse!! }
                    val label = runway.firstOrNull()?.let { "Shortest: ${it.med.name}, %.0f days".format(it.daysLeft) }
                        ?: if (list.isEmpty()) "No medicines recorded" else "${list.size} item(s)"
                    CategoryStatus(cat, days, label, days != null && days < WARN_DAYS)
                }
                else -> {
                    val rated = list.filter { (it.dailyUse ?: 0.0) > 0 }
                    val days = rated.minOfOrNull { it.quantity / it.dailyUse!! }
                    val shortest = rated.minByOrNull { it.quantity / it.dailyUse!! }
                    CategoryStatus(cat, days,
                        when {
                            list.isEmpty() -> "Nothing recorded"
                            shortest != null -> "${list.size} item(s) · runs out first: ${shortest.name}"
                            else -> "${list.size} item(s) · add daily use to estimate days"
                        },
                        days != null && days < WARN_DAYS)
                }
            }
        }
    }
}
