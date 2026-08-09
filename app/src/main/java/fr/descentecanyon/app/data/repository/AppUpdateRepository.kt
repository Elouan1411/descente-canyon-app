package fr.descentecanyon.app.data.repository

import android.content.Context
import fr.descentecanyon.app.data.model.AppUpdateInfo

interface AppUpdateRepository {
    suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo?>
    suspend fun downloadAndInstallApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<Unit>
}
