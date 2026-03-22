package fr.descentecanyon.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val screen: Screen,
) {
    HOME("Accueil", Icons.Default.Home, Screen.Home),
    SEARCH("Rechercher", Icons.Default.Search, Screen.Search),
    MAP("Carte", Icons.Default.Explore, Screen.Map),
    FAVORITES("Favoris", Icons.Default.Favorite, Screen.Favorites),
}
