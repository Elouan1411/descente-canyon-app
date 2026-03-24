package fr.descentecanyon.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
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
import fr.descentecanyon.app.ui.components.CanyonSummaryCard

@Composable
fun SearchScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFiltersSheet by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var showCountryMenu by rememberSaveable { mutableStateOf(false) }
    var showDepartmentMenu by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.criteria.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (uiState.criteria.query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearQuery) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear_query),
                        )
                    }
                }
            },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = uiState.criteria.favoritesOnly,
                onClick = {
                    viewModel.onCriteriaChanged(
                        uiState.criteria.copy(favoritesOnly = !uiState.criteria.favoritesOnly)
                    )
                },
                label = { Text(stringResource(R.string.search_filter_favorites)) },
            )

            Box {
                OutlinedButton(onClick = { showCountryMenu = true }) {
                    Text(
                        text = uiState.criteria.selectedCountry ?: stringResource(R.string.search_filter_country),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showCountryMenu,
                    onDismissRequest = { showCountryMenu = false },
                ) {
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
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showDepartmentMenu,
                    onDismissRequest = { showDepartmentMenu = false },
                ) {
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
                    if (uiState.activeFilterCount > 0) {
                        stringResource(R.string.search_filters_with_count, uiState.activeFilterCount)
                    } else {
                        stringResource(R.string.search_filters)
                    }
                )
            }

            Box {
                OutlinedButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(sortLabel(uiState.criteria.sortField))
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (uiState.criteria.sortDirection == SortDirection.ASC) {
                            Icons.Default.ArrowUpward
                        } else {
                            Icons.Default.ArrowDownward
                        },
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    SearchSortField.entries.filterNot { it == SearchSortField.DISTANCE }.forEach { field ->
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
                                viewModel.onSortSelected(field)
                            },
                        )
                    }
                }
            }
        }

        val activeFilters = buildActiveFilterActions(uiState, viewModel)
        if (activeFilters.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                activeFilters.forEach { filter ->
                    FilterChip(
                        selected = true,
                        onClick = filter.onRemove,
                        label = { Text(filter.label) },
                        trailingIcon = {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        },
                    )
                }
                TextButton(onClick = viewModel::clearAllFilters) {
                    Text(stringResource(R.string.search_clear_filters))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.search_results_count, uiState.results.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(8.dp),
            )
        }

        if (!uiState.isLoading && uiState.results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = uiState.results,
                key = { it.id },
            ) { canyon ->
                CanyonSummaryCard(
                    canyon = canyon.toSummary(),
                    onClick = { onCanyonClick(canyon.id) },
                )
            }
            item {
                Spacer(modifier = Modifier.height(96.dp))
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFiltersSheet(
    uiState: SearchUiState,
    onDismiss: () -> Unit,
    onCriteriaChanged: (SearchCriteria) -> Unit,
    onClearAll: () -> Unit,
) {
    val criteria = uiState.criteria
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_filters_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.search_clear_filters))
                }
            }

            FilterSection(title = stringResource(R.string.search_filter_cotation)) {
                RangeDropdownRow(
                    label = stringResource(R.string.search_filter_vertical),
                    range = criteria.verticalRange,
                    options = (1..7).toList(),
                    optionLabel = { "V$it" },
                    onRangeChanged = { onCriteriaChanged(criteria.copy(verticalRange = it)) },
                )
                RangeDropdownRow(
                    label = stringResource(R.string.search_filter_aquatic),
                    range = criteria.aquaticRange,
                    options = (1..7).toList(),
                    optionLabel = { "A$it" },
                    onRangeChanged = { onCriteriaChanged(criteria.copy(aquaticRange = it)) },
                )
                RangeDropdownRow(
                    label = stringResource(R.string.search_filter_engagement),
                    range = criteria.engagementRange,
                    options = (1..6).toList(),
                    optionLabel = { CotationRating.engagementLabel(it) },
                    onRangeChanged = { onCriteriaChanged(criteria.copy(engagementRange = it)) },
                )
            }

            FilterSection(title = stringResource(R.string.search_filter_interests_title)) {
                FloatDropdownField(
                    label = stringResource(R.string.search_filter_interest_min),
                    selected = criteria.interestMin,
                    options = listOf(1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f),
                    optionLabel = { if (it % 1f == 0f) it.toInt().toString() else it.toString() },
                    onSelected = { onCriteriaChanged(criteria.copy(interestMin = it)) },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = criteria.regulationOnly,
                        onClick = {
                            onCriteriaChanged(criteria.copy(regulationOnly = !criteria.regulationOnly))
                        },
                        label = { Text(stringResource(R.string.search_filter_regulated_only)) },
                    )
                    FilterChip(
                        selected = criteria.shuttleOnly,
                        onClick = {
                            onCriteriaChanged(criteria.copy(shuttleOnly = !criteria.shuttleOnly))
                        },
                        label = { Text(stringResource(R.string.search_filter_shuttle_only)) },
                    )
                }
            }

            FilterSection(title = stringResource(R.string.search_filter_numeric_title)) {
                NumericRangeRow(
                    label = stringResource(R.string.altitude),
                    range = criteria.altitudeRange,
                    onRangeChanged = { onCriteriaChanged(criteria.copy(altitudeRange = it)) },
                )
                NumericRangeRow(
                    label = stringResource(R.string.elevation),
                    range = criteria.elevationRange,
                    onRangeChanged = { onCriteriaChanged(criteria.copy(elevationRange = it)) },
                )
                NumericRangeRow(
                    label = stringResource(R.string.length),
                    range = criteria.lengthRange,
                    onRangeChanged = { onCriteriaChanged(criteria.copy(lengthRange = it)) },
                )
                NumericRangeRow(
                    label = stringResource(R.string.max_waterfall),
                    range = criteria.maxWaterfallRange,
                    onRangeChanged = { onCriteriaChanged(criteria.copy(maxWaterfallRange = it)) },
                )
                NumericRangeRow(
                    label = stringResource(R.string.rope),
                    range = criteria.ropeRange,
                    onRangeChanged = { onCriteriaChanged(criteria.copy(ropeRange = it)) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun RangeDropdownRow(
    label: String,
    range: IntRangeFilter,
    options: List<Int>,
    optionLabel: (Int) -> String,
    onRangeChanged: (IntRangeFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntDropdownField(
                label = stringResource(R.string.search_filter_min),
                selected = range.min,
                options = options,
                optionLabel = optionLabel,
                modifier = Modifier.weight(1f),
                onSelected = { onRangeChanged(range.copy(min = it)) },
            )
            IntDropdownField(
                label = stringResource(R.string.search_filter_max),
                selected = range.max,
                options = options,
                optionLabel = optionLabel,
                modifier = Modifier.weight(1f),
                onSelected = { onRangeChanged(range.copy(max = it)) },
            )
        }
    }
}

@Composable
private fun NumericRangeRow(
    label: String,
    range: IntRangeFilter,
    onRangeChanged: (IntRangeFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumericField(
                label = stringResource(R.string.search_filter_min),
                value = range.min?.toString().orEmpty(),
                modifier = Modifier.weight(1f),
                onValueChange = { onRangeChanged(range.copy(min = it.toIntOrNull())) },
            )
            NumericField(
                label = stringResource(R.string.search_filter_max),
                value = range.max?.toString().orEmpty(),
                modifier = Modifier.weight(1f),
                onValueChange = { onRangeChanged(range.copy(max = it.toIntOrNull())) },
            )
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.all(Char::isDigit) || next.isBlank()) {
                onValueChange(next)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun IntDropdownField(
    label: String,
    selected: Int?,
    options: List<Int>,
    optionLabel: (Int) -> String,
    onSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "$label: ${selected?.let(optionLabel) ?: stringResource(R.string.search_filter_any)}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_filter_any)) },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatDropdownField(
    label: String,
    selected: Float?,
    options: List<Float>,
    optionLabel: (Float) -> String,
    onSelected: (Float?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(text = "$label: ${selected?.let(optionLabel) ?: stringResource(R.string.search_filter_any)}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_filter_any)) },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

private data class ActiveFilterAction(
    val label: String,
    val onRemove: () -> Unit,
)

@Composable
private fun buildActiveFilterActions(
    uiState: SearchUiState,
    viewModel: SearchViewModel,
): List<ActiveFilterAction> {
    val criteria = uiState.criteria
    val favoritesLabel = stringResource(R.string.search_filter_favorites)
    val interestMinLabel = stringResource(R.string.search_filter_interest_min)
    val regulationsLabel = stringResource(R.string.regulations)
    val shuttleLabel = stringResource(R.string.shuttle)
    val altitudeLabel = stringResource(R.string.altitude)
    val elevationLabel = stringResource(R.string.elevation)
    val lengthLabel = stringResource(R.string.length)
    val maxWaterfallLabel = stringResource(R.string.max_waterfall)
    val ropeLabel = stringResource(R.string.rope)
    return buildList {
        if (criteria.favoritesOnly) {
            add(ActiveFilterAction(label = favoritesLabel) {
                viewModel.onCriteriaChanged(criteria.copy(favoritesOnly = false))
            })
        }
        criteria.selectedCountry?.let { country ->
            add(ActiveFilterAction(label = country, onRemove = viewModel::clearCountry))
        }
        criteria.selectedDepartment?.let { department ->
            add(ActiveFilterAction(label = department, onRemove = viewModel::clearDepartment))
        }
        criteria.verticalRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = rangeLabel("V", it)) {
                viewModel.onCriteriaChanged(criteria.copy(verticalRange = IntRangeFilter()))
            })
        }
        criteria.aquaticRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = rangeLabel("A", it)) {
                viewModel.onCriteriaChanged(criteria.copy(aquaticRange = IntRangeFilter()))
            })
        }
        criteria.engagementRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = engagementRangeLabel(it)) {
                viewModel.onCriteriaChanged(criteria.copy(engagementRange = IntRangeFilter()))
            })
        }
        criteria.interestMin?.let { minimum ->
            add(ActiveFilterAction(label = "$interestMinLabel >= $minimum") {
                viewModel.onCriteriaChanged(criteria.copy(interestMin = null))
            })
        }
        if (criteria.regulationOnly) {
            add(ActiveFilterAction(label = regulationsLabel) {
                viewModel.onCriteriaChanged(criteria.copy(regulationOnly = false))
            })
        }
        if (criteria.shuttleOnly) {
            add(ActiveFilterAction(label = shuttleLabel) {
                viewModel.onCriteriaChanged(criteria.copy(shuttleOnly = false))
            })
        }
        criteria.altitudeRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = "$altitudeLabel ${plainRangeLabel(it)}") {
                viewModel.onCriteriaChanged(criteria.copy(altitudeRange = IntRangeFilter()))
            })
        }
        criteria.elevationRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = "$elevationLabel ${plainRangeLabel(it)}") {
                viewModel.onCriteriaChanged(criteria.copy(elevationRange = IntRangeFilter()))
            })
        }
        criteria.lengthRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = "$lengthLabel ${plainRangeLabel(it)}") {
                viewModel.onCriteriaChanged(criteria.copy(lengthRange = IntRangeFilter()))
            })
        }
        criteria.maxWaterfallRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = "$maxWaterfallLabel ${plainRangeLabel(it)}") {
                viewModel.onCriteriaChanged(criteria.copy(maxWaterfallRange = IntRangeFilter()))
            })
        }
        criteria.ropeRange.takeIf(IntRangeFilter::isActive)?.let {
            add(ActiveFilterAction(label = "$ropeLabel ${plainRangeLabel(it)}") {
                viewModel.onCriteriaChanged(criteria.copy(ropeRange = IntRangeFilter()))
            })
        }
    }
}

@Composable
private fun sortLabel(field: SearchSortField): String {
    return when (field) {
        SearchSortField.RELEVANCE -> stringResource(R.string.search_sort_relevance)
        SearchSortField.NAME -> stringResource(R.string.search_sort_name)
        SearchSortField.INTEREST -> stringResource(R.string.search_sort_interest)
        SearchSortField.POPULARITY -> stringResource(R.string.search_sort_popularity)
        SearchSortField.DIFFICULTY -> stringResource(R.string.search_sort_difficulty)
        SearchSortField.ELEVATION -> stringResource(R.string.search_sort_elevation)
        SearchSortField.LENGTH -> stringResource(R.string.search_sort_length)
        SearchSortField.MAX_WATERFALL -> stringResource(R.string.search_sort_max_waterfall)
        SearchSortField.DISTANCE -> stringResource(R.string.search_sort_distance)
    }
}

private fun directionSuffix(direction: SortDirection): String {
    return if (direction == SortDirection.ASC) " ↑" else " ↓"
}

private fun rangeLabel(prefix: String, range: IntRangeFilter): String {
    return when {
        range.min != null && range.max != null -> "$prefix ${range.min}-${range.max}"
        range.min != null -> "$prefix >= ${range.min}"
        range.max != null -> "$prefix <= ${range.max}"
        else -> prefix
    }
}

private fun engagementRangeLabel(range: IntRangeFilter): String {
    fun format(value: Int?): String = CotationRating.engagementLabel(value)
    return when {
        range.min != null && range.max != null -> "E ${format(range.min)}-${format(range.max)}"
        range.min != null -> "E >= ${format(range.min)}"
        range.max != null -> "E <= ${format(range.max)}"
        else -> "E"
    }
}

private fun plainRangeLabel(range: IntRangeFilter): String {
    return when {
        range.min != null && range.max != null -> "${range.min}-${range.max}"
        range.min != null -> ">= ${range.min}"
        range.max != null -> "<= ${range.max}"
        else -> ""
    }
}
