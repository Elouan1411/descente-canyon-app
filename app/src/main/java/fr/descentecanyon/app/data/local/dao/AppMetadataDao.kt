package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.AppMetadataEntity

@Dao
interface AppMetadataDao {

    @Query("SELECT * FROM app_metadata WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: AppMetadataEntity)
}
