package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchIndexDao {

    @Query("SELECT * FROM search_index ORDER BY id")
    fun observeAll(): Flow<List<SearchIndexEntity>>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchIndexEntity>)

    @Query("UPDATE search_index SET isFavorite = :isFavorite WHERE id = :canyonId")
    suspend fun setFavorite(canyonId: Int, isFavorite: Boolean)

    @Query("DELETE FROM search_index")
    suspend fun clearAll()
}
