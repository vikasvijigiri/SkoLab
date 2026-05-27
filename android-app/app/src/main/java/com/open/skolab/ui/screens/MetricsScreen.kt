package com.open.skolab.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
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

@Composable
fun MetricsScreen(viewModel: MetricsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)

    LaunchedEffect(cachedUser) {
        val name = cachedUser?.name ?: "Vikas Vijigiri"
        viewModel.setUserContext(name)
    }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        // Flat dark background with subtle grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotSpacing = 28.dp.toPx()
            val dotRadius = 0.8.dp.toPx()
            val cols = (size.width / dotSpacing).toInt() + 1
            val rows = (size.height / dotSpacing).toInt() + 1
            for (col in 0..cols) {
                for (row in 0..rows) {
                    drawCircle(
                        color = Color(0xFF8891B8).copy(alpha = 0.05f),
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
                        CircularProgressIndicator(color = EntropiColors.Blue1, strokeWidth = 2.dp)
                        Text("Loading metrics…", color = EntropiColors.Text3, fontSize = 14.sp)
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        Text(
                            "RESEARCH METRICS",
                            color = EntropiColors.Text3,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your Impact Score",
                            color = EntropiColors.Text,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Overall Score Card
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                                    )
                                )
                                .padding(24.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${metrics.overall_score}",
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = "RESEARCH ENTROPY",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.8f),
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    // Metrics Analysis
                    item {
                        Text(
                            text = metrics.analysis,
                            color = EntropiColors.Text2,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    }

                    // Detailed Stats
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Toughness",
                                value = "${metrics.topic_toughness}/100",
                                icon = Icons.Default.Science,
                                color = EntropiColors.Purple1
                            )
                            MetricCard(
                                modifier = Modifier.weight(1f),
                                title = "Velocity",
                                value = "${metrics.velocity}/100",
                                icon = Icons.Default.Speed,
                                color = EntropiColors.Gold2
                            )
                        }
                    }

                    // Skills
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(BgCard)
                                .padding(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = EntropiColors.Green)
                                Spacer(Modifier.width(12.dp))
                                Text("Acquired Skills", color = EntropiColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            FlowRowWrapper(items = metrics.skills, color = EntropiColors.Green)
                        }
                    }

                    // Tools
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(BgCard)
                                .padding(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoGraph, contentDescription = null, tint = EntropiColors.Blue1)
                                Spacer(Modifier.width(12.dp))
                                Text("Tools & Frameworks", color = EntropiColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            FlowRowWrapper(items = metrics.tools, color = EntropiColors.Blue1)
                        }
                    }
                }
            }
        }
    }
}

// ── Shared reusable error card ────────────────────────────────────────────────
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
                .background(Color(0xFF1A1A2E))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warning icon circle
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(EntropiColors.Red.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("⚠️", fontSize = 26.sp)
            }

            Text(
                text = "Something went wrong",
                color = EntropiColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = message,
                color = EntropiColors.Text2,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )

            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EntropiColors.Blue1,
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

@Composable
fun FlowRowWrapper(items: List<String>, color: Color) {
    // Basic wrap layout since ExperimentalLayoutApi FlowRow might not be imported
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(item, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(modifier: Modifier = Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(20.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.height(12.dp))
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = EntropiColors.Text)
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 14.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Medium)
    }
}
