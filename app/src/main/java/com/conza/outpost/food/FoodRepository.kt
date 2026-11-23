package com.conza.outpost.food

import android.content.Context
import androidx.room.withTransaction
import com.conza.outpost.data.FoodWorkspaceRow
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.OutpostDatabase
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

data class FoodSnapshot(val state: FoodState, val supplies: List<InventoryItem>)

@Serializable
data class FoodSupply(
    val uuid: String, val category: String, val name: String, val quantity: Double, val unit: String,
    val litersPerUnit: Double?, val caloriesPerUnit: Double?, val expiry: String?, val notes: String,
    val dailyUse: Double? = null, val updatedAt: Long = 0,
) {
    fun row(id: Long = 0) = InventoryItem(id = id, uuid = uuid, category = category, name = name, quantity = quantity,
        unit = unit, litersPerUnit = litersPerUnit, caloriesPerUnit = caloriesPerUnit, expiry = expiry, notes = notes, dailyUse = dailyUse, updatedAt = updatedAt)
    companion object { fun from(row: InventoryItem) = FoodSupply(row.uuid, row.category, row.name, row.quantity, row.unit, row.litersPerUnit, row.caloriesPerUnit, row.expiry, row.notes, row.dailyUse, row.updatedAt) }
}

@Serializable
data class FoodBackup(val format: Int = 1, val exportedAt: String, val state: FoodState, val supplies: List<FoodSupply>)

object FoodBackups {
    fun holdRestoredTimers(state: FoodState): FoodState {
        val active = state.batches.filter { it.timerElapsedEnds != null || it.stage !in setOf("COMPLETE", "PREPARE") }.map { it.id }.toSet()
        return state.copy(batches = state.batches.map { if (it.id in active) FoodTimers.interrupted(it).copy(interruptionStage = "IMPORTED") else it },
            lots = state.lots.map { if (it.batchId in active && it.safety != "COMPROMISED") it.copy(safety = "HELD") else it })
    }
    fun encode(backup: FoodBackup): String = FoodJson.format.encodeToString(FoodBackup.serializer(), backup)
    fun decode(text: String): FoodBackup {
        require(text.length <= 20_000_000) { "Pantry backup is too large" }
        val backup = FoodJson.format.decodeFromString(FoodBackup.serializer(), text)
        require(backup.format == 1) { "Unsupported backup format" }
        FoodValidation.state(backup.state)
        require(backup.supplies.map { it.uuid }.distinct().size == backup.supplies.size)
        backup.supplies.forEach { row ->
            require(row.uuid.isNotBlank() && row.name.isNotBlank() && row.category in setOf("FOOD", "WATER"))
            FoodValidation.nonnegative(row.quantity, "Quantity")
            row.caloriesPerUnit?.let { FoodValidation.nonnegative(it, "Calories") }
            row.litersPerUnit?.let { FoodValidation.nonnegative(it, "Water") }
            row.dailyUse?.let { FoodValidation.nonnegative(it, "Daily use") }
        }
        return backup
    }
    fun csv(snapshot: FoodSnapshot): String {
        fun cell(value: Any?): String {
            val raw = value?.toString().orEmpty()
            val safe = if (raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$raw" else raw
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val lines = mutableListOf("name,kind,quantity,unit,kcal_per_unit,protein_g_per_unit,location,purchase_date,best_by,rotation_date,condition")
        snapshot.supplies.forEach { row ->
            val lot = snapshot.state.lots.firstOrNull { it.inventoryUuid == row.uuid }
            lines += listOf(row.name, lot?.kind ?: row.category, row.quantity, row.unit, row.caloriesPerUnit ?: "",
                lot?.proteinPerUnit ?: "", snapshot.state.locations.firstOrNull { it.id == lot?.locationId }?.name ?: "",
                lot?.purchaseDate ?: "", row.expiry ?: "", lot?.rotationDate ?: "", PantryMath.quality(lot)).joinToString(",", transform = ::cell)
        }
        return lines.joinToString("\r\n")
    }
}

class FoodRepository(private val context: Context, private val db: OutpostDatabase) {
    val content: FoodContent by lazy {
        context.assets.open("food/content.json").bufferedReader().use { FoodJson.format.decodeFromString(FoodContent.serializer(), it.readText()) }
    }
    private fun initial() = FoodState(
        targets = content.products.filter { it.target > 0 }.map { PurchaseTarget(it.id, it.target) }, equipment = content.equipment,
    )
    private fun decode(row: FoodWorkspaceRow?) = row?.let { FoodJson.state(it.payload) } ?: initial()
    private suspend fun state() = decode(db.foodWorkspace().get())
    private suspend fun put(state: FoodState) { FoodValidation.state(state); db.foodWorkspace().put(FoodWorkspaceRow(payload = FoodJson.encode(state))) }
    fun observe() = combine(db.foodWorkspace().observe(), db.inventory().observe()) { row, inventory ->
        FoodSnapshot(decode(row), inventory.filter { it.category in setOf("FOOD", "WATER") })
    }
    suspend fun snapshot() = FoodSnapshot(state(), db.inventory().all().filter { it.category in setOf("FOOD", "WATER") })
    suspend fun change(block: (FoodState) -> FoodState) = db.withTransaction { put(block(state())) }

    suspend fun saveLot(row: InventoryItem, lot: InventoryLot) = db.withTransaction {
        require(row.uuid == lot.inventoryUuid && row.name.isNotBlank() && row.unit.isNotBlank())
        require(row.category in setOf("FOOD", "WATER"))
        FoodValidation.nonnegative(row.quantity, "Quantity")
        row.caloriesPerUnit?.let { FoodValidation.nonnegative(it, "Calories") }
        row.litersPerUnit?.let { FoodValidation.nonnegative(it, "Water volume") }
        FoodValidation.date(row.expiry)
        val old = db.inventory().byUuid(row.uuid)
        db.inventory().upsert(row.copy(id = old?.id ?: 0, updatedAt = System.currentTimeMillis()))
        val current = state()
        lot.productId?.let { id ->
            val product = content.products.single { it.id == id }
            require(row.unit == product.unit && row.category == "FOOD" && lot.kind == product.kind) { "Use the catalog type and unit ${product.unit} for this food" }
        }
        if (lot.kind == "HOME" && lot.safety == "INSPECT") {
            require(lot.batchId != null) { "Home-canned food needs a reviewed processing batch" }
            val batch = current.batches.single { it.id == lot.batchId }
            require(batch.seal == "SEALED" && batch.processComplete && !batch.interrupted)
        }
        put(current.copy(lots = current.lots.filterNot { it.inventoryUuid == lot.inventoryUuid } + lot,
            events = current.events + InventoryEvent(inventoryUuid = row.uuid, change = row.quantity - (old?.quantity ?: 0.0), reason = if (old == null) "Acquired / already owned" else "Inventory adjustment")))
    }

    suspend fun consume(uuid: String, amount: Double, reason: String) = db.withTransaction {
        require(amount.isFinite() && amount > 0)
        val row = db.inventory().byUuid(uuid) ?: error("That inventory lot was removed")
        require(amount <= row.quantity + 1e-8) { "Quantity exceeds stock" }
        db.inventory().upsert(row.copy(quantity = (row.quantity - amount).coerceAtLeast(0.0), updatedAt = System.currentTimeMillis()))
        val current = state()
        put(current.copy(events = current.events + InventoryEvent(inventoryUuid = uuid, change = -amount, reason = reason)))
    }

    suspend fun remove(uuid: String) = db.withTransaction {
        val removed = db.inventory().byUuid(uuid)
        removed?.let { db.inventory().delete(it.id) }
        val current = state()
        put(current.copy(lots = current.lots.filterNot { it.inventoryUuid == uuid },
            events = current.events + InventoryEvent(inventoryUuid = uuid, change = -(removed?.quantity ?: 0.0), reason = "Lot removed")))
    }

    suspend fun saveBatch(batch: CanningBatch) = db.withTransaction {
        val current = state()
        val previous = current.batches.firstOrNull { it.id == batch.id }
        require(batch.revision == (previous?.revision ?: 0L)) { "This batch changed while you were viewing it. Reopen the current record before saving." }
        val recipe = batch.recipeSnapshot ?: content.recipes.single { it.id == batch.recipeId }
        require(content.recipes.any { it.id == batch.recipeId && it.version == batch.recipeVersion && it == recipe && it.verified }) { "Batch recipe does not match a verified bundled recipe version" }
        val process = CanningRules.process(recipe, batch.jar, batch.gauge, batch.elevationFeet)
        require(process.minutes == batch.processingMinutes && process.psi == batch.pressurePsi)
        require(batch.recipeVersion == recipe.version && batch.method == recipe.method && batch.food == recipe.food)
        require(batch.jars > 0)
        if (batch.seal == "SEALED") require(batch.processComplete && !batch.interrupted && batch.stage == "COMPLETE") { "Review processing and seals first" }
        val usable = batch.seal == "SEALED" && batch.processComplete && !batch.interrupted
        val lotSafety = if (usable) "INSPECT" else if (batch.seal == "COMPROMISED" || batch.seal == "FAILED") "COMPROMISED" else "HELD"
        put(current.copy(batches = current.batches.filterNot { it.id == batch.id } + batch.copy(revision = batch.revision + 1),
            lots = current.lots.map { if (it.batchId == batch.id) it.copy(safety = lotSafety) else it },
            harvests = current.harvests.map { if (it.id == batch.harvestId) it.copy(batchIds = (it.batchIds + batch.id).distinct()) else it }))
    }

    suspend fun addBatchToPantry(batchId: String) = db.withTransaction {
        val current = state()
        val batch = current.batches.single { it.id == batchId }
        require(current.lots.none { it.batchId == batchId }) { "This batch already has a pantry lot" }
        val uuid = UUID.randomUUID().toString()
        val inspected = batch.seal == "SEALED" && batch.processComplete && !batch.interrupted
        val safety = if (batch.seal in setOf("FAILED", "COMPROMISED")) "COMPROMISED" else if (inspected) "INSPECT" else "HELD"
        db.inventory().upsert(InventoryItem(uuid = uuid, category = "FOOD", name = "${batch.food} · batch ${batch.id.take(8)}", quantity = batch.jars.toDouble(), unit = batch.jar, expiry = batch.rotationDate))
        put(current.copy(lots = current.lots + InventoryLot(uuid, kind = "HOME", noCook = false, purchaseDate = batch.date,
            rotationDate = batch.rotationDate, safety = safety, batchId = batch.id, locationId = batch.locationId),
            events = current.events + InventoryEvent(inventoryUuid = uuid, change = batch.jars.toDouble(), reason = "Home-canned batch recorded · $safety")))
    }

    suspend fun consumeMeal(planId: String) = db.withTransaction {
        val current = state()
        val plan = current.mealPlans.single { it.id == planId }
        require(!plan.consumed) { "This meal was already deducted" }
        val meal = current.customMeals.firstOrNull { it.id == plan.mealId } ?: content.meals.single { it.id == plan.mealId }
        val rows = db.inventory().all().associateBy { it.uuid }.toMutableMap()
        val events = mutableListOf<InventoryEvent>()
        for (ingredient in meal.ingredients) {
            var need = ingredient.unitsPerPerson * plan.people
            val lots = current.lots.filter { it.productId == ingredient.productId && PantryMath.usable(it) }
                .sortedWith(compareBy<InventoryLot> { it.purchaseDate ?: "9999" }.thenBy { it.rotationDate ?: "9999" })
            require(lots.sumOf { rows[it.inventoryUuid]?.quantity ?: 0.0 } + 1e-8 >= need) { "Not enough ${content.products.first { it.id == ingredient.productId }.name}. No ingredients were deducted." }
            for (lot in lots) {
                val row = rows[lot.inventoryUuid] ?: continue
                val used = minOf(row.quantity, need)
                rows[row.uuid] = row.copy(quantity = (row.quantity - used).coerceAtLeast(0.0), updatedAt = System.currentTimeMillis())
                if (used > 0) events += InventoryEvent(inventoryUuid = row.uuid, change = -used, reason = "Meal ${meal.title}")
                need -= used
                if (need <= 1e-8) break
            }
        }
        events.map { it.inventoryUuid }.distinct().forEach { db.inventory().upsert(rows.getValue(it)) }
        put(current.copy(mealPlans = current.mealPlans.map { if (it.id == planId) it.copy(consumed = true) else it }, events = current.events + events))
    }

    suspend fun export(): String {
        val snapshot = db.withTransaction { snapshot() }
        return FoodBackups.encode(FoodBackup(exportedAt = java.time.Instant.now().toString(), state = snapshot.state, supplies = snapshot.supplies.map(FoodSupply::from)))
    }

    /** Adds missing IDs only. Existing stock and metadata win conflicts on import. */
    suspend fun import(text: String) {
        val backup = FoodBackups.decode(text)
        require(backup.state.lots.all { it.productId == null || content.products.any { p -> p.id == it.productId } }) { "Backup uses an unknown catalog product" }
        backup.state.lots.forEach { lot ->
            if (lot.productId != null) {
                val product = content.products.single { it.id == lot.productId }
                val row = backup.supplies.firstOrNull { it.uuid == lot.inventoryUuid }
                require(lot.kind == product.kind && (row == null || row.unit == product.unit)) { "Backup stock units do not match the food catalog" }
            }
        }
        require(backup.state.customMeals.all { meal -> meal.ingredients.all { ingredient -> content.products.any { it.id == ingredient.productId } } }) { "Backup meal has an unknown ingredient" }
        require(backup.state.mealPlans.all { plan -> (content.meals + backup.state.customMeals).any { it.id == plan.mealId } }) { "Backup has an unknown meal" }
        backup.state.batches.forEach { batch ->
            val recipe = content.recipes.singleOrNull { it.id == batch.recipeId && it.version == batch.recipeVersion && it.verified } ?: error("Backup has an unsupported recipe version; retain it as an archive and ask for an app update")
            require(batch.recipeSnapshot == null || batch.recipeSnapshot == recipe) { "Backup recipe differs from the verified bundled instructions" }
            val process = CanningRules.process(recipe, batch.jar, batch.gauge, batch.elevationFeet)
            require(process.minutes == batch.processingMinutes && process.psi == batch.pressurePsi && batch.method == recipe.method && batch.food == recipe.food)
        }
        val restored = FoodBackups.holdRestoredTimers(backup.state)
        val before = export()
        java.io.File(context.filesDir, "pantry-before-import.json").writeText(before)
        db.withTransaction {
            val current = state()
            val allExisting = db.inventory().all()
            val pristine = db.foodWorkspace().get() == null && allExisting.isEmpty()
            val existing = allExisting.map { it.uuid }.toSet()
            backup.supplies.filter { it.uuid !in existing }.forEach { db.inventory().upsert(it.row()) }
            if (pristine) { put(restored); return@withTransaction }
            fun <T> merge(old: List<T>, added: List<T>, key: (T) -> String): List<T> = old + added.filter { value -> old.none { key(it) == key(value) } }
            put(current.copy(
                profiles = merge(current.profiles, restored.profiles) { it.id },
                lots = merge(current.lots, restored.lots) { it.inventoryUuid },
                locations = merge(current.locations, restored.locations) { it.id },
                targets = merge(current.targets, restored.targets) { it.productId },
                equipment = merge(current.equipment, restored.equipment) { it.id },
                batches = merge(current.batches, restored.batches) { it.id },
                harvests = merge(current.harvests, restored.harvests) { it.id },
                events = merge(current.events, restored.events) { it.id },
                mealPlans = merge(current.mealPlans, restored.mealPlans) { it.id },
                customMeals = merge(current.customMeals, restored.customMeals) { it.id },
            ))
        }
    }
}
