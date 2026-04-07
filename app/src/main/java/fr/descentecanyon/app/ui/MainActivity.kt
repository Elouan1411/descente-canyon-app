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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.startup.AppStartupCoordinator
import fr.descentecanyon.app.startup.StartupLaunchMode
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
        PerformanceTrace.logEvent(
            event = "main_activity_created",
            "isColdStart" to (savedInstanceState == null),
        )
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
    val startupState by produceState<StartupUiState>(initialValue = StartupUiState.Resolving, key1 = appStartupCoordinator) {
        value = runCatching {
            when (appStartupCoordinator.resolveLaunchMode()) {
                StartupLaunchMode.NON_BLOCKING -> StartupUiState.AppVisible(isBackgroundInitializing = true)
                StartupLaunchMode.BLOCKING_IMPORT -> {
                    PerformanceTrace.logEvent("startup_blocking_ui_required")
                    runStartupInitialization(appStartupCoordinator)
                    StartupUiState.AppVisible(isBackgroundInitializing = false)
                }
            }
        }.getOrElse { throwable ->
            StartupUiState.Error(throwable.message ?: "Initialisation impossible")
        }
    }

    when (val state = startupState) {
        StartupUiState.Resolving -> {
            LaunchedEffect(Unit) {
                PerformanceTrace.logEvent("startup_loading_ui_visible")
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is StartupUiState.AppVisible -> {
            if (state.isBackgroundInitializing) {
                LaunchedEffect(appStartupCoordinator) {
                    runCatching {
                        runStartupInitialization(appStartupCoordinator)
                    }.onFailure { throwable ->
                        PerformanceTrace.logEvent(
                            event = "startup_background_initialize_failed",
                            "error" to (throwable.message ?: throwable::class.simpleName),
                        )
                    }
                }
            }
            MainScreen()
        }

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
    data object Resolving : StartupUiState
    data class AppVisible(val isBackgroundInitializing: Boolean) : StartupUiState
    data class Error(val message: String) : StartupUiState
}

@Composable
private fun MainScreen() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        PerformanceTrace.logEvent("main_screen_visible")
    }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val selectedBottomNavItem = resolveSelectedBottomNavItem(navBackStackEntry)
    val showBottomBar = shouldShowBottomBar(navBackStackEntry)

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
                                selected = item == selectedBottomNavItem,
                                onClick = {
                                    val startDestinationId = navController.graph.findStartDestination().id
                                    handleBottomNavClick(
                                        item = item,
                                        popToHome = {
                                            navController.popBackStack(startDestinationId, false)
                                        },
                                        navigate = { screen ->
                                            navController.navigate(screen) {
                                                applyBottomNavRootNavigation(startDestinationId)
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

internal fun resolveSelectedBottomNavItem(backStackEntry: NavBackStackEntry?): BottomNavItem? {
    val destination = backStackEntry?.destination ?: return null
    return when {
        destination.hasRoute(Screen.Home::class) -> BottomNavItem.HOME
        destination.hasRoute(Screen.Search::class) -> BottomNavItem.SEARCH
        destination.hasRoute(Screen.Map::class) -> BottomNavItem.MAP
        destination.hasRoute(Screen.Favorites::class) -> BottomNavItem.FAVORITES
        else -> null
    }
}

internal fun selectedBottomNavItemForScreen(screen: Screen): BottomNavItem? {
    return when (screen) {
        Screen.Home -> BottomNavItem.HOME
        Screen.Search -> BottomNavItem.SEARCH
        Screen.Map -> BottomNavItem.MAP
        Screen.Favorites -> BottomNavItem.FAVORITES
        else -> null
    }
}

internal fun shouldShowBottomBar(backStackEntry: NavBackStackEntry?): Boolean {
    val destination = backStackEntry?.destination ?: return false
    return destination.hasRoute(Screen.Home::class) ||
        destination.hasRoute(Screen.Search::class) ||
        destination.hasRoute(Screen.Map::class) ||
        destination.hasRoute(Screen.Favorites::class) ||
        destination.hasRoute(Screen.CanyonDetail::class) ||
        destination.hasRoute(Screen.CanyonPointsMap::class)
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

internal fun NavOptionsBuilder.applyBottomNavRootNavigation(startDestinationId: Int) {
    popUpTo(startDestinationId)
    launchSingleTop = true
}

private const val STARTUP_INITIALIZE_TRACE_KEY = "startup.initialize"

private suspend fun runStartupInitialization(appStartupCoordinator: AppStartupCoordinator) {
    PerformanceTrace.start(key = STARTUP_INITIALIZE_TRACE_KEY, event = "startup_initialize")
    try {
        appStartupCoordinator.initialize()
        PerformanceTrace.end(key = STARTUP_INITIALIZE_TRACE_KEY, outcome = "ready")
    } catch (throwable: Throwable) {
        PerformanceTrace.end(
            key = STARTUP_INITIALIZE_TRACE_KEY,
            outcome = "failed",
            "error" to (throwable.message ?: throwable::class.simpleName),
        )
        throw throwable
    }
}
