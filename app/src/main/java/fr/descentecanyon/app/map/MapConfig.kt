package fr.descentecanyon.app.map

import fr.descentecanyon.app.domain.model.CanyonSummary
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos
import kotlin.math.floor

const val MAP_STYLE_URI = "asset://map/opentopomap_style.json"
const val MAP_SEARCH_STYLE_URI = "asset://map/osm_light_style.json"
const val MAP_CLUSTER_ZOOM_THRESHOLD = 10.0
const val MAP_OFFLINE_RADIUS_KM = 3.0

sealed interface MapDisplayMarker {
    val latitude: Double
    val longitude: Double

    data class Canyon(
        val canyon: CanyonSummary,
        override val latitude: Double,
        override val longitude: Double,
    ) : MapDisplayMarker

    data class Cluster(
        val canyonIds: List<Int>,
        val count: Int,
        override val latitude: Double,
        override val longitude: Double,
    ) : MapDisplayMarker

    data class User(
        override val latitude: Double,
        override val longitude: Double,
    ) : MapDisplayMarker
}

object MapClusterEngine {
    fun cluster(
        canyons: List<CanyonSummary>,
        zoom: Double,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
    ): List<MapDisplayMarker> {
        val visibleMarkers = buildList {
            if (zoom >= MAP_CLUSTER_ZOOM_THRESHOLD) {
                canyons.forEach { canyon ->
                    val latitude = canyon.latitude ?: return@forEach
                    val longitude = canyon.longitude ?: return@forEach
                    add(MapDisplayMarker.Canyon(canyon, latitude, longitude))
                }
            } else {
                val cellSize = clusterCellSize(zoom)
                canyons.filter { it.latitude != null && it.longitude != null }
                    .groupBy { canyon ->
                        val latitude = canyon.latitude!!
                        val longitude = canyon.longitude!!
                        floor(latitude / cellSize) to floor(longitude / cellSize)
                    }
                    .values
                    .forEach { group ->
                        if (group.size == 1) {
                            val canyon = group.first()
                            add(MapDisplayMarker.Canyon(canyon, canyon.latitude!!, canyon.longitude!!))
                        } else {
                            add(
                                MapDisplayMarker.Cluster(
                                    canyonIds = group.map { it.id },
                                    count = group.size,
                                    latitude = group.map { it.latitude!! }.average(),
                                    longitude = group.map { it.longitude!! }.average(),
                                )
                            )
                        }
                    }
            }

            if (userLatitude != null && userLongitude != null) {
                add(MapDisplayMarker.User(userLatitude, userLongitude))
            }
        }

        return visibleMarkers
    }

    private fun clusterCellSize(zoom: Double): Double {
        return when {
            zoom >= 9.0 -> 0.12
            zoom >= 8.0 -> 0.22
            zoom >= 7.0 -> 0.45
            else -> 0.95
        }
    }
}

fun createOfflineBounds(
    latitude: Double,
    longitude: Double,
    radiusKm: Double,
): LatLngBounds {
    val latitudeDelta = radiusKm / 111.0
    val longitudeDelta = radiusKm / (111.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.1))

    return LatLngBounds.Builder()
        .include(LatLng(latitude + latitudeDelta, longitude - longitudeDelta))
        .include(LatLng(latitude - latitudeDelta, longitude + longitudeDelta))
        .build()
}
