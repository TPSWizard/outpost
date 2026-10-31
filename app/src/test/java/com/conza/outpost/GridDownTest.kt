package com.conza.outpost

import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.Medication
import com.conza.outpost.griddown.AnswerPolicy
import com.conza.outpost.griddown.AssistantTools
import com.conza.outpost.griddown.ActionState
import com.conza.outpost.griddown.Checklists
import com.conza.outpost.griddown.Climate
import com.conza.outpost.griddown.FirstAid
import com.conza.outpost.griddown.FoodSafety
import com.conza.outpost.griddown.Needs
import com.conza.outpost.griddown.Outage
import com.conza.outpost.griddown.PowerModel
import com.conza.outpost.griddown.Pressure
import com.conza.outpost.griddown.ProposedAction
import com.conza.outpost.griddown.Rationing
import com.conza.outpost.griddown.SunMoon
import com.conza.outpost.griddown.SupplyCategory
import com.conza.outpost.griddown.WaterDisinfection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class GridDownTest {
    private val year = 2026
    private val adult = HouseholdMember(name = "A", birthYear = 1985)
    private val adult2 = HouseholdMember(name = "B", birthYear = 1987)
    private val kid = HouseholdMember(name = "C", birthYear = 2016) // 10
    private val toddler = HouseholdMember(name = "D", birthYear = 2024) // 2
    private val dog = HouseholdMember(name = "Rex", isPet = true, petType = "dog", weightKg = 20.0)
    private val family = listOf(adult, adult2, kid, toddler)

    // ---------- Water disinfection (EPA table) ----------
    @Test fun bleachMatchesEpaTable() {
        assertEquals(8, WaterDisinfection.bleachDose(Needs.GALLON_L, 6.0, cloudy = false).drops)
        assertEquals(16, WaterDisinfection.bleachDose(Needs.GALLON_L, 6.0, cloudy = true).drops)
        assertEquals(6, WaterDisinfection.bleachDose(Needs.GALLON_L, 8.25, cloudy = false).drops)
        assertEquals(2, WaterDisinfection.bleachDose(0.946, 6.0, cloudy = false).drops) // 1 quart
        assertEquals(16, WaterDisinfection.bleachDose(2 * Needs.GALLON_L, 6.0, cloudy = false).drops) // 2 gal = 1/4 tsp
        val five = WaterDisinfection.bleachDose(5 * Needs.GALLON_L, 6.0, cloudy = false)
        assertEquals(40, five.drops)
        assertEquals(30, five.waitMinutes)
        assertEquals(1.232 * 2.5, five.milliliters, 0.01)
    }

    @Test(expected = IllegalArgumentException::class) fun bleachRejectsWrongConcentration() {
        WaterDisinfection.bleachDose(10.0, 50.0, false)
    }

    @Test fun boilTimeByAltitude() {
        assertEquals(1, WaterDisinfection.boilMinutes(0.0))
        assertEquals(1, WaterDisinfection.boilMinutes(1900.0))
        assertEquals(3, WaterDisinfection.boilMinutes(2000.0))
    }

    // ---------- Food safety (USDA) ----------
    @Test fun fridgeAndFreezerWindows() {
        assertEquals(2.0, FoodSafety.check(FoodSafety.Appliance.FRIDGE, 2.0).hoursLeft, 1e-9)
        assertEquals(0.0, FoodSafety.check(FoodSafety.Appliance.FRIDGE, 6.0).hoursLeft, 1e-9)
        assertEquals(48.0, FoodSafety.check(FoodSafety.Appliance.FREEZER, 0.0, full = true).safeHours, 1e-9)
        assertEquals(24.0, FoodSafety.check(FoodSafety.Appliance.FREEZER, 0.0, full = false).safeHours, 1e-9)
        assertEquals(2.0, FoodSafety.check(FoodSafety.Appliance.FRIDGE, 0.0, doorOpenedOften = true).safeHours, 1e-9)
        assertTrue(FoodSafety.check(FoodSafety.Appliance.FREEZER, 50.0).verdict.contains("ice crystals"))
    }

    // ---------- Needs ----------
    @Test fun waterNeeds() {
        assertEquals(Needs.GALLON_L, Needs.waterLitersPerDay(adult, Climate.TEMPERATE, year), 1e-9)
        assertEquals(2 * Needs.GALLON_L, Needs.waterLitersPerDay(adult, Climate.HOT, year), 1e-9)
        assertEquals(1.2, Needs.waterLitersPerDay(dog, Climate.TEMPERATE, year), 1e-9)
        assertEquals(4 * Needs.GALLON_L, Needs.household(family, Climate.TEMPERATE, year).waterL, 1e-9)
    }

    @Test fun calorieNeedsByAgeActivityClimate() {
        assertEquals(2300.0, Needs.caloriesPerDay(adult, Climate.TEMPERATE, year), 1e-9)
        assertEquals(2000.0, Needs.caloriesPerDay(kid, Climate.TEMPERATE, year), 1e-9)
        assertEquals(1100.0, Needs.caloriesPerDay(toddler, Climate.TEMPERATE, year), 1e-9)
        assertEquals(2300 * 1.2 * 1.1, Needs.caloriesPerDay(adult.copy(activity = "high"), Climate.COLD, year), 1e-6)
        assertEquals(2700.0, Needs.caloriesPerDay(adult.copy(pregnantOrNursing = true), Climate.TEMPERATE, year), 1e-9)
        val hh = Needs.household(family + dog, Climate.TEMPERATE, year)
        assertEquals(2300.0 + 2300 + 2000 + 1100, hh.peopleKcal, 1e-9)
        assertEquals(4, hh.people); assertEquals(1, hh.pets)
    }

    // ---------- Rationing ----------
    @Test fun daysRemainingAndWarnings() {
        val items = listOf(
            InventoryItem(category = "WATER", name = "jugs", quantity = 10.0, unit = "gal"),
            InventoryItem(category = "WATER", name = "bottles", quantity = 24.0, unit = "bottle", litersPerUnit = 0.5),
            InventoryItem(category = "FOOD", name = "rice", quantity = 20.0, unit = "lb", caloriesPerUnit = 1650.0),
            InventoryItem(category = "FOOD", name = "mystery cans", quantity = 5.0),
            InventoryItem(category = "FUEL", name = "propane", quantity = 2.0, unit = "tank", dailyUse = 0.1),
            InventoryItem(category = "BATTERIES", name = "AA", quantity = 24.0, dailyUse = 4.0),
        )
        val meds = listOf(Medication(memberId = 1, name = "Insulin", dosesPerDay = 2.0, onHand = 40.0, critical = true),
            Medication(memberId = 1, name = "Vitamin D", dosesPerDay = 1.0, onHand = 10.0))
        val s = Rationing.status(items, family, meds, Climate.TEMPERATE, year).associateBy { it.category }
        val waterL = 10 * Needs.GALLON_L + 12.0
        assertEquals(waterL / (4 * Needs.GALLON_L), s[SupplyCategory.WATER]!!.daysLeft!!, 1e-6)
        assertTrue(s[SupplyCategory.WATER]!!.warning) // ~3.3 days
        assertEquals(33000.0 / 7700.0, s[SupplyCategory.FOOD]!!.daysLeft!!, 1e-6)
        assertTrue(s[SupplyCategory.FOOD]!!.summary.contains("missing calories"))
        assertEquals(20.0, s[SupplyCategory.MEDICINE]!!.daysLeft!!, 1e-9) // critical med wins over vitamin
        assertFalse(s[SupplyCategory.MEDICINE]!!.warning)
        assertEquals(20.0, s[SupplyCategory.FUEL]!!.daysLeft!!, 1e-9)
        assertEquals(6.0, s[SupplyCategory.BATTERIES]!!.daysLeft!!, 1e-9)
        assertTrue(s[SupplyCategory.BATTERIES]!!.warning)
        assertNull(s[SupplyCategory.OTHER]!!.daysLeft)
    }

    @Test fun medicationRunwaySortedShortestFirst() {
        val r = Rationing.medRunway(listOf(
            Medication(memberId = 1, name = "X", dosesPerDay = 1.0, onHand = 30.0),
            Medication(memberId = 1, name = "Y", dosesPerDay = 3.0, onHand = 30.0),
            Medication(memberId = 1, name = "Z", dosesPerDay = 0.0, onHand = 30.0),
        ))
        assertEquals(listOf("Y", "X"), r.map { it.med.name })
        assertTrue(r.first().warning)
    }

    @Test fun unitConversion() {
        assertEquals(Needs.GALLON_L, Rationing.litersPerUnit(InventoryItem(category = "WATER", name = "", quantity = 1.0, unit = "gallons"))!!, 1e-9)
        assertEquals(0.001, Rationing.litersPerUnit(InventoryItem(category = "WATER", name = "", quantity = 1.0, unit = "mL"))!!, 1e-9)
        assertNull(Rationing.litersPerUnit(InventoryItem(category = "WATER", name = "", quantity = 1.0, unit = "box")))
    }

    // ---------- Power ----------
    @Test fun drainEstimateIgnoresCharging() {
        val h = 3_600_000L
        val now = 100 * h
        val samples = listOf(
            PowerModel.Sample(now - 10 * h, 90f, false),
            PowerModel.Sample(now - 6 * h, 82f, false), // 2 %/h
            PowerModel.Sample(now - 5 * h, 95f, true),  // charging: skipped
            PowerModel.Sample(now - 2 * h, 89f, false),
            PowerModel.Sample(now, 85f, false),          // 2 %/h
        )
        assertEquals(2.0, PowerModel.drainPercentPerHour(samples, now)!!, 1e-9)
        assertNull(PowerModel.drainPercentPerHour(samples.take(1), now))
        assertEquals("≈ 43 hours at current use", PowerModel.describeHours(PowerModel.hoursLeft(85.0, 2.0)))
        assertEquals("≈ 3.5 days at current use", PowerModel.describeHours(84.0))
    }

    @Test fun drainComparedBetweenModes() {
        val h = 3_600_000L
        val off = (0..4).map { PowerModel.Sample(it * h, 100f - it * 2f, false) to false }        // 2 %/h
        val on = (5..9).map { PowerModel.Sample(it * h, 90f - (it - 5) * 0.5f, false) to true }   // 0.5 %/h
        val charging = listOf(PowerModel.Sample(10 * h, 95f, true) to true)
        val m = PowerModel.drainByMode(off + on + charging)
        assertEquals(2.0, m[false]!!, 1e-9)
        assertEquals(0.5, m[true]!!, 1e-9)
        assertTrue(PowerModel.drainByMode(off.take(1)).isEmpty())
    }

    @Test fun powerBudget() {
        val noSun = PowerModel.budget(PowerModel.BudgetInput(batteryPercent = 100.0, powerBankWh = 37.0, screenHoursPerDay = 2.0,
            aiAnswersPerDay = 0, mapMinutesPerDay = 0.0, idlePercentPerHour = 0.5))
        val expectedUse = 2 * 1.5 + 22 * 0.5 * 0.176
        assertEquals(expectedUse, noSun.useWhPerDay, 1e-9)
        assertEquals((17.6 + 37 * 0.7) / expectedUse, noSun.daysUntilEmpty!!, 1e-6)
        val sunny = PowerModel.budget(PowerModel.BudgetInput(solarWatts = 10.0, sunHours = 5.0))
        assertNull(sunny.daysUntilEmpty) // 25 Wh/day in > use
        assertTrue(sunny.plan.any { it.contains("keeps up") })
    }

    // ---------- Pressure ----------
    @Test fun pressureTrend() {
        val h = 3_600_000L
        val now = 10 * h
        fun r(hAgo: Double, hpa: Float) = Pressure.Reading(now - (hAgo * h).toLong(), hpa)
        assertEquals(Pressure.Trend.FALLING_FAST, Pressure.trend(listOf(r(3.0, 1012f), r(0.0, 1008f)), now).first)
        assertEquals(Pressure.Trend.FALLING, Pressure.trend(listOf(r(3.0, 1012f), r(0.0, 1010.5f)), now).first)
        assertEquals(Pressure.Trend.STEADY, Pressure.trend(listOf(r(3.0, 1012f), r(0.0, 1012.4f)), now).first)
        assertEquals(Pressure.Trend.RISING, Pressure.trend(listOf(r(2.0, 1000f), r(0.0, 1002f)), now).first)
        assertEquals(Pressure.Trend.UNKNOWN, Pressure.trend(listOf(r(0.5, 1000f), r(0.0, 1002f)), now).first)
    }

    // ---------- Sun & moon (reference values from the astral library) ----------
    @Test fun sunriseSunsetMatchReference() {
        fun minutes(e: SunMoon.SunEvent) = ((e as SunMoon.SunEvent.At).localHours * 60).toInt()
        // Tampa, 2026-11-01 (EST, UTC-5): 06:41 / 17:45
        val tr = minutes(SunMoon.sunEvent(LocalDate.of(2026, 11, 1), 27.9506, -82.4572, -5.0, true))
        val ts = minutes(SunMoon.sunEvent(LocalDate.of(2026, 11, 1), 27.9506, -82.4572, -5.0, false))
        assertTrue("sunrise $tr", kotlin.math.abs(tr - (6 * 60 + 41)) <= 3)
        assertTrue("sunset $ts", kotlin.math.abs(ts - (17 * 60 + 45)) <= 3)
        // Seattle, solstice (PDT, UTC-7): 05:11 / 21:10
        val sr = minutes(SunMoon.sunEvent(LocalDate.of(2026, 6, 21), 47.6062, -122.3321, -7.0, true))
        val ss = minutes(SunMoon.sunEvent(LocalDate.of(2026, 6, 21), 47.6062, -122.3321, -7.0, false))
        assertTrue("sunrise $sr", kotlin.math.abs(sr - (5 * 60 + 11)) <= 3)
        assertTrue("sunset $ss", kotlin.math.abs(ss - (21 * 60 + 10)) <= 3)
        assertEquals(SunMoon.SunEvent.Never, SunMoon.sunEvent(LocalDate.of(2026, 12, 21), 78.2, 15.6, 1.0, true)) // Svalbard polar night
        assertEquals("6:41 AM", SunMoon.format(SunMoon.SunEvent.At(6 + 41 / 60.0)))
    }

    @Test fun moonPhaseMatchesReference() {
        fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("Last quarter", SunMoon.moon(at(2026, 11, 1)).name)
        assertEquals("Full moon", SunMoon.moon(at(2026, 11, 24)).name)
        assertTrue(SunMoon.moon(at(2026, 11, 24)).illumination > 0.95)
    }

    // ---------- Outage phases & checklists ----------
    @Test fun outagePhases() {
        assertEquals(1, Outage.day(20000, 20000))
        assertEquals(4, Outage.day(20000, 20003))
        assertEquals(1, Outage.phase(3).number)
        assertEquals(2, Outage.phase(4).number)
        assertEquals(3, Outage.phase(45).number)
        assertEquals(4, Outage.phase(46).number)
        assertEquals(4, Outage.phase(120).number)
    }

    @Test fun checklistFileIsValidAndFiltersByTraits() {
        val f = Checklists.parse(File("src/main/assets/griddown/checklists.json").readText())
        assertEquals(emptyList<String>(), Checklists.validate(f))
        val day1 = Checklists.forDay(f, 1, emptySet())
        assertTrue(day1.any { it.id == "w-fill" })
        assertTrue(day1.none { it.requires != null })
        assertTrue(Checklists.forDay(f, 1, setOf("pets", "meds")).any { it.id == "f-pets" })
        assertTrue(Checklists.forDay(f, 20, emptySet()).none { it.id == "w-fill" })
        val once = f.tasks.first { it.frequency == "once" && 1 in it.phases }
        assertEquals(1, Checklists.doneKey(once, 3))
        assertEquals(15, Checklists.doneKey(f.tasks.first { it.frequency == "once" && it.phases == listOf(3) }, 20))
    }

    // ---------- First-aid decision trees ----------
    @Test fun firstAidTreesAreValid() {
        val f = FirstAid.parse(File("src/main/assets/griddown/firstaid.json").readText())
        assertEquals(emptyList<String>(), FirstAid.validate(f))
        val ids = f.topics.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("bleeding", "burns", "fracture", "hypothermia", "heat", "dehydration", "infection")))
        // Every topic's walk reaches steps that cite a manual page.
        f.topics.forEach { t -> assertTrue(t.nodes.flatMap { it.steps }.all { it.source!!.page > 0 }) }
    }

    @Test fun firstAidValidatorCatchesBrokenTrees() {
        val f = FirstAid.parse(File("src/main/assets/griddown/firstaid.json").readText())
        val t = f.topics.first()
        val broken = f.copy(topics = listOf(t.copy(nodes = t.nodes.map { if (it.question != null) it.copy(yes = "nowhere") else it })))
        assertTrue(FirstAid.validate(broken).any { it.contains("missing 'nowhere'") })
        val cyclic = f.copy(topics = listOf(t.copy(nodes = t.nodes.map { if (it.id == "after") it.copy(next = t.start) else it })))
        assertTrue(FirstAid.validate(cyclic).any { it.contains("cycle") })
    }

    @Test fun firstAidMatching() {
        val f = FirstAid.parse(File("src/main/assets/griddown/firstaid.json").readText())
        assertEquals("bleeding", FirstAid.match(f, "My son has a deep cut and it's bleeding a lot")?.id)
        assertEquals("burns", FirstAid.match(f, "she got scalded by boiling water")?.id)
        assertEquals("heat", FirstAid.match(f, "grandpa might have heat stroke")?.id)
        assertNull(FirstAid.match(f, "how do I fix a bike chain"))
    }

    // ---------- Assistant tools & policy ----------
    @Test fun parsesActionsAndStripsThemFromText() {
        val reply = """You have plenty of water.
            |ACTION {"type":"add_supply","category":"water","name":"bottled water","quantity":24,"unit":"bottle","liters_per_unit":0.5}
            |ACTION {"type":"water_dose","liters":"20","cloudy":true}
            |ACTION {"type":"map_pin","name":"Creek","lat":27.9,"lon":-82.4}
            |ACTION {"type":"checklist","title":"Go bag","items":["Water","Meds",""]}
            |ACTION {"type":"map_pin","name":"Bad","lat":200,"lon":0}
            |ACTION {not json}""".trimMargin()
        val (text, actions) = AssistantTools.parse(reply)
        assertEquals("You have plenty of water.", text)
        assertEquals(4, actions.size)
        val supply = actions[0] as ProposedAction.AddSupply
        assertEquals("WATER", supply.category); assertEquals(0.5, supply.litersPerUnit!!, 1e-9)
        assertEquals(20.0, (actions[1] as ProposedAction.WaterDose).liters, 1e-9)
        assertEquals(listOf("Water", "Meds"), (actions[3] as ProposedAction.Checklist).items)
        val states = actions.map { ActionState(it) }
        assertEquals(states, AssistantTools.decodeStates(AssistantTools.encodeStates(states)))
    }

    @Test fun answerPolicyRoutes() {
        val f = FirstAid.parse(File("src/main/assets/griddown/firstaid.json").readText())
        assertTrue(AnswerPolicy.route("how much bleach to purify 5 gallons", f).contains(AnswerPolicy.Route.WaterTreatment))
        assertTrue(AnswerPolicy.route("is the food in my freezer still safe?", f).contains(AnswerPolicy.Route.FoodSafety))
        assertEquals(AnswerPolicy.Route.FirstAidTopic("hypothermia", "Hypothermia (too cold)"), AnswerPolicy.route("he's shivering and confused, hypothermia?", f).first())
        assertTrue(AnswerPolicy.route("tell me a story", f).isEmpty())
    }

    @Test fun contextBlockIsCompact() {
        val statuses = Rationing.status(
            listOf(InventoryItem(category = "WATER", name = "jugs", quantity = 30.0, unit = "gal")),
            family, listOf(Medication(memberId = 1, name = "Insulin", dosesPerDay = 2.0, onHand = 40.0, critical = true)),
            Climate.TEMPERATE, year)
        val block = AnswerPolicy.contextBlock(12, 4, 1, Climate.TEMPERATE, statuses, Rationing.medRunway(listOf(Medication(memberId = 1, name = "Insulin", dosesPerDay = 2.0, onHand = 40.0, critical = true))))
        assertTrue(block.contains("day 12"))
        assertTrue(block.contains("water ~8 days"))
        assertTrue(block.contains("Insulin ~20 days"))
        assertTrue("too long: ${block.length}", block.length < 480) // ≈ 120 tokens
    }
}
