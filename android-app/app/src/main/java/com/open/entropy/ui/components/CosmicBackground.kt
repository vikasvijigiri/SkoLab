package com.open.entropy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.open.entropy.ui.theme.*
import kotlin.random.Random

@Composable
fun CosmicBackground(
    modifier: Modifier = Modifier,
    particleCount: Int = 30
) {
    val particles = remember { List(particleCount) { CosmicParticle() } }
    
    val infiniteTransition = rememberInfiniteTransition(label = "cosmic")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "frame"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // Layer 1: Solid Dark Grey/Teal background matching WhatsApp
        drawRect(color = ObsidianBlack)
        
        // Layer 2: Repeating WhatsApp-Style Textured Scientific Doodle Wallpaper
        val tileWidth = 130.dp.toPx()
        val tileHeight = 130.dp.toPx()
        val strokeWidth = 1.dp.toPx()
        val doodleColor = ResQitDisruption.copy(alpha = 0.03f) // Subtler textured green
        
        val cols = (size.width / tileWidth).toInt() + 1
        val rows = (size.height / tileHeight).toInt() + 1
        
        for (col in -1..cols) {
            for (row in -1..rows) {
                // Deterministic pseudo-random seed using grid coordinates
                val seed = col * 73 + row * 109
                val randX = (Math.abs(seed % 31).toFloat() / 31f - 0.5f) * (tileWidth * 0.4f)
                val randY = (Math.abs(seed % 47).toFloat() / 47f - 0.5f) * (tileHeight * 0.4f)
                
                val centerX = col * tileWidth + tileWidth / 2f + randX
                val centerY = row * tileHeight + tileHeight / 2f + randY
                
                val rotation = (Math.abs(seed % 59).toFloat() / 59f - 0.5f) * 36f // Organic rotation
                val doodleIndex = Math.abs(seed) % 8
                val shapeSize = 26.dp.toPx()
                
                withTransform({
                    rotate(rotation, Offset(centerX, centerY))
                }) {
                    when (doodleIndex) {
                        0 -> drawAtom(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        1 -> drawBeaker(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        2 -> drawDna(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        3 -> drawLightbulb(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        4 -> drawPlanet(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        5 -> drawInfinity(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        6 -> drawTelescope(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                        7 -> drawDoodleStar(centerX, centerY, shapeSize, doodleColor, strokeWidth)
                    }
                }
            }
        }
    }
}

// Coordinate-drawn Vector Science Shapes
private fun DrawScope.drawAtom(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    drawCircle(color = color, radius = size * 0.08f, center = Offset(centerX, centerY))
    
    // Rotate orbits
    withTransform({ rotate(45f, Offset(centerX, centerY)) }) {
        drawOval(
            color = color,
            topLeft = Offset(centerX - size * 0.45f, centerY - size * 0.15f),
            size = Size(size * 0.9f, size * 0.3f),
            style = Stroke(strokeWidth)
        )
    }
    withTransform({ rotate(-45f, Offset(centerX, centerY)) }) {
        drawOval(
            color = color,
            topLeft = Offset(centerX - size * 0.45f, centerY - size * 0.15f),
            size = Size(size * 0.9f, size * 0.3f),
            style = Stroke(strokeWidth)
        )
    }
}

private fun DrawScope.drawBeaker(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    val path = Path().apply {
        moveTo(centerX - size * 0.12f, centerY - size * 0.4f)
        lineTo(centerX + size * 0.12f, centerY - size * 0.4f) // neck top
        lineTo(centerX + size * 0.12f, centerY - size * 0.15f) // neck right
        lineTo(centerX + size * 0.35f, centerY + size * 0.3f)  // flare right
        quadraticTo(centerX + size * 0.35f, centerY + size * 0.35f, centerX + size * 0.26f, centerY + size * 0.35f) // bottom right
        lineTo(centerX - size * 0.26f, centerY + size * 0.35f) // bottom flat
        quadraticTo(centerX - size * 0.35f, centerY + size * 0.35f, centerX - size * 0.35f, centerY + size * 0.3f) // bottom left
        lineTo(centerX - size * 0.12f, centerY - size * 0.15f) // neck left
        close()
    }
    drawPath(path, color = color, style = Stroke(strokeWidth))
    // fluid indicator line
    drawLine(
        color = color,
        start = Offset(centerX - size * 0.22f, centerY + size * 0.12f),
        end = Offset(centerX + size * 0.22f, centerY + size * 0.12f),
        strokeWidth = strokeWidth
    )
}

private fun DrawScope.drawDna(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    for (i in -3..3) {
        val yOffset = i * (size * 0.12f)
        val angle = i * 0.8f
        val x1 = centerX + kotlin.math.cos(angle) * (size * 0.3f)
        val x2 = centerX - kotlin.math.cos(angle) * (size * 0.3f)
        val yPos = centerY + yOffset
        
        // Helix ladder rung
        drawLine(color = color, start = Offset(x1, yPos), end = Offset(x2, yPos), strokeWidth = strokeWidth * 0.8f)
        drawCircle(color = color, radius = strokeWidth * 1.5f, center = Offset(x1, yPos))
        drawCircle(color = color, radius = strokeWidth * 1.5f, center = Offset(x2, yPos))
    }
}

private fun DrawScope.drawLightbulb(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    drawCircle(
        color = color,
        radius = size * 0.24f,
        center = Offset(centerX, centerY - size * 0.1f),
        style = Stroke(strokeWidth)
    )
    val bulbBase = Path().apply {
        moveTo(centerX - size * 0.12f, centerY + size * 0.08f)
        lineTo(centerX + size * 0.12f, centerY + size * 0.08f)
        lineTo(centerX + size * 0.08f, centerY + size * 0.24f)
        lineTo(centerX - size * 0.08f, centerY + size * 0.24f)
        close()
    }
    drawPath(bulbBase, color = color, style = Stroke(strokeWidth))
    drawCircle(
        color = color,
        radius = size * 0.04f,
        center = Offset(centerX, centerY + size * 0.28f),
        style = Stroke(strokeWidth)
    )
}

private fun DrawScope.drawPlanet(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    drawCircle(
        color = color,
        radius = size * 0.22f,
        center = Offset(centerX, centerY),
        style = Stroke(strokeWidth)
    )
    withTransform({ rotate(-15f, Offset(centerX, centerY)) }) {
        drawOval(
            color = color,
            topLeft = Offset(centerX - size * 0.4f, centerY - size * 0.08f),
            size = Size(size * 0.8f, size * 0.16f),
            style = Stroke(strokeWidth)
        )
    }
}

private fun DrawScope.drawInfinity(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    val r = size * 0.18f
    drawCircle(color = color, radius = r, center = Offset(centerX - r * 0.9f, centerY), style = Stroke(strokeWidth))
    drawCircle(color = color, radius = r, center = Offset(centerX + r * 0.9f, centerY), style = Stroke(strokeWidth))
}

private fun DrawScope.drawTelescope(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    withTransform({ rotate(-30f, Offset(centerX, centerY)) }) {
        drawRect(
            color = color,
            topLeft = Offset(centerX - size * 0.35f, centerY - size * 0.06f),
            size = Size(size * 0.7f, size * 0.12f),
            style = Stroke(strokeWidth)
        )
        drawRect(
            color = color,
            topLeft = Offset(centerX - size * 0.43f, centerY - size * 0.03f),
            size = Size(size * 0.08f, size * 0.06f),
            style = Stroke(strokeWidth)
        )
    }
    drawLine(color = color, start = Offset(centerX, centerY + size * 0.05f), end = Offset(centerX - size * 0.18f, centerY + size * 0.35f), strokeWidth = strokeWidth)
    drawLine(color = color, start = Offset(centerX, centerY + size * 0.05f), end = Offset(centerX + size * 0.18f, centerY + size * 0.35f), strokeWidth = strokeWidth)
    drawLine(color = color, start = Offset(centerX, centerY + size * 0.05f), end = Offset(centerX, centerY + size * 0.35f), strokeWidth = strokeWidth)
}

private fun DrawScope.drawDoodleStar(centerX: Float, centerY: Float, size: Float, color: Color, strokeWidth: Float) {
    val starPath = Path().apply {
        moveTo(centerX, centerY - size * 0.35f)
        quadraticTo(centerX, centerY, centerX + size * 0.35f, centerY)
        quadraticTo(centerX, centerY, centerX, centerY + size * 0.35f)
        quadraticTo(centerX, centerY, centerX - size * 0.35f, centerY)
        quadraticTo(centerX, centerY, centerX, centerY - size * 0.35f)
        close()
    }
    drawPath(starPath, color = color, style = Stroke(strokeWidth))
}

private class CosmicParticle {
    var x: Float = 0f
    var y: Float = 0f
    var radius: Float = 0f
    var color: Color = Color.White
    
    private var vx = 0f
    private var vy = 0f
    private var initialized = false

    private val palette = listOf(
        ResQitDisruption,
        ResQitNovelty,
        ResQitVelocity,
        ResQitCitations,
        ResQitAiInsight
    )

    fun update(width: Float, height: Float) {
        if (!initialized && width > 0) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height
            radius = Random.nextFloat() * 2f + 0.5f 
            vx = (Random.nextFloat() - 0.5f) * 0.4f 
            vy = (Random.nextFloat() - 0.5f) * 0.4f
            color = palette.random().copy(alpha = Random.nextFloat() * 0.2f + 0.05f)
            initialized = true
        }

        if (initialized) {
            x += vx
            y += vy

            if (x < -50) x = width + 50
            if (x > width + 50) x = -50f
            if (y < -50) y = height + 50
            if (y > height + 50) y = -50f
            
            vx += (Random.nextFloat() - 0.5f) * 0.01f
            vy += (Random.nextFloat() - 0.5f) * 0.01f
            
            vx *= 0.999f
            vy *= 0.999f
        }
    }
}
