package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class PlaybackBehavior {
    ONE_SHOT,
    LOOP,
    TOGGLE,
    PLAY_PAUSE,
    OVERLAY
}

enum class SoundType {
    KICK,
    SNARE,
    HIHAT,
    TOM,
    CLAP,
    SYNTH_SINE,
    SYNTH_SAW,
    AMBIENT_DRONE,
    LASER_SWEEP,
    SPACE_PLUCK
}

enum class LedColor {
    CYAN,
    PINK,
    PURPLE,
    GREEN,
    YELLOW,
    BLUE,
    ORANGE,
    RED
}

@Entity(tableName = "pad_configs")
data class PadConfig(
    @PrimaryKey val id: Int, // 1 to 16
    val label: String,
    val soundType: SoundType,
    val frequency: Float, // Pitch / fundamental frequency in Hz
    val durationSeconds: Float, // Sample duration in seconds
    val playbackBehavior: PlaybackBehavior,
    val ledColor: LedColor,
    val customAudioPath: String? = null
)

@Dao
interface PadDao {
    @Query("SELECT * FROM pad_configs ORDER BY id ASC")
    fun getAllPadsFlow(): Flow<List<PadConfig>>

    @Query("SELECT * FROM pad_configs WHERE id = :id")
    suspend fun getPadById(id: Int): PadConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPad(pad: PadConfig)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPads(pads: List<PadConfig>)
}

@Database(entities = [PadConfig::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun padDao(): PadDao
}

class PadRepository(private val padDao: PadDao) {
    val allPads: Flow<List<PadConfig>> = padDao.getAllPadsFlow()

    suspend fun getPadById(id: Int): PadConfig? = padDao.getPadById(id)

    suspend fun updatePad(pad: PadConfig) {
        padDao.insertPad(pad)
    }

    suspend fun insertInitialPads(pads: List<PadConfig>) {
        padDao.insertPads(pads)
    }
}
