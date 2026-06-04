package com.company.skolab.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

object SkoLabMotion {
    const val fast = 150
    const val normal = 300
    const val slow = 500

    fun tweenFast() = tween<Float>(durationMillis = fast, easing = FastOutSlowInEasing)
    fun tweenNormal() = tween<Float>(durationMillis = normal, easing = FastOutSlowInEasing)
    fun tweenSlow() = tween<Float>(durationMillis = slow, easing = FastOutSlowInEasing)

    val springSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val listStaggerMs = 60L
}

val LocalSkoLabMotion = staticCompositionLocalOf { SkoLabMotion }
