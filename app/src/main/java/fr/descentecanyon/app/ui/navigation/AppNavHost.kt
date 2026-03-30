package fr.descentecanyon.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import fr.descentecanyon.app.ui.canyon.CanyonDetailScreen
import fr.descentecanyon.app.ui.canyon.CanyonPointsMapScreen
import fr.descentecanyon.app.ui.canyon.PhotoGalleryScreen
import fr.descentecanyon.app.ui.debit.DebitFormScreen
import fr.descentecanyon.app.ui.favorites.FavoritesScreen
import fr.descentecanyon.app.ui.home.HomeScreen
import fr.descentecanyon.app.ui.map.MapScreen
import fr.descentecanyon.app.ui.search.SearchScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    topLevelContentPadding: PaddingValues = PaddingValues(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home,
        modifier = Modifier,
    ) {
        composable<Screen.Home> {
            HomeScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
                onQuickSearchClick = {
                    navController.navigate(Screen.Search)
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Search> {
            SearchScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Map> {
            MapScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Favorites> {
            FavoritesScreen(
                onCanyonClick = { canyonId ->
                    navController.navigate(Screen.CanyonDetail(canyonId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.CanyonDetail> { backStackEntry ->
            val detail = backStackEntry.toRoute<Screen.CanyonDetail>()
            CanyonDetailScreen(
                canyonId = detail.canyonId,
                onBackClick = { navController.popBackStack() },
                onReportDebitClick = { navController.navigate(Screen.DebitForm(detail.canyonId)) },
                onShowMapClick = { navController.navigate(Screen.CanyonPointsMap(detail.canyonId)) },
                onOpenPhotoGallery = { photoId ->
                    navController.navigate(Screen.PhotoGallery(detail.canyonId, photoId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.PhotoGallery> {
            PhotoGalleryScreen(
                onBackClick = { navController.popBackStack() },
            )
        }

        composable<Screen.CanyonPointsMap> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.CanyonPointsMap>()
            CanyonPointsMapScreen(
                canyonId = route.canyonId,
                onBackClick = { navController.popBackStack() },
            )
        }

        composable<Screen.DebitForm> {
            DebitFormScreen(
                onBackClick = { navController.popBackStack() },
            )
        }
    }
}
