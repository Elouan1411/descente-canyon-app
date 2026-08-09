package fr.descentecanyon.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.data.model.AppUpdateInfo
import fr.descentecanyon.app.security.SignatureUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl @Inject constructor() : AppUpdateRepository {

    private val baseUrl = "https://descente-canyon-app.vercel.app"

    override suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val path = "/api/app/update"
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

                val versionCode = jsonObj.getInt("versionCode")
                val versionName = jsonObj.getString("versionName")
                val releaseNotes = jsonObj.optString("releaseNotes", "")
                val minSupportedVersionCode = jsonObj.optInt("minSupportedVersionCode", 1)
                val uploadedAt = jsonObj.optLong("uploadedAt", 0L)
                val downloadUrl = jsonObj.optString("downloadUrl", "/api/app/update/download")

                if (versionCode > BuildConfig.VERSION_CODE) {
                    Result.success(
                        AppUpdateInfo(
                            versionCode = versionCode,
                            versionName = versionName,
                            releaseNotes = releaseNotes,
                            minSupportedVersionCode = minSupportedVersionCode,
                            uploadedAt = uploadedAt,
                            downloadUrl = downloadUrl,
                        )
                    )
                } else {
                    Result.success(null)
                }
            } else if (conn.responseCode == 404) {
                Result.success(null)
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadAndInstallApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = if (updateInfo.downloadUrl.startsWith("/")) updateInfo.downloadUrl else "/api/app/update/download"
            val authHeader = SignatureUtils.generateHmacAuthHeader(context, path)
            val url = URL(if (updateInfo.downloadUrl.startsWith("http")) updateInfo.downloadUrl else "$baseUrl$path")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-App-Auth", authHeader)
                instanceFollowRedirects = true
                readTimeout = 60000
                connectTimeout = 30000
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK || conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP || conn.responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                val fileLength = conn.contentLengthLong

                val updateDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val apkFile = File(updateDir, "descente-canyon-v${updateInfo.versionCode}.apk")

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (fileLength > 0) {
                                onProgress(totalRead.toFloat() / fileLength.toFloat())
                            }
                        }
                    }
                }

                onProgress(1.0f)

                // Trigger APK installation intent
                val apkUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(installIntent)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Download failed with HTTP ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
