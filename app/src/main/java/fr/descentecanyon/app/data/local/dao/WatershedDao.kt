package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.WatershedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatershedDao {

    @Query("SELECT * FROM watersheds WHERE canyonId = :canyonId")
    suspend fun getByCanyonId(canyonId: Int): WatershedEntity?

    @Query("SELECT * FROM watersheds WHERE canyonId = :canyonId")
    fun observeByCanyonId(canyonId: Int): Flow<WatershedEntity?>

    @Query("SELECT COUNT(*) FROM watersheds")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(watersheds: List<WatershedEntity>)

    @Query("DELETE FROM watersheds")
    suspend fun clearAll()
}
