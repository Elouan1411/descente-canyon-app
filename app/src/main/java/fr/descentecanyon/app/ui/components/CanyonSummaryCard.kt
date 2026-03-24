package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.theme.CotationDifficile
import fr.descentecanyon.app.ui.theme.CotationFacile
import fr.descentecanyon.app.ui.theme.CotationMoyen
import fr.descentecanyon.app.ui.theme.CotationTresDifficile
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.util.Locale

@Composable
fun CanyonSummaryCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                    fun String?.cleanLocationPart(): String? {
                        return this
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) && it != "-" }
                    }

                    Text(
                        text = canyon.nom,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val location = listOf(
                        canyon.pays.cleanLocationPart(),
                        canyon.departement.cleanLocationPart(),
                    ).joinToString(" - ")
                    Text(
                        text = location.ifBlank { stringResource(R.string.search_location_unknown) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                canyon.cotation.takeIf { it.isNotBlank() }?.let {
                    CotationBadge(cotation = it)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canyon.isForbidden) {
                    ForbiddenBadge()
                } else {
                    canyon.interet?.let { interest ->
                        InterestStars(interest = interest)
                    }
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
private fun ForbiddenBadge(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        ),
    ) {
        Text(
            text = stringResource(R.string.regulation_forbidden),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
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
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Text(
            text = cotation,
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
    val clamped = interest.coerceIn(0f, 4f)
    val fullStars = clamped.toInt()
    val hasHalfStar = (clamped - fullStars) >= 0.5f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(4) { index ->
            val icon = when {
                index < fullStars -> Icons.Filled.Star
                index == fullStars && hasHalfStar -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Outlined.StarOutline
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (index < fullStars || (index == fullStars && hasHalfStar)) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f", clamped),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun DebitBadge(
    niveau: NiveauDebit,
    modifier: Modifier = Modifier,
) {
    val color = when (niveau) {
        NiveauDebit.SEC -> DebitSec
        NiveauDebit.FILET -> DebitFilet
        NiveauDebit.CORRECT -> DebitCorrect
        NiveauDebit.GROS -> DebitGros
        NiveauDebit.TRES_GROS -> DebitTresGros
        NiveauDebit.CRUE -> DebitCrue
        NiveauDebit.INCONNU -> DebitInconnu
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Text(
            text = niveau.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
