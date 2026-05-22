package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.components.ScientificCard
import com.open.entropy.ui.components.primitives.SectionHeader
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.layout.screenHorizontalPadding
import com.open.entropy.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────
// NEXUS SCREEN — Peer Benchmark + Collaboration Radar
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusScreen() {
    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        // Keep the beautiful galaxy canvas as background (subtle)
        KnowledgeGalaxyMap()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .screenHorizontalPadding()
                .padding(top = 8.dp, bottom = ScreenInsets.bottomNavClearance),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Section header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "THE NEXUS",
                            style = Typography.labelSmall,
                            color = AccentTeal,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Your Research Universe",
                            style = Typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = AccentTealLight) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(AccentTeal))
                            Text("LIVE", color = AccentTealDark, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            // ── Peer Benchmark ───────────────────────────────────
            item { PeerBenchmarkCard() }

            // ── Collaboration Radar ──────────────────────────────
            item { CollaborationRadarCard() }

            // ── Knowledge Bridge (original feature, now smaller) ─
            item { KnowledgeBridgeCard() }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// PEER BENCHMARK CARD
// ─────────────────────────────────────────────────────────────────

data class BenchmarkMetric(
    val label: String,
    val yourValue: Float,   // 0.0–1.0 normalized
    val yourRaw: String,
    val peerRaw: String,
    val color: Color
)

@Composable
fun PeerBenchmarkCard() {
    // TODO: replace with live user + peer data from OpenAlex
    val metrics = listOf(
        BenchmarkMetric("H-Index",    0.72f, "18",     "12 avg",  AccentTeal),
        BenchmarkMetric("Citations",  0.85f, "1,240",  "890 avg", AccentIndigo),
        BenchmarkMetric("Works",      0.60f, "34",     "28 avg",  AccentEmerald),
        BenchmarkMetric("Impact",     0.78f, "4.2 IF", "3.1 avg", AccentAmber),
    )

    var animTrigger by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); animTrigger = true }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "PEER BENCHMARK",
                        style = Typography.labelSmall,
                        color = TextMuted,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "You vs. Top Peers — IIT Hyderabad",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentEmeraldLight
                ) {
                    Text(
                        "TOP 15%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = AccentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Metric rows
            metrics.forEach { metric ->
                BenchmarkRow(metric = metric, animate = animTrigger)
                Spacer(Modifier.height(12.dp))
            }

            // Legend
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot("You", AccentTeal)
                LegendDot("Peer Average", BorderMedium)
            }
        }
    }
}

@Composable
fun BenchmarkRow(metric: BenchmarkMetric, animate: Boolean) {
    val animatedWidth by animateFloatAsState(
        targetValue = if (animate) metric.yourValue else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "benchmark_${metric.label}"
    )
    val peerFraction = 0.55f // peer average normalized

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(metric.label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.yourRaw, color = metric.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("·", color = BorderMedium, fontSize = 12.sp)
                Text(metric.peerRaw, color = TextMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BorderLight)
        ) {
            // Peer bar (grey)
            Box(
                modifier = Modifier
                    .fillMaxWidth(peerFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderMedium)
            )
            // Your bar (color)
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedWidth)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(listOf(metric.color, metric.color.copy(alpha = 0.7f)))
                    )
            )
        }
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

// ─────────────────────────────────────────────────────────────────
// COLLABORATION RADAR CARD
// ─────────────────────────────────────────────────────────────────

data class CollabSuggestion(
    val name: String,
    val institution: String,
    val overlap: Int,      // %
    val field: String,
    val color: Color
)

@Composable
fun CollaborationRadarCard() {
    // TODO: replace with OpenAlex author similarity API
    val suggestions = listOf(
        CollabSuggestion("Dr. Priya Nair",      "IISc Bangalore",       87, "ML + Physics", AccentTeal),
        CollabSuggestion("Dr. Arjun Mehta",     "TIFR Mumbai",           74, "Quantum Computing", AccentIndigo),
        CollabSuggestion("Dr. Sarah Chen",      "MIT CSAIL",             68, "NLP + Bioinformatics", AccentEmerald),
        CollabSuggestion("Dr. Carlos Ruiz",     "ETH Zurich",            61, "Materials Science", AccentAmber),
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "COLLABORATION RADAR",
                        style = Typography.labelSmall,
                        color = TextMuted,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Researchers with overlapping interests",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Icon(Icons.Default.Hub, null, tint = AccentViolet, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.height(14.dp))

            suggestions.forEachIndexed { index, collab ->
                CollabRow(collab, index)
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(color = BorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
fun CollabRow(collab: CollabSuggestion, index: Int) {
    var animDone by remember { mutableStateOf(false) }
    val animOverlap by animateIntAsState(
        targetValue = if (animDone) collab.overlap else 0,
        animationSpec = tween(800, delayMillis = index * 120, easing = FastOutSlowInEasing),
        label = "overlap"
    )
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400L + index * 120); animDone = true }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(collab.color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                collab.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
                color = collab.color,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        // Name + institution
        Column(modifier = Modifier.weight(1f)) {
            Text(collab.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(collab.institution, color = AccentTeal, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(collab.field, color = TextMuted, fontSize = 10.sp)
        }
        // Overlap badge
        Surface(shape = RoundedCornerShape(8.dp), color = collab.color.copy(alpha = 0.1f)) {
            Text(
                "$animOverlap%",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = collab.color,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// KNOWLEDGE BRIDGE CARD (condensed original feature)
// ─────────────────────────────────────────────────────────────────

@Composable
fun KnowledgeBridgeCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AccentViolet.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = CircleShape, color = AccentViolet.copy(alpha = 0.15f)) {
                Icon(
                    Icons.Default.AutoGraph, null,
                    tint = AccentViolet,
                    modifier = Modifier.size(36.dp).padding(8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "BRIDGE DISCOVERED",
                    style = Typography.labelSmall,
                    color = AccentViolet,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    "Quantum Topology ↔ Neural Networks",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    "12× efficiency gain potential via semantic path tracing",
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = AccentViolet, modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// GALAXY CANVAS (unchanged)
// ─────────────────────────────────────────────────────────────────

@Composable
fun KnowledgeGalaxyMap() {
    val infiniteTransition = rememberInfiniteTransition(label = "galaxy")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val nodes = 20
        for (i in 0 until nodes) {
            val angle = Math.toRadians((rotation + (i * 360f / nodes)).toDouble())
            val radius = 200.dp.toPx() + (sin(rotation * 0.05f + i) * 50.dp.toPx())
            val x = center.x + radius * cos(angle).toFloat()
            val y = center.y + radius * sin(angle).toFloat()
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(AccentTeal.copy(alpha = 0.05f), Color.Transparent),
                    start = center, end = Offset(x, y)
                ),
                start = center, end = Offset(x, y), strokeWidth = 1.dp.toPx()
            )
            drawCircle(
                color = if (i % 3 == 0) AccentViolet else AccentTeal,
                radius = 3.dp.toPx(), center = Offset(x, y), alpha = 0.2f
            )
        }
    }
}
