package fr.descentecanyon.app.data.repository

import android.content.Context
import android.net.Uri
import fr.descentecanyon.app.data.local.entity.CanyonPdfEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

interface CanyonPdfRepository {
    fun getPdfsForCanyon(canyonId: Int): Flow<List<CanyonPdfEntity>>
    suspend fun syncPdfsForCanyon(canyonId: Int): Result<Unit>
    suspend fun uploadPdf(context: Context, canyonId: Int, fileUri: Uri, fileName: String, fileSize: Long): Result<CanyonPdfEntity>
    suspend fun downloadPdfFile(context: Context, pdf: CanyonPdfEntity): Result<File>
    suspend fun deletePdf(context: Context, pdf: CanyonPdfEntity): Result<Unit>
    fun openPdfWithExternalApp(context: Context, pdf: CanyonPdfEntity): Result<Unit>
}
