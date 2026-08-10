package fr.descentecanyon.app.data.repository

import android.content.Context
import fr.descentecanyon.app.security.SignatureUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessPassRepositoryImpl @Inject constructor() : AccessPassRepository {

    private val baseUrl = "https://descente-canyon-app.vercel.app"
    private val prefsName = "app_access_prefs"
    private val keyUnlocked = "is_app_unlocked"

    override fun isAppUnlocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return prefs.getBoolean(keyUnlocked, false)
    }

    override suspend fun verifyAndUnlockApp(
        context: Context,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val path = "/api/app/verify-access-pass"
            val authHeader = SignatureUtils.generateHmacAuthHeader(context, path)
            val url = URL("$baseUrl$path")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Auth", authHeader)
                doOutput = true
                readTimeout = 15000
                connectTimeout = 15000
            }

            val body = JSONObject().apply {
                put("password", password)
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val statusCode = conn.responseCode
            val responseStr = if (statusCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (statusCode == 200) {
                val json = JSONObject(responseStr)
                if (json.optBoolean("success", false)) {
                    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(keyUnlocked, true).apply()
                    Result.success(Unit)
                } else {
                    val errorMsg = json.optString("error", "Mot de passe incorrect")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val json = try { JSONObject(responseStr) } catch (_: Exception) { null }
                val errorMsg = json?.optString("error") ?: "Mot de passe d'accès incorrect"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Connexion impossible. Vérifiez votre accès internet."))
        }
    }
}
