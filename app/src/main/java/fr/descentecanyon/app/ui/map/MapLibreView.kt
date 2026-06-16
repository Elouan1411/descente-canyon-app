@file:Suppress("DEPRECATION")

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
import com.google.gson.JsonObject
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.map.MAP_STYLE_URI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val TAG = "MapLibreView"

private const val WATERSHED_SOURCE_ID = "watershed-source"
private const val WATERSHED_FILL_LAYER_ID = "watershed-fill-layer"
private const val WATERSHED_LINE_LAYER_ID = "watershed-line-layer"

private const val CANYON_SOURCE_ID = "canyon-source"
private const val CANYON_CLUSTER_LAYER_ID = "canyon-cluster-layer"
private const val CANYON_CLUSTER_COUNT_LAYER_ID = "canyon-cluster-count-layer"
private const val CANYON_VISIBLE_SOURCE_ID = "canyon-visible-source"
private const val CANYON_VISIBLE_POINT_LAYER_ID = "canyon-visible-point-layer"
private const val CANYON_POINT_LAYER_ID = "canyon-point-layer"
private const val USER_SOURCE_ID = "user-source"
private const val USER_HALO_LAYER_ID = "user-halo-layer"
private const val USER_POINT_LAYER_ID = "user-point-layer"

private const val PROPERTY_CANYON_ID = "canyonId"
private const val PROPERTY_CANYON_NAME = "name"
private const val PROPERTY_INTEREST_COLOR = "interestColor"
private const val PROPERTY_POINT_COUNT = "point_count"
private const val PROPERTY_POINT_COUNT_ABBREVIATED = "point_count_abbreviated"

private const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"

@ColorInt private const val WATERSHED_FILL_COLOR = 0x331A6B8A
@ColorInt private const val WATERSHED_LINE_COLOR = 0xFF1A6B8A.toInt()
@ColorInt private const val CANYON_POINT_STROKE = 0xFFF2F6F7.toInt()
@ColorInt private const val FORBIDDEN_CANYON_COLOR = 0xFF9F2133.toInt()
@ColorInt private const val INTEREST_UNKNOWN_COLOR = 0xFFE7F3F5.toInt()
@ColorInt private const val INTEREST_0_TO_1_COLOR = 0xFFE08A3D.toInt()
@ColorInt private const val INTEREST_1_TO_2_COLOR = 0xFFFFC857.toInt()
@ColorInt private const val INTEREST_2_TO_3_COLOR = 0xFF3DAA68.toInt()
@ColorInt private const val INTEREST_3_TO_4_COLOR = 0xFF0077E6.toInt()
@ColorInt private const val CLUSTER_SMALL_COLOR = 0xFF0B5D75.toInt()
@ColorInt private const val CLUSTER_MEDIUM_COLOR = 0xFF1F7A5C.toInt()
@ColorInt private const val CLUSTER_LARGE_COLOR = 0xFFB76022.toInt()
@ColorInt private const val CLUSTER_STROKE_COLOR = 0xFFF2F6F7.toInt()
@ColorInt private const val CLUSTER_TEXT_COLOR = 0xFFFFFFFF.toInt()
@ColorInt private const val CLUSTER_TEXT_HALO_COLOR = 0xCC071118.toInt()
@ColorInt private const val USER_HALO_COLOR = 0x663CC7D9
@ColorInt private const val USER_POINT_COLOR = 0xFF55D6EA.toInt()
@ColorInt private const val USER_STROKE_COLOR = 0xFFFFFFFF.toInt()

private const val CLUSTER_RADIUS = 88
private const val CLUSTER_MAX_ZOOM = 12
private const val CLUSTER_TAP_FALLBACK_ZOOM_DELTA = 2.0
private const val DETAIL_POINT_ZOOM_THRESHOLD = 8.4
private const val DETAIL_POINT_VISIBLE_COUNT_THRESHOLD = 12
private const val USER_LOCATION_FOCUS_ZOOM = 9.6

private val EMPTY_FEATURE_COLLECTION = FeatureCollection.fromFeatures(emptyList<Feature>())

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    userLatitude: Double?,
    userLongitude: Double?,
    onMarkerClick: (Int) -> Unit,
    onVisibleBoundsChanged: (LatLngBounds) -> Unit = {},
    onCameraChanged: (MapCameraState) -> Unit = {},
    clusterMarkers: Boolean = true,
    watershedGeometryJson: String? = null,
    watershedBounds: LatLngBounds? = null,
    showWatershed: Boolean = false,
    persistedCameraState: MapCameraState? = null,
    focusLocationRequestId: Int = 0,
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
                onVisibleBoundsChanged = onVisibleBoundsChanged,
                onCameraChanged = onCameraChanged,
                clusterMarkers = clusterMarkers,
                watershedGeometryJson = watershedGeometryJson,
                watershedBounds = watershedBounds,
                showWatershed = showWatershed,
                persistedCameraState = persistedCameraState,
                focusLocationRequestId = focusLocationRequestId,
                styleUri = styleUri,
            )
        },
    )
}

private class MapRenderState(
    private val context: android.content.Context,
) {
    private enum class RenderMode {
        VECTOR,
        ANNOTATION,
    }

    private var map: MapLibreMap? = null
    private var markers: List<CanyonSummary> = emptyList()
    private var userLatitude: Double? = null
    private var userLongitude: Double? = null
    private var onMarkerClick: (Int) -> Unit = {}
    private var onVisibleBoundsChanged: (LatLngBounds) -> Unit = {}
    private var onCameraChanged: (MapCameraState) -> Unit = {}
    private var clusterMarkers: Boolean = true
    private var watershedGeometryJson: String? = null
    private var watershedBounds: LatLngBounds? = null
    private var showWatershed: Boolean = false
    private var persistedCameraState: MapCameraState? = null
    private var focusLocationRequestId: Int = 0
    private var lastFocusedLocationRequestId: Int = 0
    private var listenersAttached = false
    private var lastRenderSignature: Int? = null
    private var lastFitDataSignature: Int? = null
    private var didFitCamera = false
    private var styleUri: String = MAP_STYLE_URI
    private var renderMode: RenderMode? = null

    fun bindMap(
        map: MapLibreMap,
        onMarkerClick: (Int) -> Unit,
        styleUri: String,
    ) {
        this.map = map
        this.onMarkerClick = onMarkerClick
        this.styleUri = styleUri
        map.setStyle(Style.Builder().fromUri(styleUri)) {
            renderMode = null
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
        onVisibleBoundsChanged: (LatLngBounds) -> Unit,
        onCameraChanged: (MapCameraState) -> Unit,
        clusterMarkers: Boolean,
        watershedGeometryJson: String?,
        watershedBounds: LatLngBounds?,
        showWatershed: Boolean,
        persistedCameraState: MapCameraState?,
        focusLocationRequestId: Int,
        styleUri: String,
    ) {
        this.markers = markers
        this.userLatitude = userLatitude
        this.userLongitude = userLongitude
        this.onMarkerClick = onMarkerClick
        this.onVisibleBoundsChanged = onVisibleBoundsChanged
        this.onCameraChanged = onCameraChanged
        this.clusterMarkers = clusterMarkers
        this.watershedGeometryJson = watershedGeometryJson
        this.watershedBounds = watershedBounds
        this.showWatershed = showWatershed
        this.persistedCameraState = persistedCameraState
        this.focusLocationRequestId = focusLocationRequestId

        if (this.styleUri != styleUri) {
            this.styleUri = styleUri
            lastRenderSignature = null
            didFitCamera = false
            map?.setStyle(Style.Builder().fromUri(styleUri)) {
                renderMode = null
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

        maybeFocusOnLocation()
    }

    private fun attachListeners(map: MapLibreMap) {
        map.addOnCameraIdleListener {
            if (clusterMarkers) {
                runCatching {
                    refreshViewportDerivedSources(map)
                }.onFailure { throwable ->
                    Log.e(TAG, "Unable to refresh clustered map after camera idle", throwable)
                }
            }
            dispatchVisibleBounds(map)
        }
        map.setOnMarkerClickListener { marker ->
            if (!clusterMarkers) {
                handleAnnotationTap(marker)
            }
            !clusterMarkers
        }
        map.addOnMapClickListener { latLng ->
            if (!clusterMarkers) {
                return@addOnMapClickListener false
            }
            handleClusteredTap(map, latLng)
        }
    }

    private fun handleAnnotationTap(marker: Marker) {
        val canyonId = marker.snippet.orEmpty()
            .removePrefix("canyon:")
            .toIntOrNull()
            ?: return
        onMarkerClick(canyonId)
    }

    private fun handleClusteredTap(
        map: MapLibreMap,
        latLng: LatLng,
    ): Boolean {
        val screenPoint = map.projection.toScreenLocation(latLng)
        val clusterFeature = map.queryRenderedFeatures(screenPoint, CANYON_CLUSTER_LAYER_ID).firstOrNull()
        if (clusterFeature != null) {
            val geometry = clusterFeature.geometry() as? Point ?: return true
            val expansionZoom = runCatching {
                map.style
                    ?.getSourceAs<GeoJsonSource>(CANYON_SOURCE_ID)
                    ?.getClusterExpansionZoom(clusterFeature)
                    ?.toDouble()
            }.getOrNull()
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(geometry.latitude(), geometry.longitude()),
                    expansionZoom ?: (map.cameraPosition.zoom + CLUSTER_TAP_FALLBACK_ZOOM_DELTA),
                ),
                350,
            )
            return true
        }

        val canyonFeature = map.queryRenderedFeatures(
            screenPoint,
            CANYON_VISIBLE_POINT_LAYER_ID,
            CANYON_POINT_LAYER_ID,
        ).firstOrNull()
        val canyonId = canyonFeature?.getNumberProperty(PROPERTY_CANYON_ID)?.toInt()
            ?: canyonFeature?.getStringProperty(PROPERTY_CANYON_ID)?.toIntOrNull()
            ?: return false
        onMarkerClick(canyonId)
        return true
    }

    private fun render(force: Boolean) {
        val map = map ?: return
        val style = map.style ?: return
        val signature = buildRenderSignature(
            markers = markers,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
            clusterMarkers = clusterMarkers,
            watershedGeometryJson = watershedGeometryJson,
            showWatershed = showWatershed,
        )
        if (!force && lastRenderSignature == signature) return
        lastRenderSignature = signature

        updateWatershed(style)

        if (clusterMarkers) {
            renderVector(style, map)
        } else {
            renderAnnotated(style, map)
        }

        if (!didFitCamera) {
            val restored = restoreCamera(map)
            if (!restored) {
                fitCamera(
                    map = map,
                    coordinates = canyonCoordinates(),
                    watershedBounds = watershedBounds.takeIf { showWatershed && !watershedGeometryJson.isNullOrBlank() },
                )
            }
            didFitCamera = true
        }

        dispatchVisibleBounds(map)
    }

    private fun renderVector(
        style: Style,
        map: MapLibreMap,
    ) {
        if (renderMode != RenderMode.VECTOR) {
            map.clear()
            renderMode = RenderMode.VECTOR
        }
        ensureVectorStyle(style)
        updateVisiblePointSource(style, map)
        style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)?.setGeoJson(buildUserFeatureCollection(userLatitude, userLongitude))
    }

    private fun refreshViewportDerivedSources(map: MapLibreMap) {
        val style = map.style ?: return
        if (renderMode != RenderMode.VECTOR) return
        updateVisiblePointSource(style, map)
    }

    private fun updateVisiblePointSource(
        style: Style,
        map: MapLibreMap,
    ) {
        val visibleMarkers = filterMarkersInViewport(markers, map)
        val detailMode = shouldShowDetailPoints(map.cameraPosition.zoom, visibleMarkers.size)
        style.getSourceAs<GeoJsonSource>(CANYON_SOURCE_ID)?.setGeoJson(
            if (detailMode) EMPTY_FEATURE_COLLECTION else buildCanyonFeatureCollection(markers)
        )
        style.getSourceAs<GeoJsonSource>(CANYON_VISIBLE_SOURCE_ID)?.setGeoJson(
            if (detailMode) {
                buildCanyonFeatureCollection(visibleMarkers)
            } else {
                EMPTY_FEATURE_COLLECTION
            }
        )
    }

    private fun renderAnnotated(
        style: Style,
        map: MapLibreMap,
    ) {
        clearClusteredSources(style)
        val iconFactory = IconFactory.getInstance(context)
        map.clear()
        renderMode = RenderMode.ANNOTATION
        buildAnnotationMarkers().forEach { marker ->
            map.addMarker(marker.toMarkerOptions(iconFactory, context))
        }
    }

    private fun fitCamera(
        map: MapLibreMap,
        coordinates: List<LatLng>,
        watershedBounds: LatLngBounds?,
    ) {
        if (coordinates.isEmpty() && watershedBounds == null) return

        if (coordinates.size == 1 && watershedBounds == null) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    coordinates.first(),
                    13.6,
                )
            )
            return
        }

        val bounds = LatLngBounds.Builder().apply {
            coordinates.forEach(::include)
            watershedBounds?.let {
                include(it.northEast)
                include(it.southWest)
            }
        }.build()

        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(96, 96, 96, 164))
        if (camera != null) {
            val adjustedZoom = coordinates.takeIf { it.isNotEmpty() }?.let(::preferredZoom)?.let { minOf(camera.zoom, it) }
                ?: camera.zoom
            val target = camera.target ?: coordinates.firstOrNull() ?: watershedBounds?.center ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    target,
                    adjustedZoom,
                )
            )
        } else {
            val fallbackTarget = coordinates.firstOrNull() ?: watershedBounds?.center ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    fallbackTarget,
                    coordinates.takeIf { it.isNotEmpty() }?.let(::preferredZoom) ?: 10.0,
                )
            )
        }
    }

    private fun preferredZoom(coordinates: List<LatLng>): Double {
        if (coordinates.size <= 1) return 14.5

        val latitudes = coordinates.map { it.latitude }
        val longitudes = coordinates.map { it.longitude }
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
    ): Int {
        var result = 17
        markers.sortedBy { it.id }.forEach { canyon ->
            result = 31 * result + canyon.id
            result = 31 * result + canyon.latitude.coordinateHash()
            result = 31 * result + canyon.longitude.coordinateHash()
        }
        result = 31 * result + showWatershed.hashCode()
        if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            result = 31 * result + watershedGeometryJson.hashCode()
            result = 31 * result + (watershedBounds?.toSignatureHash() ?: 0)
        }
        return result
    }

    private fun maybeFocusOnLocation() {
        val map = map ?: return
        val latitude = userLatitude ?: return
        val longitude = userLongitude ?: return
        if (focusLocationRequestId == 0 || focusLocationRequestId == lastFocusedLocationRequestId) return

        lastFocusedLocationRequestId = focusLocationRequestId
        didFitCamera = true
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), USER_LOCATION_FOCUS_ZOOM),
            500,
        )
    }

    private fun restoreCamera(map: MapLibreMap): Boolean {
        val cameraState = persistedCameraState ?: return false
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(cameraState.latitude, cameraState.longitude),
                cameraState.zoom,
            )
        )
        return true
    }

    private fun dispatchVisibleBounds(map: MapLibreMap) {
        runCatching {
            map.projection.visibleRegion.latLngBounds
        }.getOrNull()?.let(onVisibleBoundsChanged)

        map.cameraPosition.target?.let { target ->
            onCameraChanged(
                MapCameraState(
                    latitude = target.latitude,
                    longitude = target.longitude,
                    zoom = map.cameraPosition.zoom,
                )
            )
        }
    }

    private fun buildRenderSignature(
        markers: List<CanyonSummary>,
        userLatitude: Double?,
        userLongitude: Double?,
        clusterMarkers: Boolean,
        watershedGeometryJson: String?,
        showWatershed: Boolean,
    ): Int {
        var result = 17
        result = 31 * result + clusterMarkers.hashCode()
        markers.sortedBy { it.id }.forEach { canyon ->
            result = 31 * result + canyon.id
            result = 31 * result + canyon.latitude.coordinateHash()
            result = 31 * result + canyon.longitude.coordinateHash()
            result = 31 * result + (canyon.markerType?.ordinal ?: -1)
            result = 31 * result + (canyon.interet?.times(100)?.roundToInt() ?: -1)
        }
        result = 31 * result + userLatitude.coordinateHash()
        result = 31 * result + userLongitude.coordinateHash()
        result = 31 * result + showWatershed.hashCode()
        if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            result = 31 * result + watershedGeometryJson.hashCode()
        }
        return result
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

    private fun ensureVectorStyle(style: Style) {
        if (style.getSource(CANYON_SOURCE_ID) == null) {
            style.addSource(
                GeoJsonSource(
                    CANYON_SOURCE_ID,
                    EMPTY_FEATURE_COLLECTION,
                    GeoJsonOptions()
                        .withCluster(true)
                        .withClusterRadius(CLUSTER_RADIUS)
                        .withClusterMaxZoom(CLUSTER_MAX_ZOOM),
                )
            )
        }
        if (style.getSource(USER_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(USER_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        }
        if (style.getSource(CANYON_VISIBLE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(CANYON_VISIBLE_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        }
        if (style.getLayer(CANYON_CLUSTER_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(CANYON_CLUSTER_LAYER_ID, CANYON_SOURCE_ID)
                    .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                    .withProperties(
                        circleColor(
                            Expression.step(
                                Expression.get(PROPERTY_POINT_COUNT),
                                Expression.color(CLUSTER_SMALL_COLOR),
                                Expression.stop(20, Expression.color(CLUSTER_MEDIUM_COLOR)),
                                Expression.stop(100, Expression.color(CLUSTER_LARGE_COLOR)),
                            )
                        ),
                        circleRadius(
                            Expression.step(
                                Expression.get(PROPERTY_POINT_COUNT),
                                18,
                                Expression.stop(20, 24),
                                Expression.stop(100, 30),
                            )
                        ),
                        circleOpacity(0.94f),
                        circleStrokeColor(CLUSTER_STROKE_COLOR),
                        circleStrokeWidth(2.8f),
                        circleStrokeOpacity(0.98f),
                    )
            )
        }
        if (style.getLayer(CANYON_CLUSTER_COUNT_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(CANYON_CLUSTER_COUNT_LAYER_ID, CANYON_SOURCE_ID)
                    .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                    .withProperties(
                        textField(Expression.toString(Expression.get(PROPERTY_POINT_COUNT_ABBREVIATED))),
                        textFont(arrayOf("Open Sans Semibold")),
                        textSize(
                            Expression.step(
                                Expression.get(PROPERTY_POINT_COUNT),
                                12.5f,
                                Expression.stop(100, 13.5f),
                                Expression.stop(1000, 14.5f),
                            )
                        ),
                        textColor(CLUSTER_TEXT_COLOR),
                        textHaloColor(CLUSTER_TEXT_HALO_COLOR),
                        textHaloWidth(1.6f),
                        textAllowOverlap(false),
                        textIgnorePlacement(false),
                    )
            )
        }
        if (style.getLayer(CANYON_POINT_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(CANYON_POINT_LAYER_ID, CANYON_SOURCE_ID)
                    .withFilter(Expression.not(Expression.has(PROPERTY_POINT_COUNT)))
                    .withProperties(
                        circleColor(Expression.toColor(Expression.get(PROPERTY_INTEREST_COLOR))),
                        circleRadius(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 4.0),
                                Expression.stop(5, 5.2),
                                Expression.stop(8, 6.4),
                                Expression.stop(11, 8.0),
                            )
                        ),
                        circleOpacity(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 0.8),
                                Expression.stop(5, 0.86),
                                Expression.stop(8, 0.92),
                                Expression.stop(11, 0.96),
                            )
                        ),
                        circleStrokeColor(CANYON_POINT_STROKE),
                        circleStrokeWidth(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(2, 1.0),
                                Expression.stop(8, 1.4),
                                Expression.stop(11, 1.9),
                            )
                        ),
                        circleStrokeOpacity(0.95f),
                    )
            )
        }
        if (style.getLayer(CANYON_VISIBLE_POINT_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(CANYON_VISIBLE_POINT_LAYER_ID, CANYON_VISIBLE_SOURCE_ID)
                    .withProperties(
                        circleColor(Expression.toColor(Expression.get(PROPERTY_INTEREST_COLOR))),
                        circleRadius(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(8, 5.8),
                                Expression.stop(10, 7.0),
                                Expression.stop(12, 8.4),
                            )
                        ),
                        circleOpacity(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(8, 0.9),
                                Expression.stop(10, 0.95),
                                Expression.stop(12, 1.0),
                            )
                        ),
                        circleStrokeColor(CANYON_POINT_STROKE),
                        circleStrokeWidth(1.9f),
                        circleStrokeOpacity(0.98f),
                    )
            )
        }
        if (style.getLayer(USER_HALO_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(USER_HALO_LAYER_ID, USER_SOURCE_ID).withProperties(
                    circleColor(USER_HALO_COLOR),
                    circleRadius(18f),
                    circleOpacity(0.8f),
                )
            )
        }
        if (style.getLayer(USER_POINT_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(USER_POINT_LAYER_ID, USER_SOURCE_ID).withProperties(
                    circleColor(USER_POINT_COLOR),
                    circleRadius(7f),
                    circleOpacity(1.0f),
                    circleStrokeColor(USER_STROKE_COLOR),
                    circleStrokeWidth(2.4f),
                    circleStrokeOpacity(1.0f),
                )
            )
        }
    }

    private fun clearClusteredSources(style: Style) {
        style.getSourceAs<GeoJsonSource>(CANYON_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        style.getSourceAs<GeoJsonSource>(CANYON_VISIBLE_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    private fun buildCanyonFeatureCollection(markers: List<CanyonSummary>): FeatureCollection {
        val features = distributedCanyonPositions(markers).map { positioned ->
            val canyon = positioned.canyon
            Feature.fromGeometry(
                Point.fromLngLat(positioned.longitude, positioned.latitude),
                JsonObject().apply {
                    addProperty(PROPERTY_CANYON_ID, canyon.id)
                    addProperty(PROPERTY_CANYON_NAME, canyon.nom)
                    addProperty(PROPERTY_INTEREST_COLOR, colorIntToMapColor(interestMarkerColor(canyon)))
                },
            )
        }
        return FeatureCollection.fromFeatures(features)
    }

    private fun filterMarkersInViewport(
        markers: List<CanyonSummary>,
        map: MapLibreMap,
    ): List<CanyonSummary> {
        val bounds = visibleBounds(map) ?: return markers
        return markers.filter { canyon ->
            val latitude = canyon.latitude ?: return@filter false
            val longitude = canyon.longitude ?: return@filter false
            bounds.contains(latitude, longitude)
        }
    }

    private fun visibleBounds(map: MapLibreMap): LatLngBounds? {
        return runCatching {
            map.projection.visibleRegion.latLngBounds
        }.getOrNull()
    }

    private fun buildUserFeatureCollection(
        latitude: Double?,
        longitude: Double?,
    ): FeatureCollection {
        if (latitude == null || longitude == null) return EMPTY_FEATURE_COLLECTION
        return FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(longitude, latitude))
        )
    }

    private fun canyonCoordinates(): List<LatLng> {
        return markers.mapNotNull { canyon ->
            val latitude = canyon.latitude ?: return@mapNotNull null
            val longitude = canyon.longitude ?: return@mapNotNull null
            LatLng(latitude, longitude)
        }
    }

    private fun buildAnnotationMarkers(): List<AnnotationMarker> {
        return buildList {
            distributedCanyonPositions(markers).forEach { positioned ->
                add(AnnotationMarker.Canyon(positioned.canyon, positioned.latitude, positioned.longitude))
            }
            val currentUserLatitude = userLatitude
            val currentUserLongitude = userLongitude
            if (currentUserLatitude != null && currentUserLongitude != null) {
                add(AnnotationMarker.User(currentUserLatitude, currentUserLongitude))
            }
        }
    }

    private fun toFeatureGeoJson(geometryJson: String): String {
        return "{\"type\":\"Feature\",\"properties\":{},\"geometry\":$geometryJson}"
    }
}

private sealed interface AnnotationMarker {
    val latitude: Double
    val longitude: Double

    data class Canyon(
        val canyon: CanyonSummary,
        override val latitude: Double,
        override val longitude: Double,
    ) : AnnotationMarker

    data class User(
        override val latitude: Double,
        override val longitude: Double,
    ) : AnnotationMarker
}

private data class PositionedCanyon(
    val canyon: CanyonSummary,
    val latitude: Double,
    val longitude: Double,
)

private fun distributedCanyonPositions(markers: List<CanyonSummary>): List<PositionedCanyon> {
    val indexed = markers.mapIndexedNotNull { index, canyon ->
        val latitude = canyon.latitude ?: return@mapIndexedNotNull null
        val longitude = canyon.longitude ?: return@mapIndexedNotNull null
        Triple(index, canyon, Pair(latitude, longitude))
    }
    return indexed
        .groupBy { (_, _, coords) -> coords.first.roundForGroup() to coords.second.roundForGroup() }
        .values
        .flatMap { group ->
            if (group.size == 1) {
                val (_, canyon, coords) = group.first()
                listOf(PositionedCanyon(canyon, coords.first, coords.second))
            } else {
                val ordered = group.sortedBy { (_, canyon, _) -> canyon.id }
                val radius = 0.00018 + ((ordered.size - 2).coerceAtLeast(0) * 0.00003)
                ordered.mapIndexed { idx, (_, canyon, coords) ->
                    val angle = (2.0 * Math.PI * idx) / ordered.size
                    PositionedCanyon(
                        canyon = canyon,
                        latitude = coords.first + sin(angle) * radius,
                        longitude = coords.second + cos(angle) * radius,
                    )
                }
            }
        }
}

private fun Double.roundForGroup(): Int = (this * 10_000).roundToInt()

private fun LatLngBounds.toSignatureHash(): Int {
    var result = 17
    result = 31 * result + latitudeNorth.coordinateHash()
    result = 31 * result + longitudeEast.coordinateHash()
    result = 31 * result + latitudeSouth.coordinateHash()
    result = 31 * result + longitudeWest.coordinateHash()
    return result
}

private fun LatLngBounds.contains(latitude: Double, longitude: Double): Boolean {
    val latMatches = latitude in latitudeSouth..latitudeNorth
    if (!latMatches) return false
    return if (longitudeWest <= longitudeEast) {
        longitude in longitudeWest..longitudeEast
    } else {
        longitude >= longitudeWest || longitude <= longitudeEast
    }
}

private fun Double?.coordinateHash(): Int {
    return this?.times(1_000_000)?.roundToInt() ?: 0
}

internal fun shouldShowDetailPoints(
    zoom: Double,
    visibleMarkerCount: Int,
): Boolean {
    return zoom >= DETAIL_POINT_ZOOM_THRESHOLD ||
        visibleMarkerCount in 1..DETAIL_POINT_VISIBLE_COUNT_THRESHOLD
}

@ColorInt
internal fun interestMarkerColor(canyon: CanyonSummary): Int {
    if (canyon.isForbidden) return FORBIDDEN_CANYON_COLOR
    val interest = canyon.interet
    return when {
        interest == null || interest <= 0f -> INTEREST_UNKNOWN_COLOR
        interest <= 1f -> INTEREST_0_TO_1_COLOR
        interest <= 2f -> INTEREST_1_TO_2_COLOR
        interest <= 3f -> INTEREST_2_TO_3_COLOR
        else -> INTEREST_3_TO_4_COLOR
    }
}

private fun colorIntToMapColor(@ColorInt color: Int): String {
    return "#%06X".format(color and 0x00FFFFFF)
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

private fun drawableToBitmap(
    context: android.content.Context,
    resId: Int,
): Bitmap {
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

private fun AnnotationMarker.toMarkerBitmap(context: android.content.Context): Bitmap {
    return when (this) {
        is AnnotationMarker.Canyon -> drawableToBitmap(context, canyon.markerIconRes())
        is AnnotationMarker.User -> createMarkerBitmap(context.getString(R.string.map_user_marker_label), 0xFF111827.toInt())
    }
}

private fun MarkerOptions.applyMarkerBitmap(
    iconFactory: IconFactory,
    marker: AnnotationMarker,
    context: android.content.Context,
): MarkerOptions {
    return runCatching {
        icon(iconFactory.fromBitmap(marker.toMarkerBitmap(context)))
    }.onFailure { throwable ->
        Log.e(TAG, "Unable to load marker icon bitmap", throwable)
    }.getOrDefault(this)
}

private fun AnnotationMarker.toMarkerOptions(iconFactory: IconFactory, context: android.content.Context): MarkerOptions {
    return when (this) {
        is AnnotationMarker.Canyon -> MarkerOptions()
            .position(LatLng(latitude, longitude))
            .title(canyon.nom)
            .snippet("canyon:${canyon.id}")
            .applyMarkerBitmap(iconFactory, this, context)

        is AnnotationMarker.User -> MarkerOptions()
            .position(LatLng(latitude, longitude))
            .title(context.getString(R.string.map_user_position_title))
            .snippet("user")
            .applyMarkerBitmap(iconFactory, this, context)
    }
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
