package com.company.skolab.ui.screens.feed.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.model.FrontierMetrics
import com.company.skolab.ui.components.ScoreArcMeter
import com.company.skolab.ui.theme.*

@Composable
fun FrontierPulseCard(metrics: FrontierMetrics) {
    val dProgress by animateFloatAsState(
        targetValue = metrics.dIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "dProgress"
    )
    val sProgress by animateFloatAsState(
        targetValue = metrics.sIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "sProgress"
    )
    val influenceScore = ((dProgress + sProgress) / 2f).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Gold glow top-right
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(SkoLabColors.Gold1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.9f, size.height * 0.1f),
                            radius = size.width * 0.4f
                        )
                    )
                    // radial Blue glow bottom-left
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(SkoLabColors.Blue1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.9f),
                            radius = size.width * 0.4f
                        )
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(SkoLabColors.Gold1.copy(alpha = alpha), CircleShape)
                    )
                    Text(
                        text = "FRONTIER PULSE · LIVE",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkoLabColors.Gold1,
                        letterSpacing = 1.2.sp
                    )
                }

                // Three Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // D-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.dIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = SkoLabColors.Gold2
                        )
                        Text("D-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = SkoLabColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.dIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = SkoLabColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(SkoLabColors.Border))

                    // S-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.sIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = SkoLabColors.Blue2
                        )
                        Text("S-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = SkoLabColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.sIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = SkoLabColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(SkoLabColors.Border))

                    // Total Papers
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = metrics.papersCount.toString(),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = SkoLabColors.Purple2
                        )
                        Text("PAPERS", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = SkoLabColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${metrics.papersDelta} new", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = SkoLabColors.Green)
                    }
                }

                // Arc meters row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(SkoLabColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, SkoLabColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = dProgress, label = "Disruption", size = 52.dp, color = SkoLabColors.Gold1)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(SkoLabColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, SkoLabColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = sProgress, label = "Novelty", size = 52.dp, color = SkoLabColors.Cyan)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(SkoLabColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, SkoLabColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = influenceScore, label = "Influence", size = 52.dp, color = SkoLabColors.Purple2)
                    }
                }
            }
        }
    }
}
