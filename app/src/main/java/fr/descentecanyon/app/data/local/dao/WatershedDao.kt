package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.WatershedEntity

@Dao
interface WatershedDao {

    @Query("SELECT * FROM watersheds WHERE canyonId = :canyonId")
    suspend fun getByCanyonId(canyonId: Int): WatershedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(watersheds: List<WatershedEntity>)

    @Query("DELETE FROM watersheds")
    suspend fun clearAll()
}
