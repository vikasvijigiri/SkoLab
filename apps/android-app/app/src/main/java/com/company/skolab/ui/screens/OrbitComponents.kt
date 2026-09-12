package com.company.skolab.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.*

/**
 * Split out of PaperCollabsScreen.kt when that file's dead composable was
 * removed (2026-09-12 no-slop audit) -- these three are genuinely used by
 * HomeScreen.kt's orbit/network section, unlike the composable they used to
 * live alongside. Same package as that caller (no import changes needed).
 */

@Composable
fun OrbitMetricCell(count: String, label: String, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = count,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = tint
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = tint.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun RelationshipOrbitCanvas(
    innerNodeLabels: List<String> = listOf("Co-Author 1", "Co-Author 2", "Co-Author 3"),
    outerNodeLabels: List<String> = listOf("", "", "", ""),
    centerLabel: String = "You"
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbit")

    val rotationAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
        label = "inner"
    )
    val rotationAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(65000, easing = LinearEasing), RepeatMode.Restart),
        label = "outer"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val rad1 = 75.dp.toPx()
        val rad2 = 120.dp.toPx()

        // Glow behind center
        drawCircle(color = PRIMARY.copy(alpha = 0.06f * pulseScale), radius = 44.dp.toPx(), center = center)

        // Orbit tracks (dashed rings)
        drawCircle(
            color = BORDER.copy(alpha = 0.5f), radius = rad1, center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                1.2f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            )
        )
        drawCircle(
            color = BORDER.copy(alpha = 0.3f), radius = rad2, center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                0.8f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 14f), 0f)
            )
        )

        val innerColors = listOf(PRIMARY, AccentTeal, AccentViolet)
        val outerColors = listOf(CustomOrangeGold, BrandPurple, AccentEmerald, AccentCyan)

        // Inner orbit nodes
        val numInner = innerNodeLabels.size.coerceAtMost(3)
        for (i in 0 until numInner) {
            val angleRad = Math.toRadians((rotationAngle1 + (i * 360.0 / numInner)))
            val nc = Offset(
                (center.x + rad1 * Math.cos(angleRad)).toFloat(),
                (center.y + rad1 * Math.sin(angleRad)).toFloat()
            )
            val c = innerColors[i % innerColors.size]
            drawLine(color = c.copy(alpha = 0.25f), start = center, end = nc, strokeWidth = 1f.dp.toPx())
            drawCircle(color = c.copy(alpha = 0.12f * pulseScale), radius = 20.dp.toPx(), center = nc)
            drawCircle(color = c, radius = 8.dp.toPx(), center = nc)
        }

        // Outer orbit nodes
        val numOuter = outerNodeLabels.size.coerceAtMost(4)
        for (i in 0 until numOuter) {
            val angleRad = Math.toRadians((rotationAngle2 + (i * 360.0 / numOuter.coerceAtLeast(1))))
            val nc = Offset(
                (center.x + rad2 * Math.cos(angleRad)).toFloat(),
                (center.y + rad2 * Math.sin(angleRad)).toFloat()
            )
            val c = outerColors[i % outerColors.size]
            drawLine(color = c.copy(alpha = 0.18f), start = center, end = nc, strokeWidth = 0.7f.dp.toPx())
            drawCircle(color = c.copy(alpha = 0.09f), radius = 22.dp.toPx(), center = nc)
            drawCircle(color = c, radius = 5.5.dp.toPx(), center = nc)
        }

        // Center node — user
        drawCircle(color = PRIMARY.copy(alpha = 0.18f * pulseScale), radius = 28.dp.toPx(), center = center)
        drawCircle(color = PRIMARY, radius = 11.dp.toPx(), center = center)
        drawCircle(color = Color.White, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
fun OrbitCollaboratorRecommendationCard(
    name: String,
    institution: String,
    match: Int,
    hIndex: Int? = null,
    reason: String,
    tags: List<String>,
    onConnect: () -> Unit = {}
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Initials avatar
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PRIMARY.copy(alpha = 0.10f))
                            .border(1.dp, PRIMARY.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase(),
                            color = PRIMARY,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = institution, color = TEXT_SECONDARY, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hIndex != null && hIndex > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "h-index: $hIndex",
                                color = TEXT_MUTED,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Color-coded match score badge
                val matchBg = when {
                    match >= 88 -> AccentEmerald.copy(alpha = 0.12f)
                    match >= 75 -> AccentAmber.copy(alpha = 0.12f)
                    else        -> MATCH_SCORE_BG
                }
                val matchTxt = when {
                    match >= 88 -> EmeraldDeeper
                    match >= 75 -> AmberDeeper
                    else        -> MATCH_SCORE_TEXT
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(matchBg)
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "$match%",
                        color = matchTxt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = reason, color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 17.sp)

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(3).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SURFACE_SUBTLE)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = tag, color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send Collaboration Request", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
