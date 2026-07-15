package com.company.skolab.ui.screens.feed.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.*

@Composable
fun AIDailyBriefCard(
    briefText: String,
    isLoading: Boolean,
    userId: String
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Purple1.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Purple glow top-right
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(SkoLabColors.Purple1.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.15f),
                            radius = size.width * 0.45f
                        )
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(SkoLabColors.Purple1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Brain",
                            tint = SkoLabColors.Purple2,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "AI DAILY BRIEF",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkoLabColors.Purple2,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Today",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        color = SkoLabColors.Text3,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isLoading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShimmerBar(Modifier.fillMaxWidth().height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.85f).height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.5f).height(12.dp))
                    }
                } else {
                    val annotatedString = buildAnnotatedString {
                        val parts = briefText.split("**")
                        parts.forEachIndexed { index, part ->
                            if (index % 2 == 1) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = SkoLabColors.Gold2)) {
                                    append(part)
                                }
                            } else {
                                append(part)
                            }
                        }
                    }

                    Text(
                        text = annotatedString,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = SkoLabColors.Text
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerBar(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerBar")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.12f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SkoLabColors.Text3.copy(alpha = alpha))
    )
}
