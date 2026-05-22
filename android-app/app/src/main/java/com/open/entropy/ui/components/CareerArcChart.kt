package com.open.entropy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.open.entropy.ui.theme.ResQitPrimary

@Composable
fun CareerArcChart(
    data: List<Pair<Int, Float>>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(120.dp)
) {
    if (data.isEmpty()) return

    val minYear = data.minOf { it.first }
    val maxYear = data.maxOf { it.first }
    val yearRange = (maxYear - minYear).coerceAtLeast(1)
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val points = data.map { (year, score) ->
            val x = (year - minYear).toFloat() / yearRange * width
            val y = height - (score * height)
            Offset(x, y)
        }

        // Fill area under the curve
        val fillPath = Path().apply {
            moveTo(0f, height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(width, height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(ResQitPrimary.copy(alpha = 0.2f), Color.Transparent)
            )
        )

        // Main line
        val linePath = Path().apply {
            points.forEachIndexed { index, offset ->
                if (index == 0) moveTo(offset.x, offset.y)
                else lineTo(offset.x, offset.y)
            }
        }

        drawPath(
            path = linePath,
            color = ResQitPrimary,
            style = Stroke(width = 2.dp.toPx())
        )
        
        // Data points
        points.forEach { point ->
            drawCircle(
                color = ResQitPrimary,
                radius = 3.dp.toPx(),
                center = point
            )
        }
    }
}
