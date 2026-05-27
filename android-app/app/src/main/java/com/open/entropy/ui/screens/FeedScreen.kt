package com.open.entropy.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.open.entropy.model.Paper
import com.open.entropy.model.Author
import com.open.entropy.model.UserConnection
import com.open.entropy.ui.components.ScoreArcMeter
import com.open.entropy.ui.components.MarkdownText
import com.open.entropy.ui.components.StreakCard
import com.open.entropy.ui.components.SwipeVaultCard
import com.open.entropy.ui.components.PaperCard
import com.open.entropy.ui.components.primitives.EmptyState
import com.open.entropy.ui.components.primitives.ErrorState
import com.open.entropy.ui.components.primitives.GlassSearchBar
import com.open.entropy.ui.screens.toAuthorResponse
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// ── ReQit Professional Flipkart Colors ─────────────────────────────────────
object EntropiColors {
    val Background = com.open.entropy.ui.theme.BgPrimary
    val Card = com.open.entropy.ui.theme.BgCard
    val Card2 = com.open.entropy.ui.theme.BgElevated
    val Border = com.open.entropy.ui.theme.BorderLight
    val Gold1 = com.open.entropy.ui.theme.AccentAmber
    val Gold2 = com.open.entropy.ui.theme.AccentAmber
    val Blue1 = com.open.entropy.ui.theme.AccentTeal
    val Blue2 = com.open.entropy.ui.theme.AccentTeal
    val Cyan = com.open.entropy.ui.theme.AccentCyan
    val Purple1 = com.open.entropy.ui.theme.AccentViolet
    val Purple2 = com.open.entropy.ui.theme.AccentViolet
    val Red = com.open.entropy.ui.theme.AccentRose
    val Green = com.open.entropy.ui.theme.AccentEmerald
    val Text = com.open.entropy.ui.theme.TextPrimary
    val Text2 = com.open.entropy.ui.theme.TextSecondary
    val Text3 = com.open.entropy.ui.theme.TextMuted
}

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
    viewModel: FeedViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val searchViewModel: SearchViewModel = viewModel()
    val searchUiState by searchViewModel.uiState.collectAsState()
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(0) } // 0 = Papers, 1 = Profiles
    var searchQuery by remember { mutableStateOf("") }
    var selectedCountryFilter by remember { mutableStateOf("Global") }

    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        searchQuery = ""
        searchViewModel.onSearchQueryChanged("")
    }


    val context = LocalContext.current
    val authManager = remember { com.open.entropy.auth.AuthManager(context) }
    val userPrefs = remember { com.open.entropy.data.UserPreferences(context) }
    val apiService = remember { com.open.entropy.network.ApiService() }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val connectionsList by userPrefs.userConnections.collectAsState(initial = emptyList())

    val isInitialLoad = remember { mutableStateOf(true) }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isInitialLoad.value = false
        }
        onLoadingStateChanged(uiState.isLoading && isInitialLoad.value)
    }


    LaunchedEffect(cachedUser) {
        val uid = cachedUser?.uid ?: "user_vikas"
        val userName = cachedUser?.name ?: "Vikas Vijigiri"
        val researchFocus = cachedUser?.researchFocus ?: "Researcher"
        viewModel.setUserContext(uid, userName, researchFocus)
    }

    LaunchedEffect(uiState.suggestedConnections.size) {
        android.util.Log.d("FeedScreen", "suggestedConnections size is now: ${uiState.suggestedConnections.size}")
        android.util.Log.d("FeedScreen", "isLoading is now: ${uiState.isLoading}")
    }

    // Scroll tracking moved directly to the items list below

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {
        // Cosmic Background Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Radial base glow top-right
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(EntropiColors.Purple1.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.8f, size.height * 0.2f),
                    radius = size.width * 0.6f
                ),
                radius = size.width * 0.6f
            )
            // Radial base glow bottom-left
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(EntropiColors.Blue1.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.8f),
                    radius = size.width * 0.6f
                ),
                radius = size.width * 0.6f
            )
        }

        // Main LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sticky top header
            stickyHeader {
                if (isSearchExpanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = EntropiColors.Background.copy(alpha = 0.95f),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Search Input field
                                Box(modifier = Modifier.weight(1f)) {
                                    GlassSearchBar(
                                        value = searchQuery,
                                        onValueChange = {
                                            searchQuery = it
                                            if (searchMode == 0) {
                                                searchViewModel.onSearchQueryChanged(it)
                                            }
                                        },
                                        placeholder = if (searchMode == 0) "Search papers, topics..." else "Search researchers...",
                                        onClear = {
                                            searchQuery = ""
                                            if (searchMode == 0) {
                                                searchViewModel.onSearchQueryChanged("")
                                            }
                                        }
                                    )
                                }
                            }
                            
                            // Row selector for Papers / Profiles
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = EntropiColors.Card2.copy(alpha = 0.8f),
                                border = BorderStroke(0.5.dp, EntropiColors.Border)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("Papers", "Profiles").forEachIndexed { index, title ->
                                        val isSelected = searchMode == index
                                        Surface(
                                            onClick = { 
                                                searchMode = index 
                                                searchQuery = ""
                                                searchViewModel.onSearchQueryChanged("")
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) EntropiColors.Blue1 else Color.Transparent
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Text(
                                                    text = title,
                                                    color = if (isSelected) Color.White else EntropiColors.Text3,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    TopBar(
                        user = uiState.user,
                        unreadCount = 3,
                        onSearchClick = { isSearchExpanded = true },
                        onProfileClick = onProfileClick,
                        onChatClick = onNavigateToChatList
                    )
                }
            }

            if (isSearchExpanded) {
                if (searchMode == 0) {
                    when (val state = searchUiState) {
                        is SearchUiState.Idle -> {
                            item {
                                EmptyState(
                                    title = "Explore the literature",
                                    message = "Search OpenAlex to surface papers and research quality signals.",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 80.dp)
                                )
                            }
                        }
                        is SearchUiState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 120.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = EntropiColors.Blue1)
                                }
                            }
                        }
                        is SearchUiState.Error -> {
                            item {
                                ErrorState(
                                    message = state.message,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 80.dp),
                                    onRetry = { searchViewModel.onSearchQueryChanged(searchQuery) }
                                )
                            }
                        }
                        is SearchUiState.Success -> {
                            if (state.results.isEmpty() && searchQuery.isNotBlank()) {
                                item {
                                    EmptyState(
                                        title = "No matches",
                                        message = "No results for \"$searchQuery\". Try a broader query.",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 80.dp)
                                    )
                                }
                            } else if (state.results.isNotEmpty()) {
                                items(state.results) { paper ->
                                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                                        PaperCard(
                                            paper = paper,
                                            onClick = { onPaperClick(paper.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(580.dp)
                        ) {
                            SearchProfilesScreen(
                                onAuthorClick = onAuthorClick,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                if (uiState.error != null) {
                    item {
                        Surface(
                            color = Color(0x22EF4444),
                            border = BorderStroke(1.dp, Color(0x66EF4444)),
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
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = "API Request Failed",
                                        color = Color(0xFFEF4444),
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

                // Gamified Streak Check-In Touchpoint
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        StreakCard(
                            onClick = onNavigateToLogicEngine
                        )
                    }
                }

                // Semantic Feed: Trending Now
                item {
                    SectionHeader(
                        title = "🔥 Trending Now",
                        onSeeAll = {} // Hide "See all" since it's an infinite semantic feed
                    )
                }
                
                if (uiState.isLoading && uiState.trendingPapers.isEmpty()) {
                    items(3) {
                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            PaperShimmerCard()
                        }
                    }
                } else {
                    itemsIndexed(uiState.trendingPapers) { index, paper ->
                        val animatedProgress = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            delay(index * 40L)
                            animatedProgress.animateTo(1f, animationSpec = tween(300))
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .graphicsLayer(
                                    alpha = animatedProgress.value,
                                    translationY = (1f - animatedProgress.value) * 20.dp.value
                                )
                        ) {
                            val accentColor = when (index % 3) {
                                0 -> EntropiColors.Gold1
                                1 -> EntropiColors.Cyan
                                else -> EntropiColors.Red
                            }
                            PaperFeedCard(
                                paper = paper,
                                accentColor = accentColor,
                                onClick = { onPaperClick(paper.id) }
                            )
                        }
                    }
                }

                // Continue Reading
                if (uiState.continueReading.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "📖 Continue Reading",
                            onSeeAll = {}
                        )
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(uiState.continueReading) { progress ->
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

                // Vertical Infinite Feed of Collaborators
                
                // NEW: People You May Know Header + Filter Chips
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.People, contentDescription = null, tint = EntropiColors.Blue1, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "People You May Know",
                                color = EntropiColors.Text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("Global", "USA", "UK", "India", "Germany", "Canada", "Australia", "France", "Japan")) { country ->
                                val isSelected = selectedCountryFilter == country
                                Surface(
                                    onClick = { selectedCountryFilter = country },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) EntropiColors.Blue1 else EntropiColors.Card2,
                                    border = BorderStroke(1.dp, if (isSelected) Color.Transparent else EntropiColors.Border)
                                ) {
                                    Text(
                                        text = country,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        color = if (isSelected) Color.White else EntropiColors.Text2,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                val filteredConnections = if (selectedCountryFilter == "Global") {
                    uiState.suggestedConnections
                } else {
                    uiState.suggestedConnections.filter { 
                        it.author.country.contains(selectedCountryFilter, ignoreCase = true) || 
                        it.author.institution.contains(selectedCountryFilter, ignoreCase = true) 
                    }
                }

                if (uiState.isLoading && filteredConnections.isEmpty()) {
                    items(5) {
                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            PaperShimmerCard() // Shimmer loader
                        }
                    }
                } else if (filteredConnections.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No connections found.",
                                color = EntropiColors.Text3,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    val chunkedList = filteredConnections.chunked(2)
                    itemsIndexed(chunkedList) { index, rowItems ->
                        // Trigger load more when reaching the end of the connections list
                        if (index >= chunkedList.size - 1 && !uiState.isLoadingMoreConnections && !uiState.isLoading) {
                            LaunchedEffect(index) {
                                viewModel.loadMoreConnections()
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            for (conn in rowItems) {
                                val isConnected = connectionsList.any { it.id == conn.author.id }
                                Box(modifier = Modifier.weight(1f)) {
                                    ConnectionCard(
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
                                        onChatClick = {
                                            onNavigateToChat(conn.author.name, conn.author.id)
                                        },
                                        onAuthorClick = {
                                            try {
                                                val preview = conn.author.toAuthorResponse()
                                                apiService.cacheAuthorProfile(conn.author.id, preview)
                                            } catch (e: Exception) {
                                                android.util.Log.e("FeedScreen", "Failed to cache author profile preview", e)
                                            }
                                            onAuthorClick("${conn.author.name}|${conn.author.id}")
                                        }
                                    )
                                }
                            }
                            if (rowItems.size < 2) {
                                Spacer(modifier = Modifier.weight((2 - rowItems.size).toFloat()))
                            }
                        }
                    }
                    if (uiState.isLoadingMoreConnections) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = EntropiColors.Blue1,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
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
                    containerColor = EntropiColors.Gold1,
                    contentColor = EntropiColors.Background,
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
    }
}

// ── COMPONENT 1: TopBar ──────────────────────────────────────────────────────
@Composable
fun TopBar(
    user: User,
    unreadCount: Int,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EntropiColors.Background.copy(alpha = 0.92f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Brand Logo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Sko",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    color = EntropiColors.Purple2
                )
                Text(
                    text = "Lab",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    color = EntropiColors.Cyan
                )
            }

            Spacer(Modifier.weight(1f))

            // Chat Icon Button
            IconButton(
                onClick = onChatClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EntropiColors.Card2)
                    .border(BorderStroke(1.dp, EntropiColors.Border), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Chats",
                    tint = EntropiColors.Text2,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Search Icon Button
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EntropiColors.Card2)
                    .border(BorderStroke(1.dp, EntropiColors.Border), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = EntropiColors.Text2,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Notification Bell with Badge
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EntropiColors.Card2)
                        .border(BorderStroke(1.dp, EntropiColors.Border), RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = "Notifications",
                        tint = EntropiColors.Text2,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(14.dp)
                            .background(EntropiColors.Red, CircleShape)
                            .border(1.dp, EntropiColors.Background, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = unreadCount.toString(),
                            color = Color.White,
                            fontSize = 8.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // User Profile Avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            colors = listOf(EntropiColors.Gold1, EntropiColors.Blue1, EntropiColors.Gold1)
                        )
                    )
                    .clickable { onProfileClick() }
                    .padding(1.5.dp)
                    .background(EntropiColors.Background, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.initials,
                    color = EntropiColors.Gold2,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── COMPONENT 2: FrontierPulseCard ───────────────────────────────────────────
@Composable
fun FrontierPulseCard(metrics: FrontierMetrics) {
    val dProgress by animateFloatAsState(
        targetValue = metrics.dIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "dProgress"
    )
    val sProgress by animateFloatAsState(
        targetValue = metrics.sIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "sProgress"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Gold glow top-right
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Gold1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.9f, size.height * 0.1f),
                            radius = size.width * 0.4f
                        ),
                        radius = size.width * 0.4f
                    )
                    // radial Blue glow bottom-left
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Blue1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.9f),
                            radius = size.width * 0.4f
                        ),
                        radius = size.width * 0.4f
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(EntropiColors.Gold1.copy(alpha = alpha), CircleShape)
                    )
                    Text(
                        text = "FRONTIER PULSE · LIVE",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Gold1,
                        letterSpacing = 1.2.sp
                    )
                }

                // Three Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // D-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.dIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Gold2
                        )
                        Text("D-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.dIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(EntropiColors.Border))

                    // S-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.sIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Blue2
                        )
                        Text("S-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.sIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(EntropiColors.Border))

                    // Total Papers
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = metrics.papersCount.toString(),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Purple2
                        )
                        Text("PAPERS", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${metrics.papersDelta} new", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }
                }

                // Arc meters row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = dProgress, label = "Disruption", size = 52.dp, color = EntropiColors.Gold1)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = sProgress, label = "Novelty", size = 52.dp, color = EntropiColors.Cyan)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = 0.82f, label = "Influence", size = 52.dp, color = EntropiColors.Purple2)
                    }
                }
            }
        }
    }
}

// ── COMPONENT 3: AIDailyBriefCard ────────────────────────────────────────────
@Composable
fun AIDailyBriefCard(
    briefText: String,
    isLoading: Boolean,
    userId: String
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Purple1.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Purple glow top-right
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Purple1.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.15f),
                            radius = size.width * 0.45f
                        ),
                        radius = size.width * 0.45f
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(EntropiColors.Purple1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Brain",
                            tint = EntropiColors.Purple2,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "AI DAILY BRIEF",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Purple2,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Today",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        color = EntropiColors.Text3,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isLoading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShimmerBar(Modifier.fillMaxWidth().height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.85f).height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.5f).height(12.dp))
                    }
                } else {
                    val annotatedString = buildAnnotatedString {
                        val parts = briefText.split("**")
                        parts.forEachIndexed { index, part ->
                            if (index % 2 == 1) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = EntropiColors.Gold2)) {
                                    append(part)
                                }
                            } else {
                                append(part)
                            }
                        }
                    }

                    Text(
                        text = annotatedString,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = EntropiColors.Text
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerBar(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerBar")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.12f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(EntropiColors.Text3.copy(alpha = alpha))
    )
}

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
                color = if (isActive) EntropiColors.Gold1.copy(alpha = 0.12f) else EntropiColors.Card2,
                border = BorderStroke(1.dp, if (isActive) EntropiColors.Gold1 else EntropiColors.Border)
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
                        color = if (isActive) EntropiColors.Gold2 else EntropiColors.Text2
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, if (isPressed) EntropiColors.Gold1 else EntropiColors.Border)
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
                color = EntropiColors.Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${country.paperCount} papers",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = EntropiColors.Gold2
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
                    color = EntropiColors.Text2,
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, if (isPressed) EntropiColors.Gold1 else EntropiColors.Border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left Accent Bar (Gold -> Blue)
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically)
                    .background(Brush.verticalGradient(colors = listOf(EntropiColors.Gold1, EntropiColors.Blue1)))
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
                            color = EntropiColors.Card2,
                            border = BorderStroke(0.5.dp, EntropiColors.Border)
                        ) {
                            Text(
                                text = paper.journal.take(18),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = EntropiColors.Gold2,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = EntropiColors.Purple1.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = "D·${String.format("%.2f", paper.disruptionScore)}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = EntropiColors.Purple2,
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
                            .background(EntropiColors.Gold1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (paper.noveltyScore * 100).toInt().toString(),
                            color = EntropiColors.Gold2,
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
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                // Authors
                Text(
                    text = paper.authors.firstOrNull()?.split("|")?.firstOrNull() ?: "Unknown Author",
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 11.sp,
                    color = EntropiColors.Text3,
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
                        Icon(Icons.Default.RemoveRedEye, null, tint = EntropiColors.Text3, modifier = Modifier.size(12.dp))
                        Text(text = "1.2k views", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Text3)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.FormatQuote, null, tint = EntropiColors.Green, modifier = Modifier.size(12.dp))
                        Text(text = "${paper.citationCount} citations", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green, fontWeight = FontWeight.Bold)
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
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
                            colors = listOf(EntropiColors.Gold1, EntropiColors.Blue1)
                        )
                    )
                    .padding(2.dp)
                    .background(EntropiColors.Card, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = researcher.name.take(1).uppercase(),
                    color = EntropiColors.Gold2,
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
                color = EntropiColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Inst
            Text(
                text = researcher.institution.take(16),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 10.sp,
                color = EntropiColors.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            // Stats Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EntropiColors.Card2, RoundedCornerShape(6.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "D·${(researcher.avgDisruptionScore * 100).toInt()}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Gold2
                    )
                    Text("Disruption", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = EntropiColors.Text3)
                }
                Box(modifier = Modifier.size(width = 0.5.dp, height = 18.dp).background(EntropiColors.Border))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "6.2k",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Green
                    )
                    Text("Citations", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = EntropiColors.Text3)
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
                        containerColor = if (isConnected) EntropiColors.Card2 else Color(0xFF2E7D32), // Dark Green
                        contentColor = if (isConnected) EntropiColors.Gold1 else Color.White
                    ),
                    border = BorderStroke(1.dp, if (isConnected) EntropiColors.Border else Color.Transparent)
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
                        containerColor = EntropiColors.Card2,
                        contentColor = EntropiColors.Text2
                    ),
                    border = BorderStroke(1.dp, EntropiColors.Border)
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
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onAuthorClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Row 1: Avatar | Name block | Depth + match ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Avatar with SkoLab status overlay
                Box(modifier = Modifier.size(36.dp)) {
                    // Initials circle
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(EntropiColors.Card2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connection.author.name.take(2).uppercase(),
                            color = EntropiColors.Text2,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    // SkoLab status badge — bottom-right corner
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(
                                if (connection.isOnSkoLab) Color(0xFF00E676)
                                else EntropiColors.Border
                            )
                            .border(1.5.dp, EntropiColors.Card, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (connection.isOnSkoLab) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "On SkoLab",
                                tint = Color.Black,
                                modifier = Modifier.size(7.dp)
                            )
                        } else {
                            Text(
                                text = "–",
                                color = EntropiColors.Text3,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 7.sp
                            )
                        }
                    }
                }

                // Name + role
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.author.name,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = EntropiColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val inferredRole = when {
                        connection.author.totalPapers > 50 -> "Professor"
                        connection.author.totalPapers > 15 -> "Postdoc"
                        else -> "Researcher"
                    }
                    Text(
                        text = "$inferredRole · ${connection.author.institution.ifEmpty { connection.author.country }.take(20)}",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 9.sp,
                        color = EntropiColors.Text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Depth badge + match % stacked
                val (depthLabel, depthColor) = when (connection.depth) {
                    1 -> "Direct" to EntropiColors.Green
                    2 -> "2nd" to EntropiColors.Blue2
                    else -> "3rd+" to EntropiColors.Text3
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = depthColor.copy(alpha = 0.1f),
                        border = BorderStroke(0.5.dp, depthColor.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = depthLabel,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            color = depthColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                    Text(
                        text = "${connection.mutualCount}% match",
                        color = EntropiColors.Green.copy(alpha = 0.9f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SpaceGroteskFontFamily
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 2: Slim metrics ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(value = connection.papersCollaborated.toString(), label = "Joint", color = EntropiColors.Blue1)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(EntropiColors.Border))
                MetricPill(value = connection.totalPublications.toString(), label = "Papers", color = EntropiColors.Text)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(EntropiColors.Border))
                MetricPill(value = "h${connection.hIndex}", label = "Index", color = EntropiColors.Purple1)
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 3: Action button(s) ───────────────────────────────────────
            if (connection.isOnSkoLab) {
                if (isConnectedExternal) {
                    Surface(
                        onClick = onChatClick,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = EntropiColors.Card2,
                        border = BorderStroke(1.dp, EntropiColors.Border)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("💬 Message", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EntropiColors.Gold2)
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
                                    Brush.horizontalGradient(listOf(Color(0xFF1565C0), Color(0xFF6A1B9A))),
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
                            color = Color(0xFF1B5E20).copy(alpha = 0.85f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("🤝 Collab", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    onClick = onInvite,
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, EntropiColors.Gold1.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(listOf(EntropiColors.Gold1.copy(alpha = 0.10f), EntropiColors.Gold2.copy(alpha = 0.06f))),
                            RoundedCornerShape(8.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Invite to SkoLab", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EntropiColors.Gold1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = JetBrainsMonoFontFamily)
        Text(text = label, color = EntropiColors.Text3, fontSize = 8.sp, fontFamily = SpaceGroteskFontFamily)
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
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
                        color = EntropiColors.Gold2,
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
                    color = EntropiColors.Text,
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
                        color = EntropiColors.Green,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "2026",
                        color = EntropiColors.Text3,
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
                .background(EntropiColors.Card)
                .border(BorderStroke(1.dp, EntropiColors.Border), RoundedCornerShape(20.dp))
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
            color = EntropiColors.Text2,
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .width(280.dp)
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(EntropiColors.Green)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = EntropiColors.Green.copy(alpha = 0.08f),
                        border = BorderStroke(0.5.dp, EntropiColors.Green.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = "OPEN ACCESS",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = EntropiColors.Green,
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
                            .background(EntropiColors.Card2, CircleShape)
                            .border(0.5.dp, EntropiColors.Border, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Read",
                            tint = EntropiColors.Green,
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
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 10.sp,
                    color = EntropiColors.Text3
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
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
                    .background(EntropiColors.Border)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(progress.progressPercent / 100f)
                        .background(EntropiColors.Gold1)
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = progress.paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = progress.paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 9.sp,
                    color = EntropiColors.Text3,
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
                        color = EntropiColors.Gold2,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Resume →",
                        color = EntropiColors.Blue2,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onResume() }
                    )
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
                color = EntropiColors.Text
            )
            if (badgeCount != null) {
                Surface(
                    shape = CircleShape,
                    color = EntropiColors.Card2,
                    border = BorderStroke(0.5.dp, EntropiColors.Border)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = EntropiColors.Gold2,
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
                color = EntropiColors.Red,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = "See all →",
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                color = EntropiColors.Gold2,
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
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
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
