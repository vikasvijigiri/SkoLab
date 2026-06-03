package com.open.skolab

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
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.open.skolab.auth.AuthManager
import com.open.skolab.data.UserPreferences
import com.open.skolab.di.AppDependencies
import com.open.skolab.ui.components.primitives.BottomNavDock
import com.open.skolab.ui.components.primitives.DockItem
import com.open.skolab.ui.components.primitives.SkoLabScaffold
import com.open.skolab.ui.layout.ScreenInsets
import com.open.skolab.ui.layout.screenSafeArea
import com.open.skolab.ui.screens.ArticleReaderScreen
import com.open.skolab.ui.screens.AuthScreen
import com.open.skolab.ui.screens.AuthorDetailScreen
import com.open.skolab.ui.screens.DiscoveryScreen
import com.open.skolab.ui.screens.FeedScreen
import com.open.skolab.ui.screens.ProfileScreen
import com.open.skolab.ui.screens.LibraryScreen
import com.open.skolab.ui.screens.MetricsScreen
import com.open.skolab.ui.screens.OnboardingScreen
import com.open.skolab.ui.screens.PaperDetailScreen
import com.open.skolab.ui.screens.PapersScreen
import com.open.skolab.ui.screens.SearchScreen
import com.open.skolab.ui.screens.PaperCollabsScreen
import com.open.skolab.ui.screens.SplashScreen
import com.open.skolab.ui.screens.ChatRoomScreen
import com.open.skolab.ui.screens.ChatListScreen
import com.open.skolab.ui.screens.LogicEngineScreen
import com.open.skolab.ui.screens.ProWorkspaceScreen
import com.open.skolab.ui.screens.DailyDiscoveryScreen
import com.open.skolab.ui.screens.CoLabWorkspaceScreen
import com.open.skolab.ui.screens.ProfileSetupScreen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.layout.ime
import com.open.skolab.ui.screens.AgentScreen
import com.open.skolab.ui.theme.SkoLabTheme
import com.open.skolab.ui.theme.EntropiColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import com.open.skolab.ui.components.primitives.GlassSearchBar
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.Forum


class MainActivity : ComponentActivity() {
    companion object {
        var isInitializing = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { isInitializing }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#000000")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#000000"))
        )
        super.onCreate(savedInstanceState)

        // Remove and disable Firestore offline cache/persistence globally
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(com.google.firebase.firestore.memoryCacheSettings {})
                .build()
            db.firestoreSettings = settings
            db.clearPersistence()
            android.util.Log.i("MainActivity", "Firestore offline cache successfully disabled & cleared.")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to disable Firestore persistence", e)
        }

        setContent {
            SkoLabTheme {
                SkoLabMainApp()
            }
        }
    }
}

@Composable
fun SkoLabMainApp() {
    val context = LocalContext.current
    // Use application-scoped singletons — do NOT construct new instances here.
    val authManager = AppDependencies.authManager
    val userPrefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)

    androidx.activity.compose.BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    var startRoute by remember { mutableStateOf<String?>(null) }
    var initialGuestSignedIn by remember { mutableStateOf<Boolean?>(null) }
    var initialHasSeenOnboarding by remember { mutableStateOf<Boolean?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val hasSeen = userPrefs.hasSeenOnboarding.first()
        val isGuest = userPrefs.isGuestSignedIn.first()
        val isFirebaseAuthed = authManager.currentUser != null

        initialHasSeenOnboarding = hasSeen
        initialGuestSignedIn = isGuest

        val route = when {
            !isFirebaseAuthed && !isGuest -> {
                if (hasSeen) "auth" else "onboarding"
            }
            else -> "discover"
        }
        startRoute = route
        MainActivity.isInitializing = false
    }

    val hasSeenOnboardingState by userPrefs.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = initialHasSeenOnboarding)
    val isGuestSignedIn by userPrefs.isGuestSignedIn.collectAsStateWithLifecycle(initialValue = initialGuestSignedIn ?: false)
    val isUserAuthenticated = authManager.currentUser != null || isGuestSignedIn

    // Global Authentication & Navigation Guard
    androidx.compose.runtime.LaunchedEffect(isUserAuthenticated, currentRoute, startRoute) {
        if (startRoute == null) return@LaunchedEffect
        if (!isUserAuthenticated) {
            if (currentRoute != null && currentRoute != "auth" && currentRoute != "splash" && currentRoute != "onboarding") {
                navController.navigate("auth") {
                    popUpTo(0) { inclusive = true }
                }
            }
        } else {
            // Logged-in users should bypass splash, onboarding, and login screens
            if (currentRoute == "auth" || currentRoute == "splash" || currentRoute == "onboarding") {
                navController.navigate("discover") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }
    var isFeedLoading by remember { mutableStateOf(true) }
    var isLlmActive by remember { mutableStateOf(true) }
    // Use the application-scoped ApiService singleton
    val apiService = AppDependencies.apiService

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while(true) {
            isLlmActive = apiService.checkAiStatus()
            kotlinx.coroutines.delay(30000)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, authManager.currentUser) {
        val observer = LifecycleEventObserver { _, event ->
            if (authManager.currentUser != null) {
                scope.launch {
                    if (event == Lifecycle.Event.ON_START) {
                        authManager.setUserOnlineStatus(true)
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        authManager.setUserOnlineStatus(false)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mainTabs = listOf("discover", "collabs", "agent", "industry", "collabs_ws")
    val dockItems = listOf(
        DockItem(route = "discover", icon = Icons.Filled.Hub, label = "Pulse", badgeCount = 1),
        DockItem(route = "collabs", icon = Icons.Filled.Groups, label = "Orbit", hasBadgeDot = true),
        DockItem(route = "agent", drawableResId = com.open.skolab.R.drawable.logo, label = "Skolar"),
        DockItem(route = "industry", icon = Icons.Filled.BusinessCenter, label = "Launchpad"),
        DockItem(route = "collabs_ws", icon = Icons.Filled.GridView, label = "Collabs")
    )

    SkoLabScaffold { innerPadding ->
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
                    topBar = {
                        // Chrome is static — no independent animation.
                        // The bars snap in at t=0 while content fades over 180ms,
                        // which is below the human perception threshold (~250ms).
                        if (currentRoute in mainTabs) {
                            CommonTopBar(
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                isSearchActive = isSearchActive,
                                onSearchActiveChange = { isSearchActive = it },
                                onProfileClick = { navController.navigate("profile") },
                                onMessagesClick = { navController.navigate("chat_list") },
                                cachedUser = cachedUser
                            )
                        }
                    },
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (startRoute != null) {
                            NavHost(
                                navController = navController,
                                startDestination = startRoute!!,
                                modifier = Modifier.fillMaxSize(),
                                // Push forward: clean 200ms fade — feels deliberate
                                enterTransition = { fadeIn(animationSpec = tween(200, easing = EaseOutCubic)) },
                                exitTransition = { fadeOut(animationSpec = tween(120, easing = EaseOutCubic)) },
                                // Back: MD3 spec — returning destinations appear instantly.
                                // The outgoing screen's exit handles the animation feel.
                                // This eliminates the "bars appear before content" gap.
                                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                                popExitTransition = { fadeOut(animationSpec = tween(120, easing = EaseOutCubic)) }
                            ) {
                composable(
                    route = "splash",
                    exitTransition = {
                        fadeOut(animationSpec = tween(400, easing = EaseOutCubic))
                    }
                ) {
                    if (hasSeenOnboardingState == null) {
                        // Wait for DataStore to load
                        Box(modifier = Modifier.fillMaxSize().background(EntropiColors.Background))
                    } else if (hasSeenOnboardingState == true) {
                        // Skip splash animation if onboarding is already completed
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val next = if (!authManager.isSignedInAsync()) "auth" else "discover"
                            navController.navigate(next) {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize().background(EntropiColors.Background))
                    } else {
                        SplashScreen(
                            onAnimationFinished = {
                                navController.navigate("onboarding") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }
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
                                navController.navigate("profile_setup") {
                                    popUpTo("auth") { inclusive = true }
                                }
                            }
                        )
                    }
                }
                composable(
                    route = "profile_setup",
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
                        ProfileSetupScreen(
                            onSetupComplete = {
                                navController.navigate("discover") {
                                    popUpTo("profile_setup") { inclusive = true }
                                }
                            }
                        )
                    }
                }
                composable(route = "discover") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
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
                            },
                            onNavigateToDailyDiscovery = {
                                navController.navigate("daily_discovery")
                            },
                            onNavigateToCollabs = {
                                navController.navigate("collabs") {
                                    popUpTo("discover") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToCreateProject = {
                                navController.navigate("create_project")
                            }
                        )
                    }
                }
                composable(route = "papers") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
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
                    route = "agent?query={query}",
                    arguments = listOf(
                        androidx.navigation.navArgument("query") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { backStackEntry ->
                    val query = backStackEntry.arguments?.getString("query")?.decodeFromRoute() ?: ""
                    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                    val isKeyboardVisible = imeBottom > 0
                    val bottomPadding = if (isKeyboardVisible) 0.dp else ScreenInsets.bottomNavClearance
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            .padding(bottom = bottomPadding)
                    ) {
                        AgentScreen(initialQuery = query)
                    }
                }
                composable(route = "industry") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        com.open.skolab.ui.screens.IndustryScreen(
                            onNavigateToAuthor = { authorName ->
                                navController.navigate("author_detail/${authorName.encodeForRoute()}")
                            },
                            onNavigateToReader = { title, doi ->
                                navController.navigate("reader/${title.encodeForRoute()}/${doi.encodeForRoute()}")
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
                composable(route = "collabs") { backStackEntry ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        PaperCollabsScreen(
                            savedStateHandle = backStackEntry.savedStateHandle,
                            startTab = "orbit_network",
                            onNavigateToChat = { peerName, peerId ->
                                navController.navigate("chat/${peerName.encodeForRoute()}/${peerId.encodeForRoute()}")
                            },
                            onNavigateToWorkspace = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
                            },
                            onNavigateToCreateProject = {
                                navController.navigate("create_project")
                            },
                            onNavigateToInviteMember = { projectId ->
                                navController.navigate("invite_member/${projectId.encodeForRoute()}")
                            },
                            onNavigateToCreateTask = { projectId ->
                                navController.navigate("create_task/${projectId.encodeForRoute()}")
                            },
                            onNavigateToExternalInvite = { collaboratorName ->
                                navController.navigate("external_invite/${collaboratorName.encodeForRoute()}")
                            }
                        )
                    }
                }
                composable(route = "collabs_ws") { backStackEntry ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            .padding(bottom = ScreenInsets.bottomNavClearance)
                    ) {
                        PaperCollabsScreen(
                            savedStateHandle = backStackEntry.savedStateHandle,
                            startTab = "workspaces",
                            onNavigateToChat = { peerName, peerId ->
                                navController.navigate("chat/${peerName.encodeForRoute()}/${peerId.encodeForRoute()}")
                            },
                            onNavigateToWorkspace = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
                            },
                            onNavigateToCreateProject = {
                                navController.navigate("create_project")
                            },
                            onNavigateToInviteMember = { projectId ->
                                navController.navigate("invite_member/${projectId.encodeForRoute()}")
                            },
                            onNavigateToCreateTask = { projectId ->
                                navController.navigate("create_task/${projectId.encodeForRoute()}")
                            },
                            onNavigateToExternalInvite = { collaboratorName ->
                                navController.navigate("external_invite/${collaboratorName.encodeForRoute()}")
                            }
                        )
                    }
                }
                composable(
                    route = "create_project",
                    enterTransition = {
                        androidx.compose.animation.slideInVertically(initialOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    },
                    exitTransition = {
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize().screenSafeArea(includeBottom = true).padding(bottom = scaffoldPadding.calculateBottomPadding())) {
                        com.open.skolab.ui.screens.CreateProjectScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "invite_member/{projectId}",
                    enterTransition = {
                        androidx.compose.animation.slideInVertically(initialOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    },
                    exitTransition = {
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId")?.decodeFromRoute() ?: ""
                    Box(modifier = Modifier.fillMaxSize().screenSafeArea(includeBottom = true).padding(bottom = scaffoldPadding.calculateBottomPadding())) {
                        com.open.skolab.ui.screens.InviteMemberScreen(
                            projectId = projectId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "create_task/{projectId}",
                    enterTransition = {
                        androidx.compose.animation.slideInVertically(initialOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    },
                    exitTransition = {
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId")?.decodeFromRoute() ?: ""
                    Box(modifier = Modifier.fillMaxSize().screenSafeArea(includeBottom = true).padding(bottom = scaffoldPadding.calculateBottomPadding())) {
                        com.open.skolab.ui.screens.CreateTaskScreen(
                            projectId = projectId,
                            onBack = { navController.popBackStack() },
                            onTaskCreated = { title, assignee ->
                                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                val taskId = db.collection("collabs_groups").document(projectId).collection("tasks").document().id
                                val taskData = hashMapOf(
                                    "id" to taskId,
                                    "title" to title,
                                    "assignee" to assignee,
                                    "isCompleted" to false
                                )
                                db.collection("collabs_groups").document(projectId).collection("tasks").document(taskId).set(taskData)
                            }
                        )
                    }
                }
                composable(
                    route = "external_invite/{collaboratorName}",
                    enterTransition = {
                        androidx.compose.animation.slideInVertically(initialOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    },
                    exitTransition = {
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }, animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val collaboratorName = backStackEntry.arguments?.getString("collaboratorName")?.decodeFromRoute() ?: ""
                    Box(modifier = Modifier.fillMaxSize().screenSafeArea(includeBottom = true).padding(bottom = scaffoldPadding.calculateBottomPadding())) {
                        com.open.skolab.ui.screens.ExternalInviteScreen(
                            collaboratorName = collaboratorName,
                            onBack = { navController.popBackStack() }
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
                    route = "metrics",
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
                        MetricsScreen()
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
                            .screenSafeArea(includeBottom = false)
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
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(com.open.skolab.ui.theme.BgPrimary)
                            )
                        }

                    // Search Results Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSearchActive,
                        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.slideInVertically(initialOffsetY = { -40 }, animationSpec = androidx.compose.animation.core.tween(300)),
                        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(250)) + androidx.compose.animation.slideOutVertically(targetOffsetY = { -40 }, animationSpec = androidx.compose.animation.core.tween(250))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(com.open.skolab.ui.theme.BgPrimary)
                                .padding(top = scaffoldPadding.calculateTopPadding())
                                .padding(bottom = if (currentRoute in mainTabs) ScreenInsets.bottomNavClearance else 0.dp)
                        ) {
                            SearchScreen(
                                onPaperClick = { paperId ->
                                    isSearchActive = false
                                    searchQuery = ""
                                    navController.navigate("paper_detail/${paperId.encodeForRoute()}")
                                },
                                onAuthorClick = { authorName ->
                                    isSearchActive = false
                                    searchQuery = ""
                                    navController.navigate("author_detail/${authorName.encodeForRoute()}")
                                },
                                searchQuery = searchQuery,
                                showSearchBar = false
                            )
                        }
                    }
                }
            }
        }
    }
}
}



@Composable
fun CommonTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onProfileClick: () -> Unit,
    onMessagesClick: () -> Unit = {},
    cachedUser: com.open.skolab.model.SkoLabUser?
) {
    // Badge pulse animation
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "badge_pulse")
    val badgePulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable<Float>(
            animation = androidx.compose.animation.core.tween<Float>(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "badge_alpha"
    )

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = com.open.skolab.ui.theme.BgPrimary.copy(alpha = 0.92f),
        tonalElevation = 0.dp
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            if (isSearchActive) {
                // Back arrow when search is active
                androidx.compose.material3.IconButton(
                    onClick = {
                        onSearchQueryChange("")
                        onSearchActiveChange(false)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit Search",
                        tint = com.open.skolab.ui.theme.TextSecondary
                    )
                }
            } else {
                // ── Messages Icon (WA-style, left of search bar) ──────────────────
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(com.open.skolab.ui.theme.BgCard)
                        .clickable { onMessagesClick() },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    // Chat bubble icon
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Forum,
                        contentDescription = "Messages",
                        tint = com.open.skolab.ui.theme.AccentTeal,
                        modifier = Modifier.size(22.dp)
                    )
                    // Unread badge dot (top-right)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(9.dp)
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .clipToBounds()
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = com.open.skolab.ui.theme.AccentEmerald.copy(alpha = badgePulse),
                                radius = size.minDimension / 2
                            )
                        }
                    }
                }
            }

            // Search bar — takes all remaining space
            com.open.skolab.ui.components.primitives.GlassSearchBar(
                value = searchQuery,
                onValueChange = {
                    onSearchQueryChange(it)
                    if (it.isNotEmpty()) {
                        onSearchActiveChange(true)
                    }
                },
                placeholder = "Search papers, profiles, topics...",
                onClear = {
                    onSearchQueryChange("")
                    onSearchActiveChange(false)
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onSearchActiveChange(true)
                        }
                    }
            )

            // User Profile Avatar
            val initials = remember(cachedUser?.name) {
                (cachedUser?.name ?: "SkoLab User").split(" ")
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .map { it.first() }
                    .joinToString("")
                    .uppercase()
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.sweepGradient(
                            colors = listOf(
                                com.open.skolab.ui.theme.AccentAmber,
                                com.open.skolab.ui.theme.AccentTeal,
                                com.open.skolab.ui.theme.AccentAmber
                            )
                        )
                    )
                    .clickable { onProfileClick() }
                    .padding(1.5.dp)
                    .background(com.open.skolab.ui.theme.BgPrimary, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = initials,
                    color = com.open.skolab.ui.theme.AccentAmber,
                    fontFamily = com.open.skolab.ui.theme.SpaceGroteskFontFamily,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

private fun String.encodeForRoute(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decodeFromRoute(): String = java.net.URLDecoder.decode(this, "UTF-8")
