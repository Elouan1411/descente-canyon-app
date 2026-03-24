package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonDao {

    @Query("SELECT * FROM canyons WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<CanyonEntity>

    @Query("SELECT * FROM canyons WHERE nom LIKE '%' || :query || '%' OR nomComplet LIKE '%' || :query || '%'")
    fun searchByName(query: String): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE id = :id")
    suspend fun getById(id: Int): CanyonEntity?

    @Query("SELECT COUNT(*) FROM canyons")
    suspend fun count(): Int

    @Query("SELECT id FROM canyons WHERE isFavorite = 1")
    suspend fun getFavoriteIds(): List<Int>

    @Query("SELECT * FROM canyons WHERE isOffline = 1")
    fun getOfflineCanyons(): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE isFavorite = 1")
    fun getFavorites(): Flow<List<CanyonEntity>>

    @Query("SELECT isFavorite FROM canyons WHERE id = :canyonId")
    fun isFavorite(canyonId: Int): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(canyons: List<CanyonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(canyon: CanyonEntity)

    @Update
    suspend fun update(canyon: CanyonEntity)

    @Query("DELETE FROM canyons")
    suspend fun clearAll()

    @Query("UPDATE canyons SET isOffline = :isOffline WHERE id = :canyonId")
    suspend fun setOffline(canyonId: Int, isOffline: Boolean)

    @Query("UPDATE canyons SET isFavorite = :isFavorite WHERE id = :canyonId")
    suspend fun setFavorite(canyonId: Int, isFavorite: Boolean)

    @Query("SELECT * FROM canyons WHERE pays = :pays")
    fun getByCountry(pays: String): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE departement = :departement")
    fun getByDepartement(departement: String): Flow<List<CanyonEntity>>
}
