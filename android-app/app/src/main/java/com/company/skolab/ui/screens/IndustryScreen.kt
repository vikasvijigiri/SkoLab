package com.company.skolab.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.data.UserPreferences
import com.company.skolab.ui.theme.*
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.viewmodel.IndustryViewModel
import com.company.skolab.model.OpportunityType
import com.company.skolab.model.AssistantProfessorRoadmap
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndustryScreen(
    viewModel: IndustryViewModel = viewModel(),
    onNavigateToAuthor: (String) -> Unit = {},
    onNavigateToReader: (String, String) -> Unit = { _, _ -> }
) {
    val opportunities by viewModel.opportunities.collectAsStateWithLifecycle()
    val roadmap by viewModel.roadmap.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingRoadmap by viewModel.isLoadingRoadmap.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsStateWithLifecycle(initialValue = null)

    var selectedStream by remember { mutableStateOf("FEED") } // "FEED", "JOBS", "TRENDING", "GRANTS"
    var showRoadmapSheet by remember { mutableStateOf(false) }
    var showPostSheet by remember { mutableStateOf(false) }
    var selectedOpportunityForDetail by remember { mutableStateOf<IndustryOpportunity?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(cachedUser) {
        val focus = cachedUser?.researchFocus
        if (focus.isNullOrBlank()) {
            viewModel.setError("Profile research focus is not configured. Please set your area of research in profile settings.")
        } else {
            viewModel.loadOpportunities(focus, name = cachedUser?.name)
            viewModel.loadRoadmap(cachedUser?.uid, cachedUser?.name ?: "Researcher", focus)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Launchpad Title and icons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Launchpad",
                    color = TEXT_PRIMARY,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Post opportunity icon
                    IconButton(
                        onClick = { showPostSheet = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(SURFACE_SUBTLE, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Post Opportunity",
                            tint = TEXT_PRIMARY,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    // Career Roadmap icon
                    IconButton(
                        onClick = { 
                            showRoadmapSheet = true
                            SkoLabAnalytics.logRoadmapOpened(cachedUser?.uid ?: "")
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(SURFACE_SUBTLE, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = "Career Roadmap",
                            tint = TEXT_PRIMARY,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Top Stream Selector Chips with Nice Icons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SURFACE_SUBTLE)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val streams = listOf(
                    Triple("FEED", "Feed", Icons.Default.Explore),
                    Triple("JOBS", "Jobs", Icons.Default.Work),
                    Triple("TRENDING", "Trending", Icons.AutoMirrored.Filled.TrendingUp),
                    Triple("GRANTS", "Grants", Icons.Default.AttachMoney)
                )
                
                streams.forEach { (streamId, label, icon) ->
                    val isSelected = selectedStream == streamId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) PRIMARY else Color.Transparent)
                            .clickable { selectedStream = streamId }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = label,
                                color = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Filter/sort opportunities
            val filteredOpps = remember(opportunities, selectedStream, cachedUser?.researchFocus) {
                val userFocus = cachedUser?.researchFocus ?: "AI"
                when (selectedStream) {
                    "JOBS" -> opportunities.filter { it.type == OpportunityType.JOB }
                    "GRANTS" -> opportunities.filter { it.type == OpportunityType.FUNDING }
                    "TRENDING" -> opportunities.sortedByDescending { opp ->
                        opp.matchScore ?: run {
                            val hash = kotlin.math.abs(opp.title.hashCode() + userFocus.hashCode())
                            val b = if (opp.title.contains(userFocus, ignoreCase = true) || opp.description.contains(userFocus, ignoreCase = true)) 88 else 74
                            (b + (hash % 12)).coerceAtMost(99)
                        }
                    }
                    else -> opportunities
                }
            }

            // Pager viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (error != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SURFACE_SUBTLE),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.padding(24.dp).fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Error Alert",
                                tint = IndicatorRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = error!!,
                                color = TEXT_PRIMARY,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (isLoading && opportunities.isEmpty()) {
                    CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                } else if (filteredOpps.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WorkOutline,
                            contentDescription = null,
                            tint = TEXT_MUTED,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No opportunities found in this feed",
                            color = TEXT_SECONDARY,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    val pagerState = rememberPagerState(pageCount = { filteredOpps.size })
                    
                    // Reduced padding around pager to maximize card sizes
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
                        pageSpacing = 12.dp
                    ) { page ->
                        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val pageHeight = size.height
                                    if (pageOffset > 0) {
                                        translationY = -pageOffset * pageHeight * 0.15f
                                        alpha = (1f - pageOffset).coerceIn(0f, 1f)
                                        scaleX = 1f - (pageOffset * 0.05f)
                                        scaleY = 1f - (pageOffset * 0.05f)
                                    } else {
                                        translationY = pageOffset * pageHeight
                                        val idxDiff = pageOffset.absoluteValue
                                        translationY += idxDiff * 24.dp.toPx()
                                        val scale = 1f - (idxDiff * 0.05f).coerceIn(0f, 0.15f)
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = (1f - (idxDiff * 0.25f)).coerceIn(0.1f, 1f)
                                    }
                                }
                                .zIndex(if (pageOffset.absoluteValue < 1f) 1f - pageOffset.absoluteValue else 0f)
                        ) {
                            val opp = filteredOpps[page]
                            LaunchpadReelsCard(
                                opp = opp,
                                userFocus = cachedUser?.researchFocus ?: "AI",
                                onNextClick = {
                                    if (pagerState.currentPage < filteredOpps.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                },
                                onPrevClick = {
                                    if (pagerState.currentPage > 0) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                },
                                prevEnabled = pagerState.currentPage > 0,
                                onViewDetailsClick = {
                                    selectedOpportunityForDetail = opp
                                }
                            )
                        }
                    }
                }
            }
        }

        // Career Roadmap Bottom Sheet
        if (showRoadmapSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRoadmapSheet = false },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.85f)
                        .fillMaxWidth()
                ) {
                    if (isLoadingRoadmap && roadmap == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = PRIMARY)
                        }
                    } else {
                        AssistantProfessorRoadmapScreen(
                            roadmap = roadmap,
                            userFocus = cachedUser?.researchFocus ?: "AI",
                            onNavigateToAuthor = onNavigateToAuthor
                        )
                    }
                }
            }
        }

        // Post Position Bottom Sheet
        if (showPostSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPostSheet = false },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.85f)
                        .fillMaxWidth()
                ) {
                    ProfessorPostingForm(onDismiss = { showPostSheet = false })
                }
            }
        }

        // Details Bottom Sheet (Fixes clumsy card layout)
        if (selectedOpportunityForDetail != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedOpportunityForDetail = null },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                OpportunityDetailSheet(
                    opp = selectedOpportunityForDetail!!,
                    userFocus = cachedUser?.researchFocus ?: "AI",
                    onClose = { selectedOpportunityForDetail = null }
                )
            }
        }
    }
}

@Composable
fun LaunchpadReelsCard(
    opp: IndustryOpportunity,
    userFocus: String,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    prevEnabled: Boolean,
    onViewDetailsClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onViewDetailsClick() },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, BORDER),
        colors = CardDefaults.cardColors(containerColor = SURFACE)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header row: Badge and Match Score
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val (typeLabel, typeColor) = when (opp.type) {
                        OpportunityType.JOB -> "JOB" to PRIMARY
                        OpportunityType.FUNDING -> "FUNDING" to WhatsAppTealGreen
                        OpportunityType.REQUIREMENT -> "REQUIREMENT" to CustomOrangeGold
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(typeColor.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = typeLabel,
                            color = typeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val matchScore = remember(opp.id, userFocus) {
                        opp.matchScore ?: run {
                            val hash = kotlin.math.abs(opp.title.hashCode() + userFocus.hashCode())
                            val base = if (opp.title.contains(userFocus, ignoreCase = true) || opp.description.contains(userFocus, ignoreCase = true)) 88 else 74
                            (base + (hash % 12)).coerceAtMost(99)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MATCH_SCORE_BG)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MATCH_SCORE_TEXT,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$matchScore% Match",
                                color = MATCH_SCORE_TEXT,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Title
                Text(
                    text = opp.title,
                    color = TEXT_PRIMARY,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 28.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Company/Funder
                Text(
                    text = opp.companyOrFunder,
                    color = TEXT_SECONDARY,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Info Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("Compensation", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = opp.amount.ifBlank { "Details Online" },
                                color = TEXT_PRIMARY,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("Deadline", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = opp.deadline.ifBlank { "Open Now" },
                                color = TEXT_PRIMARY,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // AI Match analysis section
                val relevance = opp.relevanceExplanation ?: "Matches your academic publications & $userFocus research portfolio."
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(12.dp))
                        .background(SURFACE_SUBTLE)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = PRIMARY,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("AI MATCH ANALYSIS", color = PRIMARY, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = relevance,
                                color = TEXT_PRIMARY,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Required Skills
                val skills = remember(opp.id) {
                    opp.requiredSkills.ifEmpty { listOf(userFocus, "Python", "Data Analysis") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Required Skills", color = TEXT_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        skills.take(3).forEach { skill ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SURFACE_SUBTLE)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(skill, color = TEXT_SECONDARY, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // Excerpt description
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Overview", color = TEXT_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = opp.description,
                        color = TEXT_SECONDARY,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action buttons footer (Always visible on card)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev button
                IconButton(
                    onClick = {
                        onPrevClick()
                    },
                    enabled = prevEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (prevEnabled) SURFACE_SUBTLE else SURFACE_SUBTLE.copy(alpha = 0.4f))
                        .border(BorderStroke(1.dp, BORDER), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = "Previous",
                        tint = if (prevEnabled) TEXT_PRIMARY else TEXT_MUTED
                    )
                }

                // Next/Skip button
                IconButton(
                    onClick = {
                        onNextClick()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SURFACE_SUBTLE)
                        .border(BorderStroke(1.dp, BORDER), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Skip",
                        tint = AccentRose
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // View details and apply button
                Button(
                    onClick = onViewDetailsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 140.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View Details", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun OpportunityDetailSheet(
    opp: IndustryOpportunity,
    userFocus: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var activeAiTool by remember { mutableStateOf<String?>(null) } // "cover", "sop"
    val checklistState = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Opportunity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val (typeLabel, typeColor) = when (opp.type) {
                OpportunityType.JOB -> "JOB" to PRIMARY
                OpportunityType.FUNDING -> "FUNDING" to WhatsAppTealGreen
                OpportunityType.REQUIREMENT -> "REQUIREMENT" to CustomOrangeGold
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(typeColor.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val matchScore = remember(opp.id, userFocus) {
                opp.matchScore ?: 85
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MATCH_SCORE_BG)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MATCH_SCORE_TEXT,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$matchScore% Match",
                        color = MATCH_SCORE_TEXT,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = opp.title,
            color = TEXT_PRIMARY,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 28.sp
        )
        Text(
            text = opp.companyOrFunder,
            color = TEXT_SECONDARY,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // Core Information
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SURFACE_SUBTLE)
                    .padding(12.dp)
            ) {
                Column {
                    Text("Compensation", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = opp.amount.ifBlank { "Details Online" },
                        color = TEXT_PRIMARY,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SURFACE_SUBTLE)
                    .padding(12.dp)
            ) {
                Column {
                    Text("Deadline", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = opp.deadline.ifBlank { "Open Now" },
                        color = TEXT_PRIMARY,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Description
        Text("Description", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = opp.description,
            color = TEXT_SECONDARY,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Eligibility
        if (opp.eligibility.isNotBlank()) {
            Text("Eligibility Criteria", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = opp.eligibility,
                color = TEXT_SECONDARY,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Required Skills
        val skills = remember(opp.id) {
            opp.requiredSkills.ifEmpty { listOf(userFocus, "Python", "Data Analysis") }
        }
        Text("Required Skills", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            skills.take(4).forEach { skill ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SURFACE_SUBTLE)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(skill, color = TEXT_SECONDARY, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Procedure Checklist
        val steps = remember(opp.id) {
            opp.procedureSteps.ifEmpty {
                listOf(
                    "Check official guidelines & eligibility parameters",
                    "Update academic CV with $userFocus key credentials",
                    "Draft brief statement of purpose (SOP)",
                    "Submit online application form via institutional portal"
                )
            }
        }

        Text("Application Roadmap & Checklist", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        steps.forEachIndexed { index, step ->
            val stepKey = "${opp.id}_step_$index"
            val isChecked = checklistState[stepKey] ?: false
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { checklistState[stepKey] = !isChecked }
                    .padding(vertical = 6.dp)
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checklistState[stepKey] = it },
                    colors = CheckboxDefaults.colors(checkedColor = PRIMARY)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = step,
                    color = if (isChecked) TEXT_MUTED else TEXT_SECONDARY,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // AI Assistant Toolkit
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(16.dp))
                .background(SURFACE_SUBTLE)
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = PRIMARY,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Application Toolkit",
                        color = TEXT_PRIMARY,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Generate custom outlines based on your publications and focus area.",
                    color = TEXT_MUTED,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { activeAiTool = if (activeAiTool == "cover") null else "cover" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "cover") PRIMARY else SURFACE,
                            contentColor = if (activeAiTool == "cover") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cover Letter", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { activeAiTool = if (activeAiTool == "sop") null else "sop" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "sop") PRIMARY else SURFACE,
                            contentColor = if (activeAiTool == "sop") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SOP Outline", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (activeAiTool != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SURFACE)
                            .padding(14.dp)
                    ) {
                        Column {
                            val draftTitle = if (activeAiTool == "cover") "Tailored Cover Letter Draft" else "SOP Research Goal Section"
                            val draftText = if (activeAiTool == "cover") {
                                "Dear Hiring Committee,\n\nI am writing to apply for the ${opp.title} position at ${opp.companyOrFunder}. As an active researcher in the field of $userFocus, my academic background aligns precisely with your objectives. Specifically, my prior publications in this field explore key methodologies necessary for your research goals. I look forward to contributing..."
                            } else {
                                "Statement of Purpose Outline:\n1. Introduction: Passion for advanced research in $userFocus.\n2. Research Goals: Detailed outline of the target problems at ${opp.companyOrFunder}.\n3. Fit: My prior experience in related areas provides a robust foundation to succeed."
                            }
                            Text(draftTitle, color = TEXT_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(draftText, color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Direct Apply Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                border = BorderStroke(1.dp, BORDER),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT_SECONDARY),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    if (opp.url.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(opp.url))
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "Apply URL not configured.", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1.5f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Now", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun AssistantProfessorRoadmapScreen(
    roadmap: AssistantProfessorRoadmap?,
    userFocus: String,
    onNavigateToAuthor: (String) -> Unit = {}
) {
    if (roadmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Roadmap currently syncing with your profile...", color = TEXT_SECONDARY)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Welcome Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(PRIMARY, SkyBlueMedium)))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "Welcome, ${roadmap.userName}",
                    color = TEXT_ON_PRIMARY,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Assistant Professor Tenure-Track Roadmap in $userFocus",
                    color = TEXT_ON_PRIMARY.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Peer Metrics Comparison
        Text("Peer Performance Comparison", color = TEXT_PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text("Compare your current metrics with successfully hired assistant professors.", color = TEXT_SECONDARY, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        val uMetrics = roadmap.userMetrics
        val tMetrics = roadmap.targetMetrics

        val metricsList = listOf(
            Triple("h-Index", uMetrics.hIndex.toFloat() / tMetrics.hIndex, "${uMetrics.hIndex} / ${tMetrics.hIndex}"),
            Triple("Total Publications", uMetrics.worksCount.toFloat() / tMetrics.worksCount, "${uMetrics.worksCount} / ${tMetrics.worksCount}"),
            Triple("Citations", uMetrics.citationCount.toFloat() / tMetrics.citationCount, "${uMetrics.citationCount} / ${tMetrics.citationCount}"),
            Triple("Disruption Score", uMetrics.disruptionScore / tMetrics.disruptionScore, String.format("%.2f / %.2f", uMetrics.disruptionScore, tMetrics.disruptionScore))
        )

        metricsList.forEach { (label, ratio, display) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TEXT_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = display,
                            color = if (ratio >= 1.0f) WhatsAppTealGreen else PRIMARY,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ratio.coerceAtMost(1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (ratio >= 1.0f) WhatsAppTealGreen else PRIMARY,
                        trackColor = SURFACE_SUBTLE
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Milestones Timeline
        Text("Academic Career Milestones", color = TEXT_PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(12.dp))

        roadmap.milestones.forEachIndexed { index, milestone ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                when (milestone.status) {
                                    "Completed" -> WhatsAppTealGreen
                                    "Current" -> PRIMARY
                                    else -> BORDER
                                }
                            )
                    )
                    if (index < roadmap.milestones.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(50.dp)
                                .background(BORDER)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(milestone.title, color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (milestone.status) {
                                        "Completed" -> WhatsAppTealGreen.copy(alpha = 0.1f)
                                        "Current" -> PRIMARY.copy(alpha = 0.1f)
                                        else -> SURFACE_SUBTLE
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = milestone.status.uppercase(),
                                color = when (milestone.status) {
                                    "Completed" -> WhatsAppTealGreen
                                    "Current" -> PRIMARY
                                    else -> TEXT_MUTED
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text("${milestone.date} · ${milestone.description}", color = TEXT_SECONDARY, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Peer co-authors recommendations
        Text("Peer Networking Guide", color = TEXT_PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text("Collaborating with these active co-authors improves h-Index trajectory.", color = TEXT_SECONDARY, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        roadmap.peerCoauthors.forEach { coauthor ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onNavigateToAuthor(coauthor.name) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PRIMARY.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = coauthor.name.trim().take(1).uppercase(),
                                color = PRIMARY,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(coauthor.name, color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(coauthor.institution, color = TEXT_SECONDARY, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PRIMARY.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("${coauthor.match} Match", color = PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Working Templates
        Text("Application Outlines & Templates", color = TEXT_PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))

        roadmap.templates.forEach { template ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(template.name, color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(template.description, color = TEXT_SECONDARY, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    val context = LocalContext.current
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(template.downloadUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SURFACE_SUBTLE)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = PRIMARY)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ProfessorPostingForm(onDismiss: () -> Unit = {}) {
    var title by remember { mutableStateOf("") }
    var titleError by remember { mutableStateOf(false) }
    var institution by remember { mutableStateOf("") }
    var institutionError by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(OpportunityType.JOB) }
    var description by remember { mutableStateOf("") }
    var descriptionError by remember { mutableStateOf(false) }
    var tagsString by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    var postSuccess by remember { mutableStateOf(false) }

    if (postSuccess) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(WhatsAppTealGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = WhatsAppTealGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Opportunity Posted Successfully!",
                    color = TEXT_PRIMARY,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Your research opportunity is now live and matching in researchers' Launchpad universe.",
                    color = TEXT_SECONDARY,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        title = ""
                        institution = ""
                        description = ""
                        tagsString = ""
                        url = ""
                        titleError = false
                        institutionError = false
                        descriptionError = false
                        urlError = false
                        postSuccess = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Post a New Research Position",
                    color = TEXT_PRIMARY,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Recruit PhDs, Postdocs, or share funding calls with active SkoLab researchers.",
                    color = TEXT_SECONDARY,
                    fontSize = 12.sp
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    border = BorderStroke(1.dp, BORDER),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Position Details",
                            color = TEXT_PRIMARY,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        OutlinedTextField(
                            value = title,
                            onValueChange = { 
                                title = it
                                if (titleError) titleError = it.isBlank()
                            },
                            label = { Text("Opportunity Title") },
                            placeholder = { Text("e.g. Postdoc in Computational Psychiatry") },
                            shape = RoundedCornerShape(12.dp),
                            isError = titleError,
                            supportingText = {
                                if (titleError) {
                                    Text("Title is required", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY,
                                unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = institution,
                            onValueChange = { 
                                institution = it
                                if (institutionError) institutionError = it.isBlank()
                            },
                            label = { Text("Institution / Lab") },
                            placeholder = { Text("e.g. MIT Neural Systems Lab") },
                            shape = RoundedCornerShape(12.dp),
                            isError = institutionError,
                            supportingText = {
                                if (institutionError) {
                                    Text("Institution / Lab is required", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY,
                                unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Column {
                            Text(
                                text = "Opportunity Type",
                                color = TEXT_PRIMARY,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(OpportunityType.JOB, OpportunityType.FUNDING, OpportunityType.REQUIREMENT).forEach { type ->
                                    val isSelected = selectedType == type
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) PRIMARY else SURFACE_SUBTLE)
                                            .border(
                                                BorderStroke(1.dp, if (isSelected) PRIMARY else BORDER),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { selectedType = type }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = type.name,
                                            color = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    border = BorderStroke(1.dp, BORDER),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Requirements & Application",
                            color = TEXT_PRIMARY,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { 
                                description = it
                                if (descriptionError) descriptionError = it.isBlank()
                            },
                            label = { Text("Job / Funding Description") },
                            placeholder = { Text("Provide details about the project, responsibilities, and timeline...") },
                            shape = RoundedCornerShape(12.dp),
                            minLines = 4,
                            isError = descriptionError,
                            supportingText = {
                                if (descriptionError) {
                                    Text("Description is required", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY,
                                unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = tagsString,
                            onValueChange = { tagsString = it },
                            label = { Text("Keywords / Skills") },
                            placeholder = { Text("e.g. NLP, PyTorch, Bioinformatics") },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY,
                                unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = url,
                            onValueChange = { 
                                url = it
                                if (urlError) urlError = it.isNotBlank() && !android.util.Patterns.WEB_URL.matcher(it).matches()
                            },
                            label = { Text("Application / Information URL") },
                            placeholder = { Text("e.g. https://lab.mit.edu/careers/postdoc1") },
                            shape = RoundedCornerShape(12.dp),
                            isError = urlError,
                            supportingText = {
                                if (urlError) {
                                    Text("Please enter a valid website URL", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY,
                                unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        titleError = title.isBlank()
                        institutionError = institution.isBlank()
                        descriptionError = description.isBlank()
                        urlError = url.isNotBlank() && !android.util.Patterns.WEB_URL.matcher(url).matches()
                        
                        if (!titleError && !institutionError && !descriptionError && !urlError) {
                            postSuccess = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PRIMARY,
                        contentColor = TEXT_ON_PRIMARY
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Submit & Publish Opportunity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
