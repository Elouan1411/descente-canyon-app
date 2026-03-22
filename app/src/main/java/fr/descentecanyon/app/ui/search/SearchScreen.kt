package fr.descentecanyon.app.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SearchScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Rechercher un canyon",
            style = MaterialTheme.typography.headlineMedium,
        )
        // TODO: Search bar + results list
        // - Text field with debounce
        // - Filter chips (pays, cotation, debit)
        // - LazyColumn of CanyonSummary cards
    }
}
