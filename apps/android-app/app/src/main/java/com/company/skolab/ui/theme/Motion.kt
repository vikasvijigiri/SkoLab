package com.company.skolab.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

object SkoLabMotion {
    const val fast = 150
    const val normal = 300
    const val slow = 400 // Adhere to checklist budget (max 400ms)

    private fun getDuration(durationMillis: Int): Int {
        // Respect system accessibility setting: Reduce Motion / disable animations
        return if (ValueAnimator.areAnimatorsEnabled()) durationMillis else 0
    }

    fun tweenFast() = tween<Float>(durationMillis = getDuration(fast), easing = FastOutSlowInEasing)
    fun tweenNormal() = tween<Float>(durationMillis = getDuration(normal), easing = FastOutSlowInEasing)
    fun tweenSlow() = tween<Float>(durationMillis = getDuration(slow), easing = FastOutSlowInEasing)

    val springSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val listStaggerMs = 60L
}

val LocalSkoLabMotion = staticCompositionLocalOf { SkoLabMotion }
