package com.open.skolab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.model.MockData
import com.open.skolab.ui.components.RadarChart
import com.open.skolab.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparePapersScreen(paperIds: List<String>, onBack: () -> Unit) {
    val papers = paperIds.mapNotNull { id -> MockData.papers.find { it.id == id } }
    
    Scaffold(
        containerColor = SkoLabBg,
        topBar = {
            TopAppBar(
                title = { Text("COMPARE PAPERS", style = Typography.labelSmall, color = SkoLabTextSecondary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SkoLabTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RadarChart(scores = papers.firstOrNull()?.let { mapOf("Disruption" to it.disruptionScore, "Novelty" to it.noveltyScore, "Velocity" to it.citationVelocity/500f, "Depth" to 0.8f, "Influence" to 0.7f, "Breadth" to 0.6f) } ?: emptyMap(), color = SkoLabPrimary)
                }
            }
            
            item {
                Surface(
                    color = SkoLabSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SkoLabDivider)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("AI INSIGHT SUMMARY", style = Typography.labelSmall, color = SkoLabSecondary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Paper A shows 40% higher disruption but lower novelty compared to Paper B. This indicates a more paradigm-shifting approach versus a purely conceptual expansion.",
                            style = Typography.bodyMedium,
                            color = SkoLabTextPrimary
                        )
                    }
                }
            }
            
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    papers.forEach { paper ->
                        CompareRow(paper)
                    }
                }
            }
        }
    }
}

@Composable
fun CompareRow(paper: com.open.skolab.model.Paper) {
    Surface(
        color = SkoLabSurface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SkoLabDivider)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(paper.title, style = Typography.titleLarge, fontSize = 14.sp, color = SkoLabTextPrimary, maxLines = 1)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricSmall("D-INDEX", paper.disruptionScore, SkoLabPrimary)
                MetricSmall("S-INDEX", paper.noveltyScore, SkoLabSecondary)
                MetricSmall("V-INDEX", paper.citationVelocity/500f, SkoLabGold)
            }
        }
    }
}

@Composable
fun MetricSmall(label: String, value: Float, color: Color) {
    Column {
        Text(label, style = Typography.labelSmall, color = SkoLabTextSecondary, fontSize = 9.sp)
        Text(String.format("%.2f", value), style = Typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}
