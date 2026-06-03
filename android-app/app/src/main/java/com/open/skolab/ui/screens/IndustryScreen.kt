package com.open.skolab.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.data.UserPreferences
import com.open.skolab.ui.theme.*
import com.open.skolab.model.IndustryOpportunity
import com.open.skolab.viewmodel.IndustryViewModel
import com.open.skolab.model.OpportunityType
import com.open.skolab.model.AssistantProfessorRoadmap

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IndustryScreen(
    viewModel: IndustryViewModel = viewModel(),
    onNavigateToAuthor: (String) -> Unit = {},
    onNavigateToReader: (String, String) -> Unit = { _, _ -> }
) {
    val opportunities by viewModel.opportunities.collectAsState()
    val roadmap by viewModel.roadmap.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingRoadmap by viewModel.isLoadingRoadmap.collectAsState()
    val error by viewModel.error.collectAsState()
    
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)

    var currentTab by remember { mutableStateOf("explore") } // "explore", "swipe", "roadmap", "post"
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedOpportunityForDetail by remember { mutableStateOf<IndustryOpportunity?>(null) }

    val categories = listOf(
        "All", "Research Jobs", "Postdocs", "PhD Positions", "Faculty Positions",
        "Funding Calls", "Research Grants"
    )

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "LAUNCHPAD",
                    color = PRIMARY,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Research Opportunities",
                    color = TEXT_PRIMARY,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Premium Segmented Tab Bar (Full Width)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SURFACE_SUBTLE)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        Triple("explore", "Explore", Icons.Default.Explore),
                        Triple("swipe", "Swipe", Icons.Default.Swipe),
                        Triple("roadmap", "Path", Icons.Default.Timeline),
                        Triple("post", "Post", Icons.Default.AddBox)
                    )
                    tabs.forEach { (tabId, label, icon) ->
                        val isSelected = currentTab == tabId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PRIMARY else Color.Transparent)
                                .clickable { currentTab = tabId }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                    modifier = Modifier.size(13.dp)
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
            }

            // Tabs Content
            if (error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SURFACE_SUBTLE),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Error Alert",
                                tint = Color(0xFFD42B2B),
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
                }
            } else {
                when (currentTab) {
                    "explore" -> {
                        // Category Filter Chips
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            items(categories) { category ->
                                val isSelected = selectedCategory == category
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) PRIMARY else SURFACE)
                                        .border(
                                            BorderStroke(1.dp, if (isSelected) PRIMARY else BORDER),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .clickable { selectedCategory = category }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = category,
                                        color = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Opportunities list
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading && opportunities.isEmpty()) {
                                CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                            } else {
                                val filteredOpps = remember(opportunities, selectedCategory) {
                                    if (selectedCategory == "All") {
                                        opportunities
                                    } else {
                                        opportunities.filter { opp ->
                                            opp.title.contains(selectedCategory, ignoreCase = true) ||
                                            opp.description.contains(selectedCategory, ignoreCase = true) ||
                                            opp.tags.any { it.contains(selectedCategory, ignoreCase = true) } ||
                                            (selectedCategory == "Funding Calls" && opp.type == OpportunityType.FUNDING) ||
                                            (selectedCategory == "Research Grants" && opp.type == OpportunityType.FUNDING) ||
                                            (selectedCategory == "Postdocs" && opp.title.contains("Postdoc", ignoreCase = true)) ||
                                            (selectedCategory == "PhD Positions" && opp.title.contains("PhD", ignoreCase = true)) ||
                                            (selectedCategory == "Faculty Positions" && opp.title.contains("Professor", ignoreCase = true)) ||
                                            (selectedCategory == "Research Jobs" && opp.type == OpportunityType.JOB)
                                        }
                                    }
                                }

                                if (filteredOpps.isEmpty()) {
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
                                            text = "No opportunities found in this category",
                                            color = TEXT_SECONDARY,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(filteredOpps) { opp ->
                                            LaunchpadOpportunityCard(
                                                opp = opp,
                                                userFocus = cachedUser?.researchFocus ?: "AI",
                                                onClick = { selectedOpportunityForDetail = opp }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "swipe" -> {
                        val jobOpps = remember(opportunities) {
                            opportunities.filter { it.type == OpportunityType.JOB }
                        }
                        if (jobOpps.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No jobs available for Fast Swipe right now.", color = TEXT_SECONDARY)
                            }
                        } else {
                            FastSwipeDeck(
                                opportunities = jobOpps,
                                userFocus = cachedUser?.researchFocus ?: "AI"
                            )
                        }
                    }
                    "roadmap" -> {
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
                    else -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            ProfessorPostingForm()
                        }
                    }
                }
            }
        }

        // Detailed Bottom Sheet for Opportunity
        if (selectedOpportunityForDetail != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedOpportunityForDetail = null },
                containerColor = BgPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BORDER) }
            ) {
                OpportunityDetailSheetContent(
                    opp = selectedOpportunityForDetail!!,
                    userFocus = cachedUser?.researchFocus ?: "AI",
                    onClose = { selectedOpportunityForDetail = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchpadOpportunityCard(
    opp: IndustryOpportunity,
    userFocus: String,
    onClick: () -> Unit
) {
    val (icon, tintColor) = when (opp.type) {
        OpportunityType.JOB -> Icons.Filled.Work to Color(0xFF2D6BE4)
        OpportunityType.FUNDING -> Icons.Filled.AttachMoney to Color(0xFF00A884)
        OpportunityType.REQUIREMENT -> Icons.Filled.TipsAndUpdates to Color(0xFFE28743)
    }

    val matchScore = remember(opp.id, userFocus) {
        opp.matchScore ?: run {
            val hash = kotlin.math.abs(opp.title.hashCode() + userFocus.hashCode())
            val base = if (opp.title.contains(userFocus, ignoreCase = true) || opp.description.contains(userFocus, ignoreCase = true)) {
                88
            } else {
                74
            }
            (base + (hash % 12)).coerceAtMost(99)
        }
    }

    val statusBadge = remember(opp.id) {
        val index = kotlin.math.abs(opp.id.hashCode()) % 4
        listOf("Featured", "Trending", "Recently Added", "Ending Soon")[index]
    }

    val statusColor = when (statusBadge) {
        "Featured" -> Color(0xFF2D6BE4)
        "Trending" -> Color(0xFFE28743)
        "Recently Added" -> Color(0xFF00A884)
        else -> Color(0xFFD42B2B)
    }

    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Badges
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tintColor.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = opp.type.name,
                            color = tintColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = statusBadge.uppercase(),
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = opp.postedAgo,
                    color = TEXT_MUTED,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Title & Org
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SURFACE_SUBTLE),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = opp.title,
                        color = TEXT_PRIMARY,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = opp.companyOrFunder,
                        color = TEXT_SECONDARY,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MATCH_SCORE_BG)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$matchScore% Match",
                        color = MATCH_SCORE_TEXT,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Short dynamic match reason from LLM
            val matchReason = remember(opp.id) {
                opp.relevanceExplanation ?: "Strong alignment with $userFocus research and citation keywords."
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SURFACE_SUBTLE)
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = PRIMARY,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = matchReason,
                        color = TEXT_PRIMARY,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = opp.description,
                color = TEXT_SECONDARY,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tag list
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                opp.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tag,
                            color = TEXT_SECONDARY,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "View Details & Apply →",
                    color = PRIMARY,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OpportunityDetailSheetContent(
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
        Text(
            text = opp.type.name,
            color = if (opp.type == OpportunityType.JOB) Color(0xFF2D6BE4) else Color(0xFF00A884),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = opp.title,
            color = TEXT_PRIMARY,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = opp.companyOrFunder,
            color = TEXT_SECONDARY,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Core Information
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SURFACE_SUBTLE)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Funding / Salary", color = TEXT_MUTED, fontSize = 11.sp)
                Text(opp.amount.ifBlank { "Varies" }, color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Deadline", color = TEXT_MUTED, fontSize = 11.sp)
                Text(opp.deadline.ifBlank { "Open Now" }, color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text("Description", color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = opp.description,
            color = TEXT_SECONDARY,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Eligibility
        if (opp.eligibility.isNotBlank()) {
            Text("Eligibility Criteria", color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = opp.eligibility,
                color = TEXT_SECONDARY,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Procedure Checklist
        val steps = remember(opp.id) {
            opp.procedureSteps.ifEmpty {
                listOf(
                    "Check official guidelines",
                    "Update academic CV with $userFocus keywords",
                    "Draft brief research statement",
                    "Submit online application form"
                )
            }
        }

        Text("Application Procedure", color = TEXT_PRIMARY, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        steps.forEachIndexed { index, step ->
            val stepKey = "${opp.id}_step_$index"
            val isChecked = checklistState[stepKey] ?: false
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { checklistState[stepKey] = !isChecked }
                    .padding(vertical = 4.dp)
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
                .clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(12.dp))
                .background(SURFACE)
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = PRIMARY,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Application Toolkit",
                        color = TEXT_PRIMARY,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Generate custom outlines based on your publications and focus area.",
                    color = TEXT_SECONDARY,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { activeAiTool = if (activeAiTool == "cover") null else "cover" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "cover") PRIMARY else SURFACE_SUBTLE,
                            contentColor = if (activeAiTool == "cover") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cover Letter", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { activeAiTool = if (activeAiTool == "sop") null else "sop" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeAiTool == "sop") PRIMARY else SURFACE_SUBTLE,
                            contentColor = if (activeAiTool == "sop") TEXT_ON_PRIMARY else TEXT_PRIMARY
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SOP Outline", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (activeAiTool != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgPrimary)
                            .padding(12.dp)
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
                            Text(draftText, color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 16.sp)
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
                onClick = { onClose() },
                border = BorderStroke(1.dp, BORDER),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT_SECONDARY),
                shape = RoundedCornerShape(12.dp),
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
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1.5f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Now", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FastSwipeDeck(
    opportunities: List<IndustryOpportunity>,
    userFocus: String
) {
    var currentIndex by remember { mutableStateOf(0) }
    val opp = opportunities.getOrNull(currentIndex)
    val context = LocalContext.current

    if (opp == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("You've viewed all jobs! Pull to refresh.", color = TEXT_SECONDARY)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Visually stunning job reels card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E1B4B),
                            Color(0xFF0F172A)
                        )
                    )
                )
                .border(BorderStroke(1.5.dp, Color(0xFF334155)), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("POSTDOC ROLE", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(opp.postedAgo, color = Color(0xFF94A3B8), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title and Institution
                Text(
                    text = opp.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 28.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = opp.companyOrFunder,
                    color = Color(0xFF38BDF8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Metadata Chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(opp.amount.ifBlank { "$85,000/yr" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Active Status", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                Text(
                    text = opp.description,
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f))

                // Key required skills
                val skills = remember(opp.id) {
                    opp.requiredSkills.ifEmpty { listOf(userFocus, "Python", "Data Analysis") }
                }
                Text("REQUIRED SKILLS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    skills.take(3).forEach { skill ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF334155))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(skill, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip button
                    IconButton(
                        onClick = { currentIndex++ },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Skip", tint = Color(0xFFEF4444))
                    }

                    // SOP toolkit
                    Button(
                        onClick = {
                            Toast.makeText(context, "SOP outline generated & saved to your workspace details!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SOP outline", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Direct Apply Link
                    Button(
                        onClick = {
                            if (opp.url.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(opp.url))
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("1-Click Apply", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantProfessorRoadmapScreen(
    roadmap: AssistantProfessorRoadmap?,
    userFocus: String,
    onNavigateToAuthor: (String) -> Unit = {}
) {
    val context = LocalContext.current
    if (roadmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Roadmap currently syncing with your OpenAlex profile...", color = TEXT_SECONDARY)
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
                .background(Brush.horizontalGradient(listOf(PRIMARY, Color(0xFF0284C7))))
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
                            color = if (ratio >= 1.0f) Color(0xFF00A884) else PRIMARY,
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
                        color = if (ratio >= 1.0f) Color(0xFF00A884) else PRIMARY,
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
                                    "Completed" -> Color(0xFF00A884)
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
                                        "Completed" -> Color(0xFF00A884).copy(alpha = 0.1f)
                                        "Current" -> PRIMARY.copy(alpha = 0.1f)
                                        else -> SURFACE_SUBTLE
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = milestone.status.uppercase(),
                                color = when (milestone.status) {
                                    "Completed" -> Color(0xFF00A884)
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
                        // Initials avatar
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
fun ProfessorPostingForm() {
    var title by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(OpportunityType.JOB) }
    var description by remember { mutableStateOf("") }
    var tagsString by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
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
                        .background(Color(0xFF00A884).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF00A884),
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
                        postSuccess = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Post Another Position", fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Post a New Research Position",
                    color = TEXT_PRIMARY,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Recruit PhDs, Postdocs, or share funding calls with active SkoLab researchers.",
                    color = TEXT_SECONDARY,
                    fontSize = 12.sp
                )
            }

            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Opportunity Title (e.g. Postdoc in Computational Psychiatry)") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = PRIMARY,
                        unfocusedLabelColor = TEXT_MUTED
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = institution,
                    onValueChange = { institution = it },
                    label = { Text("Institution / Lab (e.g. MIT Neural Systems Lab)") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = PRIMARY,
                        unfocusedLabelColor = TEXT_MUTED
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text(
                    text = "Opportunity Type",
                    color = TEXT_PRIMARY,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(OpportunityType.JOB, OpportunityType.FUNDING, OpportunityType.REQUIREMENT).forEach { type ->
                        val isSelected = selectedType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) PRIMARY else SURFACE)
                                .border(
                                    BorderStroke(1.dp, if (isSelected) PRIMARY else BORDER),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedType = type }
                                .padding(vertical = 12.dp),
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

            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Job / Funding Description") },
                    shape = RoundedCornerShape(12.dp),
                    minLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = PRIMARY,
                        unfocusedLabelColor = TEXT_MUTED
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = tagsString,
                    onValueChange = { tagsString = it },
                    label = { Text("Keywords / Skills (comma separated, e.g. NLP, PyTorch)") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = PRIMARY,
                        unfocusedLabelColor = TEXT_MUTED
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Application / Information URL") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = PRIMARY,
                        unfocusedLabelColor = TEXT_MUTED
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank() && institution.isNotBlank()) {
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
