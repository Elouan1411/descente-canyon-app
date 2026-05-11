package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.test.TestTags
import fr.descentecanyon.app.ui.theme.CotationDifficile
import fr.descentecanyon.app.ui.theme.CotationFacile
import fr.descentecanyon.app.ui.theme.CotationMoyen
import fr.descentecanyon.app.ui.theme.CotationTresDifficile

@Composable
fun CanyonSummaryCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(TestTags.canyonCard(canyon.id)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = canyon.nom,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val location = buildString {
                        append(canyon.pays)
                        canyon.departement?.let { append(" - $it") }
                    }
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (canyon.isForbidden) {
                    ForbiddenBadge()
                } else {
                    CotationBadge(cotation = canyon.cotation)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                canyon.interet?.let { interest ->
                    InterestStars(interest = interest)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    canyon.dernierDebit?.let { niveau ->
                        DebitBadge(niveau = niveau)
                    }
                    if (canyon.isOffline) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.offline_available),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CotationBadge(
    cotation: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val color = when {
        cotation.startsWith("v1") || cotation.startsWith("V1") -> CotationFacile
        cotation.startsWith("v2") || cotation.startsWith("V2") -> CotationFacile
        cotation.startsWith("v3") || cotation.startsWith("V3") -> CotationMoyen
        cotation.startsWith("v4") || cotation.startsWith("V4") -> CotationDifficile
        cotation.startsWith("v5") || cotation.startsWith("V5") -> CotationTresDifficile
        cotation.startsWith("v6") || cotation.startsWith("V6") -> CotationTresDifficile
        else -> MaterialTheme.colorScheme.outline
    }
    val textStyle = if (large) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.labelLarge
    }
    SummaryBadge(
        text = cotation,
        color = color,
        textStyle = textStyle,
        modifier = modifier,
    )
}

@Composable
fun ForbiddenBadge(
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val textStyle = if (large) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.labelLarge
    }
    SummaryBadge(
        text = stringResource(R.string.canyon_badge_forbidden),
        color = MaterialTheme.colorScheme.error,
        textStyle = textStyle,
        modifier = modifier,
    )
}

@Composable
private fun SummaryBadge(
    text: String,
    color: Color,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Text(
            text = text,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun InterestStars(
    interest: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val clampedInterest = interest.coerceIn(0f, 4f)
        val fullStars = clampedInterest.toInt()
        val hasHalf = (clampedInterest - fullStars) >= 0.5f
        val emptyStars = 4 - fullStars - if (hasHalf) 1 else 0
        repeat(fullStars) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        if (hasHalf) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.StarHalf,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        repeat(emptyStars) {
            Icon(
                imageVector = Icons.Default.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = String.format(Locale.US, "%.1f/4", clampedInterest),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DebitBadge(
    niveau: NiveauDebit,
    modifier: Modifier = Modifier,
) {
    val color = debitLevelColor(niveau)
    val isCrue = niveau == NiveauDebit.CRUE
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isCrue) color else color.copy(alpha = 0.12f),
        ),
        border = if (isCrue) BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)) else null,
    ) {
        Text(
            text = niveau.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isCrue) Color.White else color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
