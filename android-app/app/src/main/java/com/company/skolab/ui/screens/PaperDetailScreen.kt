package com.company.skolab.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.viewmodel.PaperUiState
import com.company.skolab.viewmodel.PaperViewModel
import com.company.skolab.viewmodel.IntelligenceUiState
import com.company.skolab.model.PaperIntelligence
import androidx.compose.ui.platform.LocalContext
import com.company.skolab.R
import com.company.skolab.ui.components.*
import com.company.skolab.ui.layout.ScreenInsets
import com.company.skolab.ui.components.primitives.MetricHeroRow
import com.company.skolab.ui.components.primitives.SectionCaption
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperDetailScreen(
    paperId: String,
    onBack: () -> Unit,
    onAuthorClick: (String) -> Unit,
    viewModel: PaperViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val intelligenceState by viewModel.intelligenceState.collectAsStateWithLifecycle()
    val savedIds by libraryViewModel.savedIds.collectAsStateWithLifecycle()
    val isSaved = savedIds.contains(paperId)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showConfetti by remember { mutableStateOf(false) }

    LaunchedEffect(paperId) {
        viewModel.fetchPaperDetails(paperId)
    }

    Scaffold(
        containerColor = Color.Transparent, // Let background show through
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SkoLabTextPrimary)
                    }
                },
                actions = {
                    // Animated bookmark button
                    IconButton(onClick = {
                        scope.launch {
                            val wasSaved = isSaved
                            libraryViewModel.toggleSaved(paperId)
                            if (!wasSaved) showConfetti = true
                        }
                    }) {
                        val bookmarkIcon = if (isSaved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder
                        val bookmarkTint = if (isSaved) AccentTeal else SkoLabTextPrimary
                        Icon(
                            bookmarkIcon, null,
                            tint = bookmarkTint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (isSaved) 1.15f else 1f
                                scaleY = if (isSaved) 1.15f else 1f
                            }
                        )
                    }
                    IconButton(onClick = {
                        val paper = (uiState as? PaperUiState.Success)?.paper
                        val title = paper?.title ?: "Research Paper"
                        val doi = paper?.doi?.takeIf { it.isNotBlank() }
                        val shareText = if (doi != null) "$title\nhttps://doi.org/$doi" else title
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Paper"))
                    }) { Icon(Icons.Default.Share, null, tint = SkoLabTextPrimary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                is PaperUiState.Loading -> {
                    Column(modifier = Modifier.padding(innerPadding)) {
                        PremiumPaperLoading()
                        Spacer(modifier = Modifier.height(24.dp))
                        PremiumPaperLoading()
                    }
                }
                is PaperUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = state.message, color = SkoLabWarning, style = Typography.bodyLarge)
                    }
                }
                is PaperUiState.Success -> {
                    val paper = state.paper
                    // Hoist here so remember/LaunchedEffect are in @Composable scope
                    var intelligenceVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(intelligenceState) {
                        if (intelligenceState is IntelligenceUiState.Success) intelligenceVisible = true
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = ScreenInsets.horizontal),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        item {
                            PaperHero(paper, onAuthorClick)
                        }

                        item {
                            MetricCockpit(paper)
                        }

                        // ── Progressive AI Research Intelligence Sections ──
                        when (val intel = intelligenceState) {
                            is IntelligenceUiState.Loading -> {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = SkoLabAiInsight,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            StageProgressBar(
                                                stages = listOf(
                                                    "Parsing research content...",
                                                    "Extracting key findings...",
                                                    "Mapping techniques & tools...",
                                                    "Synthesizing intelligence report..."
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        IntelligenceShimmerBlock()
                                    }
                                }
                            }
                            is IntelligenceUiState.Unavailable -> {
                                // Ignore section gracefully: don't render a blocking retry card or stop showing other sections
                            }
                            is IntelligenceUiState.Success -> {
                                val data = intel.data

                                // Header Row with Confidence Indicator
                                item {
                                    StaggeredAnimatedVisibility(index = 0, visible = intelligenceVisible) {
                                        ResearchIntelligenceHeader(data)
                                    }
                                }

                                // 1. TL;DR Hero Card
                                item {
                                    StaggeredAnimatedVisibility(index = 1, visible = intelligenceVisible) {
                                        TldrHeroCard(data)
                                    }
                                }

                                // 2. Key Findings
                                if (data.keyFindings.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 2, visible = intelligenceVisible) {
                                            KeyFindingsBlock(data.keyFindings)
                                        }
                                    }
                                }

                                // 3. Techniques & Methods
                                if (data.techniques.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 3, visible = intelligenceVisible) {
                                            TechniquesBlock(data.techniques)
                                        }
                                    }
                                }

                                // 4. Tools & Software
                                if (data.toolsAndSoftware.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 4, visible = intelligenceVisible) {
                                            ToolsAndSoftwareBlock(data.toolsAndSoftware)
                                        }
                                    }
                                }

                                // 5. Core Concepts
                                if (data.coreConcepts.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 5, visible = intelligenceVisible) {
                                            CoreConceptsBlock(data.coreConcepts)
                                        }
                                    }
                                }

                                // 6. Mathematical Model (Formulas)
                                if (data.formulas.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 6, visible = intelligenceVisible) {
                                            IntelligenceFormulasBlock(data.formulas)
                                        }
                                    }
                                }

                                // 7. Honest Limitations
                                if (data.limitations.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 7, visible = intelligenceVisible) {
                                            LimitationsBlock(data.limitations)
                                        }
                                    }
                                }

                                // 8. Real-World Impact
                                if (data.realWorldImpact.isNotBlank()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 8, visible = intelligenceVisible) {
                                            RealWorldImpactBlock(data.realWorldImpact)
                                        }
                                    }
                                }

                                // 9. Future Directions
                                if (data.futureDirections.isNotEmpty()) {
                                    item {
                                        StaggeredAnimatedVisibility(index = 9, visible = intelligenceVisible) {
                                            FutureDirectionsBlock(data.futureDirections)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            CitationNetworkGalaxy()
                        }
                    }
                }
            }
            // Confetti overlay — fires once when user bookmarks the paper
            if (showConfetti) {
                ConfettiCelebration(
                    visible = showConfetti,
                    onFinished = { showConfetti = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperHero(paper: com.company.skolab.model.Paper, onAuthorClick: (String) -> Unit) {
    Column {
        ScientificBadge(text = paper.journal, color = SkoLabDisruption)
        Spacer(modifier = Modifier.height(16.dp))
        MarkdownText(
            markdown = paper.title,
            color = SkoLabTextPrimary,
            fontSize = 26.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            paper.authors.take(4).forEach { authorCombined ->
                val name = authorCombined.substringBefore("|")
                Text(
                    text = name,
                    style = Typography.labelMedium,
                    color = SkoLabDisruption,
                    modifier = Modifier.clickable { onAuthorClick(authorCombined) }
                )
            }
            if (paper.authors.size > 4) {
                Text(text = "+${paper.authors.size - 4} more", style = Typography.labelSmall, color = SkoLabTextMuted)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "YEAR: ${paper.year} • CITATION VELOCITY: ${paper.citationVelocity.toInt()}",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            fontFamily = MonoFontFamily,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun MetricCockpit(paper: com.company.skolab.model.Paper) {
    Column {
        SectionCaption(text = "Research quality cockpit")
        Spacer(modifier = Modifier.height(12.dp))
        MetricHeroRow(
            disruption = paper.disruptionScore.coerceIn(0f, 1f),
            novelty = paper.noveltyScore.coerceIn(0f, 1f),
            velocity = (paper.citationVelocity / 1000f).coerceIn(0f, 1f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${paper.citationCount} citations • IF ${paper.journalImpactFactor}",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            fontFamily = MonoFontFamily
        )
    }
}

@Composable
fun ResearchIntelligenceHeader(intelligence: PaperIntelligence) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = SkoLabAiInsight,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "RESEARCH INTELLIGENCE REPORT",
                style = Typography.labelMedium,
                color = SkoLabTextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val confidenceColor = when (intelligence.confidence) {
                "High" -> AccentEmerald
                "Medium" -> AccentAmber
                else -> TextMuted
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(confidenceColor, CircleShape)
            )
            Text(
                text = "${intelligence.confidence} CONFIDENCE",
                style = Typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFontFamily,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
fun TldrHeroCard(data: PaperIntelligence) {
    val sourceFriendlyName = when (data.textSource) {
        "full_text_oa" -> "Full OpenAlex OA PDF"
        "full_text_arxiv" -> "Full arXiv PDF"
        "full_text_unpaywall" -> "Full Unpaywall OA PDF"
        "full_text_s2" -> "Semantic Scholar Full Text"
        "abstract_only" -> "Metadata & Abstract only"
        else -> "Scientific Metadata"
    }

    ScientificCard(
        glowColor = SkoLabAiInsight.copy(alpha = 0.12f),
        accentColor = SkoLabAiInsight,
        borderWidth = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📌", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "EXECUTIVE TL;DR",
                    style = Typography.labelSmall,
                    color = SkoLabAiInsight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (data.isFullText) AccentEmerald else TextMuted, CircleShape)
                )
                Text(
                    text = sourceFriendlyName.uppercase(),
                    style = Typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = MonoFontFamily,
                    fontSize = 8.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        MarkdownText(
            markdown = "*${data.tldr}*",
            color = SkoLabTextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun KeyFindingsBlock(keyFindings: List<String>) {
    Column {
        Text(
            text = "🔬 KEY FINDINGS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        ScientificCard {
            keyFindings.forEachIndexed { index, finding ->
                AIInsightBullet(text = finding)
                if (index < keyFindings.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TechniquesBlock(techniques: List<String>) {
    Column {
        Text(
            text = "⚙️ TECHNIQUES & METHODS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            techniques.forEach { technique ->
                IntelligenceChip(text = technique, color = AccentIndigo)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsAndSoftwareBlock(tools: List<String>) {
    Column {
        Text(
            text = "🧰 TOOLS, SOFTWARE & DATASETS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tools.forEach { tool ->
                IntelligenceChip(text = tool, color = AccentTeal)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreConceptsBlock(concepts: List<String>) {
    Column {
        Text(
            text = "💡 CORE CONCEPTS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            concepts.forEach { concept ->
                IntelligenceChip(text = concept, color = AccentViolet)
            }
        }
    }
}

@Composable
fun IntelligenceFormulasBlock(formulas: List<String>) {
    Column {
        Text(
            text = "∑ MATHEMATICAL MODEL",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            formulas.forEach { formula ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightBlue)
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(20.dp)
                ) {
                    MarkdownText(
                        markdown = formula,
                        color = SkoLabPrimary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LimitationsBlock(limitations: List<String>) {
    Column {
        Text(
            text = "⚠️ HONEST LIMITATIONS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        ScientificCard(
            borderColor = SkoLabWarning.copy(alpha = 0.2f),
            glowColor = SkoLabWarning.copy(alpha = 0.03f)
        ) {
            limitations.forEachIndexed { index, limitation ->
                LimitationsBullet(text = limitation)
                if (index < limitations.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun LimitationsBullet(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 8.dp)
                .size(4.dp)
                .background(AccentRose, RoundedCornerShape(50))
        )
        MarkdownText(
            markdown = text,
            color = SkoLabTextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun RealWorldImpactBlock(text: String) {
    Column {
        Text(
            text = "🌍 REAL-WORLD IMPACT",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        ScientificCard(
            glowColor = AccentAmber.copy(alpha = 0.08f),
            borderColor = AccentAmber.copy(alpha = 0.3f)
        ) {
            MarkdownText(
                markdown = text,
                color = SkoLabTextPrimary,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun FutureDirectionsBlock(futureDirections: List<String>) {
    Column {
        Text(
            text = "🚀 FUTURE DIRECTIONS",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        ScientificCard {
            futureDirections.forEachIndexed { index, direction ->
                FutureBullet(text = direction)
                if (index < futureDirections.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun FutureBullet(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 8.dp)
                .size(4.dp)
                .background(AccentViolet, RoundedCornerShape(50))
        )
        MarkdownText(
            markdown = text,
            color = SkoLabTextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun IntelligenceChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = Typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun IntelligenceShimmerBlock() {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header Shimmer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonShimmer(Modifier.size(20.dp), shape = CircleShape)
                Spacer(modifier = Modifier.width(10.dp))
                SkeletonShimmer(Modifier.size(160.dp, 16.dp))
            }
            SkeletonShimmer(Modifier.size(100.dp, 14.dp))
        }

        // TL;DR Hero Card Shimmer
        ScientificCard(glowColor = SkoLabAiInsight.copy(alpha = 0.05f)) {
            SkeletonShimmer(Modifier.size(80.dp, 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonShimmer(Modifier.fillMaxWidth().height(18.dp))
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonShimmer(Modifier.fillMaxWidth(0.8f).height(18.dp))
        }

        // Key Findings Shimmer
        ScientificCard {
            SkeletonShimmer(Modifier.size(120.dp, 14.dp))
            Spacer(modifier = Modifier.height(16.dp))
            repeat(3) { index ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                    SkeletonShimmer(Modifier.size(6.dp), shape = CircleShape)
                    Spacer(modifier = Modifier.width(8.dp))
                    SkeletonShimmer(Modifier.fillMaxWidth(0.9f - index * 0.1f).height(14.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Chips Shimmer (Techniques & Tools)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonShimmer(Modifier.size(90.dp, 28.dp), shape = RoundedCornerShape(14.dp))
            SkeletonShimmer(Modifier.size(110.dp, 28.dp), shape = RoundedCornerShape(14.dp))
            SkeletonShimmer(Modifier.size(80.dp, 28.dp), shape = RoundedCornerShape(14.dp))
        }
    }
}

@Composable
fun IntelligenceUnavailableBlock(onRetry: () -> Unit, reason: String) {
    ScientificCard(
        borderColor = SkoLabWarning.copy(alpha = 0.3f),
        glowColor = SkoLabWarning.copy(alpha = 0.05f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⚠️",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Deep AI Analysis Unavailable",
                style = Typography.titleMedium,
                color = SkoLabTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (reason.contains("not reachable")) "Check your network or restart the server." else "This work cannot be fetched in full text.",
                style = Typography.bodyMedium,
                color = SkoLabTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = SkoLabPrimary)
            ) {
                Text("Retry Analysis", color = Color.White)
            }
        }
    }
}

@Composable
fun CitationNetworkGalaxy() {
    Column {
        Text(
            text = "CITATION NETWORK GALAXY",
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MidnightBlue.copy(alpha = 0.5f))
                .border(0.5.dp, GlassBorder, RoundedCornerShape(16.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val nodeCount = 15
                val centerY = size.height / 2
                val spacing = size.width / (nodeCount + 1)
                
                for (i in 1..nodeCount) {
                    val x = i * spacing
                    val y = centerY + (Math.sin(i.toDouble() * 0.7 + i.toDouble()) * 40).toFloat()
                    val color = if (i % 4 == 0) SkoLabDisruption else SkoLabTextMuted
                    
                    if (i < nodeCount) {
                        val nextX = (i + 1) * spacing
                        val nextY = centerY + (Math.sin((i + 1).toDouble() * 0.7 + (i + 1).toDouble()) * 40).toFloat()
                        drawLine(
                            color = SkoLabDivider.copy(alpha = 0.2f),
                            start = Offset(x, y),
                            end = Offset(nextX, nextY),
                            strokeWidth = 1f
                        )
                    }
                    
                    drawCircle(
                        color = color,
                        radius = if (i % 4 == 0) 5.dp.toPx() else 3.dp.toPx(),
                        center = Offset(x, y),
                        alpha = if (i % 4 == 0) 1f else 0.4f
                    )
                }
            }
        }
    }
}
