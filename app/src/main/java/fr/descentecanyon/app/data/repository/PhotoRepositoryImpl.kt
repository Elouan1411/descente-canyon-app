package fr.descentecanyon.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.database.AppDatabase
import fr.descentecanyon.app.data.local.dao.PhotoDao
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.repository.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val photoDao: PhotoDao,
    private val scraper: CanyonScraper,
    private val webClient: DescenteCanyonWebClient,
    @param:ApplicationContext private val context: Context,
) : PhotoRepository {

    override fun observePhotos(canyonId: Int): Flow<List<CanyonPhoto>> {
        return photoDao.observeByCanyonId(canyonId).map { photos ->
            photos.map { it.toDomain() }
        }
    }

    override suspend fun refreshPhotos(canyonId: Int): Result<List<CanyonPhoto>> = runCatching {
        withContext(Dispatchers.IO) {
            val existingByUrl = photoDao.getByCanyonId(canyonId).associateBy { it.url }
            val entities = scraper.scrapeCanyonPhotos(canyonId).getOrThrow().map { scrapedPhoto ->
                val existing = existingByUrl[scrapedPhoto.url]
                scrapedPhoto.toEntity().copy(
                    id = existing?.id ?: 0,
                    localPath = existing?.localPath,
                )
            }

            database.withTransaction {
                photoDao.deleteByCanyonId(canyonId)
                if (entities.isNotEmpty()) {
                    photoDao.insertAll(entities)
                }
            }

            entities.map { it.toDomain() }
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

            webClient.downloadToFile(url = photo.url, targetFile = targetFile)

            photoDao.updateLocalPath(photoId, targetFile.absolutePath)
            targetFile.absolutePath
        }
    }
}
