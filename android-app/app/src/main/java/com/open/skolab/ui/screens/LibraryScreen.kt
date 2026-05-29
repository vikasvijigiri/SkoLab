package com.open.skolab.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.network.ApiService
import com.open.skolab.di.AppDependencies
import com.open.skolab.network.DailyFeedItem
import com.open.skolab.ui.components.ScientificCard
import com.open.skolab.ui.components.ScientificBadge
import com.open.skolab.ui.layout.ScreenInsets
import com.open.skolab.ui.theme.*
import com.open.skolab.viewmodel.LibraryViewModel
import com.open.skolab.viewmodel.SavedPapersUiState
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────
// LIBRARY SCREEN — Saved Papers + Grants + Nexus Bridges + Alerts
// ─────────────────────────────────────────────────────────────────

@Composable
fun LibraryScreen(onPaperClick: (String) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("SAVED", "GRANTS 🏆", "BRIDGES", "DAILY FEED")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgPrimary)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp)
        ) {
            Text(
                text = "INTEL VAULT",
                style = Typography.labelSmall,
                color = AccentTeal,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Your Research Library",
                style = Typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // Tabs
            SecondaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AccentTeal,
                edgePadding = 0.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = Typography.labelSmall,
                                color = if (selectedTab == index) AccentTeal else TextMuted,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SavedPapersTab(onPaperClick)
                1 -> GrantFeedTab()
                2 -> NexusBridgesTab()
                3 -> DailyFeedTab(onPaperClick)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// SAVED PAPERS TAB (existing, cleaned up)
// ─────────────────────────────────────────────────────────────────

@Composable
fun SavedPapersTab(
    onPaperClick: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is SavedPapersUiState.Loading -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                items(3) { SavedPaperShimmer() }
            }
        }
        is SavedPapersUiState.Empty -> {
            EmptyStateView(
                Icons.Default.Bookmark,
                "No saved papers yet",
                "Tap the bookmark icon on any paper to save it here"
            )
        }
        is SavedPapersUiState.Error -> {
            EmptyStateView(
                Icons.Default.ErrorOutline,
                "Could not load saved papers",
                state.message
            )
        }
        is SavedPapersUiState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AccentTealLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Bookmark, null, tint = AccentTeal, modifier = Modifier.size(18.dp))
                            Text(
                                "${state.papers.size} paper${if (state.papers.size != 1) "s" else ""} saved",
                                color = AccentTealDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                items(state.papers) { paper ->
                    SavedPaperCard(
                        title = paper.title,
                        authors = paper.authors,
                        journal = paper.journal,
                        year = paper.year,
                        citedByCount = paper.citedByCount,
                        onClick = { onPaperClick(paper.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SavedPaperShimmer() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(80.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AccentTeal.copy(alpha = alpha * 0.1f),
                            Color.Transparent,
                            AccentIndigo.copy(alpha = alpha * 0.08f)
                        )
                    )
                )
        )
    }
}

@Composable
fun SavedPaperCard(
    title: String,
    authors: List<String>,
    journal: String,
    year: Int,
    citedByCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
            Text(
                title,
                style = Typography.titleMedium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            if (authors.isNotEmpty()) {
                Text(
                    authors.joinToString(", "),
                    color = AccentTeal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$journal  •  $year",
                    color = TextMuted,
                    fontSize = 10.sp
                )
                Surface(shape = RoundedCornerShape(6.dp), color = AccentIndigoLight) {
                    Text(
                        if (citedByCount >= 1000) "${citedByCount / 1000}K citations" else "$citedByCount citations",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        color = AccentIndigo,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// GRANTS TAB
// ─────────────────────────────────────────────────────────────────

@Composable
fun GrantFeedTab() {
    val activeAuthor by com.open.skolab.state.ActiveResearcherState.activeAuthor.collectAsState()
    val api = AppDependencies.apiService
    var grants by remember { mutableStateOf<List<com.open.skolab.network.GrantMatch>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeAuthor) {
        isLoading = true
        errorMsg = null
        try {
            val authorId = activeAuthor?.id ?: "A5023888391"
            val results = api.matchGrants(authorId)
            grants = results
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to match grants"
        } finally {
            isLoading = false
        }
    }

    if (isLoading) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            items(3) { SavedPaperShimmer() }
        }
        return
    }

    if (errorMsg != null) {
        EmptyStateView(
            Icons.Default.ErrorOutline,
            "Could not match grants",
            errorMsg ?: "Unknown error"
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AccentAmberLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.EmojiEvents, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            text = if (activeAuthor != null) "${grants.size} grants matching ${activeAuthor?.display_name}" else "${grants.size} prestigious STEM funding opportunities",
                            color = AccentAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Scored based on publication velocity and citation impact",
                            color = AccentAmber.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        itemsIndexed(grants) { _, grant ->
            GrantCard(grant)
        }
    }
}

@Composable
fun GrantCard(grant: com.open.skolab.network.GrantMatch) {
    var expanded by remember { mutableStateOf(false) }
    val colorHex = grant.agency_color
    val parsedColor = remember(colorHex) {
        try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            AccentTeal
        }
    }
    val urgencyColor = when {
        grant.days_left < 30 -> AccentRose
        grant.days_left < 60 -> AccentAmber
        else                -> AccentEmerald
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Top colored bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(parsedColor)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                // Agency + deadline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = parsedColor.copy(alpha = 0.1f)) {
                        Text(
                            grant.agency,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = parsedColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = urgencyColor.copy(alpha = 0.1f)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer, null, tint = urgencyColor, modifier = Modifier.size(11.dp))
                            Text(
                                "${grant.days_left}d left",
                                color = urgencyColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(grant.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AttachMoney, null, tint = AccentEmerald, modifier = Modifier.size(13.dp))
                            Text(grant.amount, color = AccentEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(grant.field, color = TextMuted, fontSize = 11.sp)
                    }
                    // Match score ring
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { grant.match_score / 100f },
                            modifier = Modifier.size(40.dp),
                            color = parsedColor,
                            trackColor = BorderLight,
                            strokeWidth = 3.dp
                        )
                        Text(
                            "${grant.match_score}%",
                            color = parsedColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderLight)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "AI match analysis",
                        color = AccentViolet,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand",
                        tint = AccentViolet,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = grant.rationale,
                        style = Typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// NEXUS BRIDGES TAB (existing, cleaned up)
// ─────────────────────────────────────────────────────────────────

@Composable
fun NexusBridgesTab() {
    val bridges = listOf("Quantum ↔ AI", "Bio ↔ Materials", "Neuro ↔ Logic")
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        items(bridges) { bridge ->
            ScientificCard(glowColor = AccentViolet.copy(alpha = 0.05f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(text = bridge, style = Typography.titleLarge, color = TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Text("Discovered semantic connection with high potential.", style = Typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DAILY FEED TAB
// ─────────────────────────────────────────────────────────────────

@Composable
fun DailyFeedTab(
    onPaperClick: (String) -> Unit,
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val activeAuthor by com.open.skolab.state.ActiveResearcherState.activeAuthor.collectAsState()
    val api = AppDependencies.apiService
    var feedItems by remember { mutableStateOf<List<DailyFeedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savedIds by libraryViewModel.savedIds.collectAsState()

    LaunchedEffect(activeAuthor) {
        isLoading = true
        errorMsg = null
        try {
            val authorId = activeAuthor?.id ?: "A5023888391"
            val fallbackQuery = activeAuthor?.field_of_study ?: "computer science"
            val results = api.getDailyFeed(authorId, fallbackQuery)
            feedItems = results
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to load daily feed"
        } finally {
            isLoading = false
        }
    }

    if (isLoading) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            items(3) { SavedPaperShimmer() }
        }
        return
    }

    if (errorMsg != null) {
        EmptyStateView(
            Icons.Default.ErrorOutline,
            "Could not load daily feed",
            errorMsg ?: "Unknown error"
        )
        return
    }

    if (feedItems.isEmpty()) {
        EmptyStateView(
            Icons.AutoMirrored.Filled.Feed,
            "No recommendations yet",
            "Try selecting a researcher to build customized recommendations"
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AccentTealLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            text = if (activeAuthor != null) "Personalized for ${activeAuthor?.display_name}" else "Daily AI Research Feed",
                            color = AccentTealDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Fresh recommendations based on your primary expertise",
                            color = AccentTealDark.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        items(feedItems) { item ->
            var expanded by remember { mutableStateOf(false) }
            val isSaved = savedIds.contains(item.id)

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentTealLight
                        ) {
                            Text(
                                text = "${item.relevance_score}% MATCH",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = AccentTealDark,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    libraryViewModel.toggleSaved(item.id)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark",
                                tint = if (isSaved) AccentTeal else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.title,
                        style = Typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.clickable { onPaperClick(item.id) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.authors.joinToString(", "),
                        color = AccentIndigo,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${item.journal} • ${item.year}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = AccentViolet,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "AI recommendation analysis",
                                color = AccentViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand",
                            tint = AccentViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = item.recommendation_reason,
                            style = Typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// EMPTY STATE
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmptyStateView(icon: ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = BgElevated) {
                Icon(icon, null, modifier = Modifier.size(56.dp).padding(14.dp), tint = TextMuted)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = Typography.titleMedium, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Typography.bodySmall, color = TextMuted)
        }
    }
}

