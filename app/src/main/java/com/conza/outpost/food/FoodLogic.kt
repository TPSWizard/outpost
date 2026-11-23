package com.conza.outpost.food

import com.conza.outpost.data.InventoryItem
import com.conza.outpost.griddown.Rationing
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.ceil

object FoodJson {
    val format = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    fun state(text: String): FoodState = format.decodeFromString(FoodState.serializer(), text).also(FoodValidation::state)
    fun encode(state: FoodState): String = format.encodeToString(FoodState.serializer(), state)
}

object FoodValidation {
    fun nonnegative(value: Double, label: String) { require(value.isFinite() && value >= 0) { "$label must be a finite, nonnegative number" } }
    fun date(value: String?) { if (!value.isNullOrBlank()) LocalDate.parse(value) }
    fun profile(profile: HouseholdProfile) {
        require(profile.name.isNotBlank() && profile.people in 1..1000 && profile.days in 1..3650 && profile.extendedDays in 1..3650)
        require(profile.calories in 1..20000)
        nonnegative(profile.waterGallons, "Water"); require(profile.waterGallons > 0)
        nonnegative(profile.proteinGrams, "Protein")
        profile.burnerKw?.let { nonnegative(it, "Burner power") }
        profile.fuelGramsPerHour?.let { nonnegative(it, "Fuel rate") }
    }
    fun state(state: FoodState) {
        require(state.format == 1 && state.profiles.isNotEmpty()) { "Unsupported pantry backup version" }
        state.profiles.forEach(::profile)
        require(state.profiles.map { it.id }.distinct().size == state.profiles.size)
        require(state.profiles.any { it.id == state.activeProfileId })
        require(state.lots.map { it.inventoryUuid }.distinct().size == state.lots.size)
        state.lots.forEach { lot ->
            lot.proteinPerUnit?.let { nonnegative(it, "Protein") }
            lot.volumeLitersPerUnit?.let { nonnegative(it, "Volume") }
            lot.pricePerUnit?.let { nonnegative(it, "Price") }
            date(lot.purchaseDate); date(lot.rotationDate); date(lot.openedDate)
            require(lot.safety in setOf("INSPECT", "UNKNOWN", "HELD", "COMPROMISED"))
            require(lot.kind in setOf("COMMERCIAL", "DRY", "HOME", "CUSTOM"))
            if (lot.kind == "HOME" && lot.safety == "INSPECT") {
                val batch = state.batches.singleOrNull { it.id == lot.batchId }
                require(batch != null && batch.seal == "SEALED" && batch.processComplete && !batch.interrupted) { "Home-canned stock needs a reviewed batch" }
            }
        }
        require(state.locations.map { it.id }.distinct().size == state.locations.size)
        state.locations.forEach { location ->
            require(location.name.isNotBlank())
            location.temperatureF?.let { require(it.isFinite() && it in -100.0..200.0) }
            location.humidityPercent?.let { require(it.isFinite() && it in 0.0..100.0) }
            location.capacityLiters?.let { nonnegative(it, "Capacity") }
        }
        state.targets.forEach { nonnegative(it.desired, "Target"); require(it.caseSize > 0); it.pricePerCase?.let { p -> nonnegative(p, "Price") } }
        require(state.targets.map { it.productId }.distinct().size == state.targets.size)
        state.equipment.forEach { nonnegative(it.owned, "Owned"); nonnegative(it.desired, "Desired"); it.price?.let { p -> nonnegative(p, "Price") } }
        state.harvests.forEach(::harvest)
        state.mealPlans.forEach { require(it.people > 0); date(it.date) }
        state.customMeals.forEach { meal ->
            require(meal.title.isNotBlank() && meal.day in 1..7 && meal.burnerMinutes >= 0 && meal.ingredients.isNotEmpty())
            nonnegative(meal.waterLitersPerPerson, "Meal water")
            meal.ingredients.forEach { nonnegative(it.unitsPerPerson, "Ingredient amount") }
        }
        state.batches.forEach { batch ->
            require(batch.jars in 1..10000 && batch.processingMinutes > 0 && batch.elevationFeet in 0..20000)
            date(batch.date); date(batch.rotationDate)
            require(batch.seal in setOf("UNCHECKED", "SEALED", "FAILED", "COMPROMISED"))
            if (batch.seal == "SEALED") require(batch.processComplete && !batch.interrupted && batch.stage == "COMPLETE")
        }
        for (ids in listOf(state.batches.map { it.id }, state.equipment.map { it.id }, state.harvests.map { it.id }, state.mealPlans.map { it.id }, state.customMeals.map { it.id })) {
            require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Duplicate or blank record ID" }
        }
    }
    fun harvest(h: GardenHarvest) {
        date(h.date); require(h.food.isNotBlank())
        listOf(h.pounds, h.fresh, h.preserved, h.spoiled, h.planting).forEach { nonnegative(it, "Harvest weight") }
        require(h.fresh + h.preserved + h.spoiled + h.planting <= h.pounds + 1e-8) { "Allocations exceed the harvest" }
    }
}

data class FoodCoverage(
    val requiredCalories: Double, val storedCalories: Double, val calorieShortfall: Double,
    val foodDays: Double, val noCookDays: Double, val cannedDays: Double, val dryDays: Double,
    val proteinGrams: Double, val proteinDays: Double, val waterGallons: Double, val requiredWaterGallons: Double,
    val waterDays: Double, val unknownNutrition: Int, val unknownProtein: Int, val heldLots: Int,
)

object PantryMath {
    const val LITERS_PER_GALLON = 3.785411784
    const val GRAMS_PER_POUND = 453.59237
    fun grams(quantity: Double, unit: String): Double {
        FoodValidation.nonnegative(quantity, "Quantity")
        return quantity * when (unit.lowercase()) { "lb" -> GRAMS_PER_POUND; "oz" -> GRAMS_PER_POUND / 16; "kg" -> 1000.0; "g" -> 1.0; else -> error("Unsupported mass unit") }
    }
    fun liters(quantity: Double, unit: String): Double {
        FoodValidation.nonnegative(quantity, "Quantity")
        return quantity * when (unit.lowercase()) { "gal" -> LITERS_PER_GALLON; "l" -> 1.0; "ml" -> 0.001; else -> error("Unsupported volume unit") }
    }
    fun rotation(date: String, months: Int): String = LocalDate.parse(date).plusMonths(months.toLong()).toString()
    fun quality(lot: InventoryLot?, today: LocalDate = LocalDate.now()): String = when {
        lot?.safety == "COMPROMISED" -> "Compromised / discard"
        lot?.safety == "HELD" -> "Held for processing or seal review"
        lot?.safety == "UNKNOWN" -> "Unknown safety status"
        lot?.openedDate != null -> "Opened — check refrigeration and handling"
        lot?.rotationDate == null -> "Inspect before use · rotation date unknown"
        LocalDate.parse(lot.rotationDate).isBefore(today) -> "Past preferred quality window · inspect before use"
        !LocalDate.parse(lot.rotationDate).isAfter(today.plusDays(30)) -> "Approaching rotation date"
        else -> "Within preferred quality window · inspect before use"
    }
    fun usable(lot: InventoryLot?) = lot?.safety !in setOf("COMPROMISED", "HELD", "UNKNOWN") && lot?.openedDate == null
    fun coverage(state: FoodState, supplies: List<InventoryItem>, days: Int = state.profile.days): FoodCoverage {
        val p = state.profile
        val lots = state.lots.associateBy { it.inventoryUuid }
        val food = supplies.filter { it.category == "FOOD" && it.quantity > 0 }
        val available = food.filter { usable(lots[it.uuid]) }
        fun calories(row: InventoryItem) = if (lots[row.uuid]?.nutritionSource?.isNotBlank() == true) row.caloriesPerUnit ?: 0.0 else 0.0
        val total = available.sumOf { it.quantity * calories(it) }
        fun energy(kind: String) = available.filter { lots[it.uuid]?.kind == kind }.sumOf { it.quantity * calories(it) }
        val daily = p.people.toDouble() * p.calories
        val noCook = available.filter { lots[it.uuid]?.noCook == true }.sumOf { it.quantity * calories(it) }
        val protein = available.sumOf { it.quantity * (lots[it.uuid]?.proteinPerUnit ?: 0.0) }
        val gallons = supplies.filter { it.category == "WATER" }.sumOf {
            it.quantity * (it.litersPerUnit ?: runCatching { liters(1.0, it.unit) }.getOrNull() ?: Rationing.litersPerUnit(it) ?: 0.0)
        } / LITERS_PER_GALLON
        val waterPerDay = p.people * p.waterGallons
        return FoodCoverage(daily * days, total, (daily * days - total).coerceAtLeast(0.0), total / daily,
            noCook / daily, energy("COMMERCIAL") / daily, energy("DRY") / daily, protein,
            if (p.proteinGrams > 0) protein / (p.people * p.proteinGrams) else 0.0,
            gallons, waterPerDay * days, gallons / waterPerDay,
            available.count { it.caloriesPerUnit == null || lots[it.uuid]?.nutritionSource.isNullOrBlank() }, available.count { lots[it.uuid]?.proteinPerUnit == null }, food.size - available.size)
    }
    fun owned(productId: String, state: FoodState, supplies: List<InventoryItem>): Double {
        val ids = state.lots.filter { it.productId == productId && usable(it) }.map { it.inventoryUuid }.toSet()
        return supplies.filter { it.uuid in ids }.sumOf { it.quantity }
    }
    fun needed(target: PurchaseTarget, state: FoodState, supplies: List<InventoryItem>) = (target.desired - owned(target.productId, state, supplies)).coerceAtLeast(0.0)
    fun cases(needed: Double, size: Int): Int { require(size > 0); return ceil(needed / size).toInt() }
    fun storageUsed(location: String, state: FoodState, supplies: List<InventoryItem>): Double = state.lots.filter { it.locationId == location }
        .sumOf { lot -> (supplies.firstOrNull { it.uuid == lot.inventoryUuid }?.quantity ?: 0.0) * (lot.volumeLitersPerUnit ?: 0.0) }
}

data class MealEstimate(val calories: Double, val protein: Double, val incomplete: Boolean, val waterLiters: Double, val energyKwh: Double?, val fuelGrams: Double?)

object MealMath {
    fun estimate(meal: ExampleMeal, people: Int, state: FoodState, rows: List<InventoryItem>): MealEstimate {
        var calories = 0.0; var protein = 0.0; var incomplete = false
        for (ingredient in meal.ingredients) {
            val ids = state.lots.filter { it.productId == ingredient.productId && PantryMath.usable(it) }.map { it.inventoryUuid }.toSet()
            val stock = rows.filter { it.uuid in ids && it.quantity > 0 }
            val quantity = stock.sumOf { it.quantity }
            if (stock.isEmpty() || stock.any { it.caloriesPerUnit == null } || stock.any { row -> state.lots.first { it.inventoryUuid == row.uuid }.let { it.proteinPerUnit == null || it.nutritionSource.isBlank() } }) incomplete = true
            if (quantity > 0) {
                calories += stock.sumOf { row -> row.quantity * (if (state.lots.first { it.inventoryUuid == row.uuid }.nutritionSource.isNotBlank()) row.caloriesPerUnit ?: 0.0 else 0.0) } / quantity * ingredient.unitsPerPerson * people
                protein += stock.sumOf { row -> row.quantity * (state.lots.first { it.inventoryUuid == row.uuid }.proteinPerUnit ?: 0.0) } / quantity * ingredient.unitsPerPerson * people
            }
        }
        return MealEstimate(calories, protein, incomplete, meal.waterLitersPerPerson * people,
            state.profile.burnerKw?.times(meal.burnerMinutes / 60.0), state.profile.fuelGramsPerHour?.times(meal.burnerMinutes / 60.0))
    }
}

data class CanningProcess(val minutes: Int, val psi: Int?)

object CanningRules {
    fun process(recipe: CannedFoodRecipe, jar: String, gauge: String, elevationFeet: Int): CanningProcess {
        require(recipe.verified) { "No verified home-canning procedure available." }
        require(elevationFeet in 0..20000) { "Enter a valid elevation in feet" }
        val schedule = recipe.schedules.singleOrNull { it.jar == jar } ?: error("This jar size is not supported")
        if (recipe.method == "PRESSURE") {
            require(elevationFeet <= 8000) { "The bundled pressure table stops at 8,000 feet. Ask Extension for a tested process." }
            val bands = when (gauge) { "DIAL" -> recipe.dial; "WEIGHTED" -> recipe.weighted; else -> error("Select dial or weighted gauge") }
            val pressure = bands.firstOrNull { elevationFeet <= it.maxFeet } ?: error("No pressure schedule at this elevation")
            require(schedule.minutes.size == 1)
            return CanningProcess(schedule.minutes.single(), pressure.psi)
        }
        require(recipe.method == "WATER_BATH" && recipe.waterBathBands.size == schedule.minutes.size)
        val index = recipe.waterBathBands.indexOfFirst { elevationFeet <= it }
        require(index >= 0) { "No tested process at this elevation" }
        return CanningProcess(schedule.minutes[index], null)
    }
}

object FoodTimers {
    fun remaining(batch: CanningBatch, nowElapsed: Long, boot: Long): Long? {
        if (batch.timerBoot != boot || batch.timerElapsedEnds == null) return null
        return (batch.timerElapsedEnds - nowElapsed).coerceAtLeast(0)
    }
    fun interrupted(batch: CanningBatch): CanningBatch = batch.copy(
        interrupted = true, pressureConfirmed = false, processComplete = false, stage = "INTERRUPTED",
        timerEndsAt = null, timerElapsedEnds = null, timerBoot = null, timerKind = null,
        seal = "UNCHECKED",
        interruptionStage = if (batch.stage == "INTERRUPTED") batch.interruptionStage else batch.stage,
    )
}

/** User confirmations, never sensor readings. Timed stages must finish on this boot. */
object CanningWorkflow {
    fun timer(batch: CanningBatch, stage: String, minutes: Int, wall: Long, elapsed: Long, boot: Long) = batch.copy(
        stage = stage, timerKind = stage, timerEndsAt = wall + minutes * 60_000L,
        timerElapsedEnds = elapsed + minutes * 60_000L, timerBoot = boot,
    )
    fun advance(batch: CanningBatch, elapsed: Long, boot: Long, wall: Long): CanningBatch {
        fun done() { require(FoodTimers.remaining(batch, elapsed, boot) == 0L) { "Timer has not completed on this device boot" } }
        fun clear(stage: String) = batch.copy(stage = stage, timerEndsAt = null, timerElapsedEnds = null, timerBoot = null, timerKind = null)
        return when (batch.stage) {
            "PREPARE" -> { require(batch.preflightComplete); if (batch.method == "PRESSURE") timer(batch, "VENT", 10, wall, elapsed, boot) else clear("REACH_PROCESS") }
            "VENT" -> { done(); clear("REACH_PROCESS") }
            "INTERRUPTED" -> {
                require(batch.interruptionStage == "PROCESS") { "Only a witnessed pressure / boil loss during processing supports a full processing restart. Uncertain venting or cooling cannot be rescued here." }
                clear("REACH_PROCESS").copy(restarts = batch.restarts + 1)
            }
            "REACH_PROCESS" -> {
                require(batch.pressureConfirmed) { "Confirm the required pressure or full boil first" }
                timer(batch.copy(interrupted = false), "PROCESS", batch.processingMinutes, wall, elapsed, boot)
            }
            "PROCESS" -> { done(); if (batch.method == "PRESSURE") clear("NATURAL_COOL").copy(processComplete = true)
                else timer(batch.copy(processComplete = true), "WAIT_REMOVE", 5, wall, elapsed, boot) }
            "NATURAL_COOL" -> { require(batch.processComplete); timer(batch, "WAIT_REMOVE", 10, wall, elapsed, boot) }
            "WAIT_REMOVE" -> { done(); timer(batch, "SEAL_WAIT", 12 * 60, wall, elapsed, boot) }
            "SEAL_WAIT" -> { done(); clear("CHECK_SEALS") }
            "CHECK_SEALS" -> { require(batch.processComplete && !batch.interrupted); clear("COMPLETE") }
            else -> error("This batch cannot advance")
        }
    }
}
