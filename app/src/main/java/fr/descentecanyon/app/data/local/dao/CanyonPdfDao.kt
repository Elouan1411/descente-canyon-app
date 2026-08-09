package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.CanyonPdfEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonPdfDao {
    @Query("SELECT * FROM canyon_pdfs WHERE canyonId = :canyonId ORDER BY uploadedAt DESC")
    fun getPdfsForCanyon(canyonId: Int): Flow<List<CanyonPdfEntity>>

    @Query("SELECT * FROM canyon_pdfs WHERE canyonId = :canyonId ORDER BY uploadedAt DESC")
    suspend fun getPdfsForCanyonSync(canyonId: Int): List<CanyonPdfEntity>

    @Query("SELECT * FROM canyon_pdfs WHERE serverPdfId = :serverPdfId LIMIT 1")
    suspend fun getPdfByServerId(serverPdfId: String): CanyonPdfEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePdf(pdf: CanyonPdfEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePdfs(pdfs: List<CanyonPdfEntity>)

    @Query("DELETE FROM canyon_pdfs WHERE serverPdfId = :serverPdfId")
    suspend fun deletePdfByServerId(serverPdfId: String)

    @Query("UPDATE canyon_pdfs SET isDownloaded = :isDownloaded, localPath = :localPath WHERE serverPdfId = :serverPdfId")
    suspend fun updateDownloadState(serverPdfId: String, isDownloaded: Boolean, localPath: String?)
}
