package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.open.entropy.R
import com.open.entropy.ui.components.BrandMark
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    val particles = remember { List(100) { Particle() } }
    
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.3f) }
    
    val textAlpha = remember { Animatable(0f) }
    val textOffsetY = remember { Animatable(30f) }
    
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
        logoAlpha.animateTo(1f, animationSpec = tween(500, easing = EaseOutCubic))
    }

    LaunchedEffect(Unit) {
        logoScale.animateTo(1f, animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ))
    }

    LaunchedEffect(Unit) {
        delay(300)
        textAlpha.animateTo(1f, animationSpec = tween(400, easing = EaseOutCubic))
    }

    LaunchedEffect(Unit) {
        delay(300)
        textOffsetY.animateTo(0f, animationSpec = tween(500, easing = EaseOutQuart))
    }

    LaunchedEffect(Unit) {
        delay(1300) // Fast, premium splash duration
        onAnimationFinished()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to AccentTeal.copy(alpha = 0.12f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = size.maxDimension * 0.8f
                )
            )
            
            @Suppress("UNUSED_VARIABLE")
            val drive = frame 
            
            particles.forEach { particle ->
                particle.update(size.width, size.height)
                
                drawCircle(
                    color = particle.color.copy(alpha = 0.04f),
                    radius = particle.radius * 7f,
                    center = Offset(particle.x, particle.y)
                )
                drawCircle(
                    color = particle.color,
                    radius = particle.radius,
                    center = Offset(particle.x, particle.y)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "ReQit Logo",
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                    }
            )
            
            Spacer(modifier = Modifier.height(18.dp))
            
            BrandMark(
                style = MaterialTheme.typography.headlineLarge,
                primaryColor = TextPrimary,
                accentColor = AccentTeal,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffsetY.value
                }
            )
        }
    }
}

private class Particle {
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
    var radius by mutableStateOf(0f)
    var color by mutableStateOf(AccentTeal)
    
    private var vx = 0f
    private var vy = 0f
    private var initialized = false

    private val palette = listOf(
        AccentTeal,
        AccentViolet,
        AccentIndigo,
        TextMuted
    )

    fun update(width: Float, height: Float) {
        if (!initialized && width > 0) {
            // Start at center for explosion effect
            x = width / 2f
            y = height / 2f
            radius = Random.nextFloat() * 5f + 1.5f 
            
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = Random.nextFloat() * 15f + 4f
            vx = cos(angle) * speed
            vy = sin(angle) * speed
            
            color = palette.random().copy(alpha = Random.nextFloat() * 0.7f + 0.3f)
            initialized = true
        }

        if (initialized) {
            x += vx
            y += vy

            // Decelerate/friction
            vx *= 0.95f
            vy *= 0.95f
            
            // Add a tiny bit of drift
            x += (Random.nextFloat() - 0.5f) * 0.4f
            y += (Random.nextFloat() - 0.5f) * 0.4f
        }
    }
}
