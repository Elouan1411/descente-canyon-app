package fr.descentecanyon.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import fr.descentecanyon.app.ui.canyon.CanyonDetailScreen
import fr.descentecanyon.app.ui.favorites.FavoritesScreen
import fr.descentecanyon.app.ui.home.HomeScreen
import fr.descentecanyon.app.ui.map.MapScreen
import fr.descentecanyon.app.ui.search.SearchScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home,
        modifier = modifier,
    ) {
        composable<Screen.Home> {
            HomeScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
            )
        }

        composable<Screen.Search> {
            SearchScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
            )
        }

        composable<Screen.Map> {
            MapScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
            )
        }

        composable<Screen.Favorites> {
            FavoritesScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
            )
        }

        composable<Screen.CanyonDetail> { backStackEntry ->
            val detail = backStackEntry.toRoute<Screen.CanyonDetail>()
            CanyonDetailScreen(
                canyonId = detail.canyonId,
                onBackClick = { navController.popBackStack() },
            )
        }
    }
}
