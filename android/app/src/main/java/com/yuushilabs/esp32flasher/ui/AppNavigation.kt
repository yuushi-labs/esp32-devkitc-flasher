package com.yuushilabs.esp32flasher.ui

import android.app.Application
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yuushilabs.esp32flasher.AppContainer

object Routes {
    const val FLASHER = "flasher"
    const val MONITOR = "monitor"
    const val SETTINGS = "settings"
}

class AppViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(FlasherViewModel::class.java) ->
            FlasherViewModel(application, container)

        modelClass.isAssignableFrom(MonitorViewModel::class.java) ->
            MonitorViewModel(application, container)

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(application, container)

        else -> throw IllegalArgumentException("未知の ViewModel: ${modelClass.name}")
    } as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Esp32FlasherRoot(container: AppContainer) {
    val application = LocalContext.current.applicationContext as Application
    val factory = remember(container, application) { AppViewModelFactory(application, container) }
    val flasherViewModel: FlasherViewModel = viewModel(factory = factory)
    val monitorViewModel: MonitorViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.FLASHER

    Scaffold(
        // The window is edge-to-edge, so the IME inset is applied here instead of relying on
        // adjustResize: the navigation bar and the content above it lift over the keyboard.
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(titleFor(currentRoute)) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            NavigationBar {
                BottomDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateSingleTop(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FLASHER,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.FLASHER) {
                FlasherScreen(
                    viewModel = flasherViewModel,
                    snackbarHostState = snackbarHostState,
                    onOpenMonitor = { navController.navigateSingleTop(Routes.MONITOR) },
                )
            }
            composable(Routes.MONITOR) {
                SerialMonitorScreen(monitorViewModel, snackbarHostState)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settingsViewModel, snackbarHostState)
            }
        }
    }
}

private enum class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    FLASHER(Routes.FLASHER, "書き込み", Icons.Filled.Memory),
    MONITOR(Routes.MONITOR, "モニター", Icons.Filled.Terminal),
    SETTINGS(Routes.SETTINGS, "設定", Icons.Filled.Settings),
}

private fun titleFor(route: String): String = when (route) {
    Routes.MONITOR -> "シリアルモニター"
    Routes.SETTINGS -> "設定"
    else -> "ESP32 Flasher"
}

private fun NavController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
