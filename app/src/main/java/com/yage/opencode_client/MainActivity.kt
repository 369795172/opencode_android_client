package com.yage.opencode_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.chat.ChatScreen
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.settings.SettingsScreen
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Chat : Screen(
        "chat",
        "Chat",
        Icons.Default.Chat,
        Icons.Outlined.ChatBubbleOutline
    )

    object Files : Screen(
        "files",
        "Files",
        Icons.Default.Folder,
        Icons.Outlined.Folder
    )

    object Settings : Screen(
        "settings",
        "Settings",
        Icons.Default.Settings,
        Icons.Outlined.Settings
    )
}

val screens = listOf(Screen.Chat, Screen.Files, Screen.Settings)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repository: OpenCodeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val darkTheme = when (state.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val windowSizeClass = calculateWindowSizeClass(this)
            val isTablet = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

            OpenCodeTheme(darkTheme = darkTheme) {
                if (isTablet) {
                    TabletLayout(repository = repository)
                } else {
                    PhoneLayout(repository = repository)
                }
            }
        }
    }
}

@Composable
private fun PhoneLayout(repository: OpenCodeRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    onNavigateToFiles = { path ->
                        navController.navigate(Screen.Files.route)
                    }
                )
            }
            composable(Screen.Files.route) {
                FilesScreen(
                    repository = repository,
                    onFileClick = { path ->
                        // Could show file in a viewer or do nothing
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun TabletLayout(repository: OpenCodeRepository) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel: Workspace (Files + Settings)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            var showSettings by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        if (showSettings) "Settings" else "Files",
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            if (showSettings) Icons.Default.Folder else Icons.Default.Settings,
                            contentDescription = if (showSettings) "Show Files" else "Show Settings"
                        )
                    }
                }
            }

            if (showSettings) {
                SettingsScreen()
            } else {
                FilesScreen(
                    repository = repository,
                    onFileClick = { }
                )
            }
        }

        VerticalDivider()

        // Middle panel: File preview (shared files screen with preview focus)
        Column(
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight()
        ) {
            FilesScreen(
                repository = repository,
                onFileClick = { }
            )
        }

        VerticalDivider()

        // Right panel: Chat
        Column(
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight()
        ) {
            ChatScreen(
                onNavigateToFiles = { }
            )
        }
    }
}
