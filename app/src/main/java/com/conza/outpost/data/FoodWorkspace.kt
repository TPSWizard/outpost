package com.conza.outpost.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Typed food metadata is versioned JSON. Quantities remain in the existing supplies table. */
@Entity(tableName = "food_workspace")
data class FoodWorkspaceRow(@PrimaryKey val id: Int = 1, val payload: String)

@Dao
interface FoodWorkspaceDao {
    @Query("SELECT * FROM food_workspace WHERE id = 1")
    fun observe(): Flow<FoodWorkspaceRow?>

    @Query("SELECT * FROM food_workspace WHERE id = 1")
    suspend fun get(): FoodWorkspaceRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: FoodWorkspaceRow)
}
