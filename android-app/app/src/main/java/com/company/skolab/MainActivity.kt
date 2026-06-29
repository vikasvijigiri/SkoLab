package com.company.skolab

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
import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.Home
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
import androidx.navigation.navDeepLink
import androidx.navigation.compose.rememberNavController
import com.company.skolab.auth.AuthManager
import com.company.skolab.data.UserPreferences
import com.company.skolab.di.AppDependencies
import com.company.skolab.ui.components.primitives.BottomNavDock
import com.company.skolab.ui.components.primitives.DockItem
import com.company.skolab.ui.components.primitives.SkoLabScaffold
import com.company.skolab.ui.layout.ScreenInsets
import com.company.skolab.ui.layout.ScreenType
import com.company.skolab.ui.layout.SkoLabScreen
import com.company.skolab.ui.layout.screenSafeArea
import com.company.skolab.ui.screens.ArticleReaderScreen
import com.company.skolab.ui.screens.AuthScreen
import com.company.skolab.ui.screens.AuthorDetailScreen
import com.company.skolab.ui.screens.DiscoveryScreen
import com.company.skolab.ui.screens.FeedScreen
import com.company.skolab.ui.screens.ProfileScreen
import com.company.skolab.ui.screens.EditProfileScreen
import com.company.skolab.ui.screens.LibraryScreen
import com.company.skolab.ui.screens.MetricsScreen
import com.company.skolab.ui.screens.OnboardingScreen
import com.company.skolab.ui.screens.PaperDetailScreen
import com.company.skolab.ui.screens.PapersScreen
import com.company.skolab.ui.screens.SearchScreen
import com.company.skolab.ui.screens.PaperCollabsScreen
import com.company.skolab.ui.screens.SplashScreen
import com.company.skolab.ui.screens.ChatRoomScreen
import com.company.skolab.ui.screens.ChatListScreen
import com.company.skolab.ui.screens.LogicEngineScreen
import com.company.skolab.ui.screens.ProWorkspaceScreen
import com.company.skolab.ui.screens.DailyDiscoveryScreen
import com.company.skolab.ui.screens.HomeScreen
import com.company.skolab.ui.screens.SparkSessionScreen
import com.company.skolab.ui.screens.CoLabWorkspaceScreen
import com.company.skolab.ui.screens.ProfileSetupScreen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import com.company.skolab.ui.screens.AgentScreen
import com.company.skolab.ui.theme.SkoLabTheme
import com.company.skolab.ui.theme.SkoLabColors
import com.company.skolab.ui.theme.AccentRed
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import com.company.skolab.ui.components.primitives.GlassSearchBar
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.Forum
import com.company.skolab.analytics.SkoLabAnalytics


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

        // Persistent disk cache — data loads instantly from disk on reopen, then refreshes in background
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(com.google.firebase.firestore.persistentCacheSettings {})
                .build()
            db.firestoreSettings = settings
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to configure Firestore persistence", e)
        }

        userPrefsForTheme = UserPreferences(applicationContext)
        SkoLabAnalytics.initSessionRecorder()
        setContent {
            val dynamicColor by userPrefsForTheme.dynamicColorEnabled.collectAsStateWithLifecycle(
                initialValue = false,
                lifecycleOwner = this
            )
            SkoLabTheme(dynamicColor = dynamicColor) {
                SkoLabMainApp()
            }
        }
    }
}

// Hoisted so setContent can observe it without triggering recompositions inside the NavHost.
private lateinit var userPrefsForTheme: com.company.skolab.data.UserPreferences

@Composable
fun SkoLabMainApp() {
    val context = LocalContext.current
    // Use application-scoped singletons — do NOT construct new instances here.
    val authManager = AppDependencies.authManager
    val userPrefs = remember { UserPreferences(context) }
    val appTheme by userPrefs.appTheme.collectAsStateWithLifecycle(initialValue = "SYSTEM")
    androidx.compose.runtime.LaunchedEffect(appTheme) {
        com.company.skolab.ui.theme.darkThemeOverrideState = when (appTheme) {
            "DARK" -> true
            else -> false  // LIGHT or SYSTEM both force the warm-sand light theme
        }
    }
    // Increment session count once per cold start for NPS trigger eligibility.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        userPrefs.incrementSessionCount()
    }

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
        userPrefs.isConsentGiven.collect { consented ->
            SkoLabAnalytics.setConsent(consented)
        }
    }

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
    // isLlmActive is now directly tied to the global WebSocket connection status.
    // When the WebSocket drops, it instantly updates this state to false.
    val isLlmActive by com.company.skolab.network.GlobalSocketManager.isConnected.collectAsStateWithLifecycle()

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

    val mainTabs = listOf("discover", "industry", "agent", "industry_academic")

    // Bottom dock is ONLY shown on the 4 main tabs.
    // All other screens (detail, full-screen, pre-auth) hide it so screens
    // have full real-estate and padding is unambiguous.
    val showBottomDock = currentRoute in mainTabs

    // Map any sub-screen back to the tab it belongs to, so the active tab stays highlighted
    val activeTab = when {
        currentRoute == null -> null
        currentRoute in mainTabs -> currentRoute
        currentRoute.startsWith("agent") -> "agent"
        currentRoute == "create_project" ||
        currentRoute.startsWith("invite_member") ||
        currentRoute.startsWith("create_task") ||
        currentRoute.startsWith("external_invite") -> "industry_academic"
        else -> "discover"
    }
    val dockItems = listOf(
        DockItem(route = "discover", icon = Icons.Filled.Home, label = "Feed"),
        DockItem(route = "industry", icon = Icons.Outlined.TravelExplore, label = "Explore"),
        DockItem(route = "agent", drawableResId = com.company.skolab.R.drawable.logo, label = "Ask AI"),
        DockItem(route = "industry_academic", icon = Icons.Filled.Hub, label = "Connect")
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
                        .background(AccentRed)
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
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        if (showBottomDock) {
                            BottomNavDock(
                                items = dockItems,
                                currentRoute = activeTab,
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
                                // Push forward: directional horizontal slide metaphor (slide left)
                                enterTransition = {
                                    if (ValueAnimator.areAnimatorsEnabled()) {
                                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                                        fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                                    } else {
                                        EnterTransition.None
                                    }
                                },
                                exitTransition = {
                                    if (ValueAnimator.areAnimatorsEnabled()) {
                                        slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                                        fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                                    } else {
                                        ExitTransition.None
                                    }
                                },
                                // Back: directional horizontal slide metaphor (slide right)
                                popEnterTransition = {
                                    if (ValueAnimator.areAnimatorsEnabled()) {
                                        slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                                        fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                                    } else {
                                        EnterTransition.None
                                    }
                                },
                                popExitTransition = {
                                    if (ValueAnimator.areAnimatorsEnabled()) {
                                        slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                                        fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                                    } else {
                                        ExitTransition.None
                                    }
                                }
                            ) {
                composable(
                    route = "splash",
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeOut(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            ExitTransition.None
                        }
                    }
                ) {
                    if (hasSeenOnboardingState == null) {
                        // Wait for DataStore to load
                        Box(modifier = Modifier.fillMaxSize().background(SkoLabColors.Background))
                    } else if (hasSeenOnboardingState == true) {
                        // Skip splash animation if onboarding is already completed
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val next = if (!authManager.isSignedInAsync()) "auth" else "discover"
                            navController.navigate(next) {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize().background(SkoLabColors.Background))
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
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.PRE_AUTH) {
                        OnboardingScreen(
                            onFinish = { consented ->
                                scope.launch {
                                    userPrefs.setConsentGiven(consented)
                                    userPrefs.setOnboardingComplete()
                                }
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
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.PRE_AUTH) {
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
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.PRE_AUTH) {
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.MAIN_TAB) {
                        HomeScreen(
                            onPaperClick = { paperId ->
                                navController.navigate("paper_detail/${paperId.encodeForRoute()}")
                            },
                            onAuthorClick = { authorName ->
                                navController.navigate("author_detail/${authorName.encodeForRoute()}")
                            },
                            onNavigateToChat = { name, id ->
                                navController.navigate("chat/${name.encodeForRoute()}/${id.encodeForRoute()}")
                            },
                            onNavigateToDailyDiscovery = {
                                navController.navigate("daily_discovery")
                            },
                            onNavigateToCollabs = {
                                navController.navigate("industry_academic") {
                                    popUpTo("discover") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToCreateProject = {
                                navController.navigate("create_project")
                            },
                            onNavigateToSparkSession = { sessionId ->
                                navController.navigate("spark_session/$sessionId")
                            },
                            onNavigateToWorkspace = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
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
                    route = "spark_session/{sessionId}",
                    arguments = listOf(
                        androidx.navigation.navArgument("sessionId") {
                            type = androidx.navigation.NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        SparkSessionScreen(
                            sessionId = sessionId,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
                composable(route = "papers") {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.MAIN_TAB) {
                        AgentScreen(initialQuery = query)
                    }
                }
                composable(route = "industry") {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.MAIN_TAB) {
                        com.company.skolab.ui.screens.IndustryScreen(
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
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        ProfileScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToProWorkspace = { navController.navigate("pro_workspace") },
                            onNavigateToEditProfile = { navController.navigate("edit_profile") }
                        )
                    }
                }
                composable(
                    route = "edit_profile",
                    enterTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else EnterTransition.None
                    },
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(250, easing = EaseOutCubic)) +
                            fadeOut(animationSpec = tween(250))
                        } else ExitTransition.None
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        EditProfileScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(
                    route = "pro_workspace",
                    enterTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        ProWorkspaceScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
                composable(route = "industry_academic") {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.MAIN_TAB) {
                        com.company.skolab.ui.screens.IndustryAcademicScreen(
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
                    route = "create_project",
                    enterTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    },
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                        } else {
                            ExitTransition.None
                        }
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        com.company.skolab.ui.screens.CreateProjectScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "invite_member/{projectId}",
                    enterTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    },
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                        } else {
                            ExitTransition.None
                        }
                    }
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        com.company.skolab.ui.screens.InviteMemberScreen(
                            projectId = projectId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "create_task/{projectId}",
                    enterTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    },
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                        } else {
                            ExitTransition.None
                        }
                    }
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        com.company.skolab.ui.screens.CreateTaskScreen(
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
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeIn(animationSpec = tween(300, easing = EaseOutCubic))
                        } else {
                            EnterTransition.None
                        }
                    },
                    exitTransition = {
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) +
                            fadeOut(animationSpec = tween(150, easing = EaseOutCubic))
                        } else {
                            ExitTransition.None
                        }
                    }
                ) { backStackEntry ->
                    val collaboratorName = backStackEntry.arguments?.getString("collaboratorName")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        com.company.skolab.ui.screens.ExternalInviteScreen(
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        LibraryScreen(onPaperClick = { paperId -> navController.navigate("paper_detail/${paperId.encodeForRoute()}") })
                    }
                }
                composable(
                    route = "metrics",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        MetricsScreen()
                    }
                }
                composable(
                    route = "paper_detail/{paperId}",
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "skolab://paper_detail/{paperId}" },
                        navDeepLink { uriPattern = "https://skolab.open.com/paper_detail/{paperId}" }
                    ),
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val paperId = backStackEntry.arguments?.getString("paperId")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        PaperDetailScreen(
                            paperId = paperId,
                            onBack = { navController.popBackStack() },
                            onAuthorClick = { authorName -> navController.navigate("author_detail/${authorName.encodeForRoute()}") }
                        )
                    }
                }
                composable(
                    route = "author_detail/{authorName}",
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "skolab://author_detail/{authorName}" },
                        navDeepLink { uriPattern = "https://skolab.open.com/author_detail/{authorName}" }
                    ),
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val authorName = backStackEntry.arguments?.getString("authorName")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        ArticleReaderScreen(
                            url = url,
                            title = title,
                            onClose = { navController.popBackStack() }
                        )
                    }
                }
                composable(
                    route = "chat/{peerName}/{peerId}",
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "skolab://chat/{peerName}/{peerId}" },
                        navDeepLink { uriPattern = "https://skolab.open.com/chat/{peerName}/{peerId}" }
                    ),
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) { backStackEntry ->
                    val peerName = backStackEntry.arguments?.getString("peerName")?.decodeFromRoute() ?: ""
                    val peerId = backStackEntry.arguments?.getString("peerId")?.decodeFromRoute() ?: ""
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.FULL_SCREEN) {
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        ChatListScreen(
                            onChatClick = { peerName, peerId ->
                                navController.navigate("chat/${peerName.encodeForRoute()}/${peerId.encodeForRoute()}")
                            },
                            onCoLabClick = { projectName ->
                                navController.navigate("colab_workspace/${projectName.encodeForRoute()}")
                            },
                            onNavigateToCreateProject = {
                                navController.navigate("create_project")
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
                    ) {
                        CoLabWorkspaceScreen(
                            projectName = projectName,
                            onBack = { navController.popBackStack() },
                            onNavigateToInviteMember = { projectId ->
                                navController.navigate("invite_member/${projectId.encodeForRoute()}")
                            },
                            onNavigateToCreateTask = { projectId ->
                                navController.navigate("create_task/${projectId.encodeForRoute()}")
                            }
                        )
                    }
                }
                composable(
                    route = "logic_engine",
                    enterTransition = {
                        fadeIn(animationSpec = tween(450, easing = EaseOutCubic))
                    }
                ) {
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
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
                    SkoLabScreen(scaffoldPadding, com.company.skolab.ui.layout.ScreenType.DETAIL) {
                        DailyDiscoveryScreen(
                            onBack = { navController.popBackStack() },
                            onPaperSaved = { paperId ->
                                SkoLabAnalytics.logPaperSaved(paperId)
                            },
                            onDiscussClick = { title ->
                                val discussPrompt = "Discussing paper: \"$title\"\n\nCould you explain the methodology, tools used, and key findings of this work?"
                                navController.navigate("agent?query=${android.net.Uri.encode(discussPrompt)}")
                            },
                            userFocus = cachedUser?.researchFocus ?: ""
                        )
                    }
                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(com.company.skolab.ui.theme.BgPrimary)
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
                                .background(com.company.skolab.ui.theme.BgPrimary)
                                .padding(top = scaffoldPadding.calculateTopPadding())
                                .padding(bottom = scaffoldPadding.calculateBottomPadding())
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
                                showSearchBar = false,
                                userFocus = cachedUser?.researchFocus ?: ""
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
    cachedUser: com.company.skolab.model.SkoLabUser?
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
        color = com.company.skolab.ui.theme.BgPrimary.copy(alpha = 0.92f),
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
                        tint = com.company.skolab.ui.theme.TextSecondary
                    )
                }
            } else {
                // ── Messages Icon (WA-style, left of search bar) ──────────────────
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(com.company.skolab.ui.theme.BgCard)
                        .clickable { onMessagesClick() },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    // Chat bubble icon
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Forum,
                        contentDescription = "Messages",
                        tint = com.company.skolab.ui.theme.AccentTeal,
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
                                color = com.company.skolab.ui.theme.AccentEmerald.copy(alpha = badgePulse),
                                radius = size.minDimension / 2
                            )
                        }
                    }
                }
            }

            // Search bar — takes all remaining space
            com.company.skolab.ui.components.primitives.GlassSearchBar(
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
                                com.company.skolab.ui.theme.AccentAmber,
                                com.company.skolab.ui.theme.AccentTeal,
                                com.company.skolab.ui.theme.AccentAmber
                            )
                        )
                    )
                    .clickable { onProfileClick() }
                    .padding(1.5.dp)
                    .background(com.company.skolab.ui.theme.BgPrimary, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = initials,
                    color = com.company.skolab.ui.theme.AccentAmber,
                    fontFamily = com.company.skolab.ui.theme.SpaceGroteskFontFamily,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

private fun String.encodeForRoute(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decodeFromRoute(): String = java.net.URLDecoder.decode(this, "UTF-8")
