package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.components.CotationBadge
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonDetailScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    onReportDebitClick: () -> Unit,
    onShowMapClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanyonDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearTransientMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.canyonDetail?.canyon?.nom ?: "Canyon #$canyonId",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onReportDebitClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.debit_form_title),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!uiState.isDownloading && uiState.canyonDetail?.canyon?.isOffline != true) {
                                viewModel.downloadForOffline()
                            }
                        },
                    ) {
                        if (uiState.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.canyonDetail?.canyon?.isOffline == true) {
                                    Icons.Default.CloudDone
                                } else {
                                    Icons.Default.CloudDownload
                                },
                                contentDescription = if (uiState.canyonDetail?.canyon?.isOffline == true) {
                                    stringResource(R.string.offline_available)
                                } else {
                                    stringResource(R.string.download_for_offline)
                                },
                                tint = if (uiState.canyonDetail?.canyon?.isOffline == true) {
                                    fr.descentecanyon.app.ui.theme.CotationFacile
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            imageVector = if (uiState.isFavorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = if (uiState.isFavorite) {
                                stringResource(R.string.remove_favorite)
                            } else {
                                stringResource(R.string.add_favorite)
                            },
                            tint = if (uiState.isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        floatingActionButton = {
            Column(
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(
                    onClick = onShowMapClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = stringResource(R.string.show_map_points),
                    )
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = uiState.error ?: stringResource(R.string.error_generic),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadCanyon(canyonId) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            uiState.canyonDetail != null -> {
                CanyonDetailContent(
                    detail = uiState.canyonDetail!!,
                    isOnline = uiState.isOnline,
                    downloadingPhotoIds = uiState.downloadingPhotoIds,
                    onDownloadPhoto = viewModel::downloadPhoto,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanyonDetailContent(
    detail: CanyonDetail,
    isOnline: Boolean,
    downloadingPhotoIds: Set<Long>,
    onDownloadPhoto: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val topoListState = rememberLazyListState()
    val photosListState = rememberLazyListState()
    val debitsListState = rememberLazyListState()
    val tabs = listOf(
        stringResource(R.string.tab_topo),
        stringResource(R.string.tab_photos),
        stringResource(R.string.tab_debits),
    )

    Column(modifier = modifier.fillMaxSize()) {
        Column {
            SummaryCard(detail = detail)
        }

        // Tabs
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        // Tab content
        when (selectedTab) {
            0 -> TopoTab(detail = detail, listState = topoListState)
            1 -> PhotosTab(
                photos = detail.photos,
                isOnline = isOnline,
                isOfflineSaved = detail.canyon.isOffline,
                downloadingPhotoIds = downloadingPhotoIds,
                onDownloadPhoto = onDownloadPhoto,
                listState = photosListState,
            )
            2 -> DebitsTab(debits = detail.debits, listState = debitsListState)
        }
    }
}

@Composable
private fun SummaryCard(
    detail: CanyonDetail,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon
    var expanded by rememberSaveable(detail.canyon.id) { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CotationBadge(cotation = canyon.cotation, large = true)
                Spacer(modifier = Modifier.width(12.dp))
                canyon.interet?.let { interest ->
                    InterestStars(interest = interest)
                }
            }
            Text(
                text = "${canyon.commune} - ${canyon.pays}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.canyon_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) stringResource(R.string.canyon_summary_collapse) else stringResource(R.string.canyon_summary_expand),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                SummaryStatsGrid(detail = detail, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryStatsGrid(
    detail: CanyonDetail,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon

    val parcoursStats = listOf(
        SummaryStat(stringResource(R.string.altitude), canyon.altitudeDepart?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.elevation), canyon.denivele?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.length), canyon.longueur?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.max_waterfall), canyon.cascadeMax?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.rope), canyon.cordeMin?.let { "${it}m" }),
    ).filter { !it.value.isNullOrBlank() }

    val timeStats = listOf(
        SummaryStat(stringResource(R.string.approach_time), canyon.tempsApproche),
        SummaryStat(stringResource(R.string.descent_time), canyon.tempsDescente),
        SummaryStat(stringResource(R.string.return_time), canyon.tempsRetour),
    ).filter { !it.value.isNullOrBlank() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (parcoursStats.isNotEmpty()) {
            SummarySection(
                title = stringResource(R.string.canyon_summary_parcours),
                stats = parcoursStats,
            )
        }
        if (timeStats.isNotEmpty()) {
            SummarySection(
                title = stringResource(R.string.canyon_summary_timing),
                stats = timeStats,
            )
        }
    }
}

private data class SummaryStat(
    val label: String,
    val value: String?,
)

@Composable
private fun SummarySection(
    title: String,
    stats: List<SummaryStat>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                stats.forEachIndexed { index, stat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stat.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stat.value ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (index != stats.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopoTab(
    detail: CanyonDetail,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        detail.accesAval?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.access_downstream),
                    content = text,
                )
            }
        }
        detail.accesAmont?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.access_upstream),
                    content = text,
                )
            }
        }
        detail.approche?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.approach),
                    content = text,
                )
            }
        }
        detail.descente?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.descent),
                    content = text,
                )
            }
        }
        detail.retour?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.return_path),
                    content = text,
                )
            }
        }
        detail.engagement?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.engagement),
                    content = text,
                )
            }
        }
        detail.periode?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.period),
                    content = text,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(132.dp)) // FAB clearance
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotosTab(
    photos: List<CanyonPhoto>,
    isOnline: Boolean,
    isOfflineSaved: Boolean,
    downloadingPhotoIds: Set<Long>,
    onDownloadPhoto: (Long) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var selectedPhoto by rememberSaveable { mutableStateOf<CanyonPhoto?>(null) }

    selectedPhoto?.let { photo ->
        Dialog(onDismissRequest = { selectedPhoto = null }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = photo.localPath ?: photo.url,
                    contentDescription = photo.description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    if (photos.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    !isOnline && isOfflineSaved -> stringResource(R.string.no_offline_photos_without_network)
                    else -> stringResource(R.string.no_photos)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = photos,
                key = { it.id.takeIf { id -> id != 0L } ?: it.url.hashCode().toLong() },
            ) { photo ->
                PhotoCard(
                    photo = photo,
                    isDownloading = downloadingPhotoIds.contains(photo.id),
                    onDownload = { onDownloadPhoto(photo.id) },
                    onOpen = { selectedPhoto = photo },
                )
            }
            item {
                Spacer(modifier = Modifier.height(132.dp))
            }
        }
    }
}

@Composable
private fun PhotoCard(
    photo: CanyonPhoto,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            AsyncImage(
                model = photo.localPath ?: photo.thumbnailUrl ?: photo.url,
                contentDescription = photo.description,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (photo.localPath != null) {
                        SmallMetaBadge(text = stringResource(R.string.photo_saved_offline))
                    }
                    if (photo.localPath == null) {
                        TextButton(
                            onClick = onDownload,
                            enabled = photo.id != 0L && !isDownloading,
                        ) {
                            Text(
                                text = if (isDownloading) {
                                    stringResource(R.string.downloading_offline)
                                } else {
                                    stringResource(R.string.photo_download_action)
                                }
                            )
                        }
                    }
                }
                photo.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                photo.auteur?.takeIf { it.isNotBlank() }?.let { auteur ->
                    Text(
                        text = auteur,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DebitsTab(
    debits: List<Debit>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (debits.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.no_debits),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = debits,
                key = { it.id },
            ) { debit ->
                DebitListItem(debit = debit)
            }
            item {
                Spacer(modifier = Modifier.height(132.dp)) // FAB clearance
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanyonGeoPointsSheet(
    detail: CanyonDetail,
    onDismiss: () -> Unit,
    onNavigate: (GeoPoint) -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.show_map_points),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = detail.canyon.nom,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenFullscreen) {
                    Text(text = stringResource(R.string.fullscreen_map))
                }
            }

            CanyonGeoPointsMapAndList(
                detail = detail,
                onNavigate = onNavigate,
                mapHeight = 260.dp,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsMapAndList(
    detail: CanyonDetail,
    onNavigate: (GeoPoint) -> Unit,
    mapHeight: androidx.compose.ui.unit.Dp,
) {
    val markers = remember(detail.geoPoints) {
        detail.geoPoints.mapIndexed { index, point ->
            fr.descentecanyon.app.domain.model.CanyonSummary(
                id = detail.canyon.id * 10 + index,
                nom = point.displayName(),
                pays = detail.canyon.pays,
                cotation = detail.canyon.cotation,
                url = detail.canyon.url,
                latitude = point.latitude,
                longitude = point.longitude,
                markerType = point.type,
            )
        }
    }

    if (markers.isNotEmpty()) {
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
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = detail.geoPoints.sortedBy { it.type.navigationPriority() },
            key = { it.id.takeIf { id -> id != 0L } ?: (it.latitude.toString() + it.longitude.toString()) },
        ) { point ->
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(point.type.mapColor(), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = point.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = point.type.mapColor(),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.map_location_coordinates,
                                point.latitude,
                                point.longitude,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onNavigate(point) }) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.navigate))
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsFullScreenDialog(
    detail: CanyonDetail,
    onDismiss: () -> Unit,
    onNavigate: (GeoPoint) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = CardDefaults.shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.show_map_points),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = detail.canyon.nom,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.back))
                    }
                }
                CanyonGeoPointsMapAndList(
                    detail = detail,
                    onNavigate = onNavigate,
                    mapHeight = 360.dp,
                )
            }
        }
    }
}

private fun GeoPoint.displayName(): String {
    return when (type) {
        GeoPointType.PARKING_AMONT -> label ?: "Parking amont"
        GeoPointType.PARKING_AVAL -> label ?: "Parking aval"
        GeoPointType.ENTREE -> label ?: "Debut du canyon"
        GeoPointType.SORTIE -> label ?: "Sortie du canyon"
        GeoPointType.POINT_REMARQUABLE -> label ?: "Point remarquable"
        GeoPointType.ECHAPPATOIRE -> label ?: "Echappatoire"
        GeoPointType.UNKNOWN -> label ?: "Point GPS"
    }
}

private fun GeoPointType.navigationPriority(): Int {
    return when (this) {
        GeoPointType.PARKING_AMONT -> 0
        GeoPointType.PARKING_AVAL -> 1
        GeoPointType.ENTREE -> 2
        GeoPointType.SORTIE -> 3
        GeoPointType.POINT_REMARQUABLE -> 4
        GeoPointType.ECHAPPATOIRE -> 5
        GeoPointType.UNKNOWN -> 6
    }
}

private fun GeoPointType.mapColor() = when (this) {
    GeoPointType.PARKING_AMONT -> fr.descentecanyon.app.ui.theme.CanyonBlue
    GeoPointType.PARKING_AVAL -> androidx.compose.ui.graphics.Color(0xFF7C3AED)
    GeoPointType.ENTREE -> fr.descentecanyon.app.ui.theme.CotationFacile
    GeoPointType.SORTIE -> fr.descentecanyon.app.ui.theme.CotationDifficile
    GeoPointType.POINT_REMARQUABLE -> fr.descentecanyon.app.ui.theme.RockBrownLight
    GeoPointType.ECHAPPATOIRE -> fr.descentecanyon.app.ui.theme.CanyonBlueDark
    GeoPointType.UNKNOWN -> fr.descentecanyon.app.ui.theme.DebitInconnu
}

private fun openNavigation(
    context: android.content.Context,
    point: GeoPoint,
) {
    val label = Uri.encode(point.label ?: "Canyon")
    val uri = Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

@Composable
private fun DebitListItem(
    debit: Debit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(debit.id) { mutableStateOf(false) }
    val bgColor = when (debit.niveau) {
        NiveauDebit.SEC -> DebitSec
        NiveauDebit.FILET -> DebitFilet
        NiveauDebit.CORRECT -> DebitCorrect
        NiveauDebit.GROS -> DebitGros
        NiveauDebit.TRES_GROS -> DebitTresGros
        NiveauDebit.CRUE -> DebitCrue
        NiveauDebit.INCONNU -> DebitInconnu
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = bgColor.copy(alpha = 0.1f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = debit.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    debit.isDescended?.let { isDescended ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isDescended) stringResource(R.string.debit_type_descended) else stringResource(R.string.debit_type_not_descended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                debit.auteur?.let { auteur ->
                    Text(
                        text = auteur,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                            Text(
                                text = comment,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            debit.waterTemperature?.let {
                                Text(
                                    text = stringResource(R.string.debit_water_temperature_short, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            debit.airTemperature?.let {
                                Text(
                                    text = stringResource(R.string.debit_air_temperature_short, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (!expanded) {
                    debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                        Text(
                            text = comment,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DebitBadge(niveau = debit.niveau)
                debit.waterTemperature?.let {
                    SmallMetaBadge(text = it)
                }
            }
        }
    }
}

@Composable
private fun SmallMetaBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
