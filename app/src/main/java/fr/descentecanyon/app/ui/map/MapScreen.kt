package fr.descentecanyon.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SnackbarHost(hostState = snackbarHostState)
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            MapHeroCard(
                canyonCount = uiState.canyons.size,
                hasLocation = uiState.userLatitude != null && uiState.userLongitude != null,
            )
        }

        item {
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
                LocationCard(
                    latitude = uiState.userLatitude,
                    longitude = uiState.userLongitude,
                    onRefresh = { loadNearbyFromDevice(context, viewModel) },
                )
            }
        }

        if (uiState.canyons.isNotEmpty()) {
            item {
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
            }
        }

        when {
            uiState.isLoading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            uiState.error != null -> {
                item {
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
            }

            uiState.canyons.isEmpty() && uiState.hasLocationPermission -> {
                item {
                    EmptyNearbyCard()
                }
            }

            else -> {
                item {
                    Text(
                        text = stringResource(R.string.map_nearby_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(
                    items = uiState.canyons,
                    key = { canyon -> canyon.id },
                ) { canyon ->
                    NearbyCanyonCard(
                        canyon = canyon,
                        onClick = { onCanyonClick(canyon.id) },
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MapHeroCard(
    canyonCount: Int,
    hasLocation: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(CanyonBlueDark, CanyonBlue, RockBrownLight),
                    )
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.tab_map),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = if (hasLocation) {
                        stringResource(R.string.map_ready_with_location, canyonCount)
                    } else {
                        stringResource(R.string.map_ready_without_location)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.92f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(canyonCount.coerceIn(1, 4)) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == 0) 14.dp else 10.dp)
                                .background(Color.White.copy(alpha = 0.9f), CircleShape),
                        )
                    }
                }
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
private fun LocationCard(
    latitude: Double?,
    longitude: Double?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.map_location_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (latitude != null && longitude != null) {
                        stringResource(R.string.map_location_coordinates, latitude, longitude)
                    } else {
                        stringResource(R.string.map_location_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
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
        canyon.latitude?.let { latitude ->
            canyon.longitude?.let { longitude ->
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.map_marker_coordinates, latitude, longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun loadNearbyFromDevice(
    context: Context,
    viewModel: MapViewModel,
) {
    val location = context.bestLastKnownLocation() ?: run {
        viewModel.onLocationUnavailable()
        return
    }
    viewModel.loadNearby(location.latitude, location.longitude)
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun Context.bestLastKnownLocation(): Location? {
    if (!hasLocationPermission()) return null

    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }

    return providers.mapNotNull { provider ->
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.accuracy.takeIf { accuracy -> accuracy > 0f }?.let { accuracy -> -accuracy } ?: Float.MIN_VALUE }
}
