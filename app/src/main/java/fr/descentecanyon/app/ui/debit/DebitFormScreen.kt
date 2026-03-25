package fr.descentecanyon.app.ui.debit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.ui.components.CompactAppBar

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebitFormScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DebitFormViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTransientMessage()
        }
    }

    LaunchedEffect(uiState.lastSubmissionStatus) {
        if (uiState.lastSubmissionStatus == DebitSubmissionStatus.SUBMITTED ||
            uiState.lastSubmissionStatus == DebitSubmissionStatus.QUEUED_OFFLINE
        ) {
            viewModel.clearLastSubmissionStatus()
            onBackClick()
        }
    }

    Scaffold(
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.debit_form_title),
                navigation = {
                    androidx.compose.material3.IconButton(onClick = onBackClick) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.pendingCount > 0) {
                Text(
                    text = stringResource(R.string.debit_pending_count, uiState.pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!uiState.isConnected) {
                OutlinedTextField(
                    value = uiState.observerName,
                    onValueChange = viewModel::onObserverNameChanged,
                    label = { Text(stringResource(R.string.debit_observer_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.observerEmail,
                    onValueChange = viewModel::onObserverEmailChanged,
                    label = { Text(stringResource(R.string.debit_observer_email)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.debit_connected_as, uiState.observerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = uiState.observationDate.toString(),
                onValueChange = viewModel::onObservationDateChanged,
                label = { Text(stringResource(R.string.debit_observation_date)) },
                modifier = Modifier.fillMaxWidth(),
            )

            FormChipSection(
                title = stringResource(R.string.debit_observation_type),
                options = ObservationType.entries,
                selected = uiState.observationType,
                label = {
                    when (it) {
                        ObservationType.NON_PARCOURU -> stringResource(R.string.debit_type_not_descended)
                        ObservationType.PARCOURU -> stringResource(R.string.debit_type_descended)
                    }
                },
                onSelected = viewModel::onObservationTypeChanged,
            )

            FormChipSection(
                title = stringResource(R.string.debit_level_title),
                options = listOf(NiveauDebit.CRUE, NiveauDebit.TRES_GROS, NiveauDebit.GROS, NiveauDebit.CORRECT, NiveauDebit.FILET, NiveauDebit.SEC),
                selected = uiState.debitLevel,
                label = { level ->
                    when (level) {
                        NiveauDebit.CRUE -> stringResource(R.string.debit_level_crue)
                        NiveauDebit.TRES_GROS -> stringResource(R.string.debit_level_tres_gros)
                        NiveauDebit.GROS -> stringResource(R.string.debit_level_gros)
                        NiveauDebit.CORRECT -> stringResource(R.string.debit_level_correct)
                        NiveauDebit.FILET -> stringResource(R.string.debit_level_filet)
                        NiveauDebit.SEC -> stringResource(R.string.debit_level_sec)
                        NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
                    }
                },
                onSelected = viewModel::onDebitLevelChanged,
            )

            FormChipSection(
                title = stringResource(R.string.debit_water_temperature),
                options = WaterTemperature.entries,
                selected = uiState.waterTemperature,
                label = {
                    when (it) {
                        WaterTemperature.CHAUDE -> stringResource(R.string.debit_water_chaude)
                        WaterTemperature.DOUCE -> stringResource(R.string.debit_water_douce)
                        WaterTemperature.FROIDE -> stringResource(R.string.debit_water_froide)
                        WaterTemperature.TRES_FROIDE -> stringResource(R.string.debit_water_tres_froide)
                        WaterTemperature.GLACEE -> stringResource(R.string.debit_water_glacee)
                        WaterTemperature.INCONNUE -> stringResource(R.string.debit_water_inconnue)
                    }
                },
                onSelected = viewModel::onWaterTemperatureChanged,
            )

            FormChipSection(
                title = stringResource(R.string.debit_air_temperature),
                options = AirTemperature.entries,
                selected = uiState.airTemperature,
                label = {
                    when (it) {
                        AirTemperature.SUPER_CHAUD -> stringResource(R.string.debit_air_super_chaud)
                        AirTemperature.CHAUD -> stringResource(R.string.debit_air_chaud)
                        AirTemperature.BON -> stringResource(R.string.debit_air_bon)
                        AirTemperature.FRISQUET -> stringResource(R.string.debit_air_frisquet)
                        AirTemperature.FROID -> stringResource(R.string.debit_air_froid)
                        AirTemperature.INCONNUE -> stringResource(R.string.debit_air_inconnue)
                    }
                },
                onSelected = viewModel::onAirTemperatureChanged,
            )

            OutlinedTextField(
                value = uiState.comment,
                onValueChange = viewModel::onCommentChanged,
                label = { Text(stringResource(R.string.debit_comment)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )

            uiState.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSubmitting,
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text(stringResource(R.string.debit_submit))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun <T> FormChipSection(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}
