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
import fr.descentecanyon.app.domain.model.CanyonSummary
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

private const val MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json"

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    onMarkerClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(Bundle())
        }
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
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                map.setStyle(MAP_STYLE_URL) {
                    map.clear()
                    markers.forEach { canyon ->
                        val latitude = canyon.latitude ?: return@forEach
                        val longitude = canyon.longitude ?: return@forEach
                        map.addMarker(
                            MarkerOptions()
                                .position(LatLng(latitude, longitude))
                                .title(canyon.nom)
                                .snippet(canyon.id.toString())
                        )
                    }

                    map.setOnMarkerClickListener { marker ->
                        marker.snippet?.toIntOrNull()?.let(onMarkerClick)
                        true
                    }

                    val firstMarker = markers.firstOrNull { it.latitude != null && it.longitude != null }
                    if (firstMarker != null) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(firstMarker.latitude!!, firstMarker.longitude!!))
                            .zoom(if (markers.size == 1) 11.5 else 8.5)
                            .build()
                    }
                }
            }
        },
    )
}
