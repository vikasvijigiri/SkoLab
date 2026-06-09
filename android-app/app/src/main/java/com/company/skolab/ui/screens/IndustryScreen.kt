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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.company.skolab.model.AssistantProfessorRoadmap
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.model.OpportunityType
import com.company.skolab.model.PositionLevel
import com.company.skolab.model.RemoteType
import com.company.skolab.ui.theme.*
import com.company.skolab.utils.IndustryMatchUtils
import com.company.skolab.utils.IndustryMatchUtils.DeadlineUrgency
import com.company.skolab.viewmodel.IndustryViewModel
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndustryScreen(
    viewModel: IndustryViewModel = viewModel(),
    onNavigateToAuthor: (String) -> Unit = {},
    onNavigateToReader: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val opportunities    by viewModel.opportunities.collectAsStateWithLifecycle()
    val roadmap          by viewModel.roadmap.collectAsStateWithLifecycle()
    val isLoading        by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingRoadmap by viewModel.isLoadingRoadmap.collectAsStateWithLifecycle()
    val error            by viewModel.error.collectAsStateWithLifecycle()
    val bookmarkedIds    by viewModel.bookmarkedIds.collectAsStateWithLifecycle()

    val userPrefs  = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsStateWithLifecycle(initialValue = null)

    var selectedStream              by remember { mutableStateOf("FEED") }
    var showRoadmapSheet            by remember { mutableStateOf(false) }
    var showPostSheet               by remember { mutableStateOf(false) }
    var selectedOpportunityForDetail by remember { mutableStateOf<IndustryOpportunity?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.initBookmarks(context) }

    LaunchedEffect(cachedUser) {
        val focus = cachedUser?.researchFocus
        if (focus.isNullOrBlank()) {
            viewModel.setError("Research focus not set. Update it in Profile settings.")
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
            // ── Header ────────────────────────────────────────────────────────
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
                    IconButton(
                        onClick = { showPostSheet = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(SURFACE_SUBTLE, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Post Opportunity",
                            tint = TEXT_PRIMARY, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = {
                            showRoadmapSheet = true
                            SkoLabAnalytics.logRoadmapOpened(cachedUser?.uid ?: "")
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(SURFACE_SUBTLE, CircleShape)
                    ) {
                        Icon(Icons.Default.Timeline, contentDescription = "Career Roadmap",
                            tint = TEXT_PRIMARY, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── Stream Selector ───────────────────────────────────────────────
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
                    Triple("FEED",     "Feed",     Icons.Default.Explore),
                    Triple("JOBS",     "Jobs",     Icons.Default.Work),
                    Triple("TRENDING", "Trending", Icons.AutoMirrored.Filled.TrendingUp),
                    Triple("GRANTS",   "Grants",   Icons.Default.AttachMoney)
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
                            Icon(icon, contentDescription = label,
                                tint = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(label,
                                color = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Filtered list ─────────────────────────────────────────────────
            val userFocus = cachedUser?.researchFocus ?: ""
            val filteredOpps = remember(opportunities, selectedStream, userFocus) {
                when (selectedStream) {
                    "JOBS"     -> opportunities.filter { it.type == OpportunityType.JOB }
                    "GRANTS"   -> opportunities.filter { it.type == OpportunityType.FUNDING }
                    "TRENDING" -> opportunities.sortedByDescending {
                        it.matchScore ?: IndustryMatchUtils.computeMatchScore(userFocus, it)
                    }
                    else       -> opportunities
                }
            }

            // ── Pager Viewport ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    error != null -> ErrorCard(error!!)

                    isLoading && opportunities.isEmpty() ->
                        CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)

                    filteredOpps.isEmpty() -> EmptyOpportunitiesState(selectedStream)

                    else -> {
                        val pagerState = rememberPagerState(pageCount = { filteredOpps.size })
                        VerticalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp, bottom = 12.dp, start = 12.dp, end = 12.dp
                            ),
                            pageSpacing = 12.dp
                        ) { page ->
                            val pageOffset =
                                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val h = size.height
                                        if (pageOffset > 0) {
                                            translationY = -pageOffset * h * 0.15f
                                            alpha  = (1f - pageOffset).coerceIn(0f, 1f)
                                            scaleX = 1f - pageOffset * 0.05f
                                            scaleY = 1f - pageOffset * 0.05f
                                        } else {
                                            val diff = pageOffset.absoluteValue
                                            translationY = pageOffset * h + diff * 24.dp.toPx()
                                            val sc = 1f - (diff * 0.05f).coerceIn(0f, 0.15f)
                                            scaleX = sc; scaleY = sc
                                            alpha = (1f - diff * 0.25f).coerceIn(0.1f, 1f)
                                        }
                                    }
                                    .zIndex(
                                        if (pageOffset.absoluteValue < 1f)
                                            1f - pageOffset.absoluteValue
                                        else 0f
                                    )
                            ) {
                                val opp = filteredOpps[page]
                                val computedScore = remember(opp.id, userFocus) {
                                    opp.matchScore
                                        ?: IndustryMatchUtils.computeMatchScore(userFocus, opp)
                                }
                                LaunchpadReelsCard(
                                    opp          = opp,
                                    userFocus    = userFocus,
                                    matchScore   = computedScore,
                                    isBookmarked = opp.id in bookmarkedIds,
                                    onBookmark   = { viewModel.toggleBookmark(opp.id) },
                                    onSkip       = {
                                        if (pagerState.currentPage < filteredOpps.size - 1) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    pagerState.currentPage + 1
                                                )
                                            }
                                        }
                                    },
                                    onViewDetails = { selectedOpportunityForDetail = opp }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Career Roadmap Sheet ───────────────────────────────────────────────
        if (showRoadmapSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRoadmapSheet = false },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                Box(modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth()) {
                    if (isLoadingRoadmap && roadmap == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = PRIMARY)
                        }
                    } else {
                        AssistantProfessorRoadmapScreen(
                            roadmap = roadmap,
                            userFocus = cachedUser?.researchFocus ?: "",
                            onNavigateToAuthor = onNavigateToAuthor
                        )
                    }
                }
            }
        }

        // ── Post Opportunity Sheet ────────────────────────────────────────────
        if (showPostSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPostSheet = false },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                Box(modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth()) {
                    ProfessorPostingForm(onDismiss = { showPostSheet = false })
                }
            }
        }

        // ── Opportunity Detail Sheet ──────────────────────────────────────────
        if (selectedOpportunityForDetail != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedOpportunityForDetail = null },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                OpportunityDetailSheet(
                    opp       = selectedOpportunityForDetail!!,
                    userFocus = cachedUser?.researchFocus ?: "",
                    userName  = cachedUser?.name ?: "",
                    viewModel = viewModel,
                    onClose   = { selectedOpportunityForDetail = null }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reels-style card — lightweight face with urgency strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LaunchpadReelsCard(
    opp: IndustryOpportunity,
    userFocus: String,
    matchScore: Int,
    isBookmarked: Boolean,
    onBookmark: () -> Unit,
    onSkip: () -> Unit,
    onViewDetails: () -> Unit
) {
    val deadlineInfo = remember(opp.deadline) {
        IndustryMatchUtils.deadlineLabel(opp.deadline)
    }
    val urgencyColor = when (deadlineInfo?.second) {
        DeadlineUrgency.CRITICAL -> ErrorRed
        DeadlineUrgency.URGENT   -> AccentAmber
        DeadlineUrgency.EXPIRED  -> TEXT_MUTED
        else                     -> WhatsAppTealGreen
    }
    val (typeLabel, typeColor) = when (opp.type) {
        OpportunityType.JOB         -> "JOB"         to PRIMARY
        OpportunityType.FUNDING     -> "FUNDING"      to WhatsAppTealGreen
        OpportunityType.REQUIREMENT -> "REQUIREMENT"  to CustomOrangeGold
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onViewDetails() },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, BORDER),
        colors = CardDefaults.cardColors(containerColor = SURFACE)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left urgency strip
            val stripColor = when (deadlineInfo?.second) {
                DeadlineUrgency.CRITICAL -> ErrorRed
                DeadlineUrgency.URGENT   -> AccentAmber
                DeadlineUrgency.EXPIRED  -> TEXT_MUTED
                else                     -> BORDER
            }
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(
                            topStart = 24.dp, bottomStart = 24.dp,
                            topEnd = 0.dp, bottomEnd = 0.dp
                        )
                    )
                    .background(stripColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Type badge + Match score
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(typeColor.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(typeLabel, color = typeColor,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MATCH_SCORE_BG)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null,
                                    tint = MATCH_SCORE_TEXT, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("$matchScore% Match", color = MATCH_SCORE_TEXT,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Title
                    Text(
                        text = opp.title,
                        color = TEXT_PRIMARY,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Institution
                    Text(
                        text = opp.companyOrFunder,
                        color = TEXT_SECONDARY,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Location row (new)
                    val locationParts = buildList {
                        if (opp.location.isNotBlank())              add(opp.location)
                        if (opp.remoteType != RemoteType.UNSPECIFIED) add(opp.remoteType.displayLabel())
                        if (opp.positionLevel != PositionLevel.UNSPECIFIED) add(opp.positionLevel.displayLabel())
                    }
                    if (locationParts.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null,
                                tint = TEXT_MUTED, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = locationParts.joinToString(" · "),
                                color = TEXT_MUTED,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Compensation + Deadline chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Compensation
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SURFACE_SUBTLE)
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("Compensation", color = TEXT_MUTED,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = opp.amount.ifBlank { "See details" },
                                    color = TEXT_PRIMARY,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Deadline with urgency color
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (deadlineInfo?.second == DeadlineUrgency.CRITICAL ||
                                        deadlineInfo?.second == DeadlineUrgency.URGENT)
                                        urgencyColor.copy(alpha = 0.08f)
                                    else SURFACE_SUBTLE
                                )
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("Deadline", color = TEXT_MUTED,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = deadlineInfo?.first ?: opp.deadline.ifBlank { "Open" },
                                    color = if (deadlineInfo != null &&
                                        deadlineInfo.second != DeadlineUrgency.OPEN)
                                        urgencyColor else TEXT_PRIMARY,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // AI Match analysis (2 lines max on card face)
                    val matchReason = opp.relevanceExplanation
                        ?: "Matches your $userFocus research profile and required expertise."
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(12.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null,
                                tint = PRIMARY, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("WHY THIS MATCHES YOU", color = PRIMARY,
                                    fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = matchReason,
                                    color = TEXT_PRIMARY,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Skills (max 3 + overflow chip)
                    val skills = opp.requiredSkills.ifEmpty {
                        listOf(userFocus, "Research", "Academic Writing")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        skills.take(3).forEach { skill ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SURFACE_SUBTLE)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(skill, color = TEXT_SECONDARY,
                                    fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (skills.size > 3) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PRIMARY.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("+${skills.size - 3}", color = PRIMARY,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ── Action Row ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bookmark toggle
                    IconButton(
                        onClick = onBookmark,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isBookmarked) PRIMARY.copy(alpha = 0.1f)
                                else SURFACE_SUBTLE
                            )
                            .border(BorderStroke(1.dp, if (isBookmarked) PRIMARY else BORDER),
                                CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark
                                          else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Bookmarked" else "Bookmark",
                            tint = if (isBookmarked) PRIMARY else TEXT_SECONDARY,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Skip / Pass
                    IconButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SURFACE_SUBTLE)
                            .border(BorderStroke(1.dp, BORDER), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Skip",
                            tint = AccentRose, modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // View Details + Apply
                    Button(
                        onClick = onViewDetails,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PRIMARY,
                            contentColor = TEXT_ON_PRIMARY
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(48.dp).widthIn(min = 140.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View Details", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Detail sheet — full description + checklist + real AI drafts
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OpportunityDetailSheet(
    opp: IndustryOpportunity,
    userFocus: String,
    userName: String,
    viewModel: IndustryViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val aiDraft          by viewModel.aiDraft.collectAsStateWithLifecycle()
    val isGenerating     by viewModel.isGeneratingDraft.collectAsStateWithLifecycle()
    val aiDraftError     by viewModel.aiDraftError.collectAsStateWithLifecycle()

    var activeAiTool     by remember { mutableStateOf<String?>(null) }
    val checklistState   = remember { mutableStateMapOf<String, Boolean>() }

    // Clear draft when tool changes or sheet re-opens
    LaunchedEffect(activeAiTool) { viewModel.clearAiDraft() }

    val deadlineInfo = remember(opp.deadline) { IndustryMatchUtils.deadlineLabel(opp.deadline) }
    val urgencyColor = when (deadlineInfo?.second) {
        DeadlineUrgency.CRITICAL -> ErrorRed
        DeadlineUrgency.URGENT   -> AccentAmber
        DeadlineUrgency.EXPIRED  -> TEXT_MUTED
        else                     -> WhatsAppTealGreen
    }
    val (typeLabel, typeColor) = when (opp.type) {
        OpportunityType.JOB         -> "JOB"         to PRIMARY
        OpportunityType.FUNDING     -> "FUNDING"      to WhatsAppTealGreen
        OpportunityType.REQUIREMENT -> "REQUIREMENT"  to CustomOrangeGold
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(typeColor.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(typeLabel, color = typeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            val matchScore = remember(opp.id, userFocus) {
                opp.matchScore ?: IndustryMatchUtils.computeMatchScore(userFocus, opp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MATCH_SCORE_BG)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MATCH_SCORE_TEXT, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$matchScore% Match", color = MATCH_SCORE_TEXT,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(opp.title, color = TEXT_PRIMARY, fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp)
        Text(opp.companyOrFunder, color = TEXT_SECONDARY,
            fontSize = 16.sp, fontWeight = FontWeight.Bold)

        // Location row
        val locationParts = buildList {
            if (opp.location.isNotBlank()) add(opp.location)
            if (opp.remoteType != RemoteType.UNSPECIFIED) add(opp.remoteType.displayLabel())
            if (opp.positionLevel != PositionLevel.UNSPECIFIED) add(opp.positionLevel.displayLabel())
        }
        if (locationParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null,
                    tint = TEXT_MUTED, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(locationParts.joinToString(" · "), color = TEXT_MUTED, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info chips
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(SURFACE_SUBTLE).padding(12.dp)
            ) {
                Column {
                    Text("Compensation", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(opp.amount.ifBlank { "See details" },
                        color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(
                        if (deadlineInfo?.second == DeadlineUrgency.CRITICAL ||
                            deadlineInfo?.second == DeadlineUrgency.URGENT)
                            urgencyColor.copy(alpha = 0.08f) else SURFACE_SUBTLE
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text("Deadline", color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = deadlineInfo?.first ?: opp.deadline.ifBlank { "Open" },
                        color = if (deadlineInfo != null &&
                            deadlineInfo.second != DeadlineUrgency.OPEN)
                            urgencyColor else TEXT_PRIMARY,
                        fontSize = 14.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Description
        Text("Description", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(opp.description, color = TEXT_SECONDARY, fontSize = 13.sp, lineHeight = 20.sp)

        Spacer(modifier = Modifier.height(20.dp))

        // Eligibility
        if (opp.eligibility.isNotBlank()) {
            Text("Eligibility", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(opp.eligibility, color = TEXT_SECONDARY, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Required skills
        val skills = opp.requiredSkills.ifEmpty {
            listOf(userFocus, "Research", "Academic Writing")
        }
        Text("Required Skills", color = TEXT_PRIMARY, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            skills.take(5).forEach { skill ->
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

        // Application checklist
        val steps = opp.procedureSteps.ifEmpty {
            listOf(
                "Review official eligibility and requirements",
                "Update academic CV with recent publications",
                "Draft statement of purpose or cover letter",
                "Submit application via institutional portal",
                "Follow up if no confirmation within 2 weeks"
            )
        }
        Text("Application Checklist", color = TEXT_PRIMARY,
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        steps.forEachIndexed { index, step ->
            val key = "${opp.id}_step_$index"
            val isChecked = checklistState[key] ?: false
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { checklistState[key] = !isChecked }
                    .padding(vertical = 6.dp)
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checklistState[key] = it },
                    colors = CheckboxDefaults.colors(checkedColor = PRIMARY)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(step,
                    color = if (isChecked) TEXT_MUTED else TEXT_SECONDARY,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // AI Application Toolkit — real Groq generation
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
                    Icon(Icons.Default.AutoAwesome, contentDescription = null,
                        tint = PRIMARY, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Application Toolkit", color = TEXT_PRIMARY,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Generates a custom draft using your research profile and this opportunity.",
                    color = TEXT_MUTED, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            activeAiTool = if (activeAiTool == "cover") null else "cover"
                            if (activeAiTool == "cover") {
                                viewModel.generateAiDraft("cover", opp, userFocus, userName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "cover") PRIMARY else SURFACE,
                            contentColor   = if (activeAiTool == "cover") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cover Letter", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            activeAiTool = if (activeAiTool == "sop") null else "sop"
                            if (activeAiTool == "sop") {
                                viewModel.generateAiDraft("sop", opp, userFocus, userName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "sop") PRIMARY else SURFACE,
                            contentColor   = if (activeAiTool == "sop") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SOP Outline", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Draft output area
                AnimatedVisibility(visible = activeAiTool != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SURFACE)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isGenerating -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = PRIMARY,
                                        strokeWidth = 2.dp
                                    )
                                    Text("Generating draft…",
                                        color = TEXT_SECONDARY, fontSize = 13.sp)
                                }
                            }
                            aiDraftError != null -> {
                                Column {
                                    Text("Could not generate draft",
                                        color = IndicatorRed,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(aiDraftError ?: "",
                                        color = TEXT_MUTED, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = {
                                        viewModel.generateAiDraft(
                                            activeAiTool!!, opp, userFocus, userName
                                        )
                                    }) {
                                        Text("Retry", color = PRIMARY,
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            aiDraft != null -> {
                                Column {
                                    val draftTitle = if (activeAiTool == "cover")
                                        "Cover Letter Draft" else "SOP Research Section"
                                    Text(draftTitle, color = TEXT_PRIMARY,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(aiDraft!!, color = TEXT_SECONDARY,
                                        fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Apply actions
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
            ) { Text("Dismiss", fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    if (opp.url.isNotBlank()) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opp.url)))
                    } else {
                        Toast.makeText(context, "Apply URL not available.", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1.5f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Now", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(message: String) {
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
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = IndicatorRed, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = TEXT_PRIMARY, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptyOpportunitiesState(stream: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(Icons.Outlined.WorkOutline, contentDescription = null,
            tint = TEXT_MUTED, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (stream) {
                "JOBS"     -> "No job listings match your profile right now"
                "GRANTS"   -> "No funding opportunities found for your research area"
                "TRENDING" -> "No trending opportunities at the moment"
                else       -> "No opportunities in this feed yet"
            },
            color = TEXT_SECONDARY, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("Check back soon or update your research focus in Profile settings.",
            color = TEXT_MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Roadmap Screen (unchanged from original — kept intact)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AssistantProfessorRoadmapScreen(
    roadmap: AssistantProfessorRoadmap?,
    userFocus: String,
    onNavigateToAuthor: (String) -> Unit = {}
) {
    if (roadmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Roadmap syncing with your profile…", color = TEXT_SECONDARY)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(PRIMARY, SkyBlueMedium)))
                .padding(20.dp)
        ) {
            Column {
                Text("Welcome, ${roadmap.userName}", color = TEXT_ON_PRIMARY,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Assistant Professor Tenure-Track · $userFocus",
                    color = TEXT_ON_PRIMARY.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Peer Performance Comparison", color = TEXT_PRIMARY,
            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text("Compare your current metrics with successfully hired assistant professors.",
            color = TEXT_SECONDARY, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        val uM = roadmap.userMetrics
        val tM = roadmap.targetMetrics
        listOf(
            Triple("h-Index",            uM.hIndex.toFloat() / tM.hIndex.coerceAtLeast(1),
                "${uM.hIndex} / ${tM.hIndex}"),
            Triple("Publications",        uM.worksCount.toFloat() / tM.worksCount.coerceAtLeast(1),
                "${uM.worksCount} / ${tM.worksCount}"),
            Triple("Citations",           uM.citationCount.toFloat() / tM.citationCount.coerceAtLeast(1),
                "${uM.citationCount} / ${tM.citationCount}"),
            Triple("Disruption Score",   uM.disruptionScore / tM.disruptionScore.coerceAtLeast(0.001f),
                String.format("%.2f / %.2f", uM.disruptionScore, tM.disruptionScore))
        ).forEach { (label, ratio, display) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TEXT_PRIMARY, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(display,
                            color = if (ratio >= 1f) WhatsAppTealGreen else PRIMARY,
                            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ratio.coerceAtMost(1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (ratio >= 1f) WhatsAppTealGreen else PRIMARY,
                        trackColor = SURFACE_SUBTLE
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Career Milestones", color = TEXT_PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(12.dp))

        roadmap.milestones.forEachIndexed { index, milestone ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(32.dp)
                ) {
                    Box(
                        modifier = Modifier.size(16.dp).clip(CircleShape)
                            .background(
                                when (milestone.status) {
                                    "Completed" -> WhatsAppTealGreen
                                    "Current"   -> PRIMARY
                                    else        -> BORDER
                                }
                            )
                    )
                    if (index < roadmap.milestones.size - 1) {
                        Box(modifier = Modifier.width(2.dp).height(50.dp).background(BORDER))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(milestone.title, color = TEXT_PRIMARY,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (milestone.status) {
                                        "Completed" -> WhatsAppTealGreen.copy(alpha = 0.1f)
                                        "Current"   -> PRIMARY.copy(alpha = 0.1f)
                                        else        -> SURFACE_SUBTLE
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(milestone.status.uppercase(),
                                color = when (milestone.status) {
                                    "Completed" -> WhatsAppTealGreen
                                    "Current"   -> PRIMARY
                                    else        -> TEXT_MUTED
                                },
                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("${milestone.date} · ${milestone.description}",
                        color = TEXT_SECONDARY, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text("Peer Networking Guide", color = TEXT_PRIMARY,
            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text("Collaborating with these researchers improves your h-index trajectory.",
            color = TEXT_SECONDARY, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        roadmap.peerCoauthors.forEach { coauthor ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { onNavigateToAuthor(coauthor.name) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(PRIMARY.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(coauthor.name.trim().take(1).uppercase(),
                                color = PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(coauthor.name, color = TEXT_PRIMARY,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(coauthor.institution, color = TEXT_SECONDARY,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(PRIMARY.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("${coauthor.match} Match", color = PRIMARY,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Application Templates", color = TEXT_PRIMARY,
            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))

        roadmap.templates.forEach { template ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(template.name, color = TEXT_PRIMARY,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(template.description, color = TEXT_SECONDARY, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    val ctx = LocalContext.current
                    IconButton(
                        onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(template.downloadUrl)))
                        },
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(SURFACE_SUBTLE)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = PRIMARY)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Post Opportunity Form (unchanged from original)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfessorPostingForm(onDismiss: () -> Unit = {}) {
    var title           by remember { mutableStateOf("") }
    var titleError      by remember { mutableStateOf(false) }
    var institution     by remember { mutableStateOf("") }
    var institutionError by remember { mutableStateOf(false) }
    var selectedType    by remember { mutableStateOf(OpportunityType.JOB) }
    var description     by remember { mutableStateOf("") }
    var descriptionError by remember { mutableStateOf(false) }
    var tagsString      by remember { mutableStateOf("") }
    var url             by remember { mutableStateOf("") }
    var urlError        by remember { mutableStateOf(false) }
    var postSuccess     by remember { mutableStateOf(false) }

    if (postSuccess) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(WhatsAppTealGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null,
                        tint = WhatsAppTealGreen, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Opportunity Posted!", color = TEXT_PRIMARY,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Your research opportunity is now live in researchers' Launchpad.",
                    color = TEXT_SECONDARY, fontSize = 13.sp,
                    lineHeight = 18.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        title = ""; institution = ""; description = ""; tagsString = ""; url = ""
                        titleError = false; institutionError = false
                        descriptionError = false; urlError = false
                        postSuccess = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Done", fontWeight = FontWeight.Bold) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Post a Research Position", color = TEXT_PRIMARY,
                    fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("Recruit PhDs, Postdocs, or share funding calls with SkoLab researchers.",
                    color = TEXT_SECONDARY, fontSize = 12.sp)
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    border = BorderStroke(1.dp, BORDER),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Position Details", color = TEXT_PRIMARY,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; if (titleError) titleError = it.isBlank() },
                            label = { Text("Opportunity Title") },
                            placeholder = { Text("e.g. Postdoc in Computational Psychiatry") },
                            shape = RoundedCornerShape(12.dp),
                            isError = titleError,
                            supportingText = {
                                if (titleError) Text("Title is required",
                                    color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY, unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY, unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY, unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = institution,
                            onValueChange = { institution = it; if (institutionError) institutionError = it.isBlank() },
                            label = { Text("Institution / Lab") },
                            placeholder = { Text("e.g. MIT Neural Systems Lab") },
                            shape = RoundedCornerShape(12.dp),
                            isError = institutionError,
                            supportingText = {
                                if (institutionError) Text("Institution is required",
                                    color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY, unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY, unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY, unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Column {
                            Text("Type", color = TEXT_PRIMARY,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(OpportunityType.JOB, OpportunityType.FUNDING,
                                    OpportunityType.REQUIREMENT).forEach { type ->
                                    val isSel = selectedType == type
                                    Box(
                                        modifier = Modifier.weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSel) PRIMARY else SURFACE_SUBTLE)
                                            .border(
                                                BorderStroke(1.dp, if (isSel) PRIMARY else BORDER),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { selectedType = type }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(type.name,
                                            color = if (isSel) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Requirements & Application", color = TEXT_PRIMARY,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it; if (descriptionError) descriptionError = it.isBlank() },
                            label = { Text("Description") },
                            placeholder = { Text("Describe the role, responsibilities, and timeline…") },
                            shape = RoundedCornerShape(12.dp),
                            minLines = 4,
                            isError = descriptionError,
                            supportingText = {
                                if (descriptionError) Text("Description is required",
                                    color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY, unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY, unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY, unfocusedTextColor = TEXT_PRIMARY
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
                                focusedBorderColor = PRIMARY, unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY, unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY, unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                if (urlError) urlError = it.isNotBlank() &&
                                    !android.util.Patterns.WEB_URL.matcher(it).matches()
                            },
                            label = { Text("Application URL") },
                            placeholder = { Text("https://lab.mit.edu/careers/postdoc") },
                            shape = RoundedCornerShape(12.dp),
                            isError = urlError,
                            supportingText = {
                                if (urlError) Text("Please enter a valid URL",
                                    color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PRIMARY, unfocusedBorderColor = BORDER,
                                focusedLabelColor = PRIMARY, unfocusedLabelColor = TEXT_MUTED,
                                focusedTextColor = TEXT_PRIMARY, unfocusedTextColor = TEXT_PRIMARY
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
                        titleError       = title.isBlank()
                        institutionError = institution.isBlank()
                        descriptionError = description.isBlank()
                        urlError         = url.isNotBlank() &&
                            !android.util.Patterns.WEB_URL.matcher(url).matches()
                        if (!titleError && !institutionError && !descriptionError && !urlError) {
                            postSuccess = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Submit & Publish Opportunity",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
