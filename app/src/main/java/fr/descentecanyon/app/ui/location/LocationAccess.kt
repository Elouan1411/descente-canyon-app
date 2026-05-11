package fr.descentecanyon.app.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

fun requestLocationSettings(
    context: Context,
    onEnabled: () -> Unit,
    onResolutionRequired: (IntentSenderRequest) -> Unit,
    onUnavailable: () -> Unit,
) {
    val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_SETTINGS_INTERVAL_MS,
    ).build()
    val settingsRequest = LocationSettingsRequest.Builder()
        .addLocationRequest(locationRequest)
        .setAlwaysShow(true)
        .build()

    LocationServices.getSettingsClient(context)
        .checkLocationSettings(settingsRequest)
        .addOnSuccessListener { onEnabled() }
        .addOnFailureListener { throwable ->
            val resolvable = throwable as? ResolvableApiException
            if (resolvable != null) {
                onResolutionRequired(
                    IntentSenderRequest.Builder(resolvable.resolution.intentSender).build()
                )
            } else {
                onUnavailable()
            }
        }
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

    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellationTokenSource = CancellationTokenSource()
    val handler = Handler(Looper.getMainLooper())
    var completed = false
    var timeout: Runnable? = null

    fun finish(location: Location?) {
        if (completed) return
        completed = true
        timeout?.let(handler::removeCallbacks)
        cancellationTokenSource.cancel()
        if (location != null && location.isFreshEnough()) {
            onLocation(location.latitude, location.longitude)
        } else {
            onUnavailable()
        }
    }

    val timeoutRunnable = Runnable { finish(null) }
    timeout = timeoutRunnable
    handler.postDelayed(timeoutRunnable, CURRENT_LOCATION_TIMEOUT_MS + CURRENT_LOCATION_TIMEOUT_GRACE_MS)

    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
        .setMaxUpdateAgeMillis(LOCATION_MAX_UPDATE_AGE_MS)
        .build()

    client.getCurrentLocation(request, cancellationTokenSource.token)
        .addOnSuccessListener { location -> finish(location) }
        .addOnFailureListener { finish(null) }
        .addOnCanceledListener { finish(null) }
}

private fun Location.isFreshEnough(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
    return ageMs in 0..LOCATION_MAX_UPDATE_AGE_MS
}

private const val LOCATION_SETTINGS_INTERVAL_MS = 10_000L
private const val CURRENT_LOCATION_TIMEOUT_MS = 15_000L
private const val CURRENT_LOCATION_TIMEOUT_GRACE_MS = 1_000L
private const val LOCATION_MAX_UPDATE_AGE_MS = 30_000L
