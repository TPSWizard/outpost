package com.conza.outpost.food

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class KnowledgeSource(val id: String, val title: String, val url: String, val verified: String)

@Serializable
data class FoodProduct(
    val id: String, val name: String, val category: String, val kind: String,
    val unit: String, val size: String = "", val target: Double = 0.0,
    val storageId: String, val noCook: Boolean = false, val waterNeeded: Boolean = false,
    val cookingMinutes: Int? = null, val qualityMonths: Int? = null,
    val portion: String = "Read the product label", val nutrition: String = "Enter calories and protein from your product label.",
)

@Serializable
data class StorageAdvice(
    val id: String, val title: String, val container: String, val conditions: String,
    val quality: String, val oxygen: String, val mylar: String, val bucket: String,
    val vacuum: String, val discard: String, val sources: List<String>,
)

@Serializable
data class RecipeSchedule(val jar: String, val minutes: List<Int>)

@Serializable
data class PressureBand(val maxFeet: Int, val psi: Int)

@Serializable
data class CannedFoodRecipe(
    val id: String, val food: String, val category: String, val method: String,
    val pack: String, val preparation: List<String>, val peeling: String, val precook: String,
    val headspace: String, val liquid: String, val saltAcid: String,
    val schedules: List<RecipeSchedule> = emptyList(),
    val dial: List<PressureBand> = emptyList(), val weighted: List<PressureBand> = emptyList(),
    val waterBathBands: List<Int> = emptyList(), val warnings: List<String>,
    val sources: List<String>, val version: String = "2026-11-22",
    val verified: Boolean = true,
)

@Serializable
data class FoodGuide(val id: String, val title: String, val section: String, val paragraphs: List<String>, val sources: List<String>)

@Serializable
data class MealIngredient(val productId: String, val unitsPerPerson: Double)

@Serializable
data class ExampleMeal(
    val id: String, val title: String, val day: Int, val type: String,
    val ingredients: List<MealIngredient>, val waterLitersPerPerson: Double,
    val burnerMinutes: Int, val instructions: String,
)

@Serializable
data class FoodContent(
    val version: String, val sources: List<KnowledgeSource>, val products: List<FoodProduct>,
    val storage: List<StorageAdvice>, val recipes: List<CannedFoodRecipe>, val guides: List<FoodGuide>,
    val meals: List<ExampleMeal>, val equipment: List<EquipmentItem>,
)

@Serializable
data class HouseholdProfile(
    val id: String = UUID.randomUUID().toString(), val name: String = "Tampa household",
    val location: String = "Tampa, Florida", val people: Int = 7, val days: Int = 30,
    val extendedDays: Int = 90, val calories: Int = 2000, val waterGallons: Double = 1.0,
    val proteinGrams: Double = 50.0, val environment: String = "Climate-controlled home",
    val refrigeration: Boolean = false, val electricCooking: Boolean = false,
    val dietaryNotes: String = "", val burnerKw: Double? = null, val fuelGramsPerHour: Double? = null,
)

@Serializable
data class InventoryLot(
    val inventoryUuid: String, val productId: String? = null, val kind: String = "CUSTOM",
    val proteinPerUnit: Double? = null, val nutritionSource: String = "",
    val noCook: Boolean = false, val waterNeeded: Boolean = false,
    val locationId: String? = null, val purchaseDate: String? = null, val openedDate: String? = null,
    val rotationDate: String? = null, val safety: String = "INSPECT",
    val batchId: String? = null, val volumeLitersPerUnit: Double? = null,
    val pricePerUnit: Double? = null,
    val foodCategory: String = "",
)

@Serializable
data class StorageLocation(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val temperatureF: Double? = null, val humidityPercent: Double? = null,
    val capacityLiters: Double? = null, val sunlight: Boolean = false, val pestProtected: Boolean = true,
    val notes: String = "",
)

@Serializable
data class PurchaseTarget(
    val productId: String, val desired: Double, val caseSize: Int = 1,
    val pricePerCase: Double? = null, val priority: String = "Normal", val notes: String = "",
)

@Serializable
data class EquipmentItem(
    val id: String, val name: String, val group: String, val desired: Double = 1.0,
    val owned: Double = 0.0, val price: Double? = null, val priority: String = "Normal",
    val location: String = "", val notes: String = "",
)

@Serializable
data class CanningBatch(
    val id: String = UUID.randomUUID().toString(), val recipeId: String, val recipeVersion: String,
    val food: String, val date: String, val jar: String, val jars: Int, val method: String,
    val gauge: String, val elevationFeet: Int, val pressurePsi: Int?, val processingMinutes: Int,
    val locationId: String? = null, val stage: String = "PREPARE",
    val seal: String = "UNCHECKED", val interrupted: Boolean = false, val restarts: Int = 0,
    val notes: String = "", val rotationDate: String,
    val timerEndsAt: Long? = null, val timerElapsedEnds: Long? = null,
    val timerBoot: Long? = null, val timerKind: String? = null,
    val pressureConfirmed: Boolean = false, val processComplete: Boolean = false,
    val preflightComplete: Boolean = false, val harvestId: String? = null,
    val recipeSnapshot: CannedFoodRecipe? = null,
    val interruptionStage: String? = null,
)

@Serializable
data class GardenHarvest(
    val id: String = UUID.randomUUID().toString(), val food: String, val date: String,
    val pounds: Double, val fresh: Double = 0.0, val preserved: Double = 0.0,
    val spoiled: Double = 0.0, val planting: Double = 0.0, val notes: String = "",
    val batchIds: List<String> = emptyList(),
)

@Serializable
data class InventoryEvent(
    val id: String = UUID.randomUUID().toString(), val inventoryUuid: String,
    val at: Long = System.currentTimeMillis(), val change: Double, val reason: String,
)

@Serializable
data class MealPlan(val id: String = UUID.randomUUID().toString(), val date: String, val mealId: String, val people: Int, val consumed: Boolean = false)

@Serializable
data class FoodState(
    val format: Int = 1, val activeProfileId: String = "tampa-7",
    val profiles: List<HouseholdProfile> = listOf(
        HouseholdProfile(id = "tampa-7"),
        HouseholdProfile(id = "tampa-6", name = "6 people", people = 6),
        HouseholdProfile(id = "tampa-9", name = "9 people", people = 9),
    ),
    val lots: List<InventoryLot> = emptyList(), val locations: List<StorageLocation> = listOf(
        StorageLocation("pantry", "Indoor pantry"), StorageLocation("closet", "Climate-controlled closet"),
        StorageLocation("kitchen", "Kitchen"), StorageLocation("garage", "Garage", pestProtected = false),
        StorageLocation("shed", "Shed", pestProtected = false), StorageLocation("basement", "Basement"),
        StorageLocation("kit", "Emergency grab-and-go kit"),
    ),
    val targets: List<PurchaseTarget> = emptyList(), val equipment: List<EquipmentItem> = emptyList(),
    val batches: List<CanningBatch> = emptyList(), val harvests: List<GardenHarvest> = emptyList(),
    val events: List<InventoryEvent> = emptyList(), val mealPlans: List<MealPlan> = emptyList(),
    val customMeals: List<ExampleMeal> = emptyList(),
) {
    val profile: HouseholdProfile get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}
