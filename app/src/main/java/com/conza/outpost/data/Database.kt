package com.conza.outpost.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One row per installed resource. `resourceId` is the catalog id (version-independent). */
@Entity(tableName = "installed_resources")
data class InstalledResource(
    @PrimaryKey val resourceId: String,
    val kind: String, // ResourceKind.name
    val version: String,
    val title: String,
    val filePath: String,
    val sizeBytes: Long,
    val installedAt: Long,
)

@Dao
interface InstalledDao {
    @Query("SELECT * FROM installed_resources ORDER BY title")
    fun observeAll(): Flow<List<InstalledResource>>

    @Query("SELECT * FROM installed_resources WHERE kind = :kind ORDER BY title")
    fun observeByKind(kind: String): Flow<List<InstalledResource>>

    @Query("SELECT * FROM installed_resources WHERE kind = :kind ORDER BY title")
    suspend fun byKind(kind: String): List<InstalledResource>

    @Query("SELECT * FROM installed_resources WHERE resourceId = :id")
    suspend fun get(id: String): InstalledResource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: InstalledResource)

    @Query("DELETE FROM installed_resources WHERE resourceId = :id")
    suspend fun delete(id: String)
}

// ---------- Notes ----------

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM notes")
    suspend fun all(): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: Note): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notes WHERE title LIKE :q OR body LIKE :q ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int = 20): List<Note>
}

// ---------- Assistant chats ----------

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chat_messages", indices = [Index("sessionId")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    /** JSON list of sources cited for this answer (see ai/Sources.kt). */
    val sourcesJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun session(id: Long): ChatSession?

    @Insert
    suspend fun insertSession(s: ChatSession): Long

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun updateSession(id: Long, title: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :id")
    suspend fun deleteMessages(id: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun messages(sessionId: Long): List<ChatMessage>

    @Insert
    suspend fun insertMessage(m: ChatMessage): Long

    @Query("UPDATE chat_messages SET content = :content, sourcesJson = :sources WHERE id = :id")
    suspend fun updateMessage(id: Long, content: String, sources: String?)
}

// ---------- Retrieval index for notes + imported documents ----------

@Entity(tableName = "documents")
data class ImportedDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    val importedAt: Long = System.currentTimeMillis(),
)

/** A passage of a note or document, with its embedding (float32 little-endian) when a search model is installed. */
@Entity(tableName = "chunks", indices = [Index(value = ["sourceType", "sourceId"])])
data class Chunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String, // "note" | "doc"
    val sourceId: Long,
    val sourceTitle: String,
    val position: Int,
    val text: String,
    val embedding: ByteArray? = null,
    val embeddingModel: String? = null,
)

@Dao
interface RetrievalDao {
    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    fun observeDocuments(): Flow<List<ImportedDocument>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: Long): ImportedDocument?

    @Query("SELECT * FROM documents")
    suspend fun allDocuments(): List<ImportedDocument>

    @Insert
    suspend fun insertDocument(d: ImportedDocument): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("SELECT * FROM chunks")
    suspend fun allChunks(): List<Chunk>

    @Query("SELECT COUNT(*) FROM chunks WHERE embedding IS NULL OR embeddingModel IS NULL OR embeddingModel != :model")
    suspend fun countStale(model: String): Int

    @Query("SELECT * FROM chunks WHERE embedding IS NULL OR embeddingModel IS NULL OR embeddingModel != :model LIMIT :limit")
    suspend fun stale(model: String, limit: Int): List<Chunk>

    @Query("UPDATE chunks SET embedding = :embedding, embeddingModel = :model WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray, model: String)

    @Insert
    suspend fun insertChunks(chunks: List<Chunk>)

    @Query("DELETE FROM chunks WHERE sourceType = :type AND sourceId = :id")
    suspend fun deleteChunks(type: String, id: Long)
}

// ---------- Map pins ----------

@Entity(tableName = "map_markers")
data class MapMarker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val color: String = "#E4572E",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MarkerDao {
    @Query("SELECT * FROM map_markers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MapMarker>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MapMarker): Long

    @Query("DELETE FROM map_markers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        InstalledResource::class, Note::class, ChatSession::class, ChatMessage::class,
        ImportedDocument::class, Chunk::class, MapMarker::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class OutpostDatabase : RoomDatabase() {
    abstract fun installed(): InstalledDao
    abstract fun notes(): NoteDao
    abstract fun chats(): ChatDao
    abstract fun retrieval(): RetrievalDao
    abstract fun markers(): MarkerDao

    companion object {
        fun create(context: Context): OutpostDatabase =
            Room.databaseBuilder(context, OutpostDatabase::class.java, "outpost.db").build()
    }
}
