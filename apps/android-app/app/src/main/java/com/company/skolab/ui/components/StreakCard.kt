package com.company.skolab.ui.components

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.company.skolab.ui.theme.*

@Composable
fun StreakCard(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userPrefs = remember { com.company.skolab.data.UserPreferences(context) }
    val scope = rememberCoroutineScope()

    val streakCount by userPrefs.streakCount.collectAsState(initial = 5)
    val lastCheckedInDate by userPrefs.lastCheckedInDate.collectAsState(initial = null)

    val today = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }
    val checkedIn = lastCheckedInDate == today

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
        onClick = onClick,
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
                        text = if (checkedIn) "Today's check-in complete!" else "Solve today's Conjecture to maintain streak.",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                // Check-in trigger
                Surface(
                    onClick = onClick,
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
