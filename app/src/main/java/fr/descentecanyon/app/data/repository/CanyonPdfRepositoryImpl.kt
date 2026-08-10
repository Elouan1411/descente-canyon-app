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

    override suspend fun syncPdfsForCanyon(context: Context, canyonId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = "/api/canyons/$canyonId/pdfs"
            val authHeader = SignatureUtils.generateHmacAuthHeader(context, path)
            val url = URL("$baseUrl$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-App-Auth", authHeader)
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
                    val mimeType = item.optString("mimeType", "application/pdf")
                    val uploaderId = item.optString("uploaderId", "").ifBlank { null }

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
                            mimeType = mimeType,
                            isDownloaded = existingLocal?.isDownloaded ?: false,
                            uploaderId = uploaderId ?: existingLocal?.uploaderId
                        )
                    )
                }

                val localPdfs = canyonPdfDao.getPdfsForCanyonSync(canyonId)
                val remoteServerIds = remotePdfs.map { it.serverPdfId }.toSet()

                for (localPdf in localPdfs) {
                    if (localPdf.serverPdfId !in remoteServerIds) {
                        localPdf.localPath?.let { path ->
                            val file = File(path)
                            if (file.exists()) file.delete()
                        }
                        canyonPdfDao.deletePdfByServerId(localPdf.serverPdfId)
                    }
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
            val myInstallationId = fr.descentecanyon.app.security.InstallationIdManager.getInstallationId(context)
            val mimeType = context.contentResolver.getType(fileUri) ?: "application/pdf"
            
            // 1. Demander le jeton d'upload direct
            val tokenPath = "/api/canyons/$canyonId/pdfs/upload-token"
            val tokenAuth = SignatureUtils.generateHmacAuthHeader(context, tokenPath)
            val tokenConn = (URL("$baseUrl$tokenPath").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-App-Auth", tokenAuth)
                setRequestProperty("Content-Type", "application/json")
            }
            
            val tokenPayload = JSONObject().apply {
                put("fileName", fileName)
                put("contentType", mimeType)
            }.toString()
            
            tokenConn.outputStream.use { os ->
                os.write(tokenPayload.toByteArray())
                os.flush()
            }
            
            if (tokenConn.responseCode != 200) {
                val err = tokenConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext Result.failure(Exception("Token fetch failed: ${tokenConn.responseCode} $err"))
            }
            
            val tokenResponse = JSONObject(tokenConn.inputStream.bufferedReader().use { it.readText() })
            val clientToken = tokenResponse.getString("clientToken")
            val blobUrl = tokenResponse.getString("uploadUrl")
            val pdfId = tokenResponse.getString("pdfId")
            
            // 2. Upload direct sur Vercel Blob (bypasse la limite de 4.5 Mo)
            val uploadConn = (URL(blobUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $clientToken")
                setRequestProperty("x-api-version", "7")
                setRequestProperty("Content-Type", mimeType)
            }
            
            uploadConn.outputStream.use { os ->
                context.contentResolver.openInputStream(fileUri)?.use { isStream ->
                    isStream.copyTo(os)
                }
            }
            
            if (uploadConn.responseCode != 200) {
                return@withContext Result.failure(Exception("Blob upload failed: ${uploadConn.responseCode}"))
            }
            
            val uploadResult = JSONObject(uploadConn.inputStream.bufferedReader().use { it.readText() })
            val finalBlobUrl = uploadResult.getString("url")
            
            // 3. Enregistrer les métadonnées sur le serveur Postgres
            val savePath = "/api/canyons/$canyonId/pdfs"
            val saveAuth = SignatureUtils.generateHmacAuthHeader(context, savePath)
            val saveConn = (URL("$baseUrl$savePath").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-App-Auth", saveAuth)
                setRequestProperty("Content-Type", "application/json")
            }
            
            val savePayload = JSONObject().apply {
                put("id", pdfId)
                put("blobUrl", finalBlobUrl)
                put("fileName", fileName)
                put("fileSize", fileSize)
                put("mimeType", mimeType)
                put("uploaderId", myInstallationId)
            }.toString()
            
            saveConn.outputStream.use { os ->
                os.write(savePayload.toByteArray())
                os.flush()
            }
            
            if (saveConn.responseCode in 200..201) {
                val jsonStr = saveConn.inputStream.bufferedReader().use { it.readText() }
                val item = JSONObject(jsonStr).getJSONObject("pdf")
                val serverPdfId = item.getString("id")
                val remoteUrl = item.getString("blobUrl")
                val uploadedAt = item.getLong("uploadedAt")
                val savedMimeType = item.optString("mimeType", mimeType)
                val savedUploaderId = item.optString("uploaderId", myInstallationId)

                val entity = CanyonPdfEntity(
                    serverPdfId = serverPdfId,
                    canyonId = canyonId,
                    fileName = fileName,
                    fileSize = fileSize,
                    localPath = null,
                    remoteUrl = remoteUrl,
                    uploadedAt = uploadedAt,
                    mimeType = savedMimeType,
                    isDownloaded = false,
                    uploaderId = savedUploaderId
                )

                val generatedId = canyonPdfDao.insertOrUpdatePdf(entity)
                Result.success(entity.copy(id = generatedId))
            } else {
                val err = saveConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP save error ${saveConn.responseCode}: $err"))
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
            val extension = pdf.fileName.substringAfterLast('.', "pdf")
            val pdfsDir = File(context.filesDir, "pdfs/${pdf.canyonId}").apply { mkdirs() }
            val localFile = File(pdfsDir, "${pdf.serverPdfId}.$extension")

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

            val targetMimeType = when {
                pdf.mimeType.isNotBlank() && pdf.mimeType != "application/octet-stream" -> pdf.mimeType
                pdf.fileName.endsWith(".gpx", ignoreCase = true) -> "application/gpx+xml"
                pdf.fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                pdf.fileName.endsWith(".jpg", ignoreCase = true) || pdf.fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                pdf.fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
                else -> "application/pdf"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, targetMimeType)
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
