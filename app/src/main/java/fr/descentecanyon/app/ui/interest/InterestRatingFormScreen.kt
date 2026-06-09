package fr.descentecanyon.app.ui.interest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.test.TestTags
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun InterestRatingFormScreen(
    onBackClick: () -> Unit,
    onSubmissionSuccess: () -> Unit = onBackClick,
    modifier: Modifier = Modifier,
    viewModel: InterestRatingFormViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
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

    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted) {
            viewModel.clearSubmitted()
            onSubmissionSuccess()
        }
    }

    Scaffold(
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.interest_rating_form_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
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
            if (!uiState.isConnected) {
                LoginSuggestionCard(onLoginClick = { showLoginDialog = true })
            } else {
                Text(
                    text = stringResource(R.string.interest_rating_connected_as, uiState.username),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (uiState.isLoading) {
                CircularProgressIndicator()
            }

            RatingSummaryCard(uiState)

            RatingSlider(
                ratingTenths = uiState.ratingTenths,
                onRatingTenthsChanged = viewModel::onRatingTenthsChanged,
                enabled = !uiState.isSubmitting,
            )

            InterestRatingGuide()

            uiState.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.interestRatingSubmitButton),
                enabled = !uiState.isSubmitting,
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text(stringResource(R.string.interest_rating_submit))
                }
            }
        }
    }
}

@Composable
private fun InterestRatingGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.interest_rating_guide_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.interest_rating_guide_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InterestRatingGuideItems.forEach { item ->
                InterestRatingGuideRow(item)
            }
        }
    }
}

@Composable
private fun InterestRatingGuideRow(item: InterestRatingGuideItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = stringResource(item.labelRes),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(item.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RatingSummaryCard(uiState: InterestRatingFormUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.interest_rating_current_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            uiState.personalRating?.let { rating ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.interest_rating_personal))
                    InterestStars(interest = rating)
                }
            } ?: Text(
                text = stringResource(R.string.interest_rating_no_personal),
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.averageRating?.let { average ->
                Text(
                    text = stringResource(
                        R.string.interest_rating_average,
                        formatRating(average),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.medianRating?.let { median ->
                Text(
                    text = stringResource(R.string.interest_rating_median, formatRating(median)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.voteCount?.let { voteCount ->
                Text(
                    text = stringResource(R.string.interest_rating_vote_count, voteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RatingSlider(
    ratingTenths: Int,
    onRatingTenthsChanged: (Int) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.interest_rating_selected, formatRating(ratingTenths / 10f)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = ratingTenths / 10f,
            onValueChange = { onRatingTenthsChanged((it * 10f).roundToInt()) },
            valueRange = 0f..4f,
            steps = 39,
            enabled = enabled,
            modifier = Modifier.testTag(TestTags.interestRatingSlider),
        )
        Text(
            text = stringResource(R.string.interest_rating_step_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = stringResource(R.string.interest_rating_login_prompt_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.interest_rating_login_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onLoginClick) {
                Text(stringResource(R.string.debit_login_prompt_button))
            }
        }
    }
}

private fun formatRating(value: Float): String = String.format(Locale.US, "%.1f/4", value.coerceIn(0f, 4f))

private data class InterestRatingGuideItem(
    val labelRes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
)

private val InterestRatingGuideItems = listOf(
    InterestRatingGuideItem(
        labelRes = R.string.interest_rating_guide_4_label,
        titleRes = R.string.interest_rating_guide_4_title,
        descriptionRes = R.string.interest_rating_guide_4_description,
    ),
    InterestRatingGuideItem(
        labelRes = R.string.interest_rating_guide_3_label,
        titleRes = R.string.interest_rating_guide_3_title,
        descriptionRes = R.string.interest_rating_guide_3_description,
    ),
    InterestRatingGuideItem(
        labelRes = R.string.interest_rating_guide_2_label,
        titleRes = R.string.interest_rating_guide_2_title,
        descriptionRes = R.string.interest_rating_guide_2_description,
    ),
    InterestRatingGuideItem(
        labelRes = R.string.interest_rating_guide_1_label,
        titleRes = R.string.interest_rating_guide_1_title,
        descriptionRes = R.string.interest_rating_guide_1_description,
    ),
    InterestRatingGuideItem(
        labelRes = R.string.interest_rating_guide_0_label,
        titleRes = R.string.interest_rating_guide_0_title,
        descriptionRes = R.string.interest_rating_guide_0_description,
    ),
)
