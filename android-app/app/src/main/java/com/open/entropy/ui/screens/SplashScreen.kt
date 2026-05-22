package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.components.BrandMark
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    // Reduced count because they are bigger now, for better performance and aesthetic
    val particles = remember { List(80) { Particle() } }
    
    val textAlpha = remember { Animatable(0f) }
    val textScale = remember { Animatable(0.8f) }
    
    val infiniteTransition = rememberInfiniteTransition(label = "entropy")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "frame"
    )

    LaunchedEffect(Unit) {
        textAlpha.animateTo(1f, animationSpec = tween(1200, easing = EaseInOutQuart))
        delay(1800)
        onAnimationFinished()
    }
    
    LaunchedEffect(Unit) {
        textScale.animateTo(1f, animationSpec = tween(2000, easing = EaseOutBack))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFF4CC9F0).copy(alpha = 0.15f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = size.maxDimension
                )
            )
            
            @Suppress("UNUSED_VARIABLE")
            val drive = frame 
            
            particles.forEach { particle ->
                particle.update(size.width, size.height)
                
                // Draw particle with an even larger aura for high contrast
                drawCircle(
                    color = particle.color.copy(alpha = 0.05f),
                    radius = particle.radius * 8f,
                    center = Offset(particle.x, particle.y)
                )
                drawCircle(
                    color = particle.color,
                    radius = particle.radius,
                    center = Offset(particle.x, particle.y)
                )
            }
        }

        BrandMark(
            style = MaterialTheme.typography.displayLarge,
            primaryColor = Color.White.copy(alpha = textAlpha.value),
            accentColor = Color(0xFF00E5FF).copy(alpha = textAlpha.value),
            modifier = Modifier.graphicsLayer {
                scaleX = textScale.value
                scaleY = textScale.value
            }
        )
    }
}

private class Particle {
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
    var radius by mutableStateOf(0f)
    var color by mutableStateOf(Color.White)
    
    private var vx = 0f
    private var vy = 0f
    private var initialized = false

    private val palette = listOf(
        Color(0xFF4CC9F0), // Vibrant Cyan
        Color(0xFFF72585), // Neon Pink
        Color(0xFFB5179E), // Grape
        Color(0xFF7209B7), // Purple
        Color.White
    )

    fun update(width: Float, height: Float) {
        if (!initialized && width > 0) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height
            // Significantly bigger radius
            radius = Random.nextFloat() * 6f + 2f 
            // Significantly faster initial velocity
            vx = (Random.nextFloat() - 0.5f) * 12f
            vy = (Random.nextFloat() - 0.5f) * 12f
            color = palette.random().copy(alpha = Random.nextFloat() * 0.8f + 0.2f)
            initialized = true
        }

        if (initialized) {
            x += vx
            y += vy

            if (x < -50) x = width + 50
            if (x > width + 50) x = -50f
            if (y < -50) y = height + 50
            if (y > height + 50) y = -50f
            
            // Increased "chaos" force for faster direction changes
            vx += (Random.nextFloat() - 0.5f) * 0.8f
            vy += (Random.nextFloat() - 0.5f) * 0.8f
            
            // Reduced friction to maintain high speed
            vx *= 0.998f
            vy *= 0.998f
        }
    }
}
