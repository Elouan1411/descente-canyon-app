package fr.descentecanyon.app.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos

const val MAP_STYLE_URI = "asset://map/opentopomap_style.json"
const val MAP_SEARCH_STYLE_URI = "asset://map/osm_light_style.json"
const val MAP_OFFLINE_RADIUS_KM = 3.0

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
