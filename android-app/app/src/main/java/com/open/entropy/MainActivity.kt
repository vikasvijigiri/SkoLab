package com.open.entropy

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
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
import com.open.entropy.ui.screens.SearchScreen
import com.open.entropy.ui.screens.SplashScreen
import com.open.entropy.ui.screens.ChatRoomScreen
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

    val mainTabs = listOf("discover", "search", "library", "nexus")
    val dockItems = listOf(
        DockItem("discover", Icons.Outlined.AutoAwesome, "Discover"),
        DockItem("search", Icons.Outlined.Search, "Papers"),
        DockItem("library", Icons.Outlined.BookmarkBorder, "Vault"),
        DockItem("nexus", Icons.Outlined.Hub, "Nexus")
    )

    ResQitScaffold { innerPadding ->
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (currentRoute in mainTabs) {
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
                                navController.navigate("paper_detail/$paperId")
                            },
                            onProfileClick = {
                                navController.navigate("profile")
                            },
                            onNavigateToChat = { name, id ->
                                navController.navigate("chat/${name.encodeForRoute()}/${id.encodeForRoute()}")
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
                            }
                        )
                    }
                }
                composable(
                    route = "search",
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
                        SearchScreen(
                            onPaperClick = { paperId -> navController.navigate("paper_detail/$paperId") },
                            onAuthorClick = { authorName -> navController.navigate("author_detail/${authorName.encodeForRoute()}") }
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
                        LibraryScreen(onPaperClick = { paperId -> navController.navigate("paper_detail/$paperId") })
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
                    val paperId = backStackEntry.arguments?.getString("paperId") ?: ""
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
                            onPaperClick = { paperId -> navController.navigate("paper_detail/$paperId") }
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
            }
        }
    }
}

private fun String.encodeForRoute(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decodeFromRoute(): String = java.net.URLDecoder.decode(this, "UTF-8")
