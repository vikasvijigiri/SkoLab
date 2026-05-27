package com.open.skolab.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.open.skolab.ui.theme.SkoLabDivider
import com.open.skolab.ui.theme.SkoLabSurface

@Composable
fun SkeletonShimmer(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    val shimmerColors = listOf(
        SkoLabSurface,
        SkoLabDivider,
        SkoLabSurface
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Box(
        modifier = modifier
            .background(brush, shape)
    )
}

@Composable
fun PremiumPaperLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            SkeletonShimmer(Modifier.size(80.dp, 20.dp))
            SkeletonShimmer(Modifier.size(60.dp, 20.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonShimmer(Modifier.fillMaxWidth().height(24.dp))
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonShimmer(Modifier.fillMaxWidth(0.7f).height(24.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SkeletonShimmer(Modifier.size(48.dp), shape = RoundedCornerShape(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                SkeletonShimmer(Modifier.size(120.dp, 16.dp))
                Spacer(modifier = Modifier.height(4.dp))
                SkeletonShimmer(Modifier.size(80.dp, 12.dp))
            }
        }
    }
}
