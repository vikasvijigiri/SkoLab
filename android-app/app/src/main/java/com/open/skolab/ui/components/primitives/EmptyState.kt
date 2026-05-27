package com.open.skolab.ui.components.primitives

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.open.skolab.ui.theme.SkoLabDisruption
import com.open.skolab.ui.theme.SkoLabTextPrimary
import com.open.skolab.ui.theme.SkoLabTextSecondary
import com.open.skolab.ui.theme.Typography

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ConstellationIcon(modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = title, style = Typography.titleLarge, color = SkoLabTextPrimary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = Typography.bodyMedium, color = SkoLabTextSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(modifier = Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Something went wrong", style = Typography.titleMedium, color = SkoLabTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = Typography.bodyMedium, color = SkoLabTextSecondary, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            SkoLabOutlinedButton(text = "Try again", onClick = onRetry)
        }
    }
}

@Composable
private fun ConstellationIcon(modifier: Modifier = Modifier) {
    val dots = listOf(
        Offset(0.2f, 0.3f) to SkoLabDisruption,
        Offset(0.5f, 0.15f) to SkoLabDisruption.copy(alpha = 0.6f),
        Offset(0.75f, 0.4f) to SkoLabDisruption.copy(alpha = 0.8f),
        Offset(0.35f, 0.65f) to SkoLabDisruption.copy(alpha = 0.5f),
        Offset(0.6f, 0.8f) to SkoLabDisruption.copy(alpha = 0.7f),
    )
    Canvas(modifier = modifier) {
        dots.forEach { (rel, color) ->
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(rel.x * size.width, rel.y * size.height)
            )
        }
        for (i in 0 until dots.size - 1) {
            val a = Offset(dots[i].first.x * size.width, dots[i].first.y * size.height)
            val b = Offset(dots[i + 1].first.x * size.width, dots[i + 1].first.y * size.height)
            drawLine(
                color = SkoLabDisruption.copy(alpha = 0.2f),
                start = a,
                end = b,
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}
