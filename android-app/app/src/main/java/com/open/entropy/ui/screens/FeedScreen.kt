package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.components.*
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.layout.screenHorizontalPadding
import com.open.entropy.ui.theme.*

data class FeedItem(
    val id: String,
    val author: String,
    val journal: String,
    val category: String,
    val title: String,
    val insight: String,
    val bullets: List<String>,
    val dIndex: Float,
    val sIndex: Float,
    val vIndex: Float,
    val tags: List<String>
)

@Composable
fun FeedScreen(onPaperClick: (String) -> Unit) {
    val items = remember { getMockFeedData() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            DiscoveryHero()
        }

        item {
            ScientificPulseRow()
        }

        item {
            Text(
                text = "EMERGING FRONTIERS",
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp),
                style = Typography.labelSmall,
                color = ResQitDisruption.copy(alpha = 0.7f),
                letterSpacing = 2.sp
            )
        }

        items(items) { item ->
            AiBriefCard(item, onPaperClick)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DiscoveryHero() {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val placeholders = listOf(
        "Search Quantum Topology...",
        "Explain CRISPR architecture...",
        "Find high-velocity citations in AI...",
        "Discover emerging biophysics..."
    )
    var placeholderIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            placeholderIndex = (placeholderIndex + 1) % placeholders.size
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .screenHorizontalPadding()
            .padding(top = 16.dp)
    ) {
        ScientificCard(
            glowColor = ResQitDisruption.copy(alpha = glowAlpha),
            borderWidth = 1.dp,
            borderColor = GlassBorder.copy(alpha = 0.6f)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(ResQitAiInsight, RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "INTELLIGENT KNOWLEDGE SEARCH",
                        style = Typography.labelSmall,
                        color = ResQitAiInsight,
                        letterSpacing = 1.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = "What would you like to discover today?",
                    style = Typography.headlineMedium,
                    color = ResQitTextPrimary,
                    lineHeight = 26.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    color = ObsidianBlack.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, GlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, null, tint = ResQitTextSecondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        AnimatedContent(
                            targetState = placeholders[placeholderIndex],
                            transitionSpec = {
                                (fadeIn(tween(500)) + slideInVertically { it / 2 }).togetherWith(
                                        fadeOut(tween(500)) + slideOutVertically { -it / 2 })
                            },
                            label = "placeholder"
                        ) { text ->
                            Text(
                                text = text,
                                style = Typography.bodyLarge,
                                color = ResQitTextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScientificPulseRow() {
    val pulses = listOf(
        "↑24% Disruption in Bio-AI",
        "🔥 New Nexus Bridge: Quantum Materials",
        "⚡ Citation Velocity Spike: LLM Theory",
        "🌀 Emerging Concept: Neural Mesh"
    )

    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenInsets.horizontal),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(pulses) { pulse ->
            ScientificCard(
                modifier = Modifier.widthIn(max = 280.dp),
                borderColor = ResQitDisruption.copy(alpha = 0.2f)
            ) {
                Text(
                    text = pulse,
                    style = Typography.labelSmall,
                    color = ResQitDisruption,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 2
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiBriefCard(item: FeedItem, onClick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.screenHorizontalPadding()) {
        ScientificCard(
            onClick = { expanded = !expanded },
            glowColor = if (item.dIndex > 0.8f) ResQitDisruption.copy(alpha = 0.1f) else Color.Transparent
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = ResQitAiInsight, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI INTEL BRIEF",
                        style = Typography.labelSmall,
                        color = ResQitAiInsight,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                ScientificBadge(text = item.category, color = ResQitNovelty)
            }

            Spacer(modifier = Modifier.height(16.dp))

            MarkdownText(
                markdown = item.title,
                color = ResQitTextPrimary,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                ScoreArcMeter(score = item.dIndex, label = "D-INDEX", size = 52.dp, color = ResQitDisruption)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    MarkdownText(
                        markdown = item.insight,
                        color = ResQitTextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${item.author} • ${item.journal}",
                        style = Typography.labelSmall,
                        color = ResQitTextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    item.bullets.forEach { bullet ->
                        AIInsightBullet(text = bullet)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item.tags.forEach { tag ->
                            ScientificTag(text = tag)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        onClick = { onClick(item.id) },
                        color = ResQitDisruption,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = "OPEN COCKPIT",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = Typography.labelSmall,
                            color = ObsidianBlack,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

fun getMockFeedData(): List<FeedItem> = listOf(
    FeedItem(
        id = "1",
        author = "Dr. Aris Xanthos",
        journal = "Nature Physics",
        category = "Quantum",
        title = "Non-Abelian Statistics in Moire Superlattices",
        insight = "Confirmed emergence of non-Abelian anyons in twisted bilayers.",
        bullets = listOf("Measured 24% increase in coherence", "Confirmed topological protection", "Mapped 12 intermediate states"),
        dIndex = 0.82f,
        sIndex = 0.75f,
        vIndex = 0.94f,
        tags = listOf("Topology", "Moire")
    ),
    FeedItem(
        id = "2",
        author = "DeepMind Research",
        journal = "Science",
        category = "AI Theory",
        title = "Neural Scaling Laws for Multi-Modal Generalization",
        insight = "Derived power-law constants for cross-modal transfer learning.",
        bullets = listOf("Optimized sparsity ratios", "Reduced compute by 40%", "Generalizes to 12 modalities"),
        dIndex = 0.94f,
        sIndex = 0.88f,
        vIndex = 0.99f,
        tags = listOf("Scaling", "Transformer")
    )
)
