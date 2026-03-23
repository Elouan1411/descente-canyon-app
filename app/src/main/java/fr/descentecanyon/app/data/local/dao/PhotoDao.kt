package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.PhotoEntity

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos WHERE id = :photoId LIMIT 1")
    suspend fun getById(photoId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE canyonId = :canyonId")
    suspend fun getByCanyonId(canyonId: Int): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Query("UPDATE photos SET localPath = :localPath WHERE id = :photoId")
    suspend fun updateLocalPath(photoId: Long, localPath: String?)

    @Query("DELETE FROM photos WHERE canyonId = :canyonId")
    suspend fun deleteByCanyonId(canyonId: Int)
}
