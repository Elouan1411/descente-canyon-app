package fr.descentecanyon.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
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

private const val TAG = "MapLibreView"

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    userLatitude: Double?,
    userLongitude: Double?,
    onMarkerClick: (Int) -> Unit,
    clusterMarkers: Boolean = true,
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
                clusterMarkers = clusterMarkers,
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
    private var clusterMarkers: Boolean = true
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
            runCatching {
                render(force = true)
            }.onFailure { throwable ->
                Log.e(TAG, "Unable to render map after style load", throwable)
            }
        }
    }

    fun updateData(
        markers: List<CanyonSummary>,
        userLatitude: Double?,
        userLongitude: Double?,
        onMarkerClick: (Int) -> Unit,
        clusterMarkers: Boolean,
    ) {
        this.markers = markers
        this.userLatitude = userLatitude
        this.userLongitude = userLongitude
        this.onMarkerClick = onMarkerClick
        this.clusterMarkers = clusterMarkers
        runCatching {
            render(force = false)
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to refresh map data", throwable)
        }
    }

    private fun attachListeners(map: MapLibreMap) {
        map.addOnCameraIdleListener {
            runCatching {
                render(force = true)
            }.onFailure { throwable ->
                Log.e(TAG, "Unable to update map after camera move", throwable)
            }
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

        val displayMarkers = if (clusterMarkers) {
            MapClusterEngine.cluster(
                canyons = markers,
                zoom = map.cameraPosition.zoom,
                userLatitude = userLatitude,
                userLongitude = userLongitude,
            )
        } else {
            buildList {
                val currentUserLatitude = userLatitude
                val currentUserLongitude = userLongitude
                markers.forEach { canyon ->
                    val latitude = canyon.latitude ?: return@forEach
                    val longitude = canyon.longitude ?: return@forEach
                    add(MapDisplayMarker.Canyon(canyon, latitude, longitude))
                }
                if (currentUserLatitude != null && currentUserLongitude != null) {
                    add(MapDisplayMarker.User(currentUserLatitude, currentUserLongitude))
                }
            }
        }
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
            val adjustedZoom = maxOf(camera.zoom, preferredZoom(displayMarkers))
            val target = camera.target ?: LatLng(displayMarkers.first().latitude, displayMarkers.first().longitude)
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    target,
                    adjustedZoom,
                )
            )
        } else {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(displayMarkers.first().latitude, displayMarkers.first().longitude),
                    preferredZoom(displayMarkers),
                )
            )
        }
    }

    private fun preferredZoom(displayMarkers: List<MapDisplayMarker>): Double {
        if (displayMarkers.size <= 1) return 14.5

        val latitudes = displayMarkers.map { it.latitude }
        val longitudes = displayMarkers.map { it.longitude }
        val maxSpan = maxOf(
            (latitudes.maxOrNull() ?: 0.0) - (latitudes.minOrNull() ?: 0.0),
            (longitudes.maxOrNull() ?: 0.0) - (longitudes.minOrNull() ?: 0.0),
        )

        return when {
            maxSpan < 0.003 -> 15.0
            maxSpan < 0.008 -> 14.0
            maxSpan < 0.02 -> 13.0
            maxSpan < 0.05 -> 12.0
            maxSpan < 0.12 -> 11.0
            else -> 9.5
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
        fun MarkerOptions.applySafeIcon(bitmap: Bitmap): MarkerOptions {
            return runCatching {
                icon(iconFactory.fromBitmap(bitmap))
            }.onFailure { throwable ->
                Log.e(TAG, "Unable to load marker icon bitmap", throwable)
            }.getOrDefault(this)
        }

        return when (this) {
            is MapDisplayMarker.Canyon -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title(canyon.nom)
                .snippet("canyon:${canyon.id}")
                .applySafeIcon(createMarkerBitmap(canyon.markerLabel(), canyon.markerColor()))

            is MapDisplayMarker.Cluster -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title("$count canyons")
                .snippet("cluster:${canyonIds.joinToString(",")}")
                .applySafeIcon(createMarkerBitmap(count.toString(), 0xFF8B6914.toInt()))

            is MapDisplayMarker.User -> MarkerOptions()
                .position(LatLng(latitude, longitude))
                .title("Votre position")
                .snippet("user")
                .applySafeIcon(createMarkerBitmap("Moi", 0xFF111827.toInt()))
        }
    }

    private fun CanyonSummary.markerLabel(): String {
        return when (markerType) {
            GeoPointType.PARKING_AMONT -> "P A"
            GeoPointType.PARKING_AVAL -> "P V"
            GeoPointType.ENTREE -> "Ent"
            GeoPointType.SORTIE -> "Sor"
            GeoPointType.POINT_REMARQUABLE -> "Pt"
            GeoPointType.ECHAPPATOIRE -> "Ech"
            GeoPointType.UNKNOWN, null -> nom.take(3).ifBlank { "Can" }
        }
    }

    private fun CanyonSummary.markerColor(): Int {
        return when (markerType) {
            GeoPointType.PARKING_AMONT -> 0xFF1A6B8A.toInt()
            GeoPointType.PARKING_AVAL -> 0xFF7C3AED.toInt()
            GeoPointType.ENTREE -> 0xFF4CAF50.toInt()
            GeoPointType.SORTIE -> 0xFFF44336.toInt()
            GeoPointType.POINT_REMARQUABLE -> 0xFFB8922E.toInt()
            GeoPointType.ECHAPPATOIRE -> 0xFF0D4F6A.toInt()
            GeoPointType.UNKNOWN, null -> 0xFF1A6B8A.toInt()
        }
    }

    private fun createMarkerBitmap(label: String, colorInt: Int): Bitmap {
        val width = 124
        val height = 56
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val rect = Rect(8, 8, width - 8, height - 8)
        canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 20f, 20f, fillPaint)
        canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 20f, 20f, strokePaint)
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label.take(4), width / 2f, y, textPaint)
        return bitmap
    }
}
