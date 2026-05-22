package com.open.entropy.ui.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Standard horizontal padding that keeps content inside the phone viewport. */
fun Modifier.screenHorizontalPadding(): Modifier =
    padding(horizontal = ScreenInsets.horizontal)

/** Top/side safe area for full-screen content under edge-to-edge system bars. */
fun Modifier.screenSafeArea(includeBottom: Boolean = false): Modifier =
    then(
        Modifier
            .statusBarsPadding()
            .then(if (includeBottom) Modifier.navigationBarsPadding() else Modifier)
    )

object ScreenInsets {
    val horizontal: Dp = 16.dp
    val bottomNavClearance: Dp = 112.dp
}

@Composable
fun rememberIsCompactWidth(): Boolean {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return screenWidth < 380.dp
}

@Composable
fun rememberMetricMeterSize(default: Dp = 88.dp, compact: Dp = 68.dp): Dp {
    return if (rememberIsCompactWidth()) compact else default
}

/** Horizontally scrollable row for metric orbs that must not clip on narrow screens. */
@Composable
fun ScrollableMetricRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        content()
    }
}
