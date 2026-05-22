package com.open.entropy.ui.screens

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.viewmodel.PaperUiState
import com.open.entropy.viewmodel.PaperViewModel
import androidx.compose.ui.platform.LocalContext
import com.open.entropy.R
import com.open.entropy.ui.components.*
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.components.primitives.MetricHeroRow
import com.open.entropy.ui.components.primitives.SectionCaption
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.LibraryViewModel
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
    val uiState by viewModel.uiState.collectAsState()
    val savedIds by libraryViewModel.savedIds.collectAsState()
    val isSaved = savedIds.contains(paperId)
    val scope = rememberCoroutineScope()

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = ResQitTextPrimary)
                    }
                },
                actions = {
                    // Animated bookmark button
                    IconButton(onClick = {
                        scope.launch { libraryViewModel.toggleSaved(paperId) }
                    }) {
                        val bookmarkIcon = if (isSaved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder
                        val bookmarkTint = if (isSaved) AccentTeal else ResQitTextPrimary
                        Icon(
                            bookmarkIcon, null,
                            tint = bookmarkTint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (isSaved) 1.15f else 1f
                                scaleY = if (isSaved) 1.15f else 1f
                            }
                        )
                    }
                    IconButton(onClick = { }) { Icon(Icons.Default.Share, null, tint = ResQitTextPrimary) }
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
                        Text(text = state.message, color = ResQitWarning, style = Typography.bodyLarge)
                    }
                }
                is PaperUiState.Success -> {
                    val paper = state.paper
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

                        item {
                            AiIntelligenceBrief(paper)
                        }

                        if (paper.latexFormula != null) {
                            item {
                                TechnicalFormulaBlock(paper.latexFormula)
                            }
                        }

                        item {
                            CitationNetworkGalaxy()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperHero(paper: com.open.entropy.model.Paper, onAuthorClick: (String) -> Unit) {
    Column {
        ScientificBadge(text = paper.journal, color = ResQitDisruption)
        Spacer(modifier = Modifier.height(16.dp))
        MarkdownText(
            markdown = paper.title,
            color = ResQitTextPrimary,
            fontSize = 26.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            paper.authors.take(4).forEach { author ->
                Text(
                    text = author,
                    style = Typography.labelMedium,
                    color = ResQitDisruption,
                    modifier = Modifier.clickable { onAuthorClick(author) }
                )
            }
            if (paper.authors.size > 4) {
                Text(text = "+${paper.authors.size - 4} more", style = Typography.labelSmall, color = ResQitTextMuted)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "YEAR: ${paper.year} • CITATION VELOCITY: ${paper.citationVelocity.toInt()}",
            style = Typography.labelSmall,
            color = ResQitTextSecondary,
            fontFamily = MonoFontFamily,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun MetricCockpit(paper: com.open.entropy.model.Paper) {
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
            color = ResQitTextSecondary,
            fontFamily = MonoFontFamily
        )
    }
}

@Composable
fun AiIntelligenceBrief(paper: com.open.entropy.model.Paper) {
    var expanded by remember { mutableStateOf(false) }
    ScientificCard(
        glowColor = ResQitAiInsight.copy(alpha = 0.1f),
        accentColor = ResQitAiInsight,
        onClick = { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = ResQitAiInsight, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = LocalContext.current.getString(R.string.brand_intel_label).uppercase(),
                    style = Typography.labelSmall,
                    color = ResQitAiInsight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = if (expanded) "▲" else "▼",
                color = ResQitAiInsight,
                style = Typography.labelMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val rawInsight = paper.keyInsight.ifBlank { "Analyzing research signals…" }
        MarkdownText(
            markdown = "*$rawInsight*",
            color = ResQitTextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                paper.aiSummary.split(". ").forEach { bullet ->
                    if (bullet.isNotBlank()) {
                        AIInsightBullet(text = bullet.trim())
                    }
                }
            }
        }
    }
}

@Composable
fun TechnicalFormulaBlock(formula: String) {
    Column {
        Text(
            text = "MATHEMATICAL MODEL",
            style = Typography.labelSmall,
            color = ResQitTextSecondary,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
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
                color = ResQitDisruption,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun CitationNetworkGalaxy() {
    Column {
        Text(
            text = "CITATION NETWORK GALAXY",
            style = Typography.labelSmall,
            color = ResQitTextSecondary,
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
                    val color = if (i % 4 == 0) ResQitDisruption else ResQitTextMuted
                    
                    if (i < nodeCount) {
                        val nextX = (i + 1) * spacing
                        val nextY = centerY + (Math.sin((i + 1).toDouble() * 0.7 + (i + 1).toDouble()) * 40).toFloat()
                        drawLine(
                            color = ResQitDivider.copy(alpha = 0.2f),
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
