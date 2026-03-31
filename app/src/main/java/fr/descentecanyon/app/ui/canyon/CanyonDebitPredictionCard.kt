package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun CanyonDebitPredictionCard(
    predictions: CanyonDebitPredictions?,
    isLoading: Boolean,
    error: String?,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prediction_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onInfoClick) {
                    Text(text = stringResource(R.string.prediction_info_cta))
                }
            }

            when {
                isLoading || (predictions == null && error == null) -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.prediction_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                predictions != null -> {
                    Text(
                        text = stringResource(
                            R.string.prediction_updated_at,
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                                .withLocale(Locale.getDefault())
                                .format(predictions.fetchedAt.atZone(runCatching { ZoneId.of(predictions.timezone) }.getOrDefault(ZoneId.of("UTC")))),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    predictions.predictions.forEachIndexed { index, prediction ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        PredictionRow(prediction = prediction)
                    }

                    Text(
                        text = stringResource(
                            R.string.prediction_lookup_source,
                            lookupSourceLabel(predictions.lookupSource),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.prediction_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PredictionRow(
    prediction: DailyDebitPrediction,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when (prediction.horizonDays) {
                    0 -> stringResource(R.string.prediction_today)
                    1 -> stringResource(R.string.prediction_tomorrow)
                    else -> stringResource(R.string.prediction_day_after_tomorrow)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = prediction.date.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = levelLabel(prediction.level),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier
                    .background(levelColor(prediction.level), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(
                    R.string.prediction_high_probability,
                    (prediction.probabilities[PredictedDebitLevel.HIGH] ?: 0.0) * 100.0,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun levelLabel(level: PredictedDebitLevel): String {
    return when (level) {
        PredictedDebitLevel.LOW -> stringResource(R.string.prediction_level_low)
        PredictedDebitLevel.MEDIUM -> stringResource(R.string.prediction_level_medium)
        PredictedDebitLevel.HIGH -> stringResource(R.string.prediction_level_high)
    }
}

@Composable
private fun lookupSourceLabel(source: RuntimeLookupSource): String {
    return when (source) {
        RuntimeLookupSource.CANYON -> stringResource(R.string.prediction_lookup_canyon)
        RuntimeLookupSource.MASSIF -> stringResource(R.string.prediction_lookup_massif)
        RuntimeLookupSource.REGION -> stringResource(R.string.prediction_lookup_region)
        RuntimeLookupSource.GLOBAL -> stringResource(R.string.prediction_lookup_global)
    }
}

@Composable
private fun levelColor(level: PredictedDebitLevel): Color {
    return when (level) {
        PredictedDebitLevel.LOW -> MaterialTheme.colorScheme.primary
        PredictedDebitLevel.MEDIUM -> DebitCorrect
        PredictedDebitLevel.HIGH -> DebitTresGros
    }
}
