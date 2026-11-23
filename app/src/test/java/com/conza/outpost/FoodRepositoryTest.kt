package com.conza.outpost

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import androidx.room.Room
import com.conza.outpost.data.*
import com.conza.outpost.food.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FoodRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val open = mutableListOf<OutpostDatabase>()
    private fun database(name: String? = null): OutpostDatabase = (if (name == null) Room.inMemoryDatabaseBuilder(context, OutpostDatabase::class.java)
        else Room.databaseBuilder(context, OutpostDatabase::class.java, name)).allowMainThreadQueries().build().also { open += it }
    @After fun close() { open.forEach { if (it.isOpen) it.close() } }

    @Test fun offlineContentAndInventoryWorkWithAirplaneModeEnabled() = runBlocking {
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val db = database(); val repo = FoodRepository(context, db)
        assertEquals(43, repo.content.products.size); assertTrue(repo.content.recipes.size >= 30)
        assertEquals(0, repo.snapshot().supplies.size)
        val row = InventoryItem(uuid = "beans", category = "FOOD", name = "My beans", quantity = 12.0, unit = "can", caloriesPerUnit = 400.0)
        repo.saveLot(row, InventoryLot("beans", productId = "c-black-beans", kind = "COMMERCIAL", proteinPerUnit = 20.0, nutritionSource = "Label", noCook = true))
        repo.consume("beans", 2.0, "Consumed")
        assertEquals(10.0, db.inventory().byUuid("beans")!!.quantity, 0.0)
        assertEquals(4000.0, PantryMath.coverage(repo.snapshot().state, repo.snapshot().supplies).storedCalories, 0.0)
        assertEquals(2, repo.snapshot().state.events.size)
    }
    @Test fun persistenceSurvivesDatabaseAndRepositoryRecreation() = runBlocking {
        val name = "pantry-${UUID.randomUUID()}.db"
        val first = database(name); val repo = FoodRepository(context, first)
        val supply = InventoryItem(uuid = "rice", category = "FOOD", name = "Rice label", quantity = 20.0, unit = "lb", caloriesPerUnit = 1600.0)
        repo.saveLot(supply, InventoryLot("rice", "white-rice", "DRY", nutritionSource = "Rice label", purchaseDate = "2026-11-22"))
        repo.change { it.copy(activeProfileId = "tampa-9", harvests = listOf(GardenHarvest(food = "Potato", date = "2026-11-22", pounds = 15.0))) }
        first.close()
        val next = FoodRepository(context, database(name)).snapshot()
        assertEquals(20.0, next.supplies.single().quantity, 0.0); assertEquals("tampa-9", next.state.activeProfileId)
        assertEquals(15.0, next.state.harvests.single().pounds, 0.0); assertEquals("2026-11-22", next.state.lots.single().purchaseDate)
    }
    @Test fun mealDeductionsAreFifoAtomicAndCannotRunTwice() = runBlocking {
        val db = database(); val repo = FoodRepository(context, db)
        val meal = ExampleMeal("test-meal", "Test meal", 1, "Lunch", listOf(MealIngredient("c-black-beans", 2.0), MealIngredient("c-chicken", 1.0)), 0.0, 0, "Open and eat")
        suspend fun stock(id: String, product: String, amount: Double, date: String) = repo.saveLot(InventoryItem(uuid = id, category = "FOOD", name = product, quantity = amount, unit = "can"),
            InventoryLot(id, product, "COMMERCIAL", purchaseDate = date))
        stock("old", "c-black-beans", 1.0, "2025-01-01"); stock("new", "c-black-beans", 3.0, "2026-01-01")
        val plan = MealPlan(id = "meal-plan", date = "2026-11-22", mealId = meal.id, people = 1)
        repo.change { it.copy(customMeals = listOf(meal), mealPlans = listOf(plan)) }
        try { repo.consumeMeal(plan.id); fail("Missing chicken must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(1.0, db.inventory().byUuid("old")!!.quantity, 0.0); assertFalse(repo.snapshot().state.mealPlans.single().consumed)
        stock("chicken", "c-chicken", 1.0, "2026-01-01")
        repo.consumeMeal(plan.id)
        assertEquals(0.0, db.inventory().byUuid("old")!!.quantity, 0.0)
        assertEquals(2.0, db.inventory().byUuid("new")!!.quantity, 0.0)
        assertEquals(0.0, db.inventory().byUuid("chicken")!!.quantity, 0.0)
        try { repo.consumeMeal(plan.id); fail("Double deduction must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(2.0, db.inventory().byUuid("new")!!.quantity, 0.0)
    }
    @Test fun freshBackupRestorePreservesUserDataAndMergeKeepsExistingStock() = runBlocking {
        val source = FoodRepository(context, database())
        source.saveLot(InventoryItem(uuid = "stock", category = "FOOD", name = "My brand", quantity = 9.0, unit = "can"), InventoryLot("stock", "c-chicken", "COMMERCIAL", purchaseDate = "2026-11-22"))
        source.change { it.copy(activeProfileId = "tampa-9", targets = listOf(PurchaseTarget("c-chicken", 50.0)), harvests = listOf(GardenHarvest(food = "Beans", date = "2026-11-22", pounds = 3.0))) }
        val exported = source.export()
        val restored = FoodRepository(context, database()); restored.import(exported)
        assertEquals(source.snapshot().state.lots, restored.snapshot().state.lots)
        assertEquals(source.snapshot().state.harvests, restored.snapshot().state.harvests)
        assertEquals(source.snapshot().state.targets, restored.snapshot().state.targets)
        assertEquals("tampa-9", restored.snapshot().state.activeProfileId)
        assertEquals(9.0, restored.snapshot().supplies.single().quantity, 0.0)
        restored.consume("stock", 2.0, "Consumed"); restored.import(exported)
        assertEquals(7.0, restored.snapshot().supplies.single().quantity, 0.0)
        assertTrue(File(context.filesDir, "pantry-before-import.json").isFile)
    }
    @Test fun homeCannedJarsAreHeldAndForgedOrUnsupportedSchedulesAreRejected() = runBlocking {
        val repo = FoodRepository(context, database()); val r = repo.content.recipes.single { it.id == "green-beans-hot" }
        val b = CanningBatch(recipeId = r.id, recipeVersion = r.version, food = r.food, date = "2026-11-22", jar = "PINT", jars = 4, method = r.method, gauge = "DIAL", elevationFeet = 0,
            pressurePsi = 11, processingMinutes = 20, rotationDate = "2027-11-22", recipeSnapshot = r)
        repo.saveBatch(b); repo.addBatchToPantry(b.id)
        assertEquals("HELD", repo.snapshot().state.lots.single().safety)
        try { repo.saveBatch(repo.snapshot().state.batches.single().copy(stage = "COMPLETE", seal = "SEALED")); fail("Unprocessed food cannot pass inspection") } catch (_: IllegalArgumentException) { }
        try { repo.saveBatch(repo.snapshot().state.batches.single().copy(recipeSnapshot = r.copy(schedules = listOf(RecipeSchedule("PINT", listOf(1)))), processingMinutes = 1)); fail("Modified recipe must be rejected") } catch (_: IllegalArgumentException) { }
        val complete = repo.snapshot().state.batches.single().copy(stage = "COMPLETE", seal = "SEALED", processComplete = true, preflightComplete = true)
        repo.saveBatch(complete)
        assertEquals("INSPECT", repo.snapshot().state.lots.single().safety)
        try { repo.saveBatch(complete.copy(notes = "Stale UI action")); fail("Stale batch edits must be rejected") } catch (_: IllegalArgumentException) { }
        repo.saveBatch(repo.snapshot().state.batches.single().copy(seal = "COMPROMISED"))
        assertEquals("COMPROMISED", repo.snapshot().state.lots.single().safety)
    }
    @Test fun allExistingSchemaVersionsMigrateWithoutLosingNotesOrSupplies() = runBlocking {
        for (version in 1..3) {
            val name = "migration-$version-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
            val schema = JSONObject(File("schemas/com.conza.outpost.data.OutpostDatabase/$version.json").readText()).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i); val table = e.getString("tableName")
                    raw.execSQL(e.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                    val indices = e.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
                if (version >= 2) raw.execSQL("INSERT INTO notes (id,title,body,pinned,createdAt,updatedAt) VALUES (1,'Keep this note','Original user data',0,1,1)")
                if (version >= 3) raw.execSQL("INSERT INTO inventory_items (id,uuid,category,name,quantity,unit,litersPerUnit,caloriesPerUnit,dailyUse,expiry,notes,updatedAt) VALUES (1,'keep-water','WATER','My water',12,'gal',NULL,NULL,NULL,NULL,'Keep this stock',1)")
                raw.version = version
            }
            val db = database(name)
            if (version >= 2) assertEquals("Original user data", db.notes().get(1)!!.body) else assertTrue(db.notes().all().isEmpty())
            if (version >= 3) assertEquals(12.0, db.inventory().byUuid("keep-water")!!.quantity, 0.0)
            val repo = FoodRepository(context, db)
            assertEquals(7, repo.snapshot().state.profile.people)
            repo.change { it.copy(activeProfileId = "tampa-6") }
            assertEquals(6, repo.snapshot().state.profile.people)
        }
    }
    @Test fun legacyDuplicateSupplyIdsKeepBothQuantitiesAndBecomeIndividuallyEditable() = runBlocking {
        val db = database()
        db.inventory().upsert(InventoryItem(uuid = "old-preset", category = "FOOD", name = "First purchase", quantity = 4.0, unit = "can"))
        db.inventory().upsert(InventoryItem(uuid = "old-preset", category = "FOOD", name = "Second purchase", quantity = 7.0, unit = "can"))
        val repo = FoodRepository(context, db)
        val snapshot = repo.snapshot()
        assertEquals(2, snapshot.supplies.size)
        assertEquals(2, snapshot.supplies.map { it.uuid }.distinct().size)
        assertEquals(11.0, snapshot.supplies.sumOf { it.quantity }, 0.0)
        assertEquals("old-preset", snapshot.supplies.first { it.name == "First purchase" }.uuid)
        val second = snapshot.supplies.first { it.name == "Second purchase" }
        repo.consume(second.uuid, 2.0, "Consumed")
        assertEquals(4.0, db.inventory().byUuid("old-preset")!!.quantity, 0.0)
        assertEquals(5.0, db.inventory().byUuid(second.uuid)!!.quantity, 0.0)
        FoodBackups.decode(repo.export())
        Unit
    }
}
