package com.open.skolab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.open.skolab.ui.theme.SkoLabMotion

/**
 * Displays content with a fade entrance animation.
 * The [index] parameter is kept for API compatibility but is no longer used
 * for stagger delays — all items appear simultaneously to avoid the
 * sequential "pop-in" effect on every navigation event.
 */
@Composable
fun StaggeredVisibility(
    index: Int,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // No delay — content is visible immediately on the next frame
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(SkoLabMotion.normal))
    ) {
        content()
    }
}

