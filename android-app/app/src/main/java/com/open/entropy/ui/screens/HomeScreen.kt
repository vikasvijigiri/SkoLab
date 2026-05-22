package com.open.entropy.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.R
import com.open.entropy.ui.components.BrandMark
import com.open.entropy.ui.components.PaperCard
import com.open.entropy.ui.components.primitives.FilterChipRow
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.FeedUiState
import com.open.entropy.viewmodel.FeedViewModel
import com.open.entropy.viewmodel.HomeViewModel
import com.open.entropy.viewmodel.TrendingUiState
import kotlinx.coroutines.delay
import java.util.Locale

// ─────────────────────────────────────────────────────────────────
// HOME SCREEN
// ─────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onPaperClick: (String) -> Unit,
    onMapClick: () -> Unit,
    viewModel: FeedViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel()
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Disruptive", "High Novelty", "Rising Stars", "Trending Fields")
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = BgPrimary,
        topBar = { ResQitTopBar("Vikas") }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Daily Research Pulse ─────────────────────────────
            item { DailyPulseCard() }

            // ── Citation Alert ───────────────────────────────────
            item { CitationAlertBanner() }

            // ── Hot Papers Trending ──────────────────────────────
            item { HotPapersRow(onPaperClick, homeViewModel) }

            // ── Filter chips ─────────────────────────────────────
            item { HomeFilterChipRow(filters, selectedFilter) { selectedFilter = it } }

            // ── Paper feed ───────────────────────────────────────
            when (val state = uiState) {
                is FeedUiState.Loading -> item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentTeal, strokeWidth = 2.dp)
                    }
                }
                is FeedUiState.Error -> item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(state.message, color = AccentRose, style = Typography.bodyMedium)
                    }
                }
                is FeedUiState.Success -> itemsIndexed(state.papers) { index, paper ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { delay(index * 60L); visible = true }
                    AnimatedVisibility(visible, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 20 }) {
                        PaperCard(paper = paper, onClick = { onPaperClick(paper.id) })
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DAILY PULSE CARD
// ─────────────────────────────────────────────────────────────────

data class PulseMetric(val label: String, val value: Int, val icon: ImageVector, val color: Color)

@Composable
fun DailyPulseCard() {
    val metrics = listOf(
        PulseMetric("Citations Today",  3,  Icons.Default.FormatQuote,   AccentTeal),
        PulseMetric("New in Field",      12, Icons.Default.Article,        AccentIndigo),
        PulseMetric("Trending Topics",   5,  Icons.AutoMirrored.Filled.TrendingUp, AccentAmber),
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Gradient accent strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(listOf(AccentTeal, AccentIndigo, AccentViolet)))
            )
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = greetingText(),
                            style = Typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Here's your research pulse",
                            style = Typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AccentTealLight
                    ) {
                        Text(
                            text = "LIVE",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = AccentTealDark,
                            style = Typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    metrics.forEach { metric ->
                        PulseMetricChip(metric = metric, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun PulseMetricChip(metric: PulseMetric, modifier: Modifier = Modifier) {
    var animTarget by remember { mutableIntStateOf(0) }
    val animatedCount by animateIntAsState(
        targetValue = animTarget,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "pulse_count"
    )
    LaunchedEffect(Unit) { delay(300); animTarget = metric.value }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = metric.color.copy(alpha = 0.07f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(metric.icon, null, tint = metric.color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = animatedCount.toString(),
                color = metric.color,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp
            )
            Text(
                text = metric.label,
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun greetingText(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning ☀️"
        hour < 17 -> "Good afternoon 🌤"
        else      -> "Good evening 🌙"
    }
}

// ─────────────────────────────────────────────────────────────────
// CITATION ALERT BANNER
// ─────────────────────────────────────────────────────────────────

@Composable
fun CitationAlertBanner() {
    var dismissed by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = !dismissed,
        exit = shrinkVertically(tween(250)) + fadeOut(tween(200))
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = AccentIndigoLight,
            border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(shape = CircleShape, color = AccentIndigo) {
                    Icon(
                        Icons.Default.FormatQuote, null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp).padding(6.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "2 researchers cited your work today",
                        color = AccentIndigo,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        "Tap to see who cited your papers",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                IconButton(
                    onClick = { dismissed = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// HOT PAPERS ROW  (live data from OpenAlex)
// ─────────────────────────────────────────────────────────────────

private val trendingPaletteColors = listOf(
    AccentIndigo, AccentTeal, AccentEmerald, AccentViolet,
    AccentAmber, AccentRose, AccentTeal, AccentIndigo
)

@Composable
fun HotPapersRow(
    onPaperClick: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.trendingState.collectAsState()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Whatshot, null, tint = AccentRose, modifier = Modifier.size(16.dp))
                Text(
                    "Trending This Week",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Text(
                "See all →",
                color = AccentTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is TrendingUiState.Loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(4) { i ->
                        HotPaperShimmerCard()
                    }
                }
            }
            is TrendingUiState.Error -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentRose.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Could not load trending papers. Tap to retry.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                            .clickable { viewModel.loadTrending() }
                    )
                }
            }
            is TrendingUiState.Success -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(s.papers) { index, paper ->
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(paper.id) { delay(index * 60L); visible = true }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(350)) + slideInHorizontally(tween(350)) { 40 }
                        ) {
                            val color = trendingPaletteColors[index % trendingPaletteColors.size]
                            HotPaperCard(
                                title = paper.title,
                                journal = paper.journal,
                                citedByCount = paper.citedByCount,
                                year = paper.year,
                                accentColor = color,
                                onClick = { onPaperClick(paper.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HotPaperShimmerCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        modifier = Modifier.width(200.dp).height(130.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            AccentTeal.copy(alpha = shimmerAlpha * 0.12f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
fun HotPaperCard(
    title: String,
    journal: String,
    citedByCount: Int,
    year: Int,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        modifier = Modifier.width(200.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accentColor)
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = year.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Citation count badge
                    Text(
                        text = if (citedByCount >= 1000) "${citedByCount / 1000}K cit." else "$citedByCount cit.",
                        color = AccentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = journal,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// TOP BAR (unchanged)
// ─────────────────────────────────────────────────────────────────

@Composable
fun ResQitTopBar(userName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark(style = Typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}) {
                    Icon(Icons.Default.Notifications, null, tint = ResQitTextSecondary)
                }
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(AccentTealLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(userName.take(1), style = Typography.labelSmall, color = AccentTeal)
                }
            }
        }
        Text(
            text = "Good ${greetingPart()}, $userName",
            style = Typography.bodySmall,
            color = ResQitTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun greetingPart(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { hour < 12 -> "morning"; hour < 17 -> "afternoon"; else -> "evening" }
}

@Composable
fun AlertStrip(text: String) {
    Surface(
        color = AccentTealLight,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().clickable { },
        border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = Typography.labelSmall,
            color = AccentTealDark
        )
    }
}

@Composable
fun HomeFilterChipRow(filters: List<String>, selected: String, onSelect: (String) -> Unit) {
    FilterChipRow(filters = filters, selected = selected, onSelect = onSelect)
}
