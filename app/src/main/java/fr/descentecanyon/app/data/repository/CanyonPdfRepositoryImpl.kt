package fr.descentecanyon.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.descentecanyon.app.data.local.dao.CanyonPdfDao
import fr.descentecanyon.app.data.local.entity.CanyonPdfEntity
import fr.descentecanyon.app.security.SignatureUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CanyonPdfRepositoryImpl @Inject constructor(
    private val canyonPdfDao: CanyonPdfDao,
) : CanyonPdfRepository {

    // Configure your Vercel deployment URL here or via BuildConfig
    private val baseUrl = "https://descente-canyon-app.vercel.app"

    override fun getPdfsForCanyon(canyonId: Int): Flow<List<CanyonPdfEntity>> {
        return canyonPdfDao.getPdfsForCanyon(canyonId)
    }

    override suspend fun syncPdfsForCanyon(canyonId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = "/api/canyons/$canyonId/pdfs"
            val url = URL("$baseUrl$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                readTimeout = 15000
                connectTimeout = 15000
            }

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(jsonStr)
                val pdfsArray = jsonObj.getJSONArray("pdfs")

                val remotePdfs = mutableListOf<CanyonPdfEntity>()
                for (i in 0 until pdfsArray.length()) {
                    val item = pdfsArray.getJSONObject(i)
                    val serverPdfId = item.getString("id")
                    val fileName = item.getString("fileName")
                    val fileSize = item.getLong("fileSize")
                    val blobUrl = item.getString("blobUrl")
                    val uploadedAt = item.getLong("uploadedAt")

                    val existingLocal = canyonPdfDao.getPdfByServerId(serverPdfId)
                    remotePdfs.add(
                        CanyonPdfEntity(
                            id = existingLocal?.id ?: 0,
                            serverPdfId = serverPdfId,
                            canyonId = canyonId,
                            fileName = fileName,
                            fileSize = fileSize,
                            localPath = existingLocal?.localPath,
                            remoteUrl = blobUrl,
                            uploadedAt = uploadedAt,
                            isDownloaded = existingLocal?.isDownloaded ?: false
                        )
                    )
                }
                canyonPdfDao.insertOrUpdatePdfs(remotePdfs)
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP Error ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadPdf(
        context: Context,
        canyonId: Int,
        fileUri: Uri,
        fileName: String,
        fileSize: Long
    ): Result<CanyonPdfEntity> = withContext(Dispatchers.IO) {
        val maxSizeBytes = 100L * 1024L * 1024L
        if (fileSize > maxSizeBytes) {
            return@withContext Result.failure(IllegalArgumentException("LIMIT_EXCEEDED_100MB"))
        }

        try {
            val path = "/api/canyons/$canyonId/pdfs"
            val authHeader = SignatureUtils.generateHmacAuthHeader(context, path)
            val url = URL("$baseUrl$path")
            val boundary = "Boundary-${System.currentTimeMillis()}"

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("X-App-Auth", authHeader)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                readTimeout = 60000
                connectTimeout = 30000
            }

            conn.outputStream.use { os ->
                val header = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/pdf\r\n\r\n"
                os.write(header.toByteArray(Charsets.UTF_8))

                context.contentResolver.openInputStream(fileUri)?.use { isStream ->
                    isStream.copyTo(os)
                } ?: throw IllegalArgumentException("Cannot read file stream")

                val footer = "\r\n--$boundary--\r\n"
                os.write(footer.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (conn.responseCode in 200..201) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val item = JSONObject(jsonStr).getJSONObject("pdf")
                val serverPdfId = item.getString("id")
                val remoteUrl = item.getString("blobUrl")
                val uploadedAt = item.getLong("uploadedAt")

                val entity = CanyonPdfEntity(
                    serverPdfId = serverPdfId,
                    canyonId = canyonId,
                    fileName = fileName,
                    fileSize = fileSize,
                    localPath = null,
                    remoteUrl = remoteUrl,
                    uploadedAt = uploadedAt,
                    isDownloaded = false
                )

                val generatedId = canyonPdfDao.insertOrUpdatePdf(entity)
                Result.success(entity.copy(id = generatedId))
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP ${conn.responseCode}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadPdfFile(
        context: Context,
        pdf: CanyonPdfEntity
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfsDir = File(context.filesDir, "pdfs/${pdf.canyonId}").apply { mkdirs() }
            val localFile = File(pdfsDir, "${pdf.serverPdfId}.pdf")

            val url = URL(pdf.remoteUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                readTimeout = 30000
                connectTimeout = 15000
            }

            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }
                canyonPdfDao.updateDownloadState(pdf.serverPdfId, isDownloaded = true, localPath = localFile.absolutePath)
                Result.success(localFile)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP Download Error ${conn.responseCode}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePdf(
        context: Context,
        pdf: CanyonPdfEntity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = "/api/pdfs/${pdf.serverPdfId}"
            val authHeader = SignatureUtils.generateHmacAuthHeader(context, path)
            val url = URL("$baseUrl$path")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("X-App-Auth", authHeader)
                readTimeout = 15000
                connectTimeout = 15000
            }

            if (conn.responseCode in 200..204) {
                pdf.localPath?.let { pathStr ->
                    val localFile = File(pathStr)
                    if (localFile.exists()) {
                        localFile.delete()
                    }
                }
                canyonPdfDao.deletePdfByServerId(pdf.serverPdfId)
                Result.success(Unit)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP Delete Error ${conn.responseCode}: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun openPdfWithExternalApp(
        context: Context,
        pdf: CanyonPdfEntity
    ): Result<Unit> {
        return try {
            val pathStr = pdf.localPath ?: return Result.failure(IllegalStateException("File not downloaded locally"))
            val localFile = File(pathStr)
            if (!localFile.exists()) {
                return Result.failure(IllegalStateException("File missing on device"))
            }

            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, localFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(intent, pdf.fileName).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooserIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
