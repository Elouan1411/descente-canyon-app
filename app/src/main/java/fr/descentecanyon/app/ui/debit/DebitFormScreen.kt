package fr.descentecanyon.app.ui.debit

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebitFormScreen(
    onBackClick: () -> Unit,
    onSubmissionSuccess: () -> Unit = onBackClick,
    modifier: Modifier = Modifier,
    viewModel: DebitFormViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showLoginDialog by remember { mutableStateOf(false) }

    if (showLoginDialog) {
        LoginDialog(
            uiState = authUiState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLoginDialog = false },
        )
    }

    LaunchedEffect(showLoginDialog, authUiState.authState) {
        if (showLoginDialog && authUiState.authState is AuthState.Connected) {
            showLoginDialog = false
        }
    }

    LaunchedEffect(uiState.loginRequiredMessage) {
        uiState.loginRequiredMessage?.let { message ->
            authViewModel.showError(message)
            showLoginDialog = true
            viewModel.clearLoginRequiredMessage()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTransientMessage()
        }
    }

    LaunchedEffect(uiState.lastSubmissionStatus) {
        when (uiState.lastSubmissionStatus) {
            DebitSubmissionStatus.SUBMITTED -> {
                viewModel.clearLastSubmissionStatus()
                onSubmissionSuccess()
            }
            DebitSubmissionStatus.QUEUED_OFFLINE -> {
                viewModel.clearLastSubmissionStatus()
                onBackClick()
            }
            null -> Unit
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
                    modifier = Modifier.testTag(TestTags.debitPendingCount),
                )
            }

            if (!uiState.isConnected) {
                LoginSuggestionCard(onLoginClick = { showLoginDialog = true })
                OutlinedTextField(
                    value = uiState.observerName,
                    onValueChange = viewModel::onObserverNameChanged,
                    label = { Text(stringResource(R.string.debit_observer_name)) },
                    supportingText = { Text(stringResource(R.string.debit_observer_name_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.debitObserverNameField),
                )
                OutlinedTextField(
                    value = uiState.observerEmail,
                    onValueChange = viewModel::onObserverEmailChanged,
                    label = { Text(stringResource(R.string.debit_observer_email)) },
                    supportingText = { Text(stringResource(R.string.debit_observer_email_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.debitObserverEmailField),
                )
            } else {
                Text(
                    text = stringResource(R.string.debit_connected_as, uiState.observerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            DatePickerField(
                date = uiState.observationDate,
                onDateSelected = viewModel::onObservationDateSelected,
                showDatePicker = { currentDate, onDateSelected ->
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
                        },
                        currentDate.year,
                        currentDate.monthValue - 1,
                        currentDate.dayOfMonth,
                    ).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                },
            )

            FormChoiceSection(
                title = stringResource(R.string.debit_observation_type),
                options = listOf(
                    FormOption(
                        ObservationType.NON_PARCOURU,
                        stringResource(R.string.debit_type_not_descended),
                    ),
                    FormOption(
                        ObservationType.PARCOURU,
                        stringResource(R.string.debit_type_descended),
                    ),
                ),
                selected = uiState.observationType,
                helpText = stringResource(R.string.debit_observation_type_help),
                onSelected = viewModel::onObservationTypeChanged,
            )

            FormChoiceSection(
                title = stringResource(R.string.debit_level_title),
                options = listOf(
                    FormOption(
                        NiveauDebit.CRUE,
                        stringResource(R.string.debit_level_crue),
                        stringResource(R.string.debit_level_crue_description),
                        debitLevelColor(NiveauDebit.CRUE),
                    ),
                    FormOption(
                        NiveauDebit.TRES_GROS,
                        stringResource(R.string.debit_level_tres_gros),
                        stringResource(R.string.debit_level_tres_gros_description),
                        debitLevelColor(NiveauDebit.TRES_GROS),
                    ),
                    FormOption(
                        NiveauDebit.GROS,
                        stringResource(R.string.debit_level_gros),
                        stringResource(R.string.debit_level_gros_description),
                        debitLevelColor(NiveauDebit.GROS),
                    ),
                    FormOption(
                        NiveauDebit.CORRECT,
                        stringResource(R.string.debit_level_correct),
                        stringResource(R.string.debit_level_correct_description),
                        debitLevelColor(NiveauDebit.CORRECT),
                    ),
                    FormOption(
                        NiveauDebit.FILET,
                        stringResource(R.string.debit_level_filet),
                        stringResource(R.string.debit_level_filet_description),
                        debitLevelColor(NiveauDebit.FILET),
                    ),
                    FormOption(
                        NiveauDebit.SEC,
                        stringResource(R.string.debit_level_sec),
                        stringResource(R.string.debit_level_sec_description),
                        debitLevelColor(NiveauDebit.SEC),
                    ),
                ),
                selected = uiState.debitLevel,
                onSelected = viewModel::onDebitLevelChanged,
            )

            FormChoiceSection(
                title = stringResource(R.string.debit_water_temperature),
                options = listOf(
                    FormOption(
                        WaterTemperature.CHAUDE,
                        stringResource(R.string.debit_water_chaude),
                        stringResource(R.string.debit_water_chaude_description),
                    ),
                    FormOption(
                        WaterTemperature.DOUCE,
                        stringResource(R.string.debit_water_douce),
                        stringResource(R.string.debit_water_douce_description),
                    ),
                    FormOption(
                        WaterTemperature.FROIDE,
                        stringResource(R.string.debit_water_froide),
                        stringResource(R.string.debit_water_froide_description),
                    ),
                    FormOption(
                        WaterTemperature.TRES_FROIDE,
                        stringResource(R.string.debit_water_tres_froide),
                        stringResource(R.string.debit_water_tres_froide_description),
                    ),
                    FormOption(
                        WaterTemperature.GLACEE,
                        stringResource(R.string.debit_water_glacee),
                        stringResource(R.string.debit_water_glacee_description),
                    ),
                    FormOption(
                        WaterTemperature.INCONNUE,
                        stringResource(R.string.debit_water_inconnue),
                        stringResource(R.string.debit_water_inconnue_description),
                    ),
                ),
                selected = uiState.waterTemperature,
                onSelected = viewModel::onWaterTemperatureChanged,
            )

            FormChoiceSection(
                title = stringResource(R.string.debit_air_temperature),
                options = listOf(
                    FormOption(
                        AirTemperature.SUPER_CHAUD,
                        stringResource(R.string.debit_air_super_chaud),
                        stringResource(R.string.debit_air_super_chaud_description),
                    ),
                    FormOption(
                        AirTemperature.CHAUD,
                        stringResource(R.string.debit_air_chaud),
                        stringResource(R.string.debit_air_chaud_description),
                    ),
                    FormOption(
                        AirTemperature.BON,
                        stringResource(R.string.debit_air_bon),
                        stringResource(R.string.debit_air_bon_description),
                    ),
                    FormOption(
                        AirTemperature.FRISQUET,
                        stringResource(R.string.debit_air_frisquet),
                        stringResource(R.string.debit_air_frisquet_description),
                    ),
                    FormOption(
                        AirTemperature.FROID,
                        stringResource(R.string.debit_air_froid),
                        stringResource(R.string.debit_air_froid_description),
                    ),
                    FormOption(
                        AirTemperature.INCONNUE,
                        stringResource(R.string.debit_air_inconnue),
                    ),
                ),
                selected = uiState.airTemperature,
                onSelected = viewModel::onAirTemperatureChanged,
            )

            OutlinedTextField(
                value = uiState.comment,
                onValueChange = viewModel::onCommentChanged,
                label = { Text(stringResource(R.string.debit_comment)) },
                supportingText = { Text(stringResource(R.string.debit_comment_help)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.debitCommentField),
                minLines = 4,
            )

            if (uiState.isConnected) {
                OutlinedTextField(
                    value = uiState.personalComment,
                    onValueChange = viewModel::onPersonalCommentChanged,
                    label = { Text(stringResource(R.string.debit_personal_comment)) },
                    supportingText = { Text(stringResource(R.string.debit_personal_comment_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.debitPersonalCommentField),
                    minLines = 3,
                )
            }

            uiState.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.debitSubmitButton),
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
private fun DatePickerField(
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    showDatePicker: (LocalDate, (LocalDate) -> Unit) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date.toString(),
            onValueChange = {},
            label = { Text(stringResource(R.string.debit_observation_date)) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(TestTags.debitObservationDateField)
                .clickable { showDatePicker(date, onDateSelected) },
        )
    }
}

@Composable
private fun LoginSuggestionCard(onLoginClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.debit_login_prompt_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.debit_login_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onLoginClick) {
                Text(stringResource(R.string.debit_login_prompt_button))
            }
        }
    }
}

@Composable
private fun <T> FormChoiceSection(
    title: String,
    options: List<FormOption<T>>,
    selected: T?,
    helpText: String? = null,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        options.forEach { option ->
            FormChoiceRow(
                option = option,
                selected = option.value == selected,
                onClick = { onSelected(option.value) },
            )
        }
        helpText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> FormChoiceRow(
    option: FormOption<T>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colorScheme.primaryContainer else colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            option.indicatorColor?.let { indicatorColor ->
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = MaterialTheme.shapes.small,
                    color = indicatorColor,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    content = {},
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                option.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class FormOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val indicatorColor: Color? = null,
)
