package com.company.skolab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.network.SummaryResponse
import com.company.skolab.ui.theme.*

@Composable
fun FrontierScoreOrb(
    score: Float,
    modifier: Modifier = Modifier
) {
    ScoreArcMeter(
        score = score,
        label = "FRONTIER",
        color = SkoLabAiInsight,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntelligenceBriefCard(
    summary: SummaryResponse,
    modifier: Modifier = Modifier
) {
    ScientificCard(
        modifier = modifier,
        glowColor = SkoLabAiInsight.copy(alpha = 0.15f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = SkoLabAiInsight,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AI INTELLIGENCE BRIEF",
                style = Typography.labelSmall,
                color = SkoLabAiInsight,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        summary.bullets.forEach { bullet ->
            AIInsightBullet(text = bullet)
        }
        
        if (summary.top_skills.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TOP KEYWORDS",
                style = Typography.labelSmall,
                color = SkoLabTextSecondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                summary.top_skills.forEach { skill ->
                    ScientificBadge(text = skill, color = SkoLabPrimary)
                }
            }
        }
    }
}

@Composable
fun AnalyticBox(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(0.5.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = Typography.labelSmall,
            color = SkoLabTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = Typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFontFamily
        )
    }
}
