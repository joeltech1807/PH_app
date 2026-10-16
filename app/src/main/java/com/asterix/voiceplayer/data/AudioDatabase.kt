package com.asterix.voiceplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.io.File

/** One imported audio file. [filePath] points to the app-private copy, so access survives the source being moved. */
@Entity(tableName = "audio_entries", indices = [Index(value = ["aliasKey"], unique = true)])
data class AudioEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val aliasKey: String,
    val originalName: String,
    val filePath: String,
    val mimeType: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAt: Long,
) {
    val fileExists: Boolean get() = File(filePath).let { it.isFile && it.canRead() }
}

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_entries ORDER BY alias COLLATE NOCASE")
    fun observeAll(): Flow<List<AudioEntry>>

    @Query("SELECT * FROM audio_entries WHERE aliasKey = :key LIMIT 1")
    suspend fun findByKey(key: String): AudioEntry?

    @Query("SELECT * FROM audio_entries WHERE id = :id")
    suspend fun findById(id: Long): AudioEntry?

    @Query("SELECT filePath FROM audio_entries")
    suspend fun allFilePaths(): List<String>

    @Insert
    suspend fun insert(entry: AudioEntry): Long

    @Update
    suspend fun update(entry: AudioEntry)

    @Delete
    suspend fun delete(entry: AudioEntry)
}

@Database(entities = [AudioEntry::class], version = 1, exportSchema = false)
abstract class AudioDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao

    companion object {
        fun create(context: Context): AudioDatabase =
            Room.databaseBuilder(context, AudioDatabase::class.java, "asterix.db").build()
    }
}
