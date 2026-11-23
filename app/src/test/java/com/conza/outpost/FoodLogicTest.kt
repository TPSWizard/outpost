package com.conza.outpost

import com.conza.outpost.data.InventoryItem
import com.conza.outpost.food.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FoodLogicTest {
    private val content = FoodJson.format.decodeFromString(FoodContent.serializer(), File("src/main/assets/food/content.json").readText())
    private fun recipe(id: String) = content.recipes.single { it.id == id }
    private fun batch(stage: String = "PROCESS") = CanningBatch(recipeId = "green-beans-hot", recipeVersion = content.version, food = "Green beans", date = "2026-11-22", jar = "PINT", jars = 4,
        method = "PRESSURE", gauge = "DIAL", elevationFeet = 0, pressurePsi = 11, processingMinutes = 20, rotationDate = "2027-11-22", stage = stage, preflightComplete = true)

    @Test fun starterShoppingIsExactly324CansAndOwnsNothing() {
        assertEquals(324.0, content.products.filter { it.kind == "COMMERCIAL" }.sumOf { it.target }, 0.0)
        val groups = content.products.filter { it.kind == "COMMERCIAL" }.groupBy { it.category }.values.map { it.sumOf { p -> p.target }.toInt() }.sorted()
        assertEquals(listOf(36, 48, 48, 60, 60, 72), groups)
        assertEquals(0.0, PantryMath.coverage(FoodState(), emptyList()).storedCalories, 0.0)
        assertEquals(75.0, content.products.single { it.id == "white-rice" }.target, 0.0)
        assertEquals(40.0, content.products.single { it.id == "dry-beans" }.target, 0.0)
        assertEquals(35.0, content.products.single { it.id == "pasta" }.target, 0.0)
    }
    @Test fun poundsOuncesKilogramsAndGallonsConvertWithoutMixedUnits() {
        assertEquals(453.59237, PantryMath.grams(1.0, "lb"), 1e-8)
        assertEquals(PantryMath.grams(1.0, "lb"), PantryMath.grams(16.0, "oz"), 1e-8)
        assertEquals(1000.0, PantryMath.grams(1.0, "kg"), 0.0)
        assertEquals(3.785411784, PantryMath.liters(1.0, "gal"), 1e-9)
        assertEquals(1.0, PantryMath.liters(1000.0, "ml"), 0.0)
        assertThrows(IllegalStateException::class.java) { PantryMath.grams(1.0, "can") }
    }
    @Test fun householdCoverageUsesRealLabelsAndConfigurablePeople() {
        val row = InventoryItem(uuid = "beans", category = "FOOD", name = "Label beans", quantity = 100.0, unit = "can", caloriesPerUnit = 400.0)
        val lot = InventoryLot("beans", kind = "COMMERCIAL", proteinPerUnit = 20.0, noCook = true, nutritionSource = "Our product label")
        val state = FoodState(lots = listOf(lot))
        val water = InventoryItem(category = "WATER", name = "Water", quantity = 210.0, unit = "gal")
        val c = PantryMath.coverage(state, listOf(row, water))
        assertEquals(420000.0, c.requiredCalories, 0.0); assertEquals(210.0, c.requiredWaterGallons, 0.0)
        assertEquals(40000.0, c.storedCalories, 0.0); assertEquals(380000.0, c.calorieShortfall, 0.0)
        assertEquals(40000.0 / 14000, c.foodDays, 1e-8); assertEquals(c.foodDays, c.noCookDays, 0.0)
        assertEquals(30.0, c.waterDays, 1e-8); assertEquals(2000.0, c.proteinGrams, 0.0)
        assertEquals(360000.0, PantryMath.coverage(state.copy(activeProfileId = "tampa-6"), listOf(row)).requiredCalories, 0.0)
        assertEquals(540000.0, PantryMath.coverage(state.copy(activeProfileId = "tampa-9"), listOf(row)).requiredCalories, 0.0)
    }
    @Test fun unknownOrHeldFoodDoesNotInventCoverage() {
        val row = InventoryItem(uuid = "x", category = "FOOD", name = "Unknown", quantity = 5.0, unit = "jar", caloriesPerUnit = 500.0)
        val state = FoodState(lots = listOf(InventoryLot("x", safety = "HELD", kind = "HOME")))
        assertEquals(0.0, PantryMath.coverage(state, listOf(row)).storedCalories, 0.0)
        assertEquals(1, PantryMath.coverage(state, listOf(row)).heldLots)
        val legacy = PantryMath.coverage(FoodState(), listOf(row))
        assertEquals(1, legacy.unknownNutrition); assertEquals(0.0, legacy.storedCalories, 0.0)
    }
    @Test fun rotationUsesMonthsAndNeverCertifiesSafety() {
        assertEquals("2027-02-28", PantryMath.rotation("2026-08-31", 6))
        assertEquals("2027-11-22", PantryMath.rotation("2026-11-22", 12))
        val past = InventoryLot("a", rotationDate = "2025-01-01")
        assertTrue(PantryMath.quality(past, LocalDate.parse("2026-11-22")).contains("quality"))
        assertTrue(PantryMath.usable(past)) // A date alone neither discards food nor declares safety.
        assertFalse(PantryMath.usable(past.copy(safety = "COMPROMISED")))
        assertTrue(content.storage.single { it.id == "commercial-low" }.quality.contains("2–5"))
        assertTrue(content.storage.single { it.id == "home" }.quality.contains("year"))
    }
    @Test fun verifiedSchedulesAreRecipeJarGaugeAndAltitudeSpecific() {
        val r = recipe("green-beans-hot")
        assertEquals(CanningProcess(20, 11), CanningRules.process(r, "PINT", "DIAL", 2000))
        assertEquals(CanningProcess(25, 12), CanningRules.process(r, "QUART", "DIAL", 2001))
        assertEquals(13, CanningRules.process(r, "PINT", "DIAL", 4001).psi)
        assertEquals(14, CanningRules.process(r, "PINT", "DIAL", 8000).psi)
        assertEquals(10, CanningRules.process(r, "PINT", "WEIGHTED", 1000).psi)
        assertEquals(15, CanningRules.process(r, "PINT", "WEIGHTED", 1001).psi)
        assertEquals(35, CanningRules.process(recipe("white-potato-cubes"), "PINT", "DIAL", 0).minutes)
        assertEquals(90, CanningRules.process(recipe("dry-beans"), "QUART", "WEIGHTED", 0).minutes)
        assertEquals(90, CanningRules.process(recipe("poultry-boneless-hot"), "QUART", "DIAL", 0).minutes)
        assertEquals(25, CanningRules.process(recipe("chicken-broth"), "QUART", "DIAL", 0).minutes)
        assertEquals(25, CanningRules.process(recipe("peaches"), "PINT", "NONE", 1001).minutes)
        assertEquals(40, CanningRules.process(recipe("peaches"), "QUART", "NONE", 6001).minutes)
    }
    @Test fun noExtrapolatedJarSizesOrUntestedRecipes() {
        assertThrows(IllegalStateException::class.java) { CanningRules.process(recipe("fish-pint"), "QUART", "DIAL", 0) }
        assertThrows(IllegalArgumentException::class.java) { CanningRules.process(recipe("green-beans-hot"), "PINT", "DIAL", 8001) }
        content.recipes.filter { !it.verified }.forEach { r -> assertThrows(IllegalArgumentException::class.java) { CanningRules.process(r, "PINT", "DIAL", 0) }; assertTrue(r.schedules.isEmpty()) }
        assertTrue(content.recipes.count { !it.verified } >= 4)
        val sourceIds = content.sources.map { it.id }.toSet()
        content.recipes.forEach { assertTrue(it.sources.isNotEmpty()); assertTrue(sourceIds.containsAll(it.sources)) }
    }
    @Test fun pressureLossRequiresFullTimerAndUncertainCoolingCannotRestart() {
        val interrupted = FoodTimers.interrupted(batch().copy(timerBoot = 3, timerElapsedEnds = 500))
        assertTrue(interrupted.interrupted); assertFalse(interrupted.processComplete); assertNull(interrupted.timerElapsedEnds)
        val ready = CanningWorkflow.advance(interrupted, 1000, 3, 10000)
        assertEquals(1, ready.restarts); assertEquals("REACH_PROCESS", ready.stage)
        assertThrows(IllegalArgumentException::class.java) { CanningWorkflow.advance(ready, 1000, 3, 10000) }
        val processing = CanningWorkflow.advance(ready.copy(pressureConfirmed = true), 1000, 3, 10000)
        assertEquals(20 * 60000L, FoodTimers.remaining(processing, 1000, 3))
        assertFalse(processing.interrupted)
        assertThrows(IllegalArgumentException::class.java) { CanningWorkflow.advance(FoodTimers.interrupted(batch("NATURAL_COOL")), 1000, 3, 10000) }
    }
    @Test fun timersUseMonotonicClockAndRequireNaturalCoolingAndSealWait() {
        val b = CanningWorkflow.timer(batch(), "PROCESS", 20, 90000, 1000, 3)
        assertNull(FoodTimers.remaining(b, 0, 4))
        assertEquals(1200000L, FoodTimers.remaining(b, 1000, 3))
        assertThrows(IllegalArgumentException::class.java) { CanningWorkflow.advance(b, 1200999, 3, 90000) }
        val cool = CanningWorkflow.advance(b, 1201000, 3, 90000)
        assertEquals("NATURAL_COOL", cool.stage); assertTrue(cool.processComplete)
        val wait = CanningWorkflow.advance(cool, 1201000, 3, 90000)
        assertEquals(600000L, FoodTimers.remaining(wait, 1201000, 3))
        val jars = CanningWorkflow.advance(wait, 1801000, 3, 90000)
        assertEquals("SEAL_WAIT", jars.stage); assertEquals(12 * 3600000L, FoodTimers.remaining(jars, 1801000, 3))
    }
    @Test fun shoppingCasesAndHarvestAllocationsValidate() {
        assertEquals(3, PantryMath.cases(25.0, 12)); assertEquals(0, PantryMath.cases(0.0, 12))
        FoodValidation.harvest(GardenHarvest(food = "Potatoes", date = "2026-11-22", pounds = 15.0, fresh = 5.0, preserved = 8.0, spoiled = 1.0, planting = 1.0))
        assertThrows(IllegalArgumentException::class.java) { FoodValidation.harvest(GardenHarvest(food = "Potatoes", date = "2026-11-22", pounds = 15.0, fresh = 16.0)) }
        assertThrows(IllegalArgumentException::class.java) { FoodValidation.nonnegative(Double.NaN, "Invalid") }
    }
    @Test fun backupRoundTripPreservesTypedMetadataAndRejectsUnsafeValues() {
        val state = FoodState(harvests = listOf(GardenHarvest(food = "Beans", date = "2026-11-22", pounds = 4.0)), targets = listOf(PurchaseTarget("c-chicken", 24.0)))
        val backup = FoodBackup(exportedAt = "2026-11-22", state = state, supplies = listOf(FoodSupply.from(InventoryItem(category = "FOOD", name = "My label", quantity = 2.0, unit = "can"))))
        assertEquals(backup, FoodBackups.decode(FoodBackups.encode(backup)))
        assertThrows(IllegalArgumentException::class.java) { FoodBackups.decode(FoodBackups.encode(backup.copy(format = 999))) }
        assertThrows(IllegalArgumentException::class.java) { FoodBackups.decode(FoodBackups.encode(backup.copy(supplies = backup.supplies.map { it.copy(quantity = -1.0) }))) }
        val csv = FoodBackups.csv(FoodSnapshot(state, listOf(InventoryItem(category = "FOOD", name = "=SUM(1,2)", quantity = 1.0, unit = "can"))))
        assertTrue(csv.contains("'="))
    }
    @Test fun menuIncludesSevenDaysNoCookAndLabelBasedIncompleteCalories() {
        assertEquals((1..7).toSet(), content.meals.map { it.day }.toSet())
        assertTrue(content.meals.any { it.burnerMinutes == 0 }); assertTrue(content.meals.any { it.ingredients.any { i -> i.productId == "white-rice" } })
        val estimate = MealMath.estimate(content.meals.first(), 7, FoodState(), emptyList())
        assertTrue(estimate.incomplete); assertEquals(0.0, estimate.calories, 0.0)
    }
    @Test fun restoredActiveTimersAreHeldEvenIfTheBootCounterHappensToMatch() {
        val active = batch().copy(timerBoot = 3, timerElapsedEnds = 10000)
        val restored = FoodBackups.holdRestoredTimers(FoodState(batches = listOf(active), lots = listOf(InventoryLot("jar", kind = "HOME", safety = "HELD", batchId = active.id))))
        assertEquals("IMPORTED", restored.batches.single().interruptionStage)
        assertNull(restored.batches.single().timerElapsedEnds)
        assertEquals("HELD", restored.lots.single().safety)
        assertThrows(IllegalArgumentException::class.java) { CanningWorkflow.advance(restored.batches.single(), 0, 3, 0) }
    }
    @Test fun waterBathUsesBoilingConfirmationFiveMinuteWaitAndTwelveHourSealCooling() {
        val draft = batch("PREPARE").copy(method = "WATER_BATH", pressurePsi = null, processingMinutes = 15)
        val ready = CanningWorkflow.advance(draft, 0, 3, 0)
        assertEquals("REACH_PROCESS", ready.stage)
        assertThrows(IllegalArgumentException::class.java) { CanningWorkflow.advance(ready, 0, 3, 0) }
        val process = CanningWorkflow.advance(ready.copy(pressureConfirmed = true), 0, 3, 0)
        val wait = CanningWorkflow.advance(process, 15 * 60000L, 3, 0)
        assertEquals("WAIT_REMOVE", wait.stage); assertEquals(300000L, FoodTimers.remaining(wait, 15 * 60000L, 3))
        val cooling = CanningWorkflow.advance(wait, 20 * 60000L, 3, 0)
        assertEquals("SEAL_WAIT", cooling.stage); assertEquals(12 * 3600000L, FoodTimers.remaining(cooling, 20 * 60000L, 3))
    }
}
