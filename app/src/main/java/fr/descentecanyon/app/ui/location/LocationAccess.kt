package fr.descentecanyon.app.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
fun loadCurrentDeviceLocation(
    context: Context,
    onLocation: (Double, Double) -> Unit,
    onUnavailable: () -> Unit,
) {
    if (!context.hasLocationPermission()) {
        onUnavailable()
        return
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (locationManager == null) {
        onUnavailable()
        return
    }

    bestLastKnownLocation(locationManager)?.let { cached ->
        onLocation(cached.latitude, cached.longitude)
        return
    }

    val provider = when {
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> {
            onUnavailable()
            return
        }
    }

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            locationManager.removeUpdates(this)
            onLocation(location.latitude, location.longitude)
        }

        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            locationManager.removeUpdates(this)
            onUnavailable()
        }
    }

    locationManager.requestLocationUpdates(
        provider,
        0L,
        0f,
        listener,
        Looper.getMainLooper(),
    )
}

private fun bestLastKnownLocation(locationManager: LocationManager): Location? {
    val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    @SuppressLint("MissingPermission")
    fun getLocation(provider: String): Location? =
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()

    return providers.mapNotNull { getLocation(it) }
        .maxByOrNull { it.accuracy.takeIf { accuracy -> accuracy > 0f }?.let { accuracy -> -accuracy } ?: Float.MIN_VALUE }
}
