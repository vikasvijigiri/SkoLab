package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.FeedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PapersScreen(
    onPaperClick: (String) -> Unit,
    onNavigateToReader: (String, String) -> Unit,
    onProfileClick: () -> Unit,
    onTabNavigate: (String) -> Unit,
    viewModel: FeedViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        // Uniform dot grid background matching FeedScreen
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val dotSpacing = 28.dp.toPx()
            val dotRadius = 0.8.dp.toPx()
            val cols = (size.width / dotSpacing).toInt() + 1
            val rows = (size.height / dotSpacing).toInt() + 1
            for (col in 0..cols) {
                for (row in 0..rows) {
                    drawCircle(
                        color = Color(0xFF8891B8).copy(alpha = 0.04f),
                        radius = dotRadius,
                        center = androidx.compose.ui.geometry.Offset(col * dotSpacing, row * dotSpacing)
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Papers",
                    color = EntropiColors.Text,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)
                )
            }

            // Quick Filter Rail (only shows the user's specific research area/disciplines)
            item {
                val userFocus = uiState.user.researchFocus.lowercase()
                val userDisciplines = remember(uiState.disciplines) { uiState.disciplines.map { it.name.lowercase() } }
                val visibleFilters = remember(userFocus, userDisciplines) {
                    val list = mutableListOf(com.open.entropy.viewmodel.ResearchFilter.ALL)
                    com.open.entropy.viewmodel.ResearchFilter.values().forEach { filter ->
                        if (filter != com.open.entropy.viewmodel.ResearchFilter.ALL) {
                            val matchesFocus = userFocus.isNotBlank() && (
                                userFocus.contains(filter.label.lowercase()) ||
                                filter.label.lowercase().contains(userFocus) ||
                                (filter == com.open.entropy.viewmodel.ResearchFilter.PHYSICS && userFocus.contains("physic")) ||
                                (filter == com.open.entropy.viewmodel.ResearchFilter.AI && (userFocus.contains("computer") || userFocus.contains("machine learning") || userFocus.contains("artificial intelligence")))
                            )
                            val matchesDiscipline = userDisciplines.any { disc ->
                                disc.contains(filter.label.lowercase()) ||
                                filter.label.lowercase().contains(disc) ||
                                (filter == com.open.entropy.viewmodel.ResearchFilter.PHYSICS && disc.contains("physic")) ||
                                (filter == com.open.entropy.viewmodel.ResearchFilter.AI && (disc.contains("computer") || disc.contains("machine learning") || disc.contains("artificial intelligence")))
                            }
                            if (matchesFocus || matchesDiscipline) {
                                if (!list.contains(filter)) {
                                    list.add(filter)
                                }
                            }
                        }
                    }
                    list
                }
                PapersFilterRail(
                    selectedFilter = uiState.selectedFilter,
                    visibleFilters = visibleFilters,
                    onFilterSelect = { filter -> viewModel.setResearchFilter(filter) }
                )
            }

            // Trending Now (Full List)
            stickyHeader {
                SectionHeader(
                    title = "🔥 Trending Now",
                    onSeeAll = {} // Hide "See all" on this screen
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
                stickyHeader {
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
        }

        // Scroll to Top FAB
        val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showFab,
                enter = androidx.compose.animation.scaleIn(spring(stiffness = Spring.StiffnessLow)),
                exit = androidx.compose.animation.scaleOut(spring(stiffness = Spring.StiffnessLow))
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

@Composable
fun PapersFilterRail(
    selectedFilter: com.open.entropy.viewmodel.ResearchFilter,
    visibleFilters: List<com.open.entropy.viewmodel.ResearchFilter>,
    onFilterSelect: (com.open.entropy.viewmodel.ResearchFilter) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(visibleFilters) { filter ->
            val isActive = selectedFilter == filter
            Surface(
                onClick = { onFilterSelect(filter) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = if (isActive) EntropiColors.Gold1.copy(alpha = 0.12f) else EntropiColors.Card2,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) EntropiColors.Gold1 else EntropiColors.Border)
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

private fun getPapersCountdownText(): String {
    val calendar = java.util.Calendar.getInstance()
    val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
    val daysLeft = if (dayOfWeek == java.util.Calendar.SUNDAY) 0 else 8 - dayOfWeek
    return "Resets in $daysLeft days"
}
