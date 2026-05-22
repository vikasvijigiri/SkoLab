package com.open.entropy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarChart(
    scores: Map<String, Float>,
    color: Color,
    modifier: Modifier = Modifier.size(200.dp)
) {
    if (scores.isEmpty()) return
    
    val labels = scores.keys.toList()
    val values = scores.values.toList()
    val numAxes = labels.size
    if (numAxes < 3) return

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.minDimension / 2 * 0.8f

        // Draw background polygons (grid)
        for (i in 1..4) {
            val r = radius * (i / 4f)
            val path = Path()
            for (j in 0 until numAxes) {
                val angle = (Math.PI * 2 * j / numAxes) - Math.PI / 2
                val x = centerX + r * cos(angle).toFloat()
                val y = centerY + r * sin(angle).toFloat()
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.05f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Draw axes lines
        for (j in 0 until numAxes) {
            val angle = (Math.PI * 2 * j / numAxes) - Math.PI / 2
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            drawLine(
                color = Color.White.copy(alpha = 0.1f),
                start = Offset(centerX, centerY),
                end = Offset(x, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw the data shape
        val dataPath = Path()
        for (j in 0 until numAxes) {
            val angle = (Math.PI * 2 * j / numAxes) - Math.PI / 2
            val r = radius * values[j].coerceIn(0f, 1f)
            val x = centerX + r * cos(angle).toFloat()
            val y = centerY + r * sin(angle).toFloat()
            if (j == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()

        // Fill data shape
        drawPath(
            path = dataPath,
            color = color.copy(alpha = 0.2f)
        )
        
        // Stroke data shape
        drawPath(
            path = dataPath,
            color = color,
            style = Stroke(width = 2.dp.toPx())
        )
        
        // Draw dots at vertices
        for (j in 0 until numAxes) {
            val angle = (Math.PI * 2 * j / numAxes) - Math.PI / 2
            val r = radius * values[j].coerceIn(0f, 1f)
            val x = centerX + r * cos(angle).toFloat()
            val y = centerY + r * sin(angle).toFloat()
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}
