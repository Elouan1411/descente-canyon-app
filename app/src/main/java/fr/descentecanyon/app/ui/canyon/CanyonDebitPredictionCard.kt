package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
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
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.prediction_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PredictionSummaryLine(
                        predictions = predictions,
                        isLoading = isLoading,
                        error = error,
                        expanded = expanded,
                    )
                }
                TextButton(onClick = onInfoClick) {
                    Text(text = stringResource(R.string.prediction_info_cta))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.canyon_summary_collapse else R.string.canyon_summary_expand,
                    ),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}

@Composable
private fun PredictionSummaryLine(
    predictions: CanyonDebitPredictions?,
    isLoading: Boolean,
    error: String?,
    expanded: Boolean,
) {
    val todayPrediction = predictions?.predictions?.firstOrNull { it.horizonDays == 0 }
        ?: predictions?.predictions?.firstOrNull()
    val text = when {
        isLoading && predictions == null -> stringResource(R.string.prediction_loading)
        todayPrediction?.ordinalLevel != null -> "${stringResource(R.string.prediction_today)} : ${ordinalLevelTitle(todayPrediction.ordinalLevel)}"
        todayPrediction != null -> "${stringResource(R.string.prediction_today)} : ${levelLabel(todayPrediction.level)}"
        error != null -> error.ifBlank { stringResource(R.string.prediction_unavailable) }
        else -> stringResource(R.string.prediction_unavailable)
    }
    val color = when {
        error != null && !isLoading && predictions == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = if (expanded) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PredictionRow(
    prediction: DailyDebitPrediction,
    modifier: Modifier = Modifier,
) {
    val ordinalLevel = prediction.ordinalLevel
    val ordinalScore = prediction.ordinalScore

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
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

            Text(
                text = ordinalLevel?.let { ordinalLevelTitle(it) } ?: levelLabel(prediction.level),
                style = MaterialTheme.typography.titleSmall,
                color = ordinalLevel?.let { ordinalColor(it) } ?: Color.White,
                textAlign = TextAlign.End,
                modifier = if (ordinalLevel == null) {
                    Modifier
                        .background(levelColor(prediction.level), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    Modifier.width(140.dp)
                },
            )
        }

        if (ordinalLevel != null && ordinalScore != null) {
            DebitOrdinalGauge(score = ordinalScore)
        }
    }
}

@Composable
private fun DebitOrdinalGauge(
    score: Double,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(999.dp)
    val markerWidth = 6.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(trackShape)
                    .border(1.dp, gaugeTrackBorderColor(), trackShape),
            ) {
                ORDINAL_GAUGE_LEVELS.forEach { gaugeLevel ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(ordinalColor(gaugeLevel))
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - markerWidth) * (score.coerceIn(0.0, 5.0).toFloat() / 5f))
                    .width(markerWidth)
                    .height(26.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp)),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ORDINAL_GAUGE_LEVELS.forEach { gaugeLevel ->
                Text(
                    text = ordinalLevelShortLabel(gaugeLevel),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
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
private fun ordinalLevelTitle(level: NiveauDebit): String {
    return when (level) {
        NiveauDebit.SEC -> stringResource(R.string.prediction_ordinal_sec)
        NiveauDebit.FILET -> stringResource(R.string.prediction_ordinal_filet)
        NiveauDebit.CORRECT -> stringResource(R.string.prediction_ordinal_correct)
        NiveauDebit.GROS -> stringResource(R.string.prediction_ordinal_gros)
        NiveauDebit.TRES_GROS -> stringResource(R.string.prediction_ordinal_tres_gros)
        NiveauDebit.CRUE -> stringResource(R.string.prediction_ordinal_crue)
        NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
    }
}

@Composable
private fun ordinalLevelShortLabel(level: NiveauDebit): String {
    return when (level) {
        NiveauDebit.SEC -> stringResource(R.string.debit_level_sec)
        NiveauDebit.FILET -> stringResource(R.string.debit_level_filet_short)
        NiveauDebit.CORRECT -> stringResource(R.string.debit_level_correct)
        NiveauDebit.GROS -> stringResource(R.string.debit_level_gros)
        NiveauDebit.TRES_GROS -> stringResource(R.string.debit_level_tres_gros)
        NiveauDebit.CRUE -> stringResource(R.string.debit_level_crue_short)
        NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
    }
}

@Composable
private fun levelColor(level: PredictedDebitLevel): Color {
    return when (level) {
        PredictedDebitLevel.LOW -> MaterialTheme.colorScheme.primary
        PredictedDebitLevel.MEDIUM -> DebitCorrect
        PredictedDebitLevel.HIGH -> DebitVeryHighRed
    }
}

private fun ordinalColor(level: NiveauDebit): Color {
    return when (level) {
        NiveauDebit.SEC -> DebitSec
        NiveauDebit.FILET -> DebitFilet
        NiveauDebit.CORRECT -> DebitCorrect
        NiveauDebit.GROS -> DebitGros
        NiveauDebit.TRES_GROS -> DebitVeryHighRed
        NiveauDebit.CRUE -> Color.Black
        NiveauDebit.INCONNU -> DebitInconnu
    }
}

@Composable
private fun gaugeTrackBorderColor(): Color {
    return MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
}

private val ORDINAL_GAUGE_LEVELS = listOf(
    NiveauDebit.SEC,
    NiveauDebit.FILET,
    NiveauDebit.CORRECT,
    NiveauDebit.GROS,
    NiveauDebit.TRES_GROS,
    NiveauDebit.CRUE,
)

private val DebitVeryHighRed = Color(0xFFE53935)
