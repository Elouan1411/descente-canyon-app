package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.CanyonTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonTrackDao {

    @Query("SELECT * FROM canyon_tracks WHERE canyonId = :canyonId ORDER BY isPrimary DESC, name ASC, trackId ASC")
    suspend fun getByCanyonId(canyonId: Int): List<CanyonTrackEntity>

    @Query("SELECT * FROM canyon_tracks WHERE canyonId = :canyonId ORDER BY isPrimary DESC, name ASC, trackId ASC")
    fun observeByCanyonId(canyonId: Int): Flow<List<CanyonTrackEntity>>

    @Query("SELECT COUNT(*) FROM canyon_tracks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<CanyonTrackEntity>)

    @Query("DELETE FROM canyon_tracks WHERE canyonId = :canyonId")
    suspend fun deleteByCanyonId(canyonId: Int)

    @Query("DELETE FROM canyon_tracks")
    suspend fun clearAll()
}
