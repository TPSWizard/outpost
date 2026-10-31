package com.conza.outpost.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A person or pet in the household. Ages drive calorie and water needs. */
@Entity(tableName = "household_members")
data class HouseholdMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Approximate birth year (people). For pets, ignored. */
    val birthYear: Int? = null,
    /** "low" | "moderate" | "high" */
    val activity: String = "moderate",
    val medicalNotes: String = "",
    val isPet: Boolean = false,
    /** "dog" | "cat" | "other" for pets. */
    val petType: String = "",
    val weightKg: Double? = null,
    val pregnantOrNursing: Boolean = false,
)

@Entity(tableName = "medications", indices = [Index("memberId")])
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: Long,
    val name: String,
    val dosesPerDay: Double,
    val onHand: Double,
    val critical: Boolean = false,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface HouseholdDao {
    @Query("SELECT * FROM household_members ORDER BY isPet, id")
    fun observeMembers(): Flow<List<HouseholdMember>>

    @Query("SELECT * FROM household_members ORDER BY isPet, id")
    suspend fun members(): List<HouseholdMember>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(m: HouseholdMember): Long

    @Query("DELETE FROM household_members WHERE id = :id")
    suspend fun deleteMember(id: Long)

    @Query("SELECT * FROM medications ORDER BY critical DESC, name")
    fun observeMeds(): Flow<List<Medication>>

    @Query("SELECT * FROM medications")
    suspend fun meds(): List<Medication>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMed(m: Medication): Long

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMed(id: Long)

    @Query("DELETE FROM medications WHERE memberId = :memberId")
    suspend fun deleteMedsFor(memberId: Long)

    @Query("SELECT * FROM plan_items ORDER BY type, id")
    fun observePlan(): Flow<List<PlanItem>>

    @Query("SELECT * FROM plan_items ORDER BY type, id")
    suspend fun plan(): List<PlanItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(p: PlanItem): Long

    @Query("DELETE FROM plan_items WHERE id = :id")
    suspend fun deletePlan(id: Long)
}

/** Supplies. Quantities are in [unit]; litersPerUnit/caloriesPerUnit turn them into needs-comparable totals. */
@Entity(tableName = "inventory_items")
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    /** WATER | FOOD | MEDICINE | FUEL | BATTERIES | OTHER */
    val category: String,
    val name: String,
    val quantity: Double,
    val unit: String = "",
    val litersPerUnit: Double? = null,
    val caloriesPerUnit: Double? = null,
    /** Units used per day, for fuel/batteries/other (when known). */
    val dailyUse: Double? = null,
    val expiry: String? = null,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_items ORDER BY category, name")
    fun observe(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory_items")
    suspend fun all(): List<InventoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(i: InventoryItem): Long

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM inventory_items WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): InventoryItem?
}

@Entity(tableName = "checklist_done", primaryKeys = ["taskId", "day"])
data class ChecklistDone(val taskId: String, val day: Int, val doneAt: Long = System.currentTimeMillis())

@Entity(tableName = "pressure_samples")
data class PressureSample(@PrimaryKey val at: Long, val hPa: Float)

@Entity(tableName = "battery_samples")
data class BatterySample(@PrimaryKey val at: Long, val percent: Float, val charging: Boolean, val survivalMode: Boolean)

/** Contacts and rally points for the family plan card. */
@Entity(tableName = "plan_items")
data class PlanItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "contact" | "rally" */
    val type: String,
    val name: String,
    val detail: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
)

@Entity(tableName = "radio_log")
data class RadioLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val channel: String,
    /** "heard" | "sent" | "schedule" */
    val kind: String,
    val station: String = "",
    val text: String = "",
)

@Entity(tableName = "mesh_messages")
data class MeshMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    /** "nearby" | "meshtastic" */
    val transport: String,
    val from: String,
    val text: String,
    val outgoing: Boolean,
)

@Dao
interface GridDownDao {
    @Query("SELECT * FROM checklist_done WHERE day = :day")
    fun observeDone(day: Int): Flow<List<ChecklistDone>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markDone(d: ChecklistDone)

    @Query("DELETE FROM checklist_done WHERE taskId = :taskId AND day = :day")
    suspend fun unmark(taskId: String, day: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPressure(s: PressureSample)

    @Query("SELECT * FROM pressure_samples WHERE at >= :since ORDER BY at")
    suspend fun pressureSince(since: Long): List<PressureSample>

    @Query("SELECT * FROM pressure_samples WHERE at >= :since ORDER BY at")
    fun observePressure(since: Long): Flow<List<PressureSample>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBattery(s: BatterySample)

    @Query("SELECT * FROM battery_samples WHERE at >= :since ORDER BY at")
    suspend fun batterySince(since: Long): List<BatterySample>

    @Query("DELETE FROM battery_samples WHERE at < :before")
    suspend fun pruneBattery(before: Long)

    @Query("DELETE FROM pressure_samples WHERE at < :before")
    suspend fun prunePressure(before: Long)

    @Query("SELECT * FROM radio_log ORDER BY at DESC")
    fun observeRadio(): Flow<List<RadioLogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRadio(e: RadioLogEntry): Long

    @Query("DELETE FROM radio_log WHERE id = :id")
    suspend fun deleteRadio(id: Long)

    @Query("SELECT * FROM mesh_messages ORDER BY at")
    fun observeMesh(): Flow<List<MeshMessage>>

    @Query("SELECT * FROM mesh_messages")
    suspend fun meshAll(): List<MeshMessage>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMesh(m: MeshMessage): Long

    @Query("SELECT COUNT(*) FROM mesh_messages WHERE uuid = :uuid")
    suspend fun meshExists(uuid: String): Int
}
