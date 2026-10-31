package com.conza.outpost.griddown

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Emergency disinfection of drinking water with household bleach, per the EPA
 * ("Emergency Disinfection of Drinking Water"): 8 drops of 6% unscented bleach per gallon of
 * clear water (6 drops of 8.25%), double for cloudy or very cold water, stir, wait 30 minutes.
 * The water should smell faintly of chlorine; if not, repeat the dose and wait 15 more minutes.
 */
object WaterDisinfection {
    /** EPA's table works out to one drop ≈ 0.077 mL (16 drops = 1/4 teaspoon). */
    const val ML_PER_DROP = 1.232 / 16
    const val ML_PER_TSP = 4.929

    data class Dose(
        val drops: Int,
        val milliliters: Double,
        val teaspoons: Double,
        val waitMinutes: Int,
        val instructions: List<String>,
    ) {
        val friendly: String get() = when {
            drops <= 40 -> "$drops drops"
            else -> "%.1f mL (about %s)".format(milliliters, tspText(teaspoons))
        }
    }

    fun tspText(t: Double): String = when {
        t < 0.2 -> "a pinch under 1/4 tsp"
        t < 0.375 -> "1/4 tsp"
        t < 0.625 -> "1/2 tsp"
        t < 0.875 -> "3/4 tsp"
        else -> "%.1f tsp".format(t)
    }

    fun bleachDose(liters: Double, bleachPercent: Double, cloudy: Boolean): Dose {
        require(liters > 0) { "Enter how much water" }
        require(bleachPercent in 4.0..10.0) { "Household bleach is 5–9% sodium hypochlorite; check the label" }
        val gallons = liters / Needs.GALLON_L
        val dropsPerGallon = 8.0 * 6.0 / bleachPercent * if (cloudy) 2 else 1
        val drops = ceil(dropsPerGallon * gallons - 1e-9).toInt().coerceAtLeast(1)
        val ml = drops * ML_PER_DROP
        return Dose(
            drops, ml, ml / ML_PER_TSP, 30,
            listOfNotNull(
                if (cloudy) "Let cloudy water settle, then filter it through a clean cloth or coffee filter first." else null,
                "Use plain, unscented bleach (no scents, color-safe, or added cleaners).",
                "Add the bleach, stir well, and let it stand 30 minutes.",
                "It should have a slight chlorine smell. If not, repeat the dose and wait another 15 minutes.",
                "If it still doesn't smell of chlorine, find another water source.",
            ),
        )
    }

    /** CDC: rolling boil for 1 minute; 3 minutes above 6,500 ft (1,981 m). */
    fun boilMinutes(altitudeMeters: Double): Int = if (altitudeMeters >= 1981) 3 else 1
}

/**
 * Food safety when the power goes out (USDA/FoodSafety.gov): an unopened refrigerator keeps food
 * safe about 4 hours; a full freezer about 48 hours (24 if half full) with the door closed.
 * Perishable food above 40 °F (4 °C) for more than 2 hours should be thrown out.
 */
object FoodSafety {
    enum class Appliance { FRIDGE, FREEZER }

    data class Result(
        val safeHours: Double,
        val hoursLeft: Double,
        val verdict: String,
        val discardIfWarm: List<String>,
        val usuallyKeep: List<String>,
    )

    val PERISHABLE = listOf(
        "Raw or cooked meat, poultry, and seafood", "Milk, cream, yogurt, and soft cheeses", "Eggs and egg dishes",
        "Cooked leftovers, casseroles, soups", "Cut fruit and cooked vegetables", "Deli meats, hot dogs", "Mayonnaise-based foods",
    )
    val USUALLY_KEEP = listOf(
        "Hard cheeses (cheddar, parmesan)", "Butter and margarine", "Whole raw fruits and vegetables",
        "Bread, rolls, and cakes without cream filling", "Peanut butter, jelly, ketchup, mustard, relish", "Opened vinegar-based dressings",
    )

    fun check(appliance: Appliance, hoursWithoutPower: Double, full: Boolean = true, doorOpenedOften: Boolean = false): Result {
        var limit = when (appliance) { Appliance.FRIDGE -> 4.0; Appliance.FREEZER -> if (full) 48.0 else 24.0 }
        if (doorOpenedOften) limit /= 2
        val left = (limit - hoursWithoutPower).coerceAtLeast(0.0)
        val verdict = when {
            left > 1 -> "Safe for about %.0f more hours if you keep the door closed.".format(left)
            left > 0 -> "Less than an hour left. Eat or cook perishables now, or move them to a cooler with ice."
            appliance == Appliance.FREEZER -> "Past the safe window. Food that still has ice crystals or is at 40 °F (4 °C) or below can be refrozen or cooked; throw out anything warmer."
            else -> "Past the safe window. Throw out perishables that have been above 40 °F (4 °C) for more than 2 hours. When in doubt, throw it out."
        }
        return Result(limit, left, verdict, PERISHABLE, USUALLY_KEEP)
    }
}

/** Estimated battery life and a daily power plan. */
object PowerModel {
    data class Sample(val at: Long, val percent: Float, val charging: Boolean)

    /**
     * Average battery drain in %/hour over the last [lookbackHours], using only stretches where
     * the phone was on battery. Recent stretches count more (half-life 12 h).
     */
    fun drainPercentPerHour(samples: List<Sample>, now: Long, lookbackHours: Double = 48.0): Double? {
        val s = samples.filter { now - it.at <= lookbackHours * 3_600_000 }.sortedBy { it.at }
        var weightedDrop = 0.0
        var weightedHours = 0.0
        for ((a, b) in s.zipWithNext()) {
            val hours = (b.at - a.at) / 3_600_000.0
            if (hours < 0.08 || a.charging || b.charging) continue
            val drop = (a.percent - b.percent).toDouble()
            if (drop < 0) continue // charged in between without us seeing it
            val ageH = (now - b.at) / 3_600_000.0
            val w = Math.pow(0.5, ageH / 12.0)
            weightedDrop += drop * w
            weightedHours += hours * w
        }
        if (weightedHours < 0.5) return null
        return (weightedDrop / weightedHours).coerceAtLeast(0.05)
    }

    /**
     * Idle drain (%/hour) with Survival mode off vs on, from samples tagged with the mode.
     * Only consecutive on-battery pairs in the same mode, less than 4 hours apart, count.
     */
    fun drainByMode(samples: List<Pair<Sample, Boolean>>): Map<Boolean, Double> {
        val drop = mutableMapOf<Boolean, Double>(); val hours = mutableMapOf<Boolean, Double>()
        for ((a, b) in samples.sortedBy { it.first.at }.zipWithNext()) {
            if (a.second != b.second || a.first.charging || b.first.charging) continue
            val h = (b.first.at - a.first.at) / 3_600_000.0
            val d = (a.first.percent - b.first.percent).toDouble()
            if (h < 0.08 || h > 4 || d < 0) continue
            drop[a.second] = (drop[a.second] ?: 0.0) + d
            hours[a.second] = (hours[a.second] ?: 0.0) + h
        }
        return hours.filterValues { it >= 1.0 }.mapValues { (k, h) -> drop[k]!! / h }
    }

    fun hoursLeft(percent: Double, drainPerHour: Double): Double = percent / drainPerHour

    fun describeHours(h: Double): String = when {
        h >= 48 -> "≈ %.1f days at current use".format(h / 24)
        h >= 1 -> "≈ %.0f hours at current use".format(h)
        else -> "less than an hour left"
    }

    @kotlinx.serialization.Serializable
    data class BudgetInput(
        val phoneWh: Double = 17.6,          // Pixel 8: 4,575 mAh × 3.85 V
        val batteryPercent: Double = 100.0,
        val solarWatts: Double = 0.0,
        val sunHours: Double = 4.0,
        val powerBankWh: Double = 0.0,
        val carHoursPerDay: Double = 0.0,
        val screenHoursPerDay: Double = 2.0,
        val aiAnswersPerDay: Int = 5,
        val aiPercentPerAnswer: Double = 0.3,
        val mapMinutesPerDay: Double = 10.0,
        val idlePercentPerHour: Double = 0.7,
    )

    data class Budget(
        val useWhPerDay: Double,
        val inWhPerDay: Double,
        val netWhPerDay: Double,
        /** Days until empty, or null if charging keeps up. */
        val daysUntilEmpty: Double?,
        val plan: List<String>,
    )

    /** Rough phone power draws: screen on ≈ 1.5 W, maps ≈ 2.5 W; panel-to-phone efficiency ≈ 50 %. */
    fun budget(i: BudgetInput): Budget {
        val pctToWh = i.phoneWh / 100.0
        val idleHours = (24 - i.screenHoursPerDay).coerceAtLeast(0.0)
        val use = i.screenHoursPerDay * 1.5 + idleHours * i.idlePercentPerHour * pctToWh +
            i.aiAnswersPerDay * i.aiPercentPerAnswer * pctToWh + i.mapMinutesPerDay / 60.0 * 2.5
        val solar = i.solarWatts * i.sunHours * 0.5
        val car = i.carHoursPerDay * 10.0 * 0.8
        val incoming = solar + car
        val net = incoming - use
        val stored = i.batteryPercent * pctToWh + i.powerBankWh * 0.7
        val days = if (net >= 0) null else stored / -net
        val plan = buildList {
            add("Your phone uses about %.1f Wh a day (a full charge is %.1f Wh).".format(use, i.phoneWh))
            if (solar > 0) add("Solar adds about %.1f Wh a day. Charge between 10 am and 3 pm with the panel facing the sun.".format(solar))
            if (car > 0) add("Car charging adds about %.1f Wh a day. Run the engine outdoors only; never in a garage.".format(car))
            if (i.powerBankWh > 0) add("Your power bank holds about %.0f usable Wh, about %.1f phone charges. Save it for cloudy days.".format(i.powerBankWh * 0.7, i.powerBankWh * 0.7 / i.phoneWh))
            if (days == null) add("Charging keeps up with use. You have about %.1f Wh to spare each day.".format(net))
            else {
                add("At this rate you'll run out in about %.1f days.".format(days))
                val screenCut = (-net / 1.5).coerceAtMost(i.screenHoursPerDay)
                add("To break even: cut screen time by %.1f hours, or add about %.0f W of solar.".format(screenCut, -net / (i.sunHours * 0.5)))
            }
            add("Turn on Survival mode, keep the phone in airplane mode except when you need radios, and lower brightness.")
        }
        return Budget(use, incoming, net, days, plan)
    }
}

/** Barometric trend from phone pressure readings (hPa). */
object Pressure {
    enum class Trend(val label: String, val advice: String) {
        FALLING_FAST("Falling fast", "Storm likely within 12–24 hours. Secure loose items, fill water containers, charge devices now."),
        FALLING("Falling", "Weather may worsen. Keep an eye on the sky."),
        STEADY("Steady", "No big change expected."),
        RISING("Rising", "Weather is likely improving."),
        UNKNOWN("Not enough readings", "Keep Outpost logging for a few hours to see a trend."),
    }

    data class Reading(val at: Long, val hPa: Float)

    /** Change over the last ~3 hours, scaled to a 3-hour rate. */
    fun trend(samples: List<Reading>, now: Long): Pair<Trend, Double?> {
        val recent = samples.filter { now - it.at <= 3.5 * 3_600_000 }.sortedBy { it.at }
        if (recent.size < 2) return Trend.UNKNOWN to null
        val first = recent.first(); val last = recent.last()
        val hours = (last.at - first.at) / 3_600_000.0
        if (hours < 1.0) return Trend.UNKNOWN to null
        val per3h = (last.hPa - first.hPa) / hours * 3.0
        val t = when {
            per3h <= -3.0 -> Trend.FALLING_FAST
            per3h <= -1.0 -> Trend.FALLING
            per3h >= 1.0 -> Trend.RISING
            else -> Trend.STEADY
        }
        return t to per3h
    }
}

/** Day N of the outage and its phase. */
object Outage {
    data class Phase(val number: Int, val name: String, val days: IntRange)

    val PHASES = listOf(
        Phase(1, "First 72 hours", 1..3),
        Phase(2, "Settling in", 4..14),
        Phase(3, "Long haul", 15..45),
        Phase(4, "Sustain", 46..90),
    )

    fun day(startEpochDay: Long, todayEpochDay: Long): Int = (todayEpochDay - startEpochDay + 1).toInt().coerceAtLeast(1)

    fun phase(day: Int): Phase = PHASES.firstOrNull { day in it.days } ?: PHASES.last()

    fun roundPercent(x: Double) = (x * 10).roundToInt() / 10.0
}
