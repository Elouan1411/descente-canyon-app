package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.repository.MapOfflineRepository
import fr.descentecanyon.app.map.MAP_STYLE_URI
import fr.descentecanyon.app.map.createOfflineBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class MapOfflineRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MapOfflineRepository {

    override suspend fun downloadRegion(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Result<Unit> = runCatching {
        MapLibre.getInstance(context)
        val definition = OfflineTilePyramidRegionDefinition(
            MAP_STYLE_URI,
            createOfflineBounds(latitude, longitude, radiusKm),
            8.0,
            15.0,
            context.resources.displayMetrics.density,
        )

        suspendCancellableCoroutine { continuation ->
            OfflineManager.getInstance(context).createOfflineRegion(
                definition,
                name.toByteArray(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        continuation.invokeOnCancellation {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        }
                        offlineRegion.setObserver(
                            object : OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                                    if (status.isComplete && continuation.isActive) {
                                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                        continuation.resume(Unit)
                                    }
                                }

                                override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(IllegalStateException(error.message))
                                    }
                                }

                                override fun mapboxTileCountLimitExceeded(limit: Long) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException("Limite de tuiles atteinte : $limit")
                                        )
                                    }
                                }
                            }
                        )
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException(error))
                        }
                    }
                },
            )
        }
    }
}
