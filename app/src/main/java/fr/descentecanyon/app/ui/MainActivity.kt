package fr.descentecanyon.app.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.descentecanyon.app.startup.AppStartupCoordinator
import fr.descentecanyon.app.ui.navigation.AppNavHost
import fr.descentecanyon.app.ui.navigation.BottomNavItem
import fr.descentecanyon.app.ui.navigation.Screen
import fr.descentecanyon.app.ui.theme.DescenteCanyonTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appStartupCoordinator: AppStartupCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            appStartupCoordinator.observeConnectivity()
                .collect { isOnline ->
                    appStartupCoordinator.syncPendingDebitsIfOnline(isOnline)
                }
        }
        setContent {
            DescenteCanyonTheme {
                AppStartupScreen(appStartupCoordinator = appStartupCoordinator)
            }
        }
    }
}

@Composable
private fun AppStartupScreen(appStartupCoordinator: AppStartupCoordinator) {
    val startupState by produceState<StartupUiState>(initialValue = StartupUiState.Loading, key1 = appStartupCoordinator) {
        value = runCatching {
            appStartupCoordinator.initialize()
            StartupUiState.Ready
        }.getOrElse { throwable ->
            StartupUiState.Error(throwable.message ?: "Initialisation impossible")
        }
    }

    when (val state = startupState) {
        StartupUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        StartupUiState.Ready -> MainScreen()

        is StartupUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.message)
            }
        }
    }
}

private sealed interface StartupUiState {
    data object Loading : StartupUiState
    data object Ready : StartupUiState
    data class Error(val message: String) : StartupUiState
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
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        windowInsets = NavigationBarDefaults.windowInsets,
                    ) {
                        BottomNavItem.entries.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
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
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            topLevelContentPadding = innerPadding,
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
