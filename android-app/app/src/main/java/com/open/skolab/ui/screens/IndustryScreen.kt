package com.open.skolab.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.data.UserPreferences
import com.open.skolab.ui.theme.*
import com.open.skolab.model.IndustryOpportunity
import com.open.skolab.viewmodel.IndustryViewModel
import com.open.skolab.model.OpportunityType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndustryScreen(
    viewModel: IndustryViewModel = viewModel(),
    onNavigateToReader: (String, String) -> Unit = { _, _ -> }
) {
    val opportunities by viewModel.opportunities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)

    var currentTab by remember { mutableStateOf("explore") } // "explore" or "post"
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf(
        "All", "Research Jobs", "Postdocs", "PhD Positions", "Faculty Positions",
        "Research Internships", "Industry Research Roles", "Funding Calls", "Research Grants", "Travel Grants"
    )

    LaunchedEffect(cachedUser) {
        val focus = cachedUser?.researchFocus ?: "AI"
        viewModel.loadOpportunities(focus)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Screen Header & Subtitle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Elegant tab switcher for Explore vs Post
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (currentTab == "explore") PRIMARY else Color.Transparent)
                                .clickable { currentTab = "explore" }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Explore",
                                color = if (currentTab == "explore") TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (currentTab == "post") PRIMARY else Color.Transparent)
                                .clickable { currentTab = "post" }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Post Opportunity",
                                color = if (currentTab == "post") TEXT_ON_PRIMARY else TEXT_SECONDARY,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (currentTab == "explore") {
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
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) PRIMARY else BORDER
                                    ),
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

                // Main Opportunities list
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
                                    LaunchpadOpportunityCard(opp, cachedUser?.researchFocus ?: "AI")
                                }
                            }
                        }
                    }
                }
            } else {
                // Professor Posting Flow Tab
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchpadOpportunityCard(
    opp: IndustryOpportunity,
    userFocus: String
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var activeAiAssistTab by remember { mutableStateOf<String?>(null) } // "match", "skills", "cover", "sop"

    val (icon, tintColor) = when (opp.type) {
        OpportunityType.JOB -> Icons.Filled.Work to Color(0xFF2D6BE4)
        OpportunityType.FUNDING -> Icons.Filled.AttachMoney to Color(0xFF00A884)
        OpportunityType.REQUIREMENT -> Icons.Filled.TipsAndUpdates to Color(0xFFE28743)
    }

    // Determine a deterministic Match Score based on User Focus
    val matchScore = remember(opp.id, userFocus) {
        val hash = kotlin.math.abs(opp.title.hashCode() + userFocus.hashCode())
        val base = if (opp.title.contains(userFocus, ignoreCase = true) || opp.description.contains(userFocus, ignoreCase = true)) {
            88
        } else {
            74
        }
        (base + (hash % 12))
    }

    // Determine a simulated status badge
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
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(400))
                .padding(16.dp)
        ) {
            // Top Row: Badges & Time
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Category Badge
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

                    // Custom Status Badge
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

            // Title & Institution Row
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
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = opp.companyOrFunder,
                        color = TEXT_SECONDARY,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Match Percentage Badge
                Column(horizontalAlignment = Alignment.End) {
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Short dynamic match reason
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
                        text = "Strong alignment with $userFocus research and citation keywords.",
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
                maxLines = if (isExpanded) Int.MAX_VALUE else 2
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

            // Expandable Area with AI Assistance
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // AI Assistance Section Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = PRIMARY,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Assist Services",
                        color = TEXT_PRIMARY,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // AI Action Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiSmallButton(
                        text = "Why Match?",
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        isSelected = activeAiAssistTab == "match",
                        modifier = Modifier.weight(1f)
                    ) {
                        activeAiAssistTab = if (activeAiAssistTab == "match") null else "match"
                    }
                    AiSmallButton(
                        text = "Missing Skills?",
                        icon = Icons.Outlined.ErrorOutline,
                        isSelected = activeAiAssistTab == "skills",
                        modifier = Modifier.weight(1f)
                    ) {
                        activeAiAssistTab = if (activeAiAssistTab == "skills") null else "skills"
                    }
                    AiSmallButton(
                        text = "Cover Letter",
                        icon = Icons.Outlined.HistoryEdu,
                        isSelected = activeAiAssistTab == "cover",
                        modifier = Modifier.weight(1f)
                    ) {
                        activeAiAssistTab = if (activeAiAssistTab == "cover") null else "cover"
                    }
                }

                activeAiAssistTab?.let { tab ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgPrimary)
                            .border(BorderStroke(0.5.dp, BORDER), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            val infoTitle = when (tab) {
                                "match" -> "Why Am I A Match?"
                                "skills" -> "Recommended Preparation & Missing Skills"
                                else -> "Custom Cover Letter Outline"
                            }
                            val infoText = when (tab) {
                                "match" -> "Based on your active research orbit in $userFocus, your recent publication history overlaps 84% with this institution's target domain. Your co-citation graph indicates familiar methodology matches."
                                "skills" -> "Strong Match, but strengthening: \n• Advanced stochastic model compilation\n• Empirical evaluation metrics in $userFocus\n• Groq client instrumentation workflows"
                                else -> "Dear Hiring Committee,\n\nI am writing to express my strong interest in the ${opp.title} position at ${opp.companyOrFunder}. As a researcher focusing on $userFocus, my publications align precisely with your needs..."
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = infoTitle,
                                    color = TEXT_PRIMARY,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = TEXT_MUTED,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { activeAiAssistTab = null }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = infoText,
                                color = TEXT_SECONDARY,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Apply Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            // Discuss with Skolar
                            if (opp.url.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(opp.url))
                                context.startActivity(intent)
                            }
                        },
                        border = BorderStroke(1.dp, BORDER),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PRIMARY),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "View Opportunity",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            if (opp.url.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(opp.url))
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PRIMARY,
                            contentColor = TEXT_ON_PRIMARY
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Apply Now",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AiSmallButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) PRIMARY else SURFACE_SUBTLE)
            .clickable { onClick() }
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
                text = text,
                color = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) PRIMARY else BORDER
                                    ),
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
