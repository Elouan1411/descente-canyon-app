package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.PendingDebitSubmissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDebitSubmissionDao {

    @Query("SELECT * FROM pending_debit_submissions ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingDebitSubmissionEntity>

    @Query("SELECT COUNT(*) FROM pending_debit_submissions")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(submission: PendingDebitSubmissionEntity): Long

    @Query("DELETE FROM pending_debit_submissions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
