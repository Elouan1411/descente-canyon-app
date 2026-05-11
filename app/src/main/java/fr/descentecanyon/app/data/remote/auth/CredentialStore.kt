@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.data.remote.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for secure credential storage.
 */
interface CredentialStore {
    fun saveCredentials(username: String, password: String)
    fun getUsername(): String?
    fun getPassword(): String?
    fun hasCredentials(): Boolean
    fun clearCredentials()
    fun saveSessionCookies(username: String, cookies: Map<String, String>)
    fun getSessionCookies(): Map<String, String>
    fun hasSessionCookies(): Boolean
}

/**
 * Implementation using EncryptedSharedPreferences (AES-256, Android Keystore-backed).
 */
@Singleton
class EncryptedCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CredentialStore {

    companion object {
        private const val PREFS_NAME = "dc_auth_prefs"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SESSION_COOKIES = "session_cookies"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (_: Exception) {
            // Keystore corruption or OS upgrade: clear and recreate
            context.deleteSharedPreferences(PREFS_NAME)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    override fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    override fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    override fun hasCredentials(): Boolean =
        getUsername() != null && getPassword() != null

    override fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    override fun saveSessionCookies(username: String, cookies: Map<String, String>) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_SESSION_COOKIES, cookies.serializeCookies())
            .apply()
    }

    override fun getSessionCookies(): Map<String, String> {
        return prefs.getString(KEY_SESSION_COOKIES, null).deserializeCookies()
    }

    override fun hasSessionCookies(): Boolean = getSessionCookies().isNotEmpty()
}

private fun Map<String, String>.serializeCookies(): String {
    return entries.joinToString("&") { (key, value) ->
        "${key.encodeCookiePart()}=${value.encodeCookiePart()}"
    }
}

private fun String?.deserializeCookies(): Map<String, String> {
    if (isNullOrBlank()) return emptyMap()
    return split('&')
        .mapNotNull { entry ->
            val separatorIndex = entry.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = entry.substring(0, separatorIndex).decodeCookiePart()
            val value = entry.substring(separatorIndex + 1).decodeCookiePart()
            key to value
        }
        .toMap()
}

private fun String.encodeCookiePart(): String = URLEncoder.encode(this, "UTF-8")

private fun String.decodeCookiePart(): String = URLDecoder.decode(this, "UTF-8")
