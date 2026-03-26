package fr.descentecanyon.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.ui.components.CanyonSummaryCard

@Composable
internal fun NearbyCanyonCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CanyonSummaryCard(
        canyon = canyon,
        onClick = onClick,
        modifier = modifier,
    )
}
