package com.open.skolab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.data.UserPreferences
import com.open.skolab.ui.theme.*
import com.open.skolab.viewmodel.MetricsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricsScreen(viewModel: MetricsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)

    var selectedFolderTab by remember { mutableStateOf("papers") } // "papers" or "jobs"

    LaunchedEffect(cachedUser) {
        val name = cachedUser?.name ?: "SkoLab User"
        viewModel.setUserContext(name)
    }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        // Uniform background with micro grid pattern
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotSpacing = 28.dp.toPx()
            val dotRadius = 0.8.dp.toPx()
            val cols = (size.width / dotSpacing).toInt() + 1
            val rows = (size.height / dotSpacing).toInt() + 1
            for (col in 0..cols) {
                for (row in 0..rows) {
                    drawCircle(
                        color = Color(0xFF2D6BE4).copy(alpha = 0.04f),
                        radius = dotRadius,
                        center = Offset(col * dotSpacing, row * dotSpacing)
                    )
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                        Text("Constructing identity metrics…", color = TEXT_SECONDARY, fontSize = 14.sp)
                    }
                }
            }

            uiState.error != null -> {
                ErrorStateCard(
                    message = uiState.error!!,
                    onRetry = { viewModel.retry() }
                )
            }

            uiState.metrics != null -> {
                val metrics = uiState.metrics!!

                // Determine dynamic research archetype from metric weights
                val archetype = remember(metrics) {
                    when {
                        metrics.velocity > 80 -> "Pioneer"
                        metrics.topic_toughness > 80 -> "Explorer"
                        metrics.skills.size > 6 -> "Connector"
                        metrics.tools.size > 5 -> "Builder"
                        else -> "Synthesizer"
                    }
                }

                val archetypeDescription = when (archetype) {
                    "Pioneer" -> "Spearheading breakthrough discoveries at extreme momentum."
                    "Explorer" -> "Navigating high-toughness fields and complex conceptual areas."
                    "Connector" -> "Bridging diverse academic frameworks and researcher orbits."
                    "Builder" -> "Engineering high-utility tools, benchmarks, and experiment frameworks."
                    else -> "Distilling complex heterogeneous papers into cohesive front."
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Page Title
                    item {
                        Column {
                            Text(
                                "ME",
                                color = PRIMARY,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Research Identity",
                                color = TEXT_PRIMARY,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // 1. Core Profile Card (Identity Centric)
                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Large Initials Avatar
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(SURFACE_SUBTLE)
                                        .border(BorderStroke(1.5.dp, BORDER), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cachedUser?.name?.split(" ")?.filter { it.isNotEmpty() }?.take(2)?.map { it.first() }?.joinToString("")?.uppercase() ?: "SU",
                                        color = PRIMARY,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = cachedUser?.name ?: "SkoLab User",
                                    color = TEXT_PRIMARY,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                Text(
                                    text = remember(cachedUser?.email) {
                                        cachedUser?.email?.substringAfter("@")?.substringBefore(".")?.replaceFirstChar { it.uppercase() }?.let { "${it} Lab" } ?: "MIT Computer Science & AI Lab"
                                    },
                                    color = TEXT_SECONDARY,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // Archetype Badge
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(PRIMARY.copy(alpha = 0.08f))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = PRIMARY,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Archetype: $archetype",
                                            color = PRIMARY,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = archetypeDescription,
                                    color = TEXT_SECONDARY,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // 2. Research Impact Metrics Section
                    item {
                        Text(
                            text = "Research Impact & Metrics",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Impact Score Card
                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "IMPACT ENTROPY SCORE",
                                    color = TEXT_MUTED,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )

                                Text(
                                    text = "${metrics.overall_score}",
                                    fontSize = 54.sp,
                                    fontWeight = FontWeight.Black,
                                    color = PRIMARY
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = metrics.analysis,
                                    color = TEXT_SECONDARY,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }

                    // Detailed stats (Toughness & Velocity)
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MeMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Topic Toughness",
                                value = "${metrics.topic_toughness}/100",
                                icon = Icons.Default.Science,
                                tint = PRIMARY
                            )
                            MeMetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Research Velocity",
                                value = "${metrics.velocity}/100",
                                icon = Icons.Default.Speed,
                                tint = Color(0xFF00A884)
                            )
                        }
                    }

                    // 3. Skills and Tools Sections
                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Build,
                                        contentDescription = null,
                                        tint = PRIMARY,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "Acquired Skills",
                                        color = TEXT_PRIMARY,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    metrics.skills.forEach { skill ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(PRIMARY.copy(alpha = 0.08f))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = skill,
                                                color = PRIMARY,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AutoGraph,
                                        contentDescription = null,
                                        tint = Color(0xFF00A884),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "Tools & Frameworks",
                                        color = TEXT_PRIMARY,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    metrics.tools.forEach { tool ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF00A884).copy(alpha = 0.08f))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = tool,
                                                color = Color(0xFF00A884),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Achievements & Badges
                    item {
                        Text(
                            text = "Achievements & Badges",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                BadgeItem(
                                    title = "Stellar Citation",
                                    desc = "Highly co-cited work in computational psychiatry.",
                                    icon = Icons.Default.Grade,
                                    tint = Color(0xFFE28743)
                                )
                                HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                                BadgeItem(
                                    title = "Pioneer Explorer",
                                    desc = "First to query emerging neural agents logic gaps.",
                                    icon = Icons.Default.Explore,
                                    tint = PRIMARY
                                )
                                HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                                BadgeItem(
                                    title = "SkoLab Builder",
                                    desc = "Demonstrated deep integration with LLM pipelines.",
                                    icon = Icons.Default.Construction,
                                    tint = Color(0xFF00A884)
                                )
                            }
                        }
                    }

                    // 5. Saved Items Folders
                    item {
                        Text(
                            text = "Saved Folders",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Folder switcher
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FolderToggleTab(
                                title = "Saved Papers",
                                count = 4,
                                icon = Icons.Outlined.Folder,
                                isSelected = selectedFolderTab == "papers",
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedFolderTab = "papers"
                            }

                            FolderToggleTab(
                                title = "Saved Opportunities",
                                count = 2,
                                icon = Icons.Outlined.FolderSpecial,
                                isSelected = selectedFolderTab == "jobs",
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedFolderTab = "jobs"
                            }
                        }
                    }

                    // Folder contents list
                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                if (selectedFolderTab == "papers") {
                                    SavedPaperItem(
                                        title = "Computational Psychiatry: Emerging frontiers and benchmarks",
                                        authors = "${cachedUser?.name ?: "SkoLab User"}, Academic Lab",
                                        score = 96
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    SavedPaperItem(
                                        title = "Neural Agents & LLM Reasoning in complex tasks",
                                        authors = "Stanford AI Team",
                                        score = 92
                                    )
                                } else {
                                    SavedOpportunityItem(
                                        title = "Postdoc in Computational Neuroscience",
                                        institution = "Stanford University",
                                        match = 94
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    SavedOpportunityItem(
                                        title = "Industry Research Intern - AI & Cognition",
                                        institution = "DeepMind Lab",
                                        match = 89
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TEXT_PRIMARY
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                color = TEXT_SECONDARY,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun BadgeItem(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = TEXT_PRIMARY,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                color = TEXT_SECONDARY,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun FolderToggleTab(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) PRIMARY else SURFACE,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isSelected) PRIMARY else BORDER),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) TEXT_ON_PRIMARY else TEXT_SECONDARY,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    color = if (isSelected) TEXT_ON_PRIMARY else TEXT_PRIMARY,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$count Items",
                    color = if (isSelected) TEXT_ON_PRIMARY_SUB else TEXT_MUTED,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SavedPaperItem(
    title: String,
    authors: String,
    score: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Article,
            contentDescription = null,
            tint = PRIMARY,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TEXT_PRIMARY,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = authors,
                color = TEXT_SECONDARY,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MATCH_SCORE_BG)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$score%",
                color = MATCH_SCORE_TEXT,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SavedOpportunityItem(
    title: String,
    institution: String,
    match: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Outlined.WorkOutline,
            contentDescription = null,
            tint = Color(0xFF00A884),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TEXT_PRIMARY,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = institution,
                color = TEXT_SECONDARY,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MATCH_SCORE_BG)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$match%",
                color = MATCH_SCORE_TEXT,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ErrorStateCard(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SURFACE)
                .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFEA0038).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚠️", fontSize = 26.sp)
            }

            Text(
                text = "Something went wrong",
                color = TEXT_PRIMARY,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = message,
                color = TEXT_SECONDARY,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )

            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PRIMARY,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

