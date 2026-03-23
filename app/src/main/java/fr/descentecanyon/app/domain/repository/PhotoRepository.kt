package fr.descentecanyon.app.domain.repository

interface PhotoRepository {
    suspend fun downloadPhoto(photoId: Long): Result<String>
}
