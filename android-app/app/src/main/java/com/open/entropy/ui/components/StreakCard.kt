package com.open.entropy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.theme.*

@Composable
fun StreakCard(
    modifier: Modifier = Modifier
) {
    var streakCount by remember { mutableIntStateOf(5) }
    var checkedIn by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "flamePulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flameScale"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Brush.linearGradient(colors = listOf(AccentAmber.copy(alpha = 0.3f), AccentOrange.copy(alpha = 0.05f))))
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(BgCard, BgSubtle)
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Flame visual
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.12f))
                        .scale(if (checkedIn) scale else 1f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = "Streak Flame",
                        tint = if (checkedIn) AccentOrange else TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Streak statistics
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$streakCount Day Research Streak",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DisplayFontFamily
                    )
                    Text(
                        text = if (checkedIn) "Today's check-in complete!" else "Review 1 paper today to maintain streak.",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                // Check-in trigger
                Surface(
                    onClick = {
                        if (!checkedIn) {
                            checkedIn = true
                            streakCount += 1
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (checkedIn) AccentEmerald.copy(alpha = 0.12f) else AccentOrange.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (checkedIn) AccentEmerald.copy(alpha = 0.3f) else AccentOrange.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = if (checkedIn) "Claimed" else "Check In",
                        color = if (checkedIn) AccentEmerald else AccentOrange,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
