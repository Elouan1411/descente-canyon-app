package fr.descentecanyon.app.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.descentecanyon.app.data.network.ConnectivityObserver
import fr.descentecanyon.app.domain.usecase.SyncPendingDebitsUseCase
import fr.descentecanyon.app.ui.navigation.AppNavHost
import fr.descentecanyon.app.ui.navigation.BottomNavItem
import fr.descentecanyon.app.ui.navigation.Screen
import fr.descentecanyon.app.ui.theme.DescenteCanyonTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var syncPendingDebitsUseCase: SyncPendingDebitsUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            connectivityObserver.observe()
                .distinctUntilChanged()
                .collect { isOnline ->
                    if (isOnline) {
                        syncPendingDebitsUseCase()
                    }
                }
        }
        setContent {
            DescenteCanyonTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isTopLevelDestination = BottomNavItem.entries.any { item ->
        currentDestination?.hasRoute(item.screen::class) == true
    }
    val isDetailScreen = currentDestination?.hasRoute(Screen.CanyonDetail::class) == true

    val showBottomBar = isTopLevelDestination || isDetailScreen

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomNavItem.entries.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hasRoute(item.screen::class) == true,
                            onClick = {
                                val startDestinationId = navController.graph.findStartDestination().id
                                handleBottomNavClick(
                                    item = item,
                                    popToHome = {
                                        navController.popBackStack(startDestinationId, false)
                                    },
                                    navigate = { screen ->
                                        navController.navigate(screen) {
                                            popUpTo(startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { _ ->
        AppNavHost(
            navController = navController,
            modifier = Modifier,
        )
    }
}

internal fun handleBottomNavClick(
    item: BottomNavItem,
    popToHome: () -> Boolean,
    navigate: (Screen) -> Unit,
) {
    if (item == BottomNavItem.HOME && popToHome()) {
        return
    }

    navigate(item.screen)
}
