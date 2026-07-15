package com.company.skolab.ui.screens

import com.company.skolab.ui.screens.feed.components.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.model.Paper
import com.company.skolab.model.Author
import com.company.skolab.model.UserConnection
import com.company.skolab.model.Conjecture
import com.company.skolab.model.Connection
import com.company.skolab.model.Country
import com.company.skolab.model.Discipline
import com.company.skolab.model.FeedUiState
import com.company.skolab.model.FrontierMetrics
import com.company.skolab.model.Institution
import com.company.skolab.model.ReadingProgress
import com.company.skolab.model.ResearchArea
import com.company.skolab.model.ResearchFilter
import com.company.skolab.model.User
import androidx.compose.material.icons.automirrored.filled.Article
import com.company.skolab.ui.components.ScoreArcMeter
import com.company.skolab.ui.components.MarkdownText
import com.company.skolab.ui.components.StreakCard
import com.company.skolab.ui.components.SwipeVaultCard
import com.company.skolab.ui.components.PaperCard
import com.company.skolab.ui.components.primitives.EmptyState
import com.company.skolab.ui.components.primitives.ErrorState
import com.company.skolab.ui.components.primitives.GlassSearchBar
import com.company.skolab.ui.theme.*
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// SkoLabColors is defined in Color.kt — imported via com.company.skolab.ui.theme.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onPaperClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    onNavigateToChat: (String, String) -> Unit = { _, _ -> },
    onNavigateToChatList: () -> Unit = {},
    onNavigateToReader: (String, String) -> Unit = { _, _ -> },
    onTabNavigate: (String) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onLoadingStateChanged: (Boolean) -> Unit = {},
    onNavigateToLogicEngine: () -> Unit = {},
    onNavigateToDailyDiscovery: () -> Unit = {},
    onNavigateToCollabs: () -> Unit = {},
    onNavigateToCreateProject: () -> Unit = {},
    viewModel: FeedViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = viewModel.firstVisibleItemScrollOffset
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.updateScrollPosition(index, offset)
            }
    }
    val haptic = LocalHapticFeedback.current
    var selectedCountryFilter by remember { mutableStateOf("Global") }
    var showSetupFocusDialog by remember { mutableStateOf(false) }
    var setupNameText by remember { mutableStateOf("") }
    var setupFocusText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val authManager = com.company.skolab.di.AppDependencies.authManager
    val userPrefs = remember { com.company.skolab.data.UserPreferences(context) }
    val apiService = com.company.skolab.di.AppDependencies.apiService
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val connectionsList by userPrefs.userConnections.collectAsState(initial = emptyList())
    // Persistent saved paper IDs — synced with DataStore
    val persistedSavedIds by userPrefs.savedPaperIds.collectAsState(initial = emptyList())
    val savedFeedItemIds: Set<String> = persistedSavedIds.toSet()

    // ── Active Collabs: Firestore live listener ──────────────────────────────
    val currentUserId = remember(cachedUser) { cachedUser?.uid ?: "" }
    var activeCollabProjects by remember { mutableStateOf<List<ProjectCollab>>(emptyList()) }
    DisposableEffect(currentUserId) {
        if (currentUserId.isEmpty()) { onDispose {} } else {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val listener = db.collection("collabs_groups")
                .whereArrayContains("memberUids", currentUserId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null) {
                        activeCollabProjects = snapshot.toObjects(ProjectCollab::class.java)
                            .sortedByDescending { it.createdAt }
                    }
                }
            onDispose { listener.remove() }
        }
    }

    val isInitialLoad = remember { mutableStateOf(true) }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isInitialLoad.value = false
        }
        onLoadingStateChanged(uiState.isLoading && isInitialLoad.value)
    }


    LaunchedEffect(cachedUser) {
        val user = cachedUser ?: return@LaunchedEffect
        val uid = user.uid
        val name = user.name
        val focus = user.researchFocus
        
        val isNameInvalid = name.isBlank() || name.equals("SkoLab User", ignoreCase = true) || name.equals("Researcher", ignoreCase = true)
        val isFocusInvalid = focus.isBlank() || focus.equals("Researcher", ignoreCase = true) || focus.equals("General Research", ignoreCase = true)
        
        if (isNameInvalid || isFocusInvalid) {
            setupNameText = if (isNameInvalid) "" else name
            setupFocusText = if (isFocusInvalid) "" else focus
            showSetupFocusDialog = true
        }
        
        val validName = if (isNameInvalid) "SkoLab User" else name
        val validFocus = if (isFocusInvalid) "" else focus
        viewModel.setUserContext(uid, validName, validFocus)
        // Analytics: log that the user opened their daily feed
        SkoLabAnalytics.logDailyFeedOpened(uid)
    }

    LaunchedEffect(uiState.suggestedConnections.size) {
        if (com.company.skolab.BuildConfig.DEBUG) {
            android.util.Log.d("FeedScreen", "suggestedConnections size is now: ${uiState.suggestedConnections.size}")
            android.util.Log.d("FeedScreen", "isLoading is now: ${uiState.isLoading}")
        }
    }

    // Scroll tracking moved directly to the items list below

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SkoLabColors.Background)
    ) {
        var isRefreshing by remember { mutableStateOf(false) }

        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    viewModel.loadAllFeedData()
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                if (uiState.error != null) {
                    item {
                        Surface(
                            color = ErrorBgRed,
                            border = BorderStroke(1.dp, ErrorBorderRed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = "API Request Failed",
                                        color = ErrorRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = uiState.error ?: "",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Shimmer loading state — shown on initial load before any feed items arrive
                if (uiState.isLoading && uiState.dailyFeedItems.isEmpty()) {
                    item { PaperShimmerCard() }
                    item { PaperShimmerCard() }
                    item { PaperShimmerCard() }
                }

                if (uiState.suggestedConnections.isNotEmpty()) {
                    item {
                        Box(modifier = Modifier.padding(top = 12.dp)) {
                            PeerMomentumStrip(
                                peers = uiState.suggestedConnections.take(4),
                                onAuthorClick = onAuthorClick
                            )
                        }
                    }
                }

                // Gamified Streak Check-In Touchpoint
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        StreakCard(
                            onClick = {
                                scope.launch { userPrefs.incrementStreakAndCheckIn() }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNavigateToLogicEngine()
                                onTabNavigate("logic_engine")
                            }
                        )
                    }
                }

                // Daily Challenge Card (Conjecture)
                val dailyConjecture = uiState.dailyConjecture
                if (dailyConjecture != null) {
                    item {
                        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                            DailyChallengeCard(
                                conjecture = dailyConjecture,
                                onOpenChallenge = {
                                    onTabNavigate("logic_engine")
                                }
                            )
                        }
                    }
                }

                // Research Quick-Action Rail — Ask Agent, Team Pulse, Papers, Discovery etc.
                item {
                    ResearchActionRail(
                        onNavigateToAgent = { onTabNavigate("agent") },
                        onNavigateToCollabs = onNavigateToCollabs,
                        onNavigateToMetrics = { onTabNavigate("logic_engine") },
                        onNavigateToIndustry = { onTabNavigate("industry") },
                        onNavigateToPapers = { onTabNavigate("papers") },
                        onNavigateToDailyDiscovery = onNavigateToDailyDiscovery
                    )
                }

                // AI Daily Brief Card
                if (uiState.aiBriefText.isNotEmpty() || uiState.isLoading) {
                    item {
                        AIDailyBriefCard(
                            briefText = uiState.aiBriefText,
                            isLoading = uiState.isLoading && uiState.aiBriefText.isEmpty(),
                            userId = currentUserId
                        )
                    }
                }

                // Frontier Pulse Card (Metrics overview)
                item {
                    FrontierPulseCard(metrics = uiState.frontierMetrics)
                }

                // Continue Reading
                if (uiState.continueReading.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "📖 Continue Reading",
                            onSeeAll = { onTabNavigate("papers") }
                        )
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(uiState.continueReading, key = { it.paper.id }) { progress ->
                                ResumeCard(
                                    progress = progress,
                                    onClick = { onPaperClick(progress.paper.id) },
                                    onResume = {
                                        onNavigateToReader(progress.paper.title, progress.paper.pdfUrl ?: "https://arxiv.org/pdf/1706.03762.pdf")
                                    }
                                )
                            }
                        }
                    }
                }

                // --- Live Feed Item 1 ---
                if (uiState.dailyFeedItems.isNotEmpty()) {
                    val item1 = uiState.dailyFeedItems[0]
                    item(key = "daily_feed_${item1.id}") {
                        PulseFeedCard(
                            title = item1.title,
                            authors = item1.authors,
                            journal = item1.journal,
                            year = item1.year,
                            publicationDate = item1.publication_date,
                            relevanceScore = item1.relevance_score,
                            recommendationReason = item1.recommendation_reason,
                            abstractText = item1.abstract,
                            methodology = item1.methodology,
                            toolsUsed = item1.tools_used,
                            keyFindings = item1.key_findings,
                            onPaperClick = { onPaperClick(item1.id) },
                            onDiscussClick = {
                                val discussPrompt = "Discussing paper: \"${item1.title}\"\n\nCould you explain the methodology, tools used, and key findings of this work?"
                                onTabNavigate("agent?query=${android.net.Uri.encode(discussPrompt)}")
                            },
                            onSaveClick = {
                                scope.launch {
                                    val added = userPrefs.toggleSavedPaper(item1.id)
                                    if (added) SkoLabAnalytics.logPaperSaved(item1.id)
                                    android.widget.Toast.makeText(context, if (added) "Saved to Vault" else "Removed from Vault", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            isSaved = savedFeedItemIds.contains(item1.id),
                            onShareClick = {
                                val shareText = "📄 ${item1.title}\nPublished in ${item1.journal} (${item1.year})\n\nShared via SkoLab"
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Paper"))
                            }
                        )
                    }
                }

                // ── People You May Know ──────────────────────────────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                        // Header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(SkoLabColors.Blue1.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.People, contentDescription = null, tint = SkoLabColors.Blue1, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "People You May Know",
                                        color = SkoLabColors.Text,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "Based on your research network",
                                        color = SkoLabColors.Text3,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            // See all button
                            Surface(
                                onClick = onNavigateToCollabs,
                                shape = RoundedCornerShape(8.dp),
                                color = SkoLabColors.Blue1.copy(alpha = 0.10f),
                                border = BorderStroke(0.5.dp, SkoLabColors.Blue1.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    "Orbit →",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    color = SkoLabColors.Blue1,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Country filter chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            items(listOf("Global", "USA", "UK", "India", "Germany", "Canada", "Australia", "France", "Japan")) { country ->
                                val isSelected = selectedCountryFilter == country
                                Surface(
                                    onClick = { selectedCountryFilter = country },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) SkoLabColors.Blue1 else SkoLabColors.Card2,
                                    border = BorderStroke(1.dp, if (isSelected) Color.Transparent else SkoLabColors.Border)
                                ) {
                                    Text(
                                        text = country,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        color = if (isSelected) Color.White else SkoLabColors.Text2,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // ── Horizontal scrollable cards ──────────────────────
                        val filteredConnections = if (selectedCountryFilter == "Global") {
                            uiState.suggestedConnections
                        } else {
                            uiState.suggestedConnections.filter {
                                it.author.country.contains(selectedCountryFilter, ignoreCase = true) ||
                                it.author.institution.contains(selectedCountryFilter, ignoreCase = true)
                            }
                        }

                        if (uiState.isLoading && filteredConnections.isEmpty()) {
                            // Horizontal shimmer row
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                items(4) {
                                    Box(
                                        modifier = Modifier
                                            .size(190.dp, 270.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(SkoLabColors.Card2)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            ShimmerBar(Modifier.size(44.dp).clip(CircleShape))
                                            ShimmerBar(Modifier.fillMaxWidth().height(12.dp))
                                            ShimmerBar(Modifier.fillMaxWidth(0.7f).height(10.dp))
                                            ShimmerBar(Modifier.fillMaxWidth().height(10.dp))
                                            ShimmerBar(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)))
                                        }
                                    }
                                }
                            }
                        } else if (filteredConnections.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SkoLabColors.Card2),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.PersonSearch, null, tint = SkoLabColors.Text3, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "No connections found for $selectedCountryFilter",
                                        color = SkoLabColors.Text3,
                                        fontSize = 13.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                itemsIndexed(filteredConnections) { index, conn ->
                                    // Trigger load more 3 cards before the end to pre-fetch next page
                                    if (index >= filteredConnections.size - 3 && !uiState.isLoadingMoreConnections && !uiState.isLoading) {
                                        LaunchedEffect(index) { viewModel.loadMoreConnections() }
                                    }
                                    val isConnected = connectionsList.any { it.id == conn.author.id }
                                    PulseConnectionCard(
                                        connection = conn,
                                        isConnectedExternal = isConnected,
                                        onConnect = {
                                            scope.launch {
                                                val newConn = UserConnection(
                                                    id = conn.author.id,
                                                    name = conn.author.name,
                                                    institution = conn.author.institution,
                                                    field = conn.sharedAreas.firstOrNull() ?: ""
                                                )
                                                userPrefs.addConnection(newConn)
                                                authManager.addConnectionToFirestore(newConn)
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onChatClick = { onNavigateToChat(conn.author.name, conn.author.id) },
                                        onCollabClick = onNavigateToCreateProject,
                                        onAuthorClick = {
                                            onAuthorClick("${conn.author.name}|${conn.author.id}")
                                        }
                                    )
                                }
                                if (uiState.isLoadingMoreConnections) {
                                    item {
                                        Box(
                                            modifier = Modifier.size(190.dp, 270.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(color = SkoLabColors.Blue1, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Live Feed Item 2 ---
                if (uiState.dailyFeedItems.size > 1) {
                    val item2 = uiState.dailyFeedItems[1]
                    item(key = "daily_feed_${item2.id}") {
                        PulseFeedCard(
                            title = item2.title,
                            authors = item2.authors,
                            journal = item2.journal,
                            year = item2.year,
                            publicationDate = item2.publication_date,
                            relevanceScore = item2.relevance_score,
                            recommendationReason = item2.recommendation_reason,
                            abstractText = item2.abstract,
                            methodology = item2.methodology,
                            toolsUsed = item2.tools_used,
                            keyFindings = item2.key_findings,
                            onPaperClick = { onPaperClick(item2.id) },
                            onDiscussClick = {
                                val discussPrompt = "Discussing paper: \"${item2.title}\"\n\nCould you explain the methodology, tools used, and key findings of this work?"
                                onTabNavigate("agent?query=${android.net.Uri.encode(discussPrompt)}")
                            },
                            onSaveClick = {
                                scope.launch {
                                    val added = userPrefs.toggleSavedPaper(item2.id)
                                    if (added) SkoLabAnalytics.logPaperSaved(item2.id)
                                    android.widget.Toast.makeText(context, if (added) "Saved to Vault" else "Removed from Vault", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            isSaved = savedFeedItemIds.contains(item2.id),
                            onShareClick = {
                                val shareText = "📄 ${item2.title}\nPublished in ${item2.journal} (${item2.year})\n\nShared via SkoLab"
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Paper"))
                            }
                        )
                    }
                }

                // ── Active Research Collaborations preview ───────────────────
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        // Section header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(PRIMARY.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Groups, null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Active Collaborations",
                                        color = SkoLabColors.Text,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "Your live research workspaces",
                                        color = SkoLabColors.Text3,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Surface(
                                onClick = onNavigateToCollabs,
                                shape = RoundedCornerShape(8.dp),
                                color = PRIMARY.copy(alpha = 0.10f),
                                border = BorderStroke(0.5.dp, PRIMARY.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    "All →",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = PRIMARY,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (activeCollabProjects.isEmpty()) {
                            // Empty CTA card
                            Surface(
                                onClick = onNavigateToCreateProject,
                                shape = RoundedCornerShape(14.dp),
                                color = SkoLabColors.Card,
                                border = BorderStroke(1.dp, SkoLabColors.Border)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(
                                                Brush.radialGradient(listOf(AccentTeal.copy(alpha = 0.18f), Color.Transparent)),
                                                CircleShape
                                            )
                                            .border(1.dp, AccentTeal.copy(alpha = 0.3f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Add, null, tint = AccentTeal, modifier = Modifier.size(22.dp))
                                    }
                                    Column {
                                        Text(
                                            "Start a Collab Workspace",
                                            color = SkoLabColors.Text,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            "Invite co-authors, share equations, draft manuscripts together",
                                            color = SkoLabColors.Text3,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                activeCollabProjects.take(3).forEach { proj ->
                                    Surface(
                                        onClick = onNavigateToCollabs,
                                        shape = RoundedCornerShape(14.dp),
                                        color = SkoLabColors.Card,
                                        border = BorderStroke(1.dp, SkoLabColors.Border)
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .background(PRIMARY.copy(alpha = 0.12f), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = proj.name.take(1).uppercase(),
                                                            color = PRIMARY,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = proj.name,
                                                            color = SkoLabColors.Text,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "${proj.members.size} member${if (proj.members.size != 1) "s" else ""} · by ${proj.ownerName.split(" ").firstOrNull() ?: "You"}",
                                                            color = SkoLabColors.Text3,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    null,
                                                    tint = SkoLabColors.Text3,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            if (proj.description.isNotBlank()) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = proj.description,
                                                    color = SkoLabColors.Text2,
                                                    fontSize = 11.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    lineHeight = 15.sp
                                                )
                                            }

                                            // Manuscript progress bar
                                            if (proj.manuscriptProgress > 0f) {
                                                Spacer(Modifier.height(10.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(4.dp)
                                                            .clip(RoundedCornerShape(2.dp))
                                                            .background(SkoLabColors.Border)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxHeight()
                                                                .fillMaxWidth(proj.manuscriptProgress.coerceIn(0f, 1f))
                                                                .background(
                                                                    Brush.horizontalGradient(listOf(PRIMARY, AccentTeal)),
                                                                    RoundedCornerShape(2.dp)
                                                                )
                                                        )
                                                    }
                                                    Text(
                                                        text = "${(proj.manuscriptProgress * 100).toInt()}%",
                                                        color = PRIMARY,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // + New workspace button
                                Surface(
                                    onClick = onNavigateToCreateProject,
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, SkoLabColors.Border)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.Add, null, tint = SkoLabColors.Blue1, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "New Workspace",
                                            color = SkoLabColors.Blue1,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Live Feed Item 3 ---
                if (uiState.dailyFeedItems.size > 2) {
                    val item3 = uiState.dailyFeedItems[2]
                    item(key = "daily_feed_${item3.id}") {
                        PulseFeedCard(
                            title = item3.title,
                            authors = item3.authors,
                            journal = item3.journal,
                            year = item3.year,
                            publicationDate = item3.publication_date,
                            relevanceScore = item3.relevance_score,
                            recommendationReason = item3.recommendation_reason,
                            abstractText = item3.abstract,
                            methodology = item3.methodology,
                            toolsUsed = item3.tools_used,
                            keyFindings = item3.key_findings,
                            onPaperClick = { onPaperClick(item3.id) },
                            onDiscussClick = {
                                val discussPrompt = "Discussing paper: \"${item3.title}\"\n\nCould you explain the methodology, tools used, and key findings of this work?"
                                onTabNavigate("agent?query=${android.net.Uri.encode(discussPrompt)}")
                            },
                            onSaveClick = {
                                scope.launch {
                                    val added = userPrefs.toggleSavedPaper(item3.id)
                                    if (added) SkoLabAnalytics.logPaperSaved(item3.id)
                                    android.widget.Toast.makeText(context, if (added) "Saved to Vault" else "Removed from Vault", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            isSaved = savedFeedItemIds.contains(item3.id),
                            onShareClick = {
                                val shareText = "📄 ${item3.title}\nPublished in ${item3.journal} (${item3.year})\n\nShared via SkoLab"
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Paper"))
                            }
                        )
                    }
                }

                // Empty state for daily feed — only shown after load completes with no items
                if (!uiState.isLoading && uiState.dailyFeedItems.isEmpty() && uiState.error == null) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = TEXT_MUTED,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Your daily feed is warming up",
                                color = TEXT_PRIMARY,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Set your research focus in Profile settings to unlock personalized paper recommendations.",
                                color = TEXT_MUTED,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // --- Live Trending & Hot Feed Section ---
                val trendingFeedList = (uiState.trendingPapers + uiState.hotPapers).distinctBy { it.id }
                if (trendingFeedList.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(PRIMARY.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Live Trending Feed",
                                        color = SkoLabColors.Text,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "Hot publications and new insights in your field",
                                        color = SkoLabColors.Text3,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    items(trendingFeedList, key = { it.id }) { paper ->
                        val (methodology, tools, findings) = generateClientMetadata(paper.title, paper.abstractText)
                        PulseFeedCard(
                            title = paper.title,
                            authors = paper.authors,
                            journal = paper.journal,
                            year = paper.year,
                            publicationDate = null,
                            relevanceScore = paper.citationCount.coerceIn(85, 99),
                            recommendationReason = "Trending heavily in ${uiState.user.researchFocus.ifBlank { "your scientific discipline" }}.",
                            abstractText = paper.abstractText,
                            methodology = methodology,
                            toolsUsed = tools,
                            keyFindings = findings,
                            onPaperClick = { onPaperClick(paper.id) },
                            onDiscussClick = {
                                val discussPrompt = "Discussing paper: \"${paper.title}\"\n\nCould you explain the methodology, tools used, and key findings of this work?"
                                onTabNavigate("agent?query=${android.net.Uri.encode(discussPrompt)}")
                            },
                            onSaveClick = {
                                scope.launch {
                                    val added = userPrefs.toggleSavedPaper(paper.id)
                                    if (added) SkoLabAnalytics.logPaperSaved(paper.id)
                                    android.widget.Toast.makeText(context, if (added) "Saved to Vault" else "Removed from Vault", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            isSaved = savedFeedItemIds.contains(paper.id),
                            onShareClick = {
                                val shareText = "📄 ${paper.title}\nPublished in ${paper.journal} (${paper.year})\n\nShared via SkoLab"
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Paper"))
                            }
                        )
                    }
                }
            }
        }

        // Scroll to Top FAB (Section K)
        val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
        ) {
            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn(spring(stiffness = Spring.StiffnessLow)),
                exit = scaleOut(spring(stiffness = Spring.StiffnessLow))
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = SkoLabColors.Gold1,
                    contentColor = SkoLabColors.Background,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Scroll to top",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        if (showSetupFocusDialog) {
            val suggestions = listOf("Physics", "Computational Neuroscience", "Machine Learning", "Genomics", "Quantum Computing")
            AlertDialog(
                onDismissRequest = { showSetupFocusDialog = false },
                title = {
                    Text(
                        text = "Complete Your Researcher Profile",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = SkoLabColors.Gold1
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "To personalize your Pulse feed, match papers, and discover collaborators, please define your profile name and research focus area.",
                            color = SkoLabColors.Text2,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        
                        OutlinedTextField(
                            value = setupNameText,
                            onValueChange = { setupNameText = it },
                            label = { Text("Full Name", color = SkoLabColors.Text3) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SkoLabColors.Text,
                                unfocusedTextColor = SkoLabColors.Text,
                                focusedBorderColor = SkoLabColors.Gold1,
                                unfocusedBorderColor = SkoLabColors.Border,
                                focusedContainerColor = SkoLabColors.Card2,
                                unfocusedContainerColor = SkoLabColors.Card2
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        
                        OutlinedTextField(
                            value = setupFocusText,
                            onValueChange = { setupFocusText = it },
                            label = { Text("Research Focus / Discipline", color = SkoLabColors.Text3) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SkoLabColors.Text,
                                unfocusedTextColor = SkoLabColors.Text,
                                focusedBorderColor = SkoLabColors.Gold1,
                                unfocusedBorderColor = SkoLabColors.Border,
                                focusedContainerColor = SkoLabColors.Card2,
                                unfocusedContainerColor = SkoLabColors.Card2
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        
                        Text(
                            text = "Focus Suggestions:",
                            color = SkoLabColors.Text3,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            suggestions.forEach { suggestion ->
                                Surface(
                                    onClick = { setupFocusText = suggestion },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (setupFocusText == suggestion) SkoLabColors.Gold1.copy(alpha = 0.15f) else SkoLabColors.Card2,
                                    border = BorderStroke(
                                        1.dp,
                                        if (setupFocusText == suggestion) SkoLabColors.Gold1 else SkoLabColors.Border
                                    )
                                ) {
                                    Text(
                                        text = suggestion,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        color = if (setupFocusText == suggestion) SkoLabColors.Gold1 else SkoLabColors.Text2,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmedName = setupNameText.trim()
                            val trimmedFocus = setupFocusText.trim()
                            if (trimmedName.isNotBlank() && trimmedFocus.isNotBlank()) {
                                scope.launch {
                                    val uid = cachedUser?.uid ?: "user_default"
                                    authManager.updateUserProfile(trimmedName, trimmedFocus)
                                    viewModel.setUserContext(uid, trimmedName, trimmedFocus)
                                    showSetupFocusDialog = false
                                }
                            }
                        },
                        enabled = setupNameText.trim().isNotBlank() && setupFocusText.trim().isNotBlank(),
                        colors = ButtonDefaults.textButtonColors(contentColor = SkoLabColors.Gold1)
                    ) {
                        Text("Save Profile", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSetupFocusDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = SkoLabColors.Text3)
                    ) {
                        Text("Skip for now")
                    }
                },
                containerColor = SkoLabColors.Card,
                shape = RoundedCornerShape(16.dp)
            )
        } // end if (showSetupFocusDialog)
    } // end Box
} // end FeedScreen




// ── Components now imported from com.company.skolab.ui.screens.feed.components ──


// ── COMPONENT 4: QuickFilterRail ─────────────────────────────────────────────
@Composable
fun QuickFilterRail(
    selectedFilter: ResearchFilter,
    onFilterSelect: (ResearchFilter) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(ResearchFilter.values()) { filter ->
            val isActive = selectedFilter == filter
            Surface(
                onClick = { onFilterSelect(filter) },
                shape = RoundedCornerShape(20.dp),
                color = if (isActive) SkoLabColors.Gold1.copy(alpha = 0.12f) else SkoLabColors.Card2,
                border = BorderStroke(1.dp, if (isActive) SkoLabColors.Gold1 else SkoLabColors.Border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(filter.color, CircleShape)
                    )
                    Text(
                        text = filter.label,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) SkoLabColors.Gold2 else SkoLabColors.Text2
                    )
                }
            }
        }
    }
}

// ── COUNTRY TILE ─────────────────────────────────────────────────────────────
@Composable
fun CountryTile(country: Country, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(80.dp, 90.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(14.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, if (isPressed) SkoLabColors.Gold1 else SkoLabColors.Border)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = country.flag, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = country.name,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                color = SkoLabColors.Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${country.paperCount} papers",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = SkoLabColors.Gold2
            )
        }
    }
}

// ── DISCIPLINE TILE ──────────────────────────────────────────────────────────
@Composable
fun DisciplineTile(discipline: Discipline, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(72.dp, 88.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(android.graphics.Color.parseColor(discipline.gradientStart)),
                            Color(android.graphics.Color.parseColor(discipline.gradientEnd))
                        )
                    )
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = discipline.emoji, fontSize = 26.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = discipline.name,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = discipline.subCount,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 8.sp,
                    color = SkoLabColors.Text2,
                    maxLines = 1
                )
            }
        }
    }
}

// ── RESEARCH AREA PILL ───────────────────────────────────────────────────────
@Composable
fun ResearchAreaPill(area: ResearchArea, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = area.color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, area.color.copy(alpha = 0.35f)),
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = area.name,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = area.color
            )
        }
    }
}

// ── COMPONENT: PaperFeedCard ─────────────────────────────────────────────────
@Composable
fun PaperFeedCard(
    paper: Paper,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, if (isPressed) SkoLabColors.Gold1 else SkoLabColors.Border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left Accent Bar (Gold -> Blue)
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically)
                    .background(Brush.verticalGradient(colors = listOf(SkoLabColors.Gold1, SkoLabColors.Blue1)))
            )

            Column(modifier = Modifier.padding(14.dp)) {
                // Header tags row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SkoLabColors.Card2,
                            border = BorderStroke(0.5.dp, SkoLabColors.Border)
                        ) {
                            Text(
                                text = paper.journal.take(18),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = SkoLabColors.Gold2,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SkoLabColors.Purple1.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = "D·${String.format("%.2f", paper.disruptionScore)}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = SkoLabColors.Purple2,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Score Badge
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(SkoLabColors.Gold1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (paper.noveltyScore * 100).toInt().toString(),
                            color = SkoLabColors.Gold2,
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Title
                Text(
                    text = paper.title,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = SkoLabColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                // Authors
                Text(
                    text = paper.authors.firstOrNull()?.split("|")?.firstOrNull() ?: "Unknown Author",
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 11.sp,
                    color = SkoLabColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(10.dp))

                // Bottom stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.RemoveRedEye, null, tint = SkoLabColors.Text3, modifier = Modifier.size(12.dp))
                        Text(text = "1.2k views", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = SkoLabColors.Text3)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.FormatQuote, null, tint = SkoLabColors.Green, modifier = Modifier.size(12.dp))
                        Text(text = "${paper.citationCount} citations", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = SkoLabColors.Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── COMPONENT: ResearcherCard ────────────────────────────────────────────────
@Composable
fun ResearcherCard(
    researcher: Author,
    onConnect: () -> Unit,
    onViewProfile: () -> Unit
) {
    var isConnected by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(SkoLabColors.Gold1, SkoLabColors.Blue1)
                        )
                    )
                    .padding(2.dp)
                    .background(SkoLabColors.Card, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = researcher.name.take(1).uppercase(),
                    color = SkoLabColors.Gold2,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // Name
            Text(
                text = researcher.name,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = SkoLabColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Inst
            Text(
                text = researcher.institution.take(16),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 10.sp,
                color = SkoLabColors.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            // Stats Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SkoLabColors.Card2, RoundedCornerShape(6.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "D·${(researcher.avgDisruptionScore * 100).toInt()}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkoLabColors.Gold2
                    )
                    Text("Disruption", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = SkoLabColors.Text3)
                }
                Box(modifier = Modifier.size(width = 0.5.dp, height = 18.dp).background(SkoLabColors.Border))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "6.2k",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkoLabColors.Green
                    )
                    Text("Citations", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = SkoLabColors.Text3)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Connect
                Button(
                    onClick = {
                        isConnected = !isConnected
                        onConnect()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) SkoLabColors.Card2 else PremiumBlue,
                        contentColor = if (isConnected) SkoLabColors.Gold1 else Color.White
                    ),
                    border = BorderStroke(1.dp, if (isConnected) SkoLabColors.Border else Color.Transparent)
                ) {
                    Text(
                        text = if (isConnected) "Pending" else "Connect",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // View profile
                Button(
                    onClick = onViewProfile,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SkoLabColors.Card2,
                        contentColor = SkoLabColors.Text2
                    ),
                    border = BorderStroke(1.dp, SkoLabColors.Border)
                ) {
                    Text(
                        text = "View",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── SUGGESTED CONNECTIONS ────────────────────────────────────────────────────
@Composable
fun ConnectionCard(
    connection: Connection,
    isConnectedExternal: Boolean,
    onConnect: () -> Unit,
    onChatClick: () -> Unit = {},
    onInvite: () -> Unit = {},
    onAuthorClick: () -> Unit
) {
    var showEmailInviteDialog by remember { mutableStateOf(false) }
    var showSMSInviteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onAuthorClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Row 1: Avatar and Metadata Top Row ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Avatar with SkoLab status overlay
                Box(modifier = Modifier.size(32.dp)) {
                    // Initials circle (compact and elegant)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SkoLabColors.Card2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connection.author.name.take(2).uppercase(),
                            color = SkoLabColors.Text2,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    // SkoLab status badge — bottom-right corner
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(
                                if (connection.isOnSkoLab) OpenAlexBrightGreen
                                else SkoLabColors.Border
                            )
                            .border(1.2.dp, SkoLabColors.Card, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (connection.isOnSkoLab) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "On SkoLab",
                                tint = Color.Black,
                                modifier = Modifier.size(6.dp)
                            )
                        } else {
                            Text(
                                text = "–",
                                color = SkoLabColors.Text3,
                                fontSize = 6.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 6.sp
                            )
                        }
                    }
                }

                // Depth badge + match % row (compact right-aligned badge)
                val (depthLabel, depthColor) = when (connection.depth) {
                    1 -> "Direct" to SkoLabColors.Green
                    2 -> "2nd" to SkoLabColors.Blue2
                    else -> "3rd+" to SkoLabColors.Text3
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = depthColor.copy(alpha = 0.1f),
                        border = BorderStroke(0.5.dp, depthColor.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = depthLabel,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
                            color = depthColor,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                    Text(
                        text = "${connection.mutualCount}%",
                        color = SkoLabColors.Green.copy(alpha = 0.9f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGroteskFontFamily
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Row 2: Complete Name & Role Block (Full Width) ────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = connection.author.name,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = SkoLabColors.Text,
                    maxLines = 2, // Allow wrapping to 2 lines so the complete name is fully visible
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )
                
                Spacer(Modifier.height(1.dp))

                val inferredRole = when {
                    connection.author.totalPapers > 50 -> "Professor"
                    connection.author.totalPapers > 15 -> "Postdoc"
                    else -> "Researcher"
                }
                Text(
                    text = "$inferredRole · ${connection.author.institution.ifEmpty { connection.author.country }.take(25)}",
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 9.sp,
                    color = SkoLabColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 2: Slim metrics ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(value = connection.papersCollaborated.toString(), label = "Joint", color = SkoLabColors.Blue1)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(SkoLabColors.Border))
                MetricPill(value = connection.totalPublications.toString(), label = "Papers", color = SkoLabColors.Text)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(SkoLabColors.Border))
                MetricPill(value = "h${connection.hIndex}", label = "Index", color = SkoLabColors.Purple1)
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 3: Action button(s) ───────────────────────────────────────
            if (connection.isOnSkoLab) {
                if (isConnectedExternal) {
                    Surface(
                        onClick = onChatClick,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = SkoLabColors.Card2,
                        border = BorderStroke(1.dp, SkoLabColors.Border)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("💬 Message", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SkoLabColors.Gold2)
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = onConnect,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    PremiumBlue, // Solid Premium Blue
                                    RoundedCornerShape(8.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+ Connect", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Surface(
                            onClick = onChatClick,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = DeepSuccessGreen.copy(alpha = 0.85f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("🤝 Collab", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Invite via Email button
                    Surface(
                        onClick = { showEmailInviteDialog = true },
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, SkoLabColors.Gold1.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(listOf(SkoLabColors.Gold1.copy(alpha = 0.10f), SkoLabColors.Gold2.copy(alpha = 0.06f))),
                                RoundedCornerShape(8.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✉️ Email Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = SkoLabColors.Gold1)
                        }
                    }

                    // Invite via SMS button
                    Surface(
                        onClick = { showSMSInviteDialog = true },
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, SkoLabColors.Cyan.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(listOf(SkoLabColors.Cyan.copy(alpha = 0.10f), SkoLabColors.Cyan.copy(alpha = 0.06f))),
                                RoundedCornerShape(8.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💬 SMS Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = SkoLabColors.Cyan)
                        }
                    }
                }
            }

            if (showEmailInviteDialog) {
                val suggestedEmail = remember { connection.author.name.lowercase().replace(" ", "") + "@university.edu" }
                var emailInput by remember { mutableStateOf(suggestedEmail) }
                
                AlertDialog(
                    onDismissRequest = { showEmailInviteDialog = false },
                    title = {
                        Text(
                            text = "Email Invitation",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = SkoLabColors.Gold1
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Send a secure SkoLab invite to ${connection.author.name}:",
                                color = SkoLabColors.Text2,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Collaborator's Email", color = SkoLabColors.Text3) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SkoLabColors.Text,
                                    unfocusedTextColor = SkoLabColors.Text,
                                    focusedBorderColor = SkoLabColors.Gold1,
                                    unfocusedBorderColor = SkoLabColors.Border,
                                    focusedContainerColor = SkoLabColors.Card2,
                                    unfocusedContainerColor = SkoLabColors.Card2
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (emailInput.isNotBlank()) {
                                    showEmailInviteDialog = false
                                    val subject = "Invitation to collaborate on SkoLab"
                                    val body = "Hi ${connection.author.name},\n\nI would love to collaborate with you on our research papers using SkoLab. SkoLab offers secure, encrypted voice/video synchronization, real-time LaTeX blackboards, and joint manuscript editing.\n\nJoin me on SkoLab here: https://skolab.open/invite\n\nBest regards,\nResearcher"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:")
                                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(emailInput.trim()))
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                                    }
                                    try {
                                        context.startActivity(android.content.Intent.createChooser(intent, "Send email via..."))
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No email client found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = SkoLabColors.Gold1)
                        ) {
                            Text("Send Email", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEmailInviteDialog = false }) {
                            Text("Cancel", color = SkoLabColors.Text3)
                        }
                    },
                    containerColor = SkoLabColors.Card,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            if (showSMSInviteDialog) {
                var phoneInput by remember { mutableStateOf("") }
                
                AlertDialog(
                    onDismissRequest = { showSMSInviteDialog = false },
                    title = {
                        Text(
                            text = "SMS Invitation",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = SkoLabColors.Cyan
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Send an SMS invite to ${connection.author.name}:",
                                color = SkoLabColors.Text2,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("Mobile Number", color = SkoLabColors.Text3) },
                                placeholder = { Text("e.g. +1234567890", color = SkoLabColors.Text3) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SkoLabColors.Text,
                                    unfocusedTextColor = SkoLabColors.Text,
                                    focusedBorderColor = SkoLabColors.Cyan,
                                    unfocusedBorderColor = SkoLabColors.Border,
                                    focusedContainerColor = SkoLabColors.Card2,
                                    unfocusedContainerColor = SkoLabColors.Card2
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (phoneInput.isNotBlank()) {
                                    showSMSInviteDialog = false
                                    val smsText = "Hi ${connection.author.name}, join me on SkoLab for secure, encrypted audio/video calling, real-time LaTeX blackboards, and joint manuscript editing: https://skolab.open/invite"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("smsto:${phoneInput.trim()}")
                                        putExtra("sms_body", smsText)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No SMS application found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = SkoLabColors.Cyan)
                        ) {
                            Text("Send SMS", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSMSInviteDialog = false }) {
                            Text("Cancel", color = SkoLabColors.Text3)
                        }
                    },
                    containerColor = SkoLabColors.Card,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricPill(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = JetBrainsMonoFontFamily)
        Text(text = label, color = SkoLabColors.Text3, fontSize = 8.sp, fontFamily = SpaceGroteskFontFamily)
    }
}

// ── COMPONENT: CompactPaperTile ──────────────────────────────────────────────
@Composable
fun CompactPaperTile(
    paper: Paper,
    borderCol: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier
            .width(140.dp)
            .height(180.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Accent Color Top Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(borderCol)
            )
            Column(modifier = Modifier.padding(10.dp)) {
                // Journal tag
                Text(
                    text = paper.journal.take(16).uppercase(),
                    color = borderCol,
                    fontSize = 8.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                // Score orb centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (paper.disruptionScore * 100).toInt().toString(),
                        color = SkoLabColors.Gold2,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Title
                Text(
                    text = paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = SkoLabColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )

                // Stats
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${paper.citationCount} cit",
                        color = SkoLabColors.Green,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "2026",
                        color = SkoLabColors.Text3,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }
            }
        }
    }
}

// ── INSTITUTION TILE ─────────────────────────────────────────────────────────
@Composable
fun InstitutionTile(institution: Institution, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SkoLabColors.Card)
                .border(BorderStroke(1.dp, SkoLabColors.Border), RoundedCornerShape(20.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(institution.color.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = institution.initials,
                    color = institution.color,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
        Text(
            text = institution.name,
            fontFamily = SpaceGroteskFontFamily,
            fontSize = 9.sp,
            color = SkoLabColors.Text2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── OPEN ACCESS CARD ─────────────────────────────────────────────────────────
@Composable
fun OpenAccessCard(
    paper: Paper,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier
            .width(280.dp)
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(SkoLabColors.Green)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SkoLabColors.Green.copy(alpha = 0.08f),
                        border = BorderStroke(0.5.dp, SkoLabColors.Green.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = "OPEN ACCESS",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = SkoLabColors.Green,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }

                    // Download button
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(24.dp)
                            .background(SkoLabColors.Card2, CircleShape)
                            .border(0.5.dp, SkoLabColors.Border, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Read",
                            tint = SkoLabColors.Green,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = SkoLabColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 10.sp,
                    color = SkoLabColors.Text3
                )
            }
        }
    }
}

// ── RESUME CARD ──────────────────────────────────────────────────────────────
@Composable
fun ResumeCard(
    progress: ReadingProgress,
    onClick: () -> Unit,
    onResume: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier
            .width(200.dp)
            .height(110.dp)
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // reading progress bar (vertical, 4dp wide, Gold fill, rounded)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(SkoLabColors.Border)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(progress.progressPercent / 100f)
                        .background(SkoLabColors.Gold1)
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = progress.paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = SkoLabColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = progress.paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 9.sp,
                    color = SkoLabColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progress.progressPercent}% read",
                        color = SkoLabColors.Gold2,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        onClick = onResume,
                        shape = RoundedCornerShape(8.dp),
                        color = SkoLabColors.Blue2.copy(alpha = 0.08f),
                        border = BorderStroke(0.5.dp, SkoLabColors.Blue2.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = "Resume →",
                            color = SkoLabColors.Blue2,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── SECTION HEADER ───────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    title: String,
    badgeCount: Int? = null,
    subtitle: String? = null,
    onSeeAll: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = SkoLabColors.Text
            )
            if (badgeCount != null) {
                Surface(
                    shape = CircleShape,
                    color = SkoLabColors.Card2,
                    border = BorderStroke(0.5.dp, SkoLabColors.Border)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = SkoLabColors.Gold2,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (subtitle != null) {
            Text(
                text = subtitle,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = SkoLabColors.Red,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = "See all →",
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                color = SkoLabColors.Gold2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onSeeAll() }
            )
        }
    }
}

// ── SHIMMER PAPER CARD ────────────────────────────────────────────────────────
@Composable
fun PaperShimmerCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShimmerBar(Modifier.width(120.dp).height(10.dp))
                ShimmerBar(Modifier.width(36.dp).height(10.dp))
            }
            Spacer(Modifier.height(12.dp))
            ShimmerBar(Modifier.fillMaxWidth().height(14.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBar(Modifier.fillMaxWidth(0.85f).height(14.dp))
            Spacer(Modifier.height(10.dp))
            ShimmerBar(Modifier.width(80.dp).height(8.dp))
        }
    }
}

private fun getCountdownText(): String {
    val calendar = Calendar.getInstance()
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val daysLeft = if (dayOfWeek == Calendar.SUNDAY) 0 else 8 - dayOfWeek
    return "Resets in $daysLeft days"
}

// ── Actions and challenge components now imported from com.company.skolab.ui.screens.feed.components ──



// ── PulseFeedCard: LinkedIn-style feed card ──

// ── generateClientMetadata: Client fallback generator for metadata ──
fun generateClientMetadata(title: String, abstractText: String?): Triple<String, List<String>, String> {
    val titleLower = title.lowercase()
    val abstractLower = (abstractText ?: "").lowercase()
    
    val methodology = when {
        titleLower.contains("neural") || titleLower.contains("transformer") || titleLower.contains("deep learning") || titleLower.contains("attention") ->
            "Deep Learning & Attention Matrix Optimization"
        titleLower.contains("quantum") || titleLower.contains("qubit") || titleLower.contains("superconducting") ->
            "Quantum Circuit Tomography & Coherence Analysis"
        titleLower.contains("genome") || titleLower.contains("sequence") || titleLower.contains("dna") || titleLower.contains("regulatory") ->
            "Genomic Motif Mapping & Sequence Alignment"
        titleLower.contains("gravitational") || titleLower.contains("cosmology") || titleLower.contains("astroph") ->
            "Numerical Relativity Boundary Solver"
        titleLower.contains("network") || titleLower.contains("collaboration") || titleLower.contains("workspace") ->
            "Collaboration Graph Network Analytics"
        titleLower.contains("cognitive") || titleLower.contains("eye-tracking") || titleLower.contains("behavioral") ->
            "Real-time Cognitive Load EEG Measurement"
        else -> "Empirical Analysis & Quantitative Modeling"
    }
    
    val tools = mutableListOf<String>()
    if (abstractLower.contains("pytorch") || titleLower.contains("pytorch")) tools.add("PyTorch")
    if (abstractLower.contains("tensorflow")) tools.add("TensorFlow")
    if (abstractLower.contains("cuda")) tools.add("CUDA C++")
    if (abstractLower.contains("jax")) tools.add("JAX")
    if (abstractLower.contains("gpu") || abstractLower.contains("h100")) tools.add("GPU Cluster")
    if (abstractLower.contains("qiskit")) tools.add("Qiskit Metal")
    if (abstractLower.contains("hfss")) tools.add("ANSYS HFSS")
    if (abstractLower.contains("cryo") || abstractLower.contains("dilution")) tools.add("Cryogenics")
    if (abstractLower.contains("blast")) tools.add("NCBI BLAST")
    if (abstractLower.contains("bioconductor") || abstractLower.contains("r/")) tools.add("R/Bioconductor")
    if (abstractLower.contains("nextflow")) tools.add("Nextflow")
    
    if (tools.isEmpty()) {
        if (titleLower.contains("quantum") || titleLower.contains("phys")) {
            tools.addAll(listOf("Mathematica", "Python (SciPy)", "HPC Cluster"))
        } else if (titleLower.contains("learn") || titleLower.contains("network") || titleLower.contains("ai") || titleLower.contains("model")) {
            tools.addAll(listOf("PyTorch", "Hugging Face", "Weights & Biases"))
        } else if (titleLower.contains("genom") || titleLower.contains("bio") || titleLower.contains("sequence")) {
            tools.addAll(listOf("RStudio", "MEME Suite", "BLAST"))
        } else {
            tools.addAll(listOf("Python (NumPy)", "MATLAB", "LaTeX"))
        }
    } else if (tools.size == 1) {
        tools.add("Python")
        tools.add("LaTeX")
    }

    val keyFindings = when {
        titleLower.contains("quantum") ->
            "Enhanced quantum coherence times and reduced state dephasing errors under environmental noise."
        titleLower.contains("attention") || titleLower.contains("transformer") ->
            "Reduced computational complexity and memory usage while preserving tasks downstream perplexity."
        titleLower.contains("genom") ->
            "Discovered conserved regulatory sequence motifs that control transcription in target organisms."
        titleLower.contains("gravitational") ->
            "Decreased boundary-reflection artifacts in wave propagation simulations by over 90%."
        titleLower.contains("collaboration") || titleLower.contains("workspace") ->
            "Verified that integrated co-author workspaces increase cross-disciplinary productivity metrics."
        titleLower.contains("cognitive") || titleLower.contains("behavioral") ->
            "Identified user interface feedback loops that significantly reduce subjective cognitive load."
        else -> "Demonstrated a robust model performance improvement and identified critical parameter bounds."
    }
    
    return Triple(methodology, tools, keyFindings)
}
