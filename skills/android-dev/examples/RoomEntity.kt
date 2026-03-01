package com.example.core.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

// ─── Entity ──────────────────────────────────────────────────────────────────
//
// @Entity maps a data class to a database table.
// Table name defaults to the class name; set tableName to control it explicitly.
// Keep entities as pure data classes — no business logic, no Android imports
// beyond Room annotations.

@Entity(tableName = "items")
data class ItemEntity(
    // autoGenerate = true: Room assigns the ID on insert when the value is 0
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val title: String,

    // Store timestamps as Long (milliseconds since epoch) — no converter needed.
    // For complex types (enums, nested objects), use @TypeConverters.
    val createdAt: Long,

    val isCompleted: Boolean = false
)

// Extension functions to convert between layers live alongside the entity
// (or in a dedicated mapper file). They must NOT go into the entity class itself.
fun ItemEntity.toDomain(): com.example.core.domain.model.Item =
    com.example.core.domain.model.Item(
        id = id,
        title = title,
        createdAt = java.time.Instant.ofEpochMilli(createdAt),
        isCompleted = isCompleted
    )

// ─── DAO ─────────────────────────────────────────────────────────────────────
//
// @Dao defines database operations. Room generates the implementation at
// compile time via KSP.
//
// Rules:
// - Read queries that observe changes return Flow<T> — Room re-emits automatically
// - Write operations (insert, update, delete) are suspend functions
// - Never do work on Dispatchers.Main — the repository applies flowOn(IO)

@Dao
interface ItemDao {

    // Flow<List<T>> — Room re-emits the full list whenever the table changes.
    // Collectors never need to poll or manually refresh.
    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<ItemEntity>>

    // Flow<T?> — emits null if the row doesn't exist
    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    fun getItemById(id: Int): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE isCompleted = 0 ORDER BY createdAt DESC")
    fun getActiveItems(): Flow<List<ItemEntity>>

    // OnConflictStrategy.REPLACE: if a row with the same primary key exists,
    // delete it and insert the new one. Use IGNORE to silently skip conflicts.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ItemEntity): Long   // Returns the new row ID

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ItemEntity>)

    @Delete
    suspend fun delete(entity: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM items")
    suspend fun clearAll()

    // Use @Query for updates that target specific columns to avoid overwriting
    // fields you didn't intend to change
    @Query("UPDATE items SET isCompleted = :isCompleted WHERE id = :id")
    suspend fun updateCompletionStatus(id: Int, isCompleted: Boolean)
}

// ─── Database ─────────────────────────────────────────────────────────────────
//
// @Database declares the database class. List all entities here.
// Increment version on every schema change and provide a Migration.
// Never use fallbackToDestructiveMigration() in production — it deletes user data.

@Database(
    entities = [ItemEntity::class],
    version = 2,                   // Increment when the schema changes
    exportSchema = true            // Export schema JSON to assets/ for migration testing
)
abstract class AppDatabase : RoomDatabase() {

    // One abstract getter per DAO
    abstract fun itemDao(): ItemDao

    companion object {
        // Migration from version 1 to 2: added isCompleted column
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        // For destructive migration during development only — comment out before shipping
        // val MIGRATION_DESTRUCTIVE = object : Migration(1, 2) { ... }
    }
}

// ─── Hilt DatabaseModule ─────────────────────────────────────────────────────
//
// Hilt can't inject third-party classes directly — use @Provides for Room.
// @Singleton ensures one database instance exists for the app's lifetime.
// Providing the DAO separately lets feature modules depend on just the DAO,
// not the full database class.

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "app.db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            // Enable WAL mode for better concurrent read performance
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    // The DAO provider depends on AppDatabase — Hilt resolves the dependency graph.
    // No @Singleton needed: the DAO is a thin wrapper; the database is the singleton.
    @Provides
    fun provideItemDao(database: AppDatabase): ItemDao = database.itemDao()
}
