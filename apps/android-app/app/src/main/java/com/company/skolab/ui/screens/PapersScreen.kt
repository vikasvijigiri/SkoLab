package com.company.skolab.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.model.ResearchFilter
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.FeedViewModel
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
                        color = PaperCardTint.copy(alpha = 0.04f),
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
                    color = SkoLabColors.Text,
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
                    val list = mutableListOf<ResearchFilter>(ResearchFilter.ALL)
                    ResearchFilter.entries.forEach { filter ->
                        if (filter != ResearchFilter.ALL) {
                            val matchesFocus = userFocus.isNotBlank() && (
                                userFocus.contains(filter.label.lowercase()) ||
                                filter.label.lowercase().contains(userFocus) ||
                                (filter == ResearchFilter.PHYSICS && userFocus.contains("physic")) ||
                                (filter == ResearchFilter.AI && (userFocus.contains("computer") || userFocus.contains("machine learning") || userFocus.contains("artificial intelligence")))
                            )
                            val matchesDiscipline = userDisciplines.any { disc ->
                                disc.contains(filter.label.lowercase()) ||
                                filter.label.lowercase().contains(disc) ||
                                (filter == ResearchFilter.PHYSICS && disc.contains("physic")) ||
                                (filter == ResearchFilter.AI && (disc.contains("computer") || disc.contains("machine learning") || disc.contains("artificial intelligence")))
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
                    title = "🔥 Trending Now"
                )
            }
            if (uiState.isLoading && uiState.trendingPapers.isEmpty()) {
                items(3) {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        PaperShimmerCard()
                    }
                }
            } else {
                items(uiState.trendingPapers, key = { it.id }) { paper ->
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        val accentColor = when (uiState.trendingPapers.indexOf(paper) % 3) {
                            0 -> SkoLabColors.Gold1
                            1 -> SkoLabColors.Cyan
                            else -> SkoLabColors.Red
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
                        title = "📖 Continue Reading"
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
    }
}

@Composable
fun PapersFilterRail(
    selectedFilter: ResearchFilter,
    visibleFilters: List<ResearchFilter>,
    onFilterSelect: (ResearchFilter) -> Unit
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
                color = if (isActive) SkoLabColors.Gold1.copy(alpha = 0.12f) else SkoLabColors.Card2,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) SkoLabColors.Gold1 else SkoLabColors.Border)
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

private fun getPapersCountdownText(): String {
    val calendar = java.util.Calendar.getInstance()
    val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
    val daysLeft = if (dayOfWeek == java.util.Calendar.SUNDAY) 0 else 8 - dayOfWeek
    return "Resets in $daysLeft days"
}
