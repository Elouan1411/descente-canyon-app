package fr.descentecanyon.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.map.MAP_OFFLINE_RADIUS_KM
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.theme.CanyonBlue
import fr.descentecanyon.app.ui.theme.CanyonBlueDark
import fr.descentecanyon.app.ui.theme.RockBrownLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            loadNearbyFromDevice(context, viewModel)
        }
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
            loadNearbyFromDevice(context, viewModel)
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessage()
        }
    }

    uiState.selectedCanyon?.let { canyon ->
        ModalBottomSheet(
            onDismissRequest = viewModel::clearSelectedCanyon,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = canyon.nom,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.map_bottom_sheet_meta,
                        canyon.cotation,
                        canyon.pays,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { onCanyonClick(canyon.id) }) {
                        Text(text = stringResource(R.string.map_bottom_sheet_open))
                    }
                    Button(
                        onClick = viewModel::downloadSelectedRegion,
                        enabled = !uiState.isDownloadingOfflineRegion,
                    ) {
                        Text(
                            text = if (uiState.isDownloadingOfflineRegion) {
                                stringResource(R.string.map_bottom_sheet_downloading)
                            } else {
                                stringResource(R.string.map_bottom_sheet_download, MAP_OFFLINE_RADIUS_KM.toInt())
                            }
                        )
                    }
                    TextButton(onClick = viewModel::clearSelectedCanyon) {
                        Text(text = stringResource(R.string.back))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SnackbarHost(hostState = snackbarHostState)
        Spacer(modifier = Modifier.height(8.dp))

        if (!uiState.hasLocationPermission) {
            PermissionCard(
                showRationale = uiState.hasRequestedLocationPermission,
                onRequestPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
            )
        } else {
            NearbyStatusCard(
                canyonCount = uiState.canyons.size,
                hasLocation = uiState.userLatitude != null && uiState.userLongitude != null,
                onRefresh = { loadNearbyFromDevice(context, viewModel) },
            )
        }

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.error != null -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = uiState.error.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            uiState.canyons.isEmpty() && uiState.hasLocationPermission -> {
                EmptyNearbyCard()
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    MapLibreView(
                        markers = uiState.canyons,
                        userLatitude = uiState.userLatitude,
                        userLongitude = uiState.userLongitude,
                        onMarkerClick = viewModel::selectCanyon,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.map_nearby_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxSize(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = uiState.canyons,
                            key = { canyon -> canyon.id },
                        ) { canyon ->
                            NearbyCanyonCard(
                                canyon = canyon,
                                onClick = { onCanyonClick(canyon.id) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyStatusCard(
    canyonCount: Int,
    hasLocation: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.map_nearby_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = if (hasLocation) {
                        stringResource(R.string.map_ready_with_location, canyonCount)
                    } else {
                        stringResource(R.string.map_ready_without_location)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(onClick = onRefresh) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    showRationale: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (showRationale) {
                    stringResource(R.string.map_permission_rationale)
                } else {
                    stringResource(R.string.map_permission_description)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestPermission) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.map_permission_action))
            }
        }
    }
}

@Composable
private fun EmptyNearbyCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.map_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.map_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NearbyCanyonCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        CanyonSummaryCard(
            canyon = canyon,
            onClick = onClick,
        )
    }
}

@SuppressLint("MissingPermission")
private fun loadNearbyFromDevice(
    context: Context,
    viewModel: MapViewModel,
) {
    if (!context.hasLocationPermission()) {
        viewModel.onLocationUnavailable()
        return
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (locationManager == null) {
        viewModel.onLocationUnavailable()
        return
    }

    // Try cached location first
    val cached = bestLastKnownLocation(locationManager)
    if (cached != null) {
        viewModel.loadNearby(cached.latitude, cached.longitude)
        return
    }

    // No cached location: request a fresh one
    val provider = when {
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> {
            viewModel.onLocationUnavailable()
            return
        }
    }

    locationManager.requestSingleUpdate(
        provider,
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                viewModel.loadNearby(location.latitude, location.longitude)
            }

            @Deprecated("Deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                viewModel.onLocationUnavailable()
            }
        },
        Looper.getMainLooper(),
    )
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
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
        .maxByOrNull { it.accuracy.takeIf { a -> a > 0f }?.let { a -> -a } ?: Float.MIN_VALUE }
}
