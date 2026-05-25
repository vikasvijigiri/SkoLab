package com.open.entropy

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.open.entropy.auth.AuthManager
import com.open.entropy.data.UserPreferences
import com.open.entropy.ui.components.primitives.BottomNavDock
import com.open.entropy.ui.components.primitives.DockItem
import com.open.entropy.ui.components.primitives.ResQitScaffold
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.layout.screenSafeArea
import com.open.entropy.ui.screens.ArticleReaderScreen
import com.open.entropy.ui.screens.AuthScreen
import com.open.entropy.ui.screens.AuthorDetailScreen
import com.open.entropy.ui.screens.DiscoveryScreen
import com.open.entropy.ui.screens.FeedScreen
import com.open.entropy.ui.screens.ProfileScreen
import com.open.entropy.ui.screens.LibraryScreen
import com.open.entropy.ui.screens.NexusScreen
import com.open.entropy.ui.screens.OnboardingScreen
import com.open.entropy.ui.screens.PaperDetailScreen
import com.open.entropy.ui.screens.PapersScreen
import com.open.entropy.ui.screens.SearchScreen
import com.open.entropy.ui.screens.PaperCollabsScreen
import com.open.entropy.ui.screens.SplashScreen
import com.open.entropy.ui.screens.ChatRoomScreen
import com.open.entropy.ui.screens.ChatListScreen
import com.open.entropy.ui.screens.LogicEngineScreen
import com.open.entropy.ui.screens.ProWorkspaceScreen
import com.open.entropy.ui.screens.DailyDiscoveryScreen
import com.open.entropy.ui.screens.CoLabWorkspaceScreen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import com.open.entropy.ui.screens.BrainstormingScreen
import com.open.entropy.ui.theme.ResQitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            ResQitTheme {
                ResQitMainApp()
            }
        }
    }
}

@Composable
fun ResQitMainApp() {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val hasSeenOnboarding by userPrefs.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = false)
    var isFeedLoading by remember { mutableStateOf(true) }
    var isLlmActive by remember { mutableStateOf(true) }
    val apiService = remember { com.open.entropy.network.ApiService() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while(true) {
            isLlmActive = apiService.checkAiStatus()
            kotlinx.coroutines.delay(30000)
        }
    }

    val mainTabs = listOf("discover", "collabs", "papers", "nexus", "profile")
    val dockItems = listOf(
        DockItem("discover", Icons.Filled.Hub, "Home", badgeCount = 1),
        DockItem("collabs", Icons.Filled.Groups, "Collabs", hasBadgeDot = true),
        DockItem("brainstorm", Icons.Filled.Lightbulb, "Brainstorm"),
        DockItem("nexus", Icons.Filled.AutoGraph, "Network"),
        DockItem("profile", Icons.Outlined.Person, "You")
    )

    ResQitScaffold { innerPadding ->
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !isLlmActive,
                enter = androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE53935))
                        .statusBarsPadding()
                        .padding(top = 8.dp, bottom = 8.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "AI Offline",
                            tint = Color.White,
                            modifier = Modifier.padding(end = 8.dp).size(16.dp)
                        )
                        androidx.compose.material3.Text(
                            text = "AI Services Offline (Credits Exhausted)",
                            color = Color.White,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        val showBottomBar = currentRoute in mainTabs
                        if (showBottomBar) {
                            BottomNavDock(
                                items = dockItems,
                                currentRoute = currentRoute,
                                onItemClick = { item ->
                                    navController.navigate(item.route) {
                                        popUpTo("discover") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { scaffoldPadding ->
                    NavHost(
                navController = navController,
                startDestination = "splash",
                modifier = Modifier.fillMaxSize()
            ) {
                composable(
                    route = "splash",
                    exitTransition = {
                        fadeOut(animationSpec = tween(400, easing = EaseOutCubic))
                    }
                ) {
                    SplashScreen(
                        onAnimationFinished = {
                            val next = when {
                                !hasSeenOnboarding -> "onboarding"
                                !authManager.isSignedIn -> "auth"
                                else -> "discover"
                            }
                            navController.navigate(next) {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    )
                }
                composable(
                    route = "onboarding",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        OnboardingScreen(
                            onFinish = {
                                scope.launch { userPrefs.setOnboardingComplete() }
                                val next = if (authManager.isSignedIn) "discover" else "auth"
                                navController.navigate(next) {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }
                }
                composable(
                    route = "auth",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        AuthScreen(
                            onAuthSuccess = {
                                navController.navigate("discover") {
                                    popUpTo("auth") { inclusive = true }
                                }
                            }
                        )
                    }
                }
                composable(
                    route = "discover",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        FeedScreen(
                            onPaperClick = { paperId ->
                                navController.navigate("paper_detail/${paperId.encodeForRoute()}")
                            },
                            onProfileClick = {
                                navController.navigate("profile")
                            },
                            onNavigateToChat = { name, id ->
                                navController.navigate("chat/${name.encodeForRoute()}/${id.encodeForRoute()}")
                            },
                            onNavigateToChatList = {
                                navController.navigate("chat_list")
                            },
                            onNavigateToReader = { title, doi ->
                                navController.navigate("reader/${title.encodeForRoute()}/${doi.encodeForRoute()}")
                            },
                            onTabNavigate = { route ->
                                navController.navigate(route) {
                                    popUpTo("discover") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onAuthorClick = { authorName ->
                                navController.navigate("author_detail/${authorName.encodeForRoute()}")
                            },
                            onLoadingStateChanged = { isFeedLoading = it },
                            onNavigateToLogicEngine = {
                                navController.navigate("logic_engine")
                            }
                        )
                    }
                }
                composable(
                    route = "papers",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        PapersScreen(
                            onPaperClick = { paperId ->
                                navController.navigate("paper_detail/${paperId.encodeForRoute()}")
                            },
                            onProfileClick = {
                                navController.navigate("profile")
                            },
                            onNavigateToReader = { title, doi ->
                                navController.navigate("reader/${title.encodeForRoute()}/${doi.encodeForRoute()}")
                            },
                            onTabNavigate = { route ->
                                navController.navigate(route) {
                                    popUpTo("discover") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
                composable(
                    route = "brainstorm",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        BrainstormingScreen(
                            onSaveIdea = { /* To be implemented */ }
                        )
                    }
                }
                composable(
                    route = "profile",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        ProfileScreen(
                            onBack = {
                                navController.popBackStack()
                            },
                            onNavigateToProWorkspace = {
                                navController.navigate("pro_workspace")
                            }
                        )
                    }
                }
                composable(
                    route = "pro_workspace",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        ProWorkspaceScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
                composable(
                    route = "collabs",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        PaperCollabsScreen(
                            onNavigateToChat = { peerName, peerId ->
                                navController.navigate("chat/${peerName.encodeForRoute()}/${peerId.encodeForRoute()}")
                            },
                            onNavigateToWorkspace = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
                            }
                        )
                    }
                }
                composable(
                    route = "library",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        LibraryScreen(onPaperClick = { paperId -> navController.navigate("paper_detail/${paperId.encodeForRoute()}") })
                    }
                }
                composable(
                    route = "nexus",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = false)
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        NexusScreen()
                    }
                }
                composable(
                    route = "paper_detail/{paperId}",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val paperId = backStackEntry.arguments?.getString("paperId")?.decodeFromRoute() ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        PaperDetailScreen(
                            paperId = paperId,
                            onBack = { navController.popBackStack() },
                            onAuthorClick = { authorName -> navController.navigate("author_detail/${authorName.encodeForRoute()}") }
                        )
                    }
                }
                composable(
                    route = "author_detail/{authorName}",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val authorName = backStackEntry.arguments?.getString("authorName")?.decodeFromRoute() ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        AuthorDetailScreen(
                            authorName = authorName,
                            onBack = { navController.popBackStack() },
                            onPaperClick = { paperId -> navController.navigate("paper_detail/${paperId.encodeForRoute()}") }
                        )
                    }
                }
                composable(
                    route = "reader/{title}/{url}",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val title = backStackEntry.arguments?.getString("title")?.decodeFromRoute() ?: ""
                    val urlArg = backStackEntry.arguments?.getString("url")?.decodeFromRoute() ?: ""
                    val url = if (urlArg.startsWith("http")) urlArg else "https://doi.org/$urlArg"
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        ArticleReaderScreen(
                            url = url,
                            title = title,
                            onClose = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "chat/{peerName}/{peerId}",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val peerName = backStackEntry.arguments?.getString("peerName")?.decodeFromRoute() ?: ""
                    val peerId = backStackEntry.arguments?.getString("peerId")?.decodeFromRoute() ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        ChatRoomScreen(
                            peerName = peerName,
                            peerId = peerId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "chat_list",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        ChatListScreen(
                            onChatClick = { peerName, peerId ->
                                navController.navigate("chat/${peerName.encodeForRoute()}/${peerId.encodeForRoute()}")
                            },
                            onCoLabClick = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "colab_workspace/{projectName}",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val projectName = backStackEntry.arguments?.getString("projectName")?.decodeFromRoute() ?: "Project Nexus"
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        CoLabWorkspaceScreen(
                            projectName = projectName,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "logic_engine",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        LogicEngineScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "daily_discovery",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .screenSafeArea(includeBottom = true)
                            .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    ) {
                        DailyDiscoveryScreen(
                            onBack = { navController.popBackStack() },
                            onPaperSaved = { paperId ->
                                // no-op for now, would typically save to Vault
                            }
                        )
                    }
                }
                    }
                }
            }
        }
    }
}

private fun String.encodeForRoute(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decodeFromRoute(): String = java.net.URLDecoder.decode(this, "UTF-8")
