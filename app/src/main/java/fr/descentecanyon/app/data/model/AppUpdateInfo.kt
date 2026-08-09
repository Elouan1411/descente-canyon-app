package fr.descentecanyon.app.data.model

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val minSupportedVersionCode: Int,
    val uploadedAt: Long,
    val downloadUrl: String,
)
