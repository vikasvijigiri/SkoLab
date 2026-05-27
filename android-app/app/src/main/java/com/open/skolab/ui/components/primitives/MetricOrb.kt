package com.open.skolab.ui.components.primitives

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.open.skolab.ui.components.ScoreArcMeter
import com.open.skolab.ui.layout.rememberMetricMeterSize
import com.open.skolab.ui.theme.SkoLabMotion
import com.open.skolab.ui.theme.SkoLabTextSecondary
import com.open.skolab.ui.theme.MonoFontFamily
import com.open.skolab.ui.theme.Typography

@Composable
fun MetricOrb(
    score: Float,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = rememberMetricMeterSize(),
    trend: String? = null
) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(score) { target = score.coerceIn(0f, 1f) }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(SkoLabMotion.slow),
        label = "metricOrb"
    )

    Column(
        modifier = modifier.semantics {
            contentDescription = "$label ${(animated * 100).toInt()} percent"
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScoreArcMeter(
            score = animated,
            label = label,
            color = color,
            size = size
        )
        if (trend != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = trend,
                style = Typography.labelSmall,
                color = color,
                fontFamily = MonoFontFamily
            )
        }
    }
}

@Composable
fun MetricHeroRow(
    disruption: Float,
    novelty: Float,
    velocity: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MetricOrb(disruption, "D-INDEX", com.open.skolab.ui.theme.MetricDisruption)
        MetricOrb(novelty, "S-INDEX", com.open.skolab.ui.theme.MetricNovelty)
        MetricOrb(velocity, "V-INDEX", com.open.skolab.ui.theme.MetricVelocity)
    }
}
