package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.map.MapLibreView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonPointsMapScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanyonDetailViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompactAppBar(
                title = uiState.canyonDetail?.canyon?.nom ?: "Canyon #$canyonId",
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.canyonDetail == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.canyonDetail != null -> CanyonPointsMapContent(
                detail = uiState.canyonDetail,
                modifier = Modifier.padding(innerPadding),
                onNavigate = { point ->
                    val label = Uri.encode(point.label ?: "Canyon")
                    val uri = Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
            )

            else -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text(uiState.error ?: "Erreur") }
        }
    }
}

@Composable
private fun CanyonPointsMapContent(
    detail: CanyonDetail,
    onNavigate: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val markers = remember(detail.geoPoints) {
        detail.geoPoints.mapIndexed { index, point ->
            fr.descentecanyon.app.domain.model.CanyonSummary(
                id = detail.canyon.id * 10 + index,
                nom = pointDisplayName(point),
                pays = detail.canyon.pays,
                cotation = detail.canyon.cotation,
                url = detail.canyon.url,
                latitude = point.latitude,
                longitude = point.longitude,
                markerType = point.type,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        val mapHeight = 360.dp
        val listHeight = (maxHeight - mapHeight - 12.dp).coerceAtLeast(180.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                MapLibreView(
                    markers = markers,
                    userLatitude = null,
                    userLongitude = null,
                    onMarkerClick = {},
                    clusterMarkers = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight),
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        detail.geoPoints.sortedBy { pointPriority(it.type) },
                        key = { it.id.takeIf { id -> id != 0L } ?: (it.latitude.toString() + it.longitude.toString()) },
                    ) { point ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(12.dp).background(pointColor(point.type), CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            pointDisplayName(point),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = pointColor(point.type),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.map_location_coordinates, point.latitude, point.longitude),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onNavigate(point) }) {
                                    Icon(Icons.Default.Navigation, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.navigate))
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

private fun pointDisplayName(point: GeoPoint): String = when (point.type) {
    GeoPointType.PARKING_AMONT -> point.label ?: "Parking amont"
    GeoPointType.PARKING_AVAL -> point.label ?: "Parking aval"
    GeoPointType.ENTREE -> point.label ?: "Debut du canyon"
    GeoPointType.SORTIE -> point.label ?: "Sortie du canyon"
    GeoPointType.POINT_REMARQUABLE -> point.label ?: "Point remarquable"
    GeoPointType.ECHAPPATOIRE -> point.label ?: "Echappatoire"
    GeoPointType.UNKNOWN -> point.label ?: "Point GPS"
}

private fun pointPriority(type: GeoPointType): Int = when (type) {
    GeoPointType.PARKING_AMONT -> 0
    GeoPointType.PARKING_AVAL -> 1
    GeoPointType.ENTREE -> 2
    GeoPointType.SORTIE -> 3
    GeoPointType.POINT_REMARQUABLE -> 4
    GeoPointType.ECHAPPATOIRE -> 5
    GeoPointType.UNKNOWN -> 6
}

private fun pointColor(type: GeoPointType): Color = when (type) {
    GeoPointType.PARKING_AMONT -> fr.descentecanyon.app.ui.theme.CanyonBlue
    GeoPointType.PARKING_AVAL -> Color(0xFF7C3AED)
    GeoPointType.ENTREE -> fr.descentecanyon.app.ui.theme.CotationFacile
    GeoPointType.SORTIE -> fr.descentecanyon.app.ui.theme.CotationDifficile
    GeoPointType.POINT_REMARQUABLE -> fr.descentecanyon.app.ui.theme.RockBrownLight
    GeoPointType.ECHAPPATOIRE -> fr.descentecanyon.app.ui.theme.CanyonBlueDark
    GeoPointType.UNKNOWN -> fr.descentecanyon.app.ui.theme.DebitInconnu
}
