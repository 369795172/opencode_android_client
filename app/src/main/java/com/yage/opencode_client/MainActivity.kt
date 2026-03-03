package com.yage.opencode_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.chat.ChatScreen
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.settings.SettingsScreen
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import com.yage.opencode_client.ui.theme.compactTypography
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
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.testConnection()
                }
            }
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
                    TabletLayout(viewModel = viewModel, repository = repository)
                } else {
                    PhoneLayout(viewModel = viewModel, repository = repository)
                }
            }
        }
    }
}

@Composable
private fun PhoneLayout(viewModel: MainViewModel, repository: OpenCodeRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Chat.route) {
                                        saveState = true
                                        inclusive = (screen.route == Screen.Chat.route)
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
                    viewModel = viewModel,
                    onNavigateToFiles = { path ->
                        viewModel.showFileInFiles(path)
                        navController.navigate(Screen.Files.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    showSettingsButton = false
                )
            }
            composable(Screen.Files.route) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                FilesScreen(
                    repository = repository,
                    pathToShow = state.filePathToShowInFiles,
                    sessionDirectory = state.currentSession?.directory,
                    onCloseFile = { viewModel.clearFileToShow() },
                    onFileClick = { }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletLayout(viewModel: MainViewModel, repository: OpenCodeRepository) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val onOpenSettings: () -> Unit = { selectedTab = 1 }
    val state by viewModel.state.collectAsStateWithLifecycle()

        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
        // Left panel: Session list or Settings — 25% (no tabs on tablet)
        Column(
            modifier = Modifier
                .weight(0.25f)
                .fillMaxHeight()
        ) {
            if (selectedTab == 1) {
                SettingsScreen(
                    onBack = { selectedTab = 0 }
                )
            } else {
                SessionList(
                    sessions = state.sessions,
                    currentSessionId = state.currentSessionId,
                    onSelectSession = { viewModel.selectSession(it) },
                    onCreateSession = { viewModel.createSession() },
                    onOpenSettings = { selectedTab = 1 }
                )
            }
        }

        VerticalDivider()

        // Middle panel: FilesScreen (file preview) — 37.5%
        Column(
            modifier = Modifier
                .weight(0.375f)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                FilesScreen(
                repository = repository,
                pathToShow = state.filePathToShowInFiles,
                sessionDirectory = state.currentSession?.directory,
                onCloseFile = { viewModel.clearFileToShow() },
                onFileClick = { }
            )
            }
        }

        VerticalDivider()

        // Right panel: Chat — 37.5%
        Column(
            modifier = Modifier
                .weight(0.375f)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToFiles = { path ->
                        viewModel.showFileInFiles(path)
                    },
                    onNavigateToSettings = onOpenSettings,
                    showSettingsButton = false,
                    showNewSessionInTopBar = false,
                    showSessionListInTopBar = false
                )
            }
        }
    }
}

private const val SESSION_PAGE_SIZE = 20

@Composable
private fun SessionList(
    sessions: List<Session>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    var displayedCount by remember { mutableStateOf(SESSION_PAGE_SIZE) }
    val sessionsToShow = sessions.take(displayedCount)
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastVisible ->
            if (lastVisible >= sessionsToShow.size - 2 && sessionsToShow.size < sessions.size) {
                displayedCount = (displayedCount + SESSION_PAGE_SIZE).coerceAtMost(sessions.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCreateSession) {
                    Text("New")
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            itemsIndexed(sessionsToShow, key = { _, s -> s.id }) { index, session ->
                val isSelected = session.id == currentSessionId
                val altBg = index % 2 == 1
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (altBg) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelectSession(session.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (index < sessionsToShow.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            if (sessionsToShow.size < sessions.size) {
                item(key = "load_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            "Loading more...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
