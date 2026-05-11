package fr.descentecanyon.app.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.components.SelectedCanyonSheetContent
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.location.requestLocationSettings
import fr.descentecanyon.app.ui.theme.CanyonBlue
import fr.descentecanyon.app.ui.theme.CanyonBlueDark
import fr.descentecanyon.app.ui.theme.RockBrownLight
import org.maplibre.android.geometry.LatLngBounds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var visibleBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    val visibleCanyons = remember(uiState.mapCanyons, visibleBounds) {
        visibleBounds?.let { bounds ->
            uiState.mapCanyons.filter { canyon -> canyon.isWithin(bounds) }
        } ?: uiState.mapCanyons
    }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            focusAroundUserFromDevice(context, viewModel)
        } else {
            viewModel.onLocationUnavailable()
        }
    }

    fun focusAroundUserWithSettingsCheck() {
        requestLocationSettings(
            context = context,
            onEnabled = { focusAroundUserFromDevice(context, viewModel) },
            onResolutionRequired = { request -> locationSettingsLauncher.launch(request) },
            onUnavailable = viewModel::onLocationUnavailable,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            focusAroundUserWithSettingsCheck()
        }
    }

    LaunchedEffect(Unit) {
        PerformanceTrace.logEvent("map_screen_visible")
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
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
            SelectedCanyonSheetContent(
                canyon = canyon,
                onOpen = {
                    viewModel.clearSelectedCanyon()
                    onCanyonClick(canyon.id)
                },
                onClose = viewModel::clearSelectedCanyon,
            )
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

        MapHeroCard(
            onAroundMeClick = {
                if (context.hasLocationPermission()) {
                    viewModel.onLocationPermissionResult(true)
                    focusAroundUserWithSettingsCheck()
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            MapLibreView(
                markers = uiState.mapCanyons,
                userLatitude = uiState.userLatitude,
                userLongitude = uiState.userLongitude,
                onMarkerClick = viewModel::selectCanyon,
                onVisibleBoundsChanged = { bounds -> visibleBounds = bounds },
                onCameraChanged = viewModel::onCameraChanged,
                persistedCameraState = uiState.cameraState,
                focusLocationRequestId = uiState.focusLocationRequestId,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )
        }

        if (!uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_visible_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.map_visible_description, visibleCanyons.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                visibleCanyons.isEmpty() -> {
                    EmptyVisibleCard(modifier = Modifier.fillMaxWidth())
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = visibleCanyons,
                            key = { canyon -> canyon.id },
                        ) { canyon ->
                            NearbyCanyonCard(
                                canyon = canyon,
                                onClick = { onCanyonClick(canyon.id) },
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapHeroCard(
    onAroundMeClick: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Button(onClick = onAroundMeClick) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(R.string.map_focus_action))
                }
            }
        }
    }
}

@Composable
private fun EmptyVisibleCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.map_visible_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.map_visible_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun focusAroundUserFromDevice(
    context: Context,
    viewModel: MapViewModel,
) {
    loadCurrentDeviceLocation(
        context = context,
        onLocation = { latitude, longitude -> viewModel.focusAroundUser(latitude, longitude) },
        onUnavailable = viewModel::onLocationUnavailable,
    )
}

private fun CanyonSummary.isWithin(bounds: LatLngBounds): Boolean {
    val lat = latitude ?: return false
    val lon = longitude ?: return false
    val latInRange = lat in bounds.latitudeSouth..bounds.latitudeNorth
    val lonInRange = if (bounds.longitudeWest <= bounds.longitudeEast) {
        lon in bounds.longitudeWest..bounds.longitudeEast
    } else {
        lon >= bounds.longitudeWest || lon <= bounds.longitudeEast
    }
    return latInRange && lonInRange
}
