package fr.descentecanyon.app.ui.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.IntRangeFilter
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.map.MAP_SEARCH_STYLE_URI
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showFiltersSheet by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var showCountryMenu by rememberSaveable { mutableStateOf(false) }
    var showDepartmentMenu by rememberSaveable { mutableStateOf(false) }
    var pendingDistanceSort by rememberSaveable { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            viewModel.onLocationLookupStarted()
            loadCurrentDeviceLocation(
                context = context,
                onLocation = { latitude, longitude ->
                    viewModel.onUserLocationUpdated(latitude, longitude)
                    if (pendingDistanceSort) {
                        viewModel.onSortSelected(SearchSortField.DISTANCE)
                        pendingDistanceSort = false
                    }
                },
                onUnavailable = {
                    viewModel.onLocationUnavailable()
                    pendingDistanceSort = false
                },
            )
        } else {
            pendingDistanceSort = false
        }
    }

    fun requestCurrentLocation(applyDistanceSortWhenReady: Boolean) {
        if (applyDistanceSortWhenReady) pendingDistanceSort = true

        if (!context.hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }

        viewModel.onLocationPermissionResult(true)
        if (uiState.userLatitude != null && uiState.userLongitude != null) {
            if (pendingDistanceSort) {
                viewModel.onSortSelected(SearchSortField.DISTANCE)
                pendingDistanceSort = false
            }
            return
        }

        viewModel.onLocationLookupStarted()
        loadCurrentDeviceLocation(
            context = context,
            onLocation = { latitude, longitude ->
                viewModel.onUserLocationUpdated(latitude, longitude)
                if (pendingDistanceSort) {
                    viewModel.onSortSelected(SearchSortField.DISTANCE)
                    pendingDistanceSort = false
                }
            },
            onUnavailable = {
                viewModel.onLocationUnavailable()
                pendingDistanceSort = false
            },
        )
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
        }
    }

    LaunchedEffect(uiState.criteria) {
        listState.scrollToItem(0)
    }

    val activeFilters = buildActiveFilterActions(uiState, viewModel)

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.queryDraft,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.searchQueryField),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        uiState.queryDraft.isNotEmpty() -> {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_query))
                            }
                        }

                        uiState.isSearching -> {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box {
                    OutlinedButton(onClick = { showCountryMenu = true }) {
                        Text(
                            text = uiState.criteria.selectedCountry ?: stringResource(R.string.search_filter_country),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = showCountryMenu, onDismissRequest = { showCountryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.search_filter_any_country)) },
                            onClick = {
                                showCountryMenu = false
                                viewModel.clearCountry()
                            },
                        )
                        uiState.availableCountries.forEach { country ->
                            DropdownMenuItem(
                                text = { Text(country) },
                                onClick = {
                                    showCountryMenu = false
                                    viewModel.onCriteriaChanged(uiState.criteria.copy(selectedCountry = country))
                                },
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { showDepartmentMenu = true },
                        enabled = uiState.availableDepartments.isNotEmpty(),
                    ) {
                        Text(
                            text = uiState.criteria.selectedDepartment ?: stringResource(R.string.search_filter_department),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = showDepartmentMenu, onDismissRequest = { showDepartmentMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.search_filter_any_department)) },
                            onClick = {
                                showDepartmentMenu = false
                                viewModel.clearDepartment()
                            },
                        )
                        uiState.availableDepartments.forEach { department ->
                            DropdownMenuItem(
                                text = { Text(department) },
                                onClick = {
                                    showDepartmentMenu = false
                                    viewModel.onCriteriaChanged(uiState.criteria.copy(selectedDepartment = department))
                                },
                            )
                        }
                    }
                }

                OutlinedButton(onClick = { showFiltersSheet = true }) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (uiState.activeFilterCount > 0) stringResource(R.string.search_filters_with_count, uiState.activeFilterCount)
                        else stringResource(R.string.search_filters)
                    )
                }

                Box {
                    OutlinedButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(sortLabel(uiState.criteria.sortField))
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (uiState.criteria.sortDirection == SortDirection.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        SearchSortField.entries.forEach { field ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.criteria.sortField == field) {
                                            sortLabel(field) + directionSuffix(uiState.criteria.sortDirection)
                                        } else {
                                            sortLabel(field)
                                        }
                                    )
                                },
                                onClick = {
                                    showSortMenu = false
                                    if (field == SearchSortField.DISTANCE) {
                                        requestCurrentLocation(applyDistanceSortWhenReady = true)
                                    } else {
                                        pendingDistanceSort = false
                                        viewModel.onSortSelected(field)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (activeFilters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    activeFilters.forEach { filter ->
                        FilterChip(
                            selected = true,
                            onClick = filter.onRemove,
                            label = { Text(filter.label) },
                            trailingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                        )
                    }
                    TextButton(onClick = viewModel::clearAllFilters) {
                        Text(stringResource(R.string.search_clear_filters))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_results_count, uiState.totalResultsCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            if (!uiState.isLoading && uiState.isResultListDeferred) {
                Box(modifier = Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_broad_results_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!uiState.isLoading && uiState.results.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (uiState.resultViewMode == SearchResultViewMode.LIST) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.results, key = { it.id }) { canyon ->
                        CanyonSummaryCard(
                            canyon = canyon.toSummary(),
                            onClick = { onCanyonClick(canyon.id) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(96.dp)) }
                }
            } else {
                val markers = remember(uiState.results) {
                    uiState.results.mapNotNull { item -> item.takeIf { it.representativeLat != null && it.representativeLng != null }?.toSummary() }
                }
                if (markers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.search_map_no_coordinates),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        MapLibreView(
                            markers = markers,
                            userLatitude = uiState.userLatitude,
                            userLongitude = uiState.userLongitude,
                            onMarkerClick = viewModel::selectCanyon,
                            styleUri = MAP_SEARCH_STYLE_URI,
                            modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                val next = if (uiState.resultViewMode == SearchResultViewMode.MAP) SearchResultViewMode.LIST else SearchResultViewMode.MAP
                viewModel.onResultViewModeChanged(next)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 92.dp),
        ) {
            Icon(
                imageVector = if (uiState.resultViewMode == SearchResultViewMode.MAP) Icons.AutoMirrored.Filled.List else Icons.Default.Map,
                contentDescription = null,
            )
        }
    }

    if (showFiltersSheet) {
        SearchFiltersSheet(
            uiState = uiState,
            onDismiss = { showFiltersSheet = false },
            onCriteriaChanged = viewModel::onCriteriaChanged,
            onClearAll = viewModel::clearAllFilters,
        )
    }

    uiState.selectedCanyon?.let { selectedCanyon ->
        ModalBottomSheet(onDismissRequest = viewModel::clearSelectedCanyon) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(selectedCanyon.nom, style = MaterialTheme.typography.headlineSmall)
                CanyonSummaryCard(
                    canyon = selectedCanyon.toSummary(),
                    onClick = {
                        viewModel.clearSelectedCanyon()
                        onCanyonClick(selectedCanyon.id)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.clearSelectedCanyon()
                        onCanyonClick(selectedCanyon.id)
                    }) {
                        Text(stringResource(R.string.map_bottom_sheet_open))
                    }
                    TextButton(onClick = viewModel::clearSelectedCanyon) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
