package com.company.skolab.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ScoreArcMeter(
    score: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = MeterCyan,
    trackColor: Color = MeterTrackColor.copy(alpha = 0.3f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val radius = size.toPx() / 2 - 4.dp.toPx()
            
            // Subtle Outer Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.15f * pulseAlpha), Color.Transparent),
                    center = center,
                    radius = radius + 10.dp.toPx()
                ),
                radius = radius + 10.dp.toPx()
            )

            // Background Track
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 1.dp.toPx())
            )

            // Main Progress Arc (Glassy feel)
            drawArc(
                brush = Brush.sweepGradient(
                    0f to color.copy(alpha = 0.2f),
                    score to color,
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = 360f * score.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // Orbiting Particle
            val angleRad = Math.toRadians((rotation - 90).toDouble())
            val particleX = center.x + radius * cos(angleRad).toFloat()
            val particleY = center.y + radius * sin(angleRad).toFloat()
            
            drawCircle(
                color = color,
                radius = 2.dp.toPx(),
                center = Offset(particleX, particleY)
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = (score * 100).toInt().toString(),
                color = com.company.skolab.ui.theme.TextPrimary,
                fontSize = (size.value * 0.25).sp,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = color.copy(alpha = 0.7f),
                fontSize = (size.value * 0.12).sp,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
