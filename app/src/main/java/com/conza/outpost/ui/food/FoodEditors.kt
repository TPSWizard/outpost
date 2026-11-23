package com.conza.outpost.ui.food

import com.conza.outpost.data.InventoryItem
import com.conza.outpost.food.*
import java.time.LocalDate
import java.util.UUID

internal fun profileForm(repo: FoodRepository, p: HouseholdProfile, create: Boolean = false) = FoodFormSpec(
    if (create) "New planning profile" else "Edit ${p.name}",
    "Planning assumptions only. Changing people or days never adds inventory. The water baseline is a minimum; heat and extra sanitation can increase it. Protein is an editable planning target, not a medical prescription.",
    listOf(input("name", "Profile name", p.name), input("location", "Location", p.location), input("people", "People", p.people, true),
        input("days", "Initial days", p.days, true), input("extended", "Extended days", p.extendedDays, true), input("calories", "Calories / person / day", p.calories, true),
        input("water", "US gallons / person / day", p.waterGallons, true), input("protein", "Protein grams / person / day", p.proteinGrams, true),
        input("environment", "Storage environment", p.environment), toggle("fridge", "Reliable refrigeration available", p.refrigeration), toggle("electric", "Electric cooking available", p.electricCooking),
        input("diet", "Dietary needs / allergies", p.dietaryNotes), input("kw", "Burner kW (optional estimate)", p.burnerKw, true), input("fuel", "Fuel grams/hour (optional measured rate)", p.fuelGramsPerHour, true)),
) { v ->
    val next = p.copy(id = if (create) UUID.randomUUID().toString() else p.id, name = v.text("name"), location = v.text("location"), people = v.integer("people"),
        days = v.integer("days"), extendedDays = v.integer("extended"), calories = v.integer("calories"), waterGallons = v.number("water"), proteinGrams = v.number("protein"),
        environment = v.text("environment"), refrigeration = v.flag("fridge"), electricCooking = v.flag("electric"), dietaryNotes = v.text("diet"), burnerKw = v.optionalNumber("kw"), fuelGramsPerHour = v.optionalNumber("fuel"))
    FoodValidation.profile(next)
    repo.change { it.copy(activeProfileId = next.id, profiles = it.profiles.filterNot { old -> old.id == next.id } + next) }
}

internal fun lotForm(repo: FoodRepository, state: FoodState, row: InventoryItem? = null, product: FoodProduct? = null, water: Boolean = false): FoodFormSpec {
    val old = row ?: InventoryItem(category = if (water) "WATER" else "FOOD", name = product?.name.orEmpty(), quantity = 0.0, unit = product?.unit ?: if (water) "gal" else "unit")
    val lot = state.lots.firstOrNull { it.inventoryUuid == old.uuid } ?: InventoryLot(old.uuid, product?.id, kind = product?.kind ?: "CUSTOM", noCook = product?.noCook == true, waterNeeded = product?.waterNeeded == true)
    return FoodFormSpec(if (row == null) "Record owned / purchased stock" else "Edit pantry lot", "Only enter food or water actually owned. Quantity uses the catalog unit shown below. Nutrition = label per serving × servings per ONE stock unit (one can, lb, jar, gal, etc.). For pounds, convert the label serving weight first. Blank nutrition is unknown. Dates use YYYY-MM-DD. Rotation dates are quality reminders, never safety guarantees. Home-canned stock needs a reviewed linked batch before it counts toward coverage.",
        listOf(input("name", "Product / brand / size", old.name), choice("product", "Catalog link", lot.productId, listOf("" to "Custom / no catalog link") + repo.content.products.map { it.id to "${it.name} · ${it.unit}" }),
            choice("category", "Supply category", old.category, listOf("FOOD" to "Food", "WATER" to "Water")),
            choice("kind", "Food type", lot.kind, listOf("COMMERCIAL" to "Commercial canned", "DRY" to "Dry / bulk", "HOME" to "Home-canned", "CUSTOM" to "Other")),
            input("quantity", "Quantity owned", old.quantity, true), input("unit", "Stock unit (can, lb, jar, gal…)", old.unit),
            input("servings", "Servings per stock unit", 1, true), input("calories", "Calories per label serving (optional)", old.caloriesPerUnit, true), input("protein", "Protein grams per label serving (optional)", lot.proteinPerUnit, true),
            input("source", "Nutrition source / product label", lot.nutritionSource), input("liters", "Water liters per stock unit (optional)", old.litersPerUnit, true),
            choice("location", "Storage location", lot.locationId, listOf("" to "Not set") + state.locations.map { it.id to it.name }), input("purchase", "Purchase / packing date", lot.purchaseDate),
            input("bestby", "Manufacturer best-by (optional)", old.expiry), input("rotation", "Preferred rotation date (optional)", lot.rotationDate), input("opened", "Opened date (optional)", lot.openedDate),
            choice("safety", "Condition / review", lot.safety, listOf("INSPECT" to "Inspect before use", "UNKNOWN" to "Unknown safety status", "HELD" to "Held for review", "COMPROMISED" to "Compromised / discard")),
            toggle("nocook", "Ready to eat without cooking", lot.noCook), toggle("waterneeded", "Water needed for preparation", lot.waterNeeded),
            input("volume", "Shelf space liters per stock unit (optional)", lot.volumeLitersPerUnit, true), input("price", "Price per stock unit (optional)", lot.pricePerUnit, true), input("notes", "Notes / package size / provenance", old.notes)),
    ) { v ->
        val servings = v.number("servings"); require(servings > 0)
        val productId = v.opt("product")
        val selected = repo.content.products.firstOrNull { it.id == productId }
        val kind = v.text("kind")
        if (selected != null) require(kind == selected.kind) { "Food type must match the catalog. Use Custom for home-canned food." }
        val kcal = v.optionalNumber("calories")?.times(servings)
        val protein = v.optionalNumber("protein")?.times(servings)
        if (kcal != null || protein != null) require(v.text("source").isNotBlank()) { "Record the product label or nutrition source" }
        val purchased = v.opt("purchase")
        val rotation = v.opt("rotation") ?: purchased?.let { date -> if (kind == "HOME") PantryMath.rotation(date, 12) else selected?.qualityMonths?.let { PantryMath.rotation(date, it) } }
        repo.saveLot(old.copy(name = v.text("name"), category = v.text("category"), quantity = v.number("quantity"), unit = v.text("unit"),
            caloriesPerUnit = kcal, litersPerUnit = v.optionalNumber("liters"), expiry = v.opt("bestby"), notes = v.text("notes")),
            lot.copy(productId = productId, kind = kind, proteinPerUnit = protein, nutritionSource = v.text("source"), noCook = kind != "HOME" && v.flag("nocook"), waterNeeded = v.flag("waterneeded"),
                locationId = v.opt("location"), purchaseDate = purchased, rotationDate = rotation, openedDate = v.opt("opened"), safety = v.text("safety"),
                volumeLitersPerUnit = v.optionalNumber("volume"), pricePerUnit = v.optionalNumber("price")))
    }
}

internal fun targetForm(repo: FoodRepository, product: FoodProduct, target: PurchaseTarget?) : FoodFormSpec {
    val t = target ?: PurchaseTarget(product.id, 0.0)
    return FoodFormSpec("Shopping: ${product.name}", "Targets are in ${product.unit}. They never create owned stock. Record purchases as pantry lots, then Needed updates automatically. A case contains this many stock units.",
        listOf(input("desired", "Target ${product.unit}", t.desired, true), input("case", "Units per case", t.caseSize, true), input("price", "Price per case (optional)", t.pricePerCase, true), input("priority", "Priority", t.priority), input("notes", "Notes / store", t.notes))) { v ->
        val next = t.copy(desired = v.number("desired"), caseSize = v.integer("case"), pricePerCase = v.optionalNumber("price"), priority = v.text("priority"), notes = v.text("notes"))
        repo.change { it.copy(targets = it.targets.filterNot { old -> old.productId == product.id } + next) }
    }
}

internal fun locationForm(repo: FoodRepository, location: StorageLocation = StorageLocation(name = "")) = FoodFormSpec("Storage location", "Home-canned quality storage: ideally 50–70°F; avoid above 95°F. Freezing, dampness, sun and pests can damage food or packaging. Tampa garages and sheds can be much hotter and more humid than indoor rooms. Capacity is only as complete as entered lot volumes.",
    listOf(input("name", "Name", location.name), input("temp", "Temperature °F (optional)", location.temperatureF), input("humidity", "Humidity % (optional)", location.humidityPercent, true), input("capacity", "Capacity liters (optional)", location.capacityLiters, true),
        toggle("sun", "Direct sunlight", location.sunlight), toggle("pests", "Pest protection in place", location.pestProtected), input("notes", "Notes / monitoring", location.notes))) { v ->
    val next = location.copy(name = v.text("name"), temperatureF = v.opt("temp")?.let { it.toDoubleOrNull() ?: error("Invalid temperature") }, humidityPercent = v.optionalNumber("humidity"), capacityLiters = v.optionalNumber("capacity"), sunlight = v.flag("sun"), pestProtected = v.flag("pests"), notes = v.text("notes"))
    repo.change { it.copy(locations = it.locations.filterNot { old -> old.id == location.id } + next) }
}

internal fun equipmentForm(repo: FoodRepository, item: EquipmentItem = EquipmentItem(UUID.randomUUID().toString(), "", "Custom")) = FoodFormSpec("Equipment checklist", "Desired and owned are independent. Price is per item; Needed × price estimates the remaining cost. Oxygen absorbers are only appropriate for suitable sufficiently dry foods, never every pantry item.",
    listOf(input("name", "Name", item.name), input("group", "Group", item.group), input("desired", "Quantity desired", item.desired, true), input("owned", "Quantity owned", item.owned, true), input("price", "Unit price (optional)", item.price, true), input("priority", "Priority", item.priority), input("location", "Location", item.location), input("notes", "Notes", item.notes))) { v ->
    require(v.text("name").isNotBlank())
    val next = item.copy(name = v.text("name"), group = v.text("group"), desired = v.number("desired"), owned = v.number("owned"), price = v.optionalNumber("price"), priority = v.text("priority"), location = v.text("location"), notes = v.text("notes"))
    repo.change { it.copy(equipment = it.equipment.filterNot { old -> old.id == next.id } + next) }
}

internal fun harvestForm(repo: FoodRepository, h: GardenHarvest = GardenHarvest(food = "", date = LocalDate.now().toString(), pounds = 0.0)) = FoodFormSpec("Harvest allocation", "Enter pounds. Fresh + preserved + spoiled + retained for suitable planting cannot exceed the harvest. Link a preservation batch from the canning wizard; this does not assume every harvest is canned. Retain only healthy suitable planting material.",
    listOf(input("food", "Crop", h.food), input("date", "Harvest date YYYY-MM-DD", h.date), input("pounds", "Total pounds", h.pounds, true), input("fresh", "Used fresh lb", h.fresh, true), input("preserved", "Preserved lb", h.preserved, true), input("spoiled", "Spoiled lb", h.spoiled, true), input("planting", "Retained for planting lb", h.planting, true), input("notes", "Notes", h.notes))) { v ->
    val next = h.copy(food = v.text("food"), date = v.text("date"), pounds = v.number("pounds"), fresh = v.number("fresh"), preserved = v.number("preserved"), spoiled = v.number("spoiled"), planting = v.number("planting"), notes = v.text("notes"))
    FoodValidation.harvest(next)
    repo.change { it.copy(harvests = it.harvests.filterNot { old -> old.id == next.id } + next) }
}

internal fun mealForm(repo: FoodRepository, meal: ExampleMeal) = FoodFormSpec("Edit ${meal.title}", "Amounts are per person in the catalog stock unit. Water and burner time are editable planning assumptions, not tested cooking instructions. Follow each food's safe cooking directions. Nutrition comes from your entered product labels. Burner time is for one household batch; cookware and group size change actual fuel use.",
    listOf(input("title", "Meal name", meal.title), input("water", "Cooking water liters / person", meal.waterLitersPerPerson, true), input("minutes", "Household burner minutes", meal.burnerMinutes, true), input("instructions", "Preparation notes", meal.instructions)) +
        meal.ingredients.map { ingredient -> input(ingredient.productId, "${repo.content.products.single { it.id == ingredient.productId }.name} (${repo.content.products.single { it.id == ingredient.productId }.unit} / person)", ingredient.unitsPerPerson, true) }) { v ->
    val next = meal.copy(title = v.text("title"), waterLitersPerPerson = v.number("water"), burnerMinutes = v.integer("minutes"), instructions = v.text("instructions"), ingredients = meal.ingredients.map { it.copy(unitsPerPerson = v.number(it.productId)) })
    repo.change { it.copy(customMeals = it.customMeals.filterNot { old -> old.id == next.id } + next) }
}
