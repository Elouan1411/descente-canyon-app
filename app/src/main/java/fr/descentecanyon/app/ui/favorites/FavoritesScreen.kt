package fr.descentecanyon.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcSectionHeader
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.test.TestTags

@Composable
fun FavoritesScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalDcColors.current.backgroundBase)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(contentWidth)
                .align(Alignment.TopCenter)
                .padding(horizontal = screenHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            DcSectionHeader(
                title = stringResource(R.string.tab_favorites),
                subtitle = stringResource(R.string.favorites_screen_subtitle),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (uiState.rawFavorites.isNotEmpty()) {
                FavoritesFilterBar(
                    uiState = uiState,
                    onSortSelected = viewModel::setSortOption,
                    onCountrySelected = viewModel::setCountryFilter,
                    onRegionSelected = viewModel::setRegionFilter,
                    onMinRatingSelected = viewModel::setMinRatingFilter,
                    onResetFilters = viewModel::resetFilters,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            ) {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.rawFavorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DcEmptyState(
                            title = stringResource(R.string.no_favorites),
                            icon = Icons.Default.FavoriteBorder,
                        )
                    }
                } else if (uiState.filteredFavorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            DcEmptyState(
                                title = stringResource(R.string.favorite_no_matching_filters),
                                icon = Icons.Default.FilterList,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = viewModel::resetFilters) {
                                Text(stringResource(R.string.favorite_reset_filters))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.testTag(TestTags.favoritesList),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.filteredFavorites,
                            key = { it.id },
                        ) { canyon ->
                            FavoriteDismissItem(
                                onRemove = { viewModel.removeFavorite(canyon.id) },
                            ) {
                                CanyonSummaryCard(
                                    canyon = canyon,
                                    onClick = { onCanyonClick(canyon.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesFilterBar(
    uiState: FavoritesUiState,
    onSortSelected: (FavoriteSortOption) -> Unit,
    onCountrySelected: (String?) -> Unit,
    onRegionSelected: (String?) -> Unit,
    onMinRatingSelected: (Float) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var countryMenuExpanded by remember { mutableStateOf(false) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var ratingMenuExpanded by remember { mutableStateOf(false) }

    val hasActiveFilters = uiState.selectedCountry != null ||
            uiState.selectedRegion != null ||
            uiState.minRating > 0f ||
            uiState.selectedSort != FavoriteSortOption.DATE_ADDED_DESC

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // --- Sort Chip ---
        Box {
            FilterChip(
                selected = uiState.selectedSort != FavoriteSortOption.DATE_ADDED_DESC,
                onClick = { sortMenuExpanded = true },
                label = {
                    val sortLabel = when (uiState.selectedSort) {
                        FavoriteSortOption.DATE_ADDED_DESC -> stringResource(R.string.favorite_sort_date_desc)
                        FavoriteSortOption.DATE_ADDED_ASC -> stringResource(R.string.favorite_sort_date_asc)
                        FavoriteSortOption.RATING_DESC -> stringResource(R.string.favorite_sort_rating_desc)
                        FavoriteSortOption.NAME_ASC -> stringResource(R.string.favorite_sort_name_asc)
                    }
                    Text("Tri: $sortLabel")
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_sort_date_desc)) },
                    onClick = { onSortSelected(FavoriteSortOption.DATE_ADDED_DESC); sortMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_sort_date_asc)) },
                    onClick = { onSortSelected(FavoriteSortOption.DATE_ADDED_ASC); sortMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_sort_rating_desc)) },
                    onClick = { onSortSelected(FavoriteSortOption.RATING_DESC); sortMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_sort_name_asc)) },
                    onClick = { onSortSelected(FavoriteSortOption.NAME_ASC); sortMenuExpanded = false },
                )
            }
        }

        // --- Country Chip ---
        if (uiState.availableCountries.size > 1 || uiState.selectedCountry != null) {
            Box {
                FilterChip(
                    selected = uiState.selectedCountry != null,
                    onClick = { countryMenuExpanded = true },
                    label = {
                        Text(uiState.selectedCountry ?: stringResource(R.string.favorite_filter_country))
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
                DropdownMenu(
                    expanded = countryMenuExpanded,
                    onDismissRequest = { countryMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favorite_filter_all)) },
                        onClick = { onCountrySelected(null); countryMenuExpanded = false },
                    )
                    uiState.availableCountries.forEach { country ->
                        DropdownMenuItem(
                            text = { Text(country) },
                            onClick = { onCountrySelected(country); countryMenuExpanded = false },
                        )
                    }
                }
            }
        }

        // --- Region Chip ---
        if (uiState.availableRegions.size > 1 || uiState.selectedRegion != null) {
            Box {
                FilterChip(
                    selected = uiState.selectedRegion != null,
                    onClick = { regionMenuExpanded = true },
                    label = {
                        Text(uiState.selectedRegion ?: stringResource(R.string.favorite_filter_region))
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
                DropdownMenu(
                    expanded = regionMenuExpanded,
                    onDismissRequest = { regionMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favorite_filter_all)) },
                        onClick = { onRegionSelected(null); regionMenuExpanded = false },
                    )
                    uiState.availableRegions.forEach { region ->
                        DropdownMenuItem(
                            text = { Text(region) },
                            onClick = { onRegionSelected(region); regionMenuExpanded = false },
                        )
                    }
                }
            }
        }

        // --- Rating Chip ---
        Box {
            FilterChip(
                selected = uiState.minRating > 0f,
                onClick = { ratingMenuExpanded = true },
                label = {
                    val text = if (uiState.minRating > 0f) "≥ ${uiState.minRating}★" else stringResource(R.string.favorite_filter_rating)
                    Text(text)
                },
                leadingIcon = {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            DropdownMenu(
                expanded = ratingMenuExpanded,
                onDismissRequest = { ratingMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_filter_all)) },
                    onClick = { onMinRatingSelected(0f); ratingMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text("≥ 2.0 ★") },
                    onClick = { onMinRatingSelected(2.0f); ratingMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text("≥ 3.0 ★") },
                    onClick = { onMinRatingSelected(3.0f); ratingMenuExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text("≥ 3.5 ★") },
                    onClick = { onMinRatingSelected(3.5f); ratingMenuExpanded = false },
                )
            }
        }

        // --- Reset Button ---
        if (hasActiveFilters) {
            IconButton(
                onClick = onResetFilters,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.favorite_reset_filters),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FavoriteDismissItem(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
            }
            value != SwipeToDismissBoxValue.StartToEnd
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            val isDismissed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart ||
                dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.remove_favorite),
                        tint = if (isDismissed) MaterialTheme.colorScheme.error else Color.Transparent,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            }
        },
    ) {
        content()
    }
}
