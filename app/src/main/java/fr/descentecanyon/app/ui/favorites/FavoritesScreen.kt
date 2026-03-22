package fr.descentecanyon.app.ui.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FavoritesScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Mes favoris",
            style = MaterialTheme.typography.headlineMedium,
        )
        // TODO: LazyColumn of favorite canyons
        // - Offline indicator
        // - Quick access to last debits
        // - Swipe to remove
    }
}
