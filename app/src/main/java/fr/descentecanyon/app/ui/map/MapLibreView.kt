package fr.descentecanyon.app.ui.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.map.MAP_CLUSTER_ZOOM_THRESHOLD
import fr.descentecanyon.app.map.MAP_STYLE_URI
import fr.descentecanyon.app.map.MapClusterEngine
import fr.descentecanyon.app.map.MapDisplayMarker
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    userLatitude: Double?,
    userLongitude: Double?,
    onMarkerClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderState = remember { MapRenderState(context) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    renderState.bindMap(map, onMarkerClick)
                }
            }
        },
        modifier = modifier,
        update = {
            renderState.updateData(
                markers = markers,
                userLatitude = userLatitude,
                userLongitude = userLongitude,
                onMarkerClick = onMarkerClick,
            )
        },
    )
}

private class MapRenderState(
    private val context: android.content.Context,
) {
    private var map: MapLibreMap? = null
    private var markers: List<CanyonSummary> = emptyList()
    private var userLatitude: Double? = null
    private var userLongitude: Double? = null
    private var onMarkerClick: (Int) -> Unit = {}
    private var listenersAttached = false
    private var lastSignature: String? = null
    private var didFitCamera = false

    fun bindMap(
        map: MapLibreMap,
        onMarkerClick: (Int) -> Unit,
    ) {
        this.map = map
        this.onMarkerClick = onMarkerClick
        map.setStyle(Style.Builder().fromUri(MAP_STYLE_URI)) {
            if (!listenersAttached) {
                attachListeners(map)
                listenersAttached = true
            }
            render(force = true)
        }
    }

    fun updateData(
        markers: List<CanyonSummary>,
        userLatitude: Double?,
        userLongitude: Double?,
        onMarkerClick: (Int) -> Unit,
    ) {
        this.markers = markers
        this.userLatitude = userLatitude
        this.userLongitude = userLongitude
        this.onMarkerClick = onMarkerClick
        render(force = false)
    }

    private fun attachListeners(map: MapLibreMap) {
        map.addOnCameraIdleListener {
            render(force = true)
        }
        map.setOnMarkerClickListener { marker ->
            handleMarkerTap(map, marker)
            true
        }
    }

    private fun handleMarkerTap(
        map: MapLibreMap,
        marker: Marker,
    ) {
        val snippet = marker.snippet.orEmpty()
        when {
            snippet.startsWith("canyon:") -> snippet.removePrefix("canyon:").toIntOrNull()?.let(onMarkerClick)
            snippet.startsWith("cluster:") -> {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        marker.position,
                        maxOf(map.cameraPosition.zoom + 2.0, MAP_CLUSTER_ZOOM_THRESHOLD + 0.5),
                    ),
                    500,
                )
            }
        }
    }

    private fun render(force: Boolean) {
        val map = map ?: return
        if (map.style == null) return

        val displayMarkers = MapClusterEngine.cluster(
            canyons = markers,
            zoom = map.cameraPosition.zoom,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
        )
        val signature = buildSignature(displayMarkers, map.cameraPosition.zoom)
        if (!force && lastSignature == signature) return
        lastSignature = signature

        val iconFactory = IconFactory.getInstance(context)
        map.clear()

        displayMarkers.forEach { marker ->
            map.addMarker(marker.toMarkerOptions(iconFactory))
        }

        if (!didFitCamera) {
            fitCamera(map, displayMarkers)
            didFitCamera = true
        }
    }

    private fun fitCamera(
        map: MapLibreMap,
        displayMarkers: List<MapDisplayMarker>,
    ) {
        if (displayMarkers.isEmpty()) return

        val bounds = LatLngBounds.Builder().apply {
            displayMarkers.forEach { marker ->
                include(LatLng(marker.latitude, marker.longitude))
            }
        }.build()

        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(80, 80, 80, 80))
        if (camera != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        } else {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(displayMarkers.first().latitude, displayMarkers.first().longitude),
                    if (displayMarkers.size == 1) 11.0 else 8.5,
                )
            )
        }
    }

    private fun buildSignature(
        displayMarkers: List<MapDisplayMarker>,
        zoom: Double,
    ): String {
        return buildString {
            append(zoom.toInt())
            displayMarkers.forEach { marker ->
                append('|')
                when (marker) {
                    is MapDisplayMarker.Canyon -> append("c").append(marker.canyon.id)
                    is MapDisplayMarker.Cluster -> append("k").append(marker.count).append(':').append(marker.canyonIds.joinToString(","))
                    is MapDisplayMarker.User -> append("u")
                }
            }
        }
    }

    private fun MapDisplayMarker.toMarkerOptions(iconFactory: IconFactory): MarkerOptions {
        return when (this) {
            is MapDisplayMarker.Canyon -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(canyon.nom)
                .snippet("canyon:${canyon.id}")
                .icon(iconFactory.fromResource(canyon.markerType.markerIconRes()))

            is MapDisplayMarker.Cluster -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title("$count canyons")
                .snippet("cluster:${canyonIds.joinToString(",")}")
                .icon(iconFactory.fromResource(R.drawable.map_marker_cluster))

            is MapDisplayMarker.User -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title("Votre position")
                .snippet("user")
                .icon(iconFactory.fromResource(R.drawable.map_marker_user))
        }
    }

    private fun GeoPointType?.markerIconRes(): Int {
        return when (this) {
            GeoPointType.PARKING_AMONT,
            GeoPointType.PARKING_AVAL -> R.drawable.map_marker_parking

            GeoPointType.ENTREE -> R.drawable.map_marker_entry
            GeoPointType.SORTIE -> R.drawable.map_marker_exit
            else -> R.drawable.map_marker_parking
        }
    }
}
