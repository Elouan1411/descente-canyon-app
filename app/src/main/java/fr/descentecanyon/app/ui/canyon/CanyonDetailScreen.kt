package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
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
import coil3.request.ImageRequest
import coil3.request.allowHardware
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.ui.components.CotationBadge
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonDetailScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    onReportDebitClick: () -> Unit,
    onShowMapClick: () -> Unit,
    onOpenPhotoGallery: (Long) -> Unit,
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
            CompactAppBar(
                title = uiState.canyonDetail?.canyon?.nom ?: "Canyon #$canyonId",
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onReportDebitClick,
                        modifier = Modifier.testTag(TestTags.detailReportDebitButton),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.debit_form_title),
                        )
                    }
                    IconButton(
                        onClick = viewModel::toggleFavorite,
                        modifier = Modifier.testTag(TestTags.detailFavoriteButton),
                    ) {
                        Icon(
                            imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (uiState.isFavorite) {
                                stringResource(R.string.remove_favorite)
                            } else {
                                stringResource(R.string.add_favorite)
                            },
                            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(
                    onClick = onShowMapClick,
                    modifier = Modifier.size(68.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = stringResource(R.string.show_map_points),
                        modifier = Modifier.size(30.dp),
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
                    isLoadingPhotos = uiState.isLoadingPhotos,
                    isLoadingDebits = uiState.isLoadingDebits,
                    weather = uiState.weather,
                    isLoadingWeather = uiState.isLoadingWeather,
                    weatherError = uiState.weatherError,
                    downloadingPhotoIds = uiState.downloadingPhotoIds,
                    onOpenPhotoGallery = onOpenPhotoGallery,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CanyonDetailContent(
    detail: CanyonDetail,
    isOnline: Boolean,
    isLoadingPhotos: Boolean,
    isLoadingDebits: Boolean,
    weather: fr.descentecanyon.app.domain.model.CanyonWeather?,
    isLoadingWeather: Boolean,
    weatherError: String?,
    downloadingPhotoIds: Set<Long>,
    onOpenPhotoGallery: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val tabs = listOf(
        stringResource(R.string.tab_topo),
        if (isLoadingPhotos && detail.photos.isEmpty()) {
            stringResource(R.string.tab_photos_loading)
        } else {
            stringResource(R.string.tab_photos_with_count, detail.photos.size)
        },
        if (isLoadingDebits && detail.debits.isEmpty()) {
            stringResource(R.string.tab_debits_loading)
        } else {
            stringResource(R.string.tab_debits_with_count, detail.debits.size)
        },
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { SummaryCard(detail = detail) }
        item {
            CanyonWeatherCard(
                weather = weather,
                isLoading = isLoadingWeather,
                error = weatherError,
            )
        }

        stickyHeader {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> topoItems(detail)
            1 -> photosItems(
                photos = detail.photos,
                isOnline = isOnline,
                isLoadingPhotos = isLoadingPhotos,
                isOfflineSaved = detail.canyon.isOffline,
                downloadingPhotoIds = downloadingPhotoIds,
                onOpenPhotoGallery = onOpenPhotoGallery,
            )
            2 -> debitItems(detail.debits, isLoadingDebits)
        }
    }
}

@Composable
private fun SummaryCard(
    detail: CanyonDetail,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon

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
            SummaryStatsGrid(detail = detail, modifier = Modifier.padding(top = 12.dp))
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

    val locationStats = listOf(
        SummaryStat(
            stringResource(R.string.communes),
            canyon.communes.takeIf { it.isNotEmpty() }?.joinToString(),
        ),
        SummaryStat(stringResource(R.string.region), canyon.region),
        SummaryStat(stringResource(R.string.department), canyon.departement),
        SummaryStat(stringResource(R.string.massif), canyon.massif),
        SummaryStat(stringResource(R.string.basin), canyon.bassin),
        SummaryStat(
            stringResource(R.string.watershed_area),
            detail.watershed?.areaKm2?.let(::formatAreaKm2),
        ),
        SummaryStat(stringResource(R.string.watercourse), canyon.coursEau),
    ).filter { !it.value.isNullOrBlank() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (locationStats.isNotEmpty()) {
            SummarySection(
                title = stringResource(R.string.location),
                stats = locationStats,
            )
        }
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

private fun formatAreaKm2(areaKm2: Double): String {
    val precision = if (areaKm2 >= 100.0) "%.0f" else if (areaKm2 >= 10.0) "%.1f" else "%.2f"
    return String.format(Locale.getDefault(), "$precision km2", areaKm2)
}

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

private fun LazyListScope.topoItems(detail: CanyonDetail) {
        val sectionModifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        detail.accesAval?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.access_downstream),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.accesAmont?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.access_upstream),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.approche?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.approach),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.descente?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.descent),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.retour?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.return_path),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.engagement?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.engagement),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.periode?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.period),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.geologie?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.geology),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.historique?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.history),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.remarques?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.notes),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        if (detail.bibliography.isNotEmpty()) {
            item {
                BibliographySection(
                    entries = detail.bibliography,
                    modifier = sectionModifier,
                )
            }
        }
        if (detail.regulations.isNotEmpty()) {
            item {
                RegulationSection(
                    regulations = detail.regulations,
                    modifier = sectionModifier,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(132.dp)) // FAB clearance
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
private fun BibliographySection(
    entries: List<BibliographyEntry>,
    modifier: Modifier = Modifier,
) {
    val topoguides = entries.filter { it.kind == BibliographyKind.TOPOGUIDE }
    val maps = entries.filter { it.kind == BibliographyKind.MAP }
    val resources = entries.filter { it.kind == BibliographyKind.RESOURCE }

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
                    text = stringResource(R.string.bibliography),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (topoguides.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.topoguides_with_count, topoguides.size), entries = topoguides)
                    }
                    if (maps.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.maps_with_count, maps.size), entries = maps)
                    }
                    if (resources.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.resources_with_count, resources.size), entries = resources)
                    }
                }
            }
        }
    }
}

@Composable
private fun BibliographyGroup(
    title: String,
    entries: List<BibliographyEntry>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        entries.forEach { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                        Text(
                            text = stringResource(R.string.bibliography_authors, authors.joinToString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    bibliographyMetaLine(entry)?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.status?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.url?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Language,
                            label = url,
                            url = url,
                        )
                    }
                    entry.detailUrl?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Description,
                            label = stringResource(R.string.open_reference),
                            url = url,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegulationSection(
    regulations: List<Regulation>,
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
                    text = stringResource(R.string.regulations_with_count, regulations.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    regulations.forEach { regulation ->
                        RegulationItem(regulation = regulation)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegulationItem(
    regulation: Regulation,
    modifier: Modifier = Modifier,
) {
    val status = regulation.status.orEmpty()
    val isInactive = status.contains("obsol", ignoreCase = true) ||
        status.contains("abrog", ignoreCase = true)

    val containerColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect.copy(alpha = 0.12f)
        status.contains("temp", ignoreCase = true) -> DebitTresGros.copy(alpha = 0.12f)
        isInactive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val accentColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect
        status.contains("temp", ignoreCase = true) -> DebitTresGros
        isInactive -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    var expanded by rememberSaveable(regulation.id) { mutableStateOf(!isInactive) }
    val statusText = regulation.status?.let { stringResource(R.string.regulation_status, it) }
    val actionText = regulation.action?.let { stringResource(R.string.regulation_action, it) }
    val effectiveDateText = regulation.effectiveDate?.let {
        stringResource(R.string.regulation_effective_date, it)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = regulation.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    RegulationStatusBadge(text = regulation.status ?: stringResource(R.string.regulation_status_unknown), accentColor)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = accentColor,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val body = buildString {
                        appendLineIfNotBlank(statusText)
                        appendLineIfNotBlank(actionText)
                        appendLineIfNotBlank(effectiveDateText)
                        appendLineIfNotBlank(regulation.summary)
                        if (!isInactive) {
                            appendLineIfNotBlank(regulation.remark)
                            appendLineIfNotBlank(regulation.details)
                        }
                    }.trim()
                    if (body.isNotBlank()) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!isInactive) {
                        regulation.attachments.forEach { attachment ->
                            LinkRow(
                                icon = Icons.Default.Description,
                                label = attachment.label,
                                url = attachment.url,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    LinkRow(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.open_regulation_page),
                        url = regulation.textUrl,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RegulationStatusBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun bibliographyMetaLine(entry: BibliographyEntry): String? {
    val parts = buildList {
        entry.publicationYear?.let { add(it.toString()) }
        entry.editor?.let { add(it) }
        entry.reference?.let { add(it) }
        entry.scale?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

private fun StringBuilder.appendLineIfNotBlank(value: String?) {
    if (!value.isNullOrBlank()) {
        if (isNotEmpty()) {
            append("\n\n")
        }
        append(value.trim())
    }
}

private fun LazyListScope.photosItems(
    photos: List<CanyonPhoto>,
    isOnline: Boolean,
    isLoadingPhotos: Boolean,
    isOfflineSaved: Boolean,
    downloadingPhotoIds: Set<Long>,
    onOpenPhotoGallery: (Long) -> Unit,
) {
    if (isLoadingPhotos && photos.isEmpty()) {
        item {
            LoadingSectionItem(text = stringResource(R.string.loading_photos))
        }
    } else if (photos.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
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
        }
    } else {
        if (isLoadingPhotos) {
            item {
                InlineLoadingBanner(
                    text = stringResource(R.string.loading_photos),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(
            items = photos,
            key = { it.id.takeIf { id -> id != 0L } ?: it.url.hashCode().toLong() },
        ) { photo ->
            PhotoCard(
                photo = photo,
                isDownloading = downloadingPhotoIds.contains(photo.id),
                onOpen = {
                    onOpenPhotoGallery(photo.id)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun PhotoCard(
    photo: CanyonPhoto,
    isDownloading: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            ProgressivePhoto(
                photo = photo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Crop,
            )
            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun ProgressivePhoto(
    photo: CanyonPhoto,
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) {
    val context = LocalContext.current
    val previewRequest: ImageRequest = remember(context, photo.localPath, photo.thumbnailUrl, photo.url) {
        ImageRequest.Builder(context)
            .data(photo.localPath ?: photo.thumbnailUrl ?: photo.url)
            .allowHardware(false)
            .build()
    }
    val fullRequest: ImageRequest = remember(context, photo.url) {
        ImageRequest.Builder(context)
            .data(photo.url)
            .allowHardware(false)
            .build()
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        AsyncImage(
            model = previewRequest,
            contentDescription = photo.description,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
        if (photo.localPath == null) {
            AsyncImage(
                model = fullRequest,
                contentDescription = photo.description,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private fun LazyListScope.debitItems(
    debits: List<Debit>,
    isLoadingDebits: Boolean,
) {
    if (isLoadingDebits && debits.isEmpty()) {
        item {
            LoadingSectionItem(text = stringResource(R.string.loading_debits))
        }
    } else if (debits.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_debits),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        if (isLoadingDebits) {
            item {
                InlineLoadingBanner(
                    text = stringResource(R.string.loading_debits),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(
            items = debits,
            key = { it.id },
        ) { debit ->
            DebitListItem(
                debit = debit,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun LoadingSectionItem(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InlineLoadingBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                nom = point.navigationLabel(),
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
                        point.displaySubtitle()?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
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

private fun openNavigation(
    context: android.content.Context,
    point: GeoPoint,
) {
    val label = Uri.encode(point.navigationLabel())
    val uri = Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

