package fr.descentecanyon.app.data.repository

import android.content.Context

interface AccessPassRepository {
    fun isAppUnlocked(context: Context): Boolean
    suspend fun verifyAndUnlockApp(context: Context, password: String): Result<Unit>
}
