package com.open.entropy.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.model.GlobalResearcher
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.ResearcherViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaultScreen(viewModel: ResearcherViewModel = viewModel()) {
    val researchers by viewModel.researchers.collectAsState(initial = emptyList())
    val selectedField by viewModel.selectedField.collectAsState()
    
    val fields = listOf("All", "Physics", "Medicine", "Computer Science", "Biology", "Mathematics")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = "Research Vault",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Text(
                text = "Real-time 1.1TB Graph Explorer",
                style = MaterialTheme.typography.labelMedium,
                color = AccentTeal,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fields.forEach { field ->
                    val isSelected = selectedField == field
                    Surface(
                        onClick = { viewModel.setField(field) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AccentTeal.copy(alpha = 0.15f) else BgCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, 
                            if (isSelected) AccentTeal.copy(alpha = 0.5f) else BorderLight.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = field,
                                fontSize = 13.sp,
                                color = if (isSelected) AccentTeal else TextMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        if (researchers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp, top = 16.dp)
            ) {
                items(researchers) { researcher ->
                    UltraModernResearcherCard(researcher)
                }
            }
        }
    }
}

@Composable
fun UltraModernResearcherCard(researcher: GlobalResearcher) {
    Surface(
        color = BgCard.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            Brush.verticalGradient(listOf(BorderLight.copy(alpha = 0.2f), Color.Transparent))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(AccentTeal.copy(alpha = 0.2f), Color.Transparent)))
                        .border(1.dp, AccentTeal.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = researcher.display_name.take(1),
                        color = AccentTeal,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = researcher.display_name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (researcher.is_verified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Verified, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = researcher.current_institution,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SCORE",
                        color = TextMuted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${researcher.innovation_score.toInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        color = AccentTeal,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = DisplayFontFamily
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgCard.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem(Icons.Default.AutoGraph, researcher.field_of_study.uppercase(), isLabel = true)
                VerticalDivider()
                MetricItem(Icons.Default.School, "h-index: ${researcher.h_index}")
                VerticalDivider()
                MetricItem(Icons.AutoMirrored.Filled.TrendingUp, "${formatCount(researcher.cited_by_count)} Citations")
            }
        }
    }
}

@Composable
fun MetricItem(icon: ImageVector, value: String, isLabel: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isLabel) AccentTeal else TextMuted,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            color = if (isLabel) AccentTeal else TextPrimary,
            fontSize = 10.sp,
            fontWeight = if (isLabel) FontWeight.Black else FontWeight.SemiBold,
            letterSpacing = if (isLabel) 0.5.sp else 0.sp
        )
    }
}

@Composable
fun VerticalDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 12.dp)
            .background(BorderLight)
    )
}

fun formatCount(count: Int): String {
    return when {
        count >= 1000000 -> "${count / 1000000}M"
        count >= 1000 -> "${count / 1000}K"
        else -> count.toString()
    }
}
