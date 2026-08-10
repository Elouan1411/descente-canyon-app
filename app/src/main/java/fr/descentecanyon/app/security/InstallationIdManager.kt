package fr.descentecanyon.app.security

import android.content.Context
import java.util.UUID

object InstallationIdManager {
    private const val PREFS_NAME = "installation_identity_prefs"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun getInstallationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var installationId = prefs.getString(KEY_INSTALLATION_ID, null)
        if (installationId.isNullOrBlank()) {
            installationId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_ID, installationId).apply()
        }
        return installationId
    }
}
