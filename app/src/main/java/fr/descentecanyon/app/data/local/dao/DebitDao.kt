package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.DebitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebitDao {

    @Query("SELECT * FROM debits WHERE canyonId = :canyonId ORDER BY date DESC")
    fun getByCanyonId(canyonId: Int): Flow<List<DebitEntity>>

    @Query("SELECT * FROM debits ORDER BY date DESC LIMIT :limit")
    fun getLatest(limit: Int): Flow<List<DebitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(debits: List<DebitEntity>)

    @Query("DELETE FROM debits WHERE canyonId = :canyonId")
    suspend fun deleteByCanyonId(canyonId: Int)
}
