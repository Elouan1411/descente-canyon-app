package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary

@Composable
fun SelectedCanyonSheetContent(
    canyon: CanyonSummary,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = canyon.nom,
            style = MaterialTheme.typography.headlineSmall,
        )
        CanyonSummaryCard(
            canyon = canyon,
            onClick = onOpen,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOpen) {
                Text(stringResource(R.string.map_bottom_sheet_open))
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.close))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
