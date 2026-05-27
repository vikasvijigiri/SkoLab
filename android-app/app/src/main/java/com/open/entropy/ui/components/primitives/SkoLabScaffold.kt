package com.open.entropy.ui.components.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.open.entropy.ui.components.CosmicBackground
import com.open.entropy.ui.theme.ObsidianBlack

@Composable
fun SkoLabScaffold(
    modifier: Modifier = Modifier,
    particleCount: Int = 40,
    content: @Composable (PaddingValues) -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(ObsidianBlack)) {
        CosmicBackground(particleCount = particleCount)
        androidx.compose.material3.Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            content = content
        )
    }
}
