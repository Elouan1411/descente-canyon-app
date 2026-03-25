package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.repository.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    @param:ApplicationContext private val context: Context,
) : PhotoRepository {

    override fun observePhotos(canyonId: Int): Flow<List<CanyonPhoto>> {
        return photoDao.observeByCanyonId(canyonId).map { photos ->
            photos.map { it.toDomain() }
        }
    }

    override suspend fun downloadPhoto(photoId: Long): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val photo = photoDao.getById(photoId)
                ?: error("Photo introuvable")

            photo.localPath?.takeIf { File(it).exists() }?.let { return@withContext it }

            val photosDir = File(context.filesDir, "offline-photos/${photo.canyonId}")
            photosDir.mkdirs()

            val extension = photo.url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.isNotBlank() } ?: "jpg"
            val targetFile = File(photosDir, "photo-${photoId}.${extension}")

            URL(photo.url).openStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            photoDao.updateLocalPath(photoId, targetFile.absolutePath)
            targetFile.absolutePath
        }
    }
}
