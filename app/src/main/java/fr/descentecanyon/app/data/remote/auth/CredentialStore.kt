@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.data.remote.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
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
}
