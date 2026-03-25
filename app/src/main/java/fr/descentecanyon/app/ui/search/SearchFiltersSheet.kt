package fr.descentecanyon.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.IntRangeFilter
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFiltersSheet(
    uiState: SearchUiState,
    onDismiss: () -> Unit,
    onCriteriaChanged: (SearchCriteria) -> Unit,
    onClearAll: () -> Unit,
) {
    val criteria = uiState.criteria
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_filters_title), style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearAll) { Text(stringResource(R.string.search_clear_filters)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }

            FilterSection(title = stringResource(R.string.search_filters)) {
                FilterChip(
                    selected = criteria.favoritesOnly,
                    onClick = { onCriteriaChanged(criteria.copy(favoritesOnly = !criteria.favoritesOnly)) },
                    label = { Text(stringResource(R.string.search_filter_favorites)) },
                )
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = criteria.regulationOnly,
                        onClick = { onCriteriaChanged(criteria.copy(regulationOnly = !criteria.regulationOnly)) },
                        label = { Text(stringResource(R.string.search_filter_regulated_only)) },
                    )
                    FilterChip(
                        selected = criteria.shuttleOnly,
                        onClick = { onCriteriaChanged(criteria.copy(shuttleOnly = !criteria.shuttleOnly)) },
                        label = { Text(stringResource(R.string.search_filter_shuttle_only)) },
                    )
                }
            }

            FilterSection(title = stringResource(R.string.search_filter_numeric_title)) {
                NumericRangeRow(stringResource(R.string.altitude), criteria.altitudeRange) { onCriteriaChanged(criteria.copy(altitudeRange = it)) }
                NumericRangeRow(stringResource(R.string.elevation), criteria.elevationRange) { onCriteriaChanged(criteria.copy(elevationRange = it)) }
                NumericRangeRow(stringResource(R.string.length), criteria.lengthRange) { onCriteriaChanged(criteria.copy(lengthRange = it)) }
                NumericRangeRow(stringResource(R.string.max_waterfall), criteria.maxWaterfallRange) { onCriteriaChanged(criteria.copy(maxWaterfallRange = it)) }
                NumericRangeRow(stringResource(R.string.rope), criteria.ropeRange) { onCriteriaChanged(criteria.copy(ropeRange = it)) }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun NumericRangeRow(label: String, range: IntRangeFilter, onRangeChanged: (IntRangeFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.all(Char::isDigit) || next.isBlank()) onValueChange(next) },
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
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "$label: ${selected?.let(optionLabel) ?: stringResource(R.string.search_filter_any)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_filter_any)) },
                onClick = { expanded = false; onSelected(null) },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { expanded = false; onSelected(option) },
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_filter_any)) },
                onClick = { expanded = false; onSelected(null) },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { expanded = false; onSelected(option) },
                )
            }
        }
    }
}

data class ActiveFilterAction(val label: String, val onRemove: () -> Unit)

@Composable
fun buildActiveFilterActions(uiState: SearchUiState, viewModel: SearchViewModel): List<ActiveFilterAction> {
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
        if (criteria.favoritesOnly) add(ActiveFilterAction(favoritesLabel) { viewModel.onCriteriaChanged(criteria.copy(favoritesOnly = false)) })
        criteria.selectedCountry?.let { add(ActiveFilterAction(it, viewModel::clearCountry)) }
        criteria.selectedDepartment?.let { add(ActiveFilterAction(it, viewModel::clearDepartment)) }
        criteria.verticalRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction(rangeLabel("V", it)) { viewModel.onCriteriaChanged(criteria.copy(verticalRange = IntRangeFilter())) }) }
        criteria.aquaticRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction(rangeLabel("A", it)) { viewModel.onCriteriaChanged(criteria.copy(aquaticRange = IntRangeFilter())) }) }
        criteria.engagementRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction(engagementRangeLabel(it)) { viewModel.onCriteriaChanged(criteria.copy(engagementRange = IntRangeFilter())) }) }
        criteria.interestMin?.let { add(ActiveFilterAction("$interestMinLabel >= $it") { viewModel.onCriteriaChanged(criteria.copy(interestMin = null)) }) }
        if (criteria.regulationOnly) add(ActiveFilterAction(regulationsLabel) { viewModel.onCriteriaChanged(criteria.copy(regulationOnly = false)) })
        if (criteria.shuttleOnly) add(ActiveFilterAction(shuttleLabel) { viewModel.onCriteriaChanged(criteria.copy(shuttleOnly = false)) })
        criteria.altitudeRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction("$altitudeLabel ${plainRangeLabel(it)}") { viewModel.onCriteriaChanged(criteria.copy(altitudeRange = IntRangeFilter())) }) }
        criteria.elevationRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction("$elevationLabel ${plainRangeLabel(it)}") { viewModel.onCriteriaChanged(criteria.copy(elevationRange = IntRangeFilter())) }) }
        criteria.lengthRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction("$lengthLabel ${plainRangeLabel(it)}") { viewModel.onCriteriaChanged(criteria.copy(lengthRange = IntRangeFilter())) }) }
        criteria.maxWaterfallRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction("$maxWaterfallLabel ${plainRangeLabel(it)}") { viewModel.onCriteriaChanged(criteria.copy(maxWaterfallRange = IntRangeFilter())) }) }
        criteria.ropeRange.takeIf(IntRangeFilter::isActive)?.let { add(ActiveFilterAction("$ropeLabel ${plainRangeLabel(it)}") { viewModel.onCriteriaChanged(criteria.copy(ropeRange = IntRangeFilter())) }) }
    }
}

@Composable
fun sortLabel(field: SearchSortField): String = when (field) {
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

fun directionSuffix(direction: SortDirection): String = if (direction == SortDirection.ASC) " ↑" else " ↓"

private fun rangeLabel(prefix: String, range: IntRangeFilter): String = when {
    range.min != null && range.max != null -> "$prefix ${range.min}-${range.max}"
    range.min != null -> "$prefix >= ${range.min}"
    range.max != null -> "$prefix <= ${range.max}"
    else -> prefix
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

private fun plainRangeLabel(range: IntRangeFilter): String = when {
    range.min != null && range.max != null -> "${range.min}-${range.max}"
    range.min != null -> ">= ${range.min}"
    range.max != null -> "<= ${range.max}"
    else -> ""
}
