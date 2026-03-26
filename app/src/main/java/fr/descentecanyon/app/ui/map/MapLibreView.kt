package fr.descentecanyon.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

private const val TAG = "MapLibreView"
private const val WATERSHED_SOURCE_ID = "watershed-source"
private const val WATERSHED_FILL_LAYER_ID = "watershed-fill-layer"
private const val WATERSHED_LINE_LAYER_ID = "watershed-line-layer"
private const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
@ColorInt private const val WATERSHED_FILL_COLOR = 0x331A6B8A
@ColorInt private const val WATERSHED_LINE_COLOR = 0xFF1A6B8A.toInt()

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    userLatitude: Double?,
    userLongitude: Double?,
    onMarkerClick: (Int) -> Unit,
    clusterMarkers: Boolean = true,
    watershedGeometryJson: String? = null,
    watershedBounds: LatLngBounds? = null,
    showWatershed: Boolean = false,
    styleUri: String = MAP_STYLE_URI,
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
                Lifecycle.Event.ON_DESTROY -> Unit
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
                    renderState.bindMap(map, onMarkerClick, styleUri)
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
                watershedGeometryJson = watershedGeometryJson,
                watershedBounds = watershedBounds,
                showWatershed = showWatershed,
                styleUri = styleUri,
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
    private var watershedGeometryJson: String? = null
    private var watershedBounds: LatLngBounds? = null
    private var showWatershed: Boolean = false
    private var listenersAttached = false
    private var lastSignature: String? = null
    private var lastFitDataSignature: String? = null
    private var didFitCamera = false
    private var styleUri: String = MAP_STYLE_URI

    fun bindMap(
        map: MapLibreMap,
        onMarkerClick: (Int) -> Unit,
        styleUri: String,
    ) {
        this.map = map
        this.onMarkerClick = onMarkerClick
        this.styleUri = styleUri
        map.setStyle(Style.Builder().fromUri(styleUri)) {
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
        watershedGeometryJson: String?,
        watershedBounds: LatLngBounds?,
        showWatershed: Boolean,
        styleUri: String,
    ) {
        this.markers = markers
        this.userLatitude = userLatitude
        this.userLongitude = userLongitude
        this.onMarkerClick = onMarkerClick
        this.clusterMarkers = clusterMarkers
        this.watershedGeometryJson = watershedGeometryJson
        this.watershedBounds = watershedBounds
        this.showWatershed = showWatershed
        if (this.styleUri != styleUri) {
            this.styleUri = styleUri
            lastSignature = null
            didFitCamera = false
            map?.setStyle(Style.Builder().fromUri(styleUri)) {
                render(force = true)
            }
            return
        }

        val fitDataSignature = buildFitDataSignature(markers, watershedBounds, watershedGeometryJson, showWatershed)
        if (fitDataSignature != lastFitDataSignature) {
            lastFitDataSignature = fitDataSignature
            didFitCamera = false
        }
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
        val style = map.style ?: return

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
        val fitMarkers = buildList {
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
        val signature = buildSignature(
            displayMarkers = displayMarkers,
            zoom = map.cameraPosition.zoom,
            watershedGeometryJson = watershedGeometryJson,
            showWatershed = showWatershed,
        )
        if (!force && lastSignature == signature) return
        lastSignature = signature

        updateWatershed(style)

        val iconFactory = IconFactory.getInstance(context)
        map.clear()

        displayMarkers.forEach { marker ->
            map.addMarker(marker.toMarkerOptions(iconFactory))
        }

        if (!didFitCamera) {
            fitCamera(
                map = map,
                displayMarkers = fitMarkers.ifEmpty { displayMarkers },
                watershedBounds = watershedBounds.takeIf { showWatershed && !watershedGeometryJson.isNullOrBlank() },
            )
            didFitCamera = true
        }
    }

    private fun fitCamera(
        map: MapLibreMap,
        displayMarkers: List<MapDisplayMarker>,
        watershedBounds: LatLngBounds?,
    ) {
        val canyonMarkers = displayMarkers.filterNot { it is MapDisplayMarker.User }
        if (canyonMarkers.isEmpty() && watershedBounds == null) return

        val bounds = LatLngBounds.Builder().apply {
            canyonMarkers.forEach { marker ->
                include(LatLng(marker.latitude, marker.longitude))
            }
            watershedBounds?.let {
                include(it.northEast)
                include(it.southWest)
            }
        }.build()

        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(96, 96, 96, 164))
        if (camera != null) {
            val adjustedZoom = canyonMarkers.takeIf { it.isNotEmpty() }?.let { minOf(camera.zoom, preferredZoom(it)) } ?: camera.zoom
            val target = camera.target
                ?: canyonMarkers.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
                ?: watershedBounds?.center
                ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    target,
                    adjustedZoom,
                )
            )
        } else {
            val fallbackTarget = canyonMarkers.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
                ?: watershedBounds?.center
                ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    fallbackTarget,
                    canyonMarkers.takeIf { it.isNotEmpty() }?.let(::preferredZoom) ?: 10.0,
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
            maxSpan < 0.003 -> 14.3
            maxSpan < 0.008 -> 13.3
            maxSpan < 0.02 -> 12.2
            maxSpan < 0.05 -> 11.2
            maxSpan < 0.12 -> 10.2
            else -> 8.8
        }
    }

    private fun buildFitDataSignature(
        markers: List<CanyonSummary>,
        watershedBounds: LatLngBounds?,
        watershedGeometryJson: String?,
        showWatershed: Boolean,
    ): String {
        return buildString {
            markers.sortedBy { it.id }.forEach { canyon ->
                append('|')
                append(canyon.id)
                append(':')
                append(canyon.latitude?.toString().orEmpty())
                append(':')
                append(canyon.longitude?.toString().orEmpty())
            }
            append("|w:")
            append(showWatershed)
            if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
                append(':').append(watershedGeometryJson.hashCode())
                append(':').append(watershedBounds?.toSignature().orEmpty())
            }
        }
    }

    private fun buildSignature(
        displayMarkers: List<MapDisplayMarker>,
        zoom: Double,
        watershedGeometryJson: String?,
        showWatershed: Boolean,
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
            append("|w:")
            append(showWatershed)
            if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
                append(':').append(watershedGeometryJson.hashCode())
            }
        }
    }

    private fun updateWatershed(style: Style) {
        ensureWatershedStyle(style)
        val source = style.getSource(WATERSHED_SOURCE_ID) as? GeoJsonSource ?: return
        val geoJson = if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            toFeatureGeoJson(watershedGeometryJson!!)
        } else {
            EMPTY_GEOJSON
        }
        runCatching {
            source.setGeoJson(geoJson)
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to update watershed polygon", throwable)
            source.setGeoJson(EMPTY_GEOJSON)
        }
    }

    private fun ensureWatershedStyle(style: Style) {
        if (style.getSource(WATERSHED_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(WATERSHED_SOURCE_ID, EMPTY_GEOJSON))
        }
        if (style.getLayer(WATERSHED_FILL_LAYER_ID) == null) {
            style.addLayer(
                FillLayer(WATERSHED_FILL_LAYER_ID, WATERSHED_SOURCE_ID).withProperties(
                    fillAntialias(true),
                    fillColor(WATERSHED_FILL_COLOR),
                    fillOpacity(1.0f),
                )
            )
        }
        if (style.getLayer(WATERSHED_LINE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(WATERSHED_LINE_LAYER_ID, WATERSHED_SOURCE_ID).withProperties(
                    lineColor(WATERSHED_LINE_COLOR),
                    lineOpacity(0.9f),
                    lineWidth(2.2f),
                )
            )
        }
    }

    private fun toFeatureGeoJson(geometryJson: String): String {
        return "{\"type\":\"Feature\",\"properties\":{},\"geometry\":$geometryJson}"
    }

    private fun LatLngBounds.toSignature(): String {
        return listOf(
            latitudeNorth,
            longitudeEast,
            latitudeSouth,
            longitudeWest,
        ).joinToString(":")
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
                .applySafeIcon(drawableToBitmap(canyon.markerIconRes()))

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

    private fun CanyonSummary.markerIconRes(): Int {
        return when (markerType) {
            GeoPointType.PARKING_AMONT -> R.drawable.map_marker_parking_amont
            GeoPointType.PARKING_AVAL -> R.drawable.map_marker_parking_aval
            GeoPointType.ENTREE -> R.drawable.map_marker_entry
            GeoPointType.SORTIE -> R.drawable.map_marker_exit
            GeoPointType.POINT_REMARQUABLE -> R.drawable.map_marker_remarkable
            GeoPointType.ECHAPPATOIRE -> R.drawable.map_marker_escape
            GeoPointType.UNKNOWN, null -> R.drawable.map_marker_point
        }
    }

    private fun drawableToBitmap(resId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)
            ?: return createMarkerBitmap("?", 0xFF1A6B8A.toInt())
        val width = drawable.intrinsicWidth.coerceAtLeast(88)
        val height = drawable.intrinsicHeight.coerceAtLeast(88)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun createMarkerBitmap(label: String, colorInt: Int): Bitmap {
        val width = 132
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val rect = Rect(8, 8, width - 8, height - 8)
        canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 22f, 22f, fillPaint)
        canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 22f, 22f, strokePaint)
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label.take(4), width / 2f, y, textPaint)
        return bitmap
    }
}
