package com.company.skolab.ui.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Screen Classification
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Classifies how a screen participates in the global layout system.
 *
 * | Type         | Top bar | Bottom dock | Top inset   | Bottom inset        |
 * |--------------|---------|-------------|-------------|---------------------|
 * | MAIN_TAB     | ✅      | ✅          | scaffoldTop | scaffoldBottom      |
 * | DETAIL       | ❌      | ❌          | statusBars  | navigationBars only |
 * | FULL_SCREEN  | custom  | ❌          | none        | none (self-managed) |
 * | PRE_AUTH     | ❌      | ❌          | statusBars  | navigationBars      |
 *
 * Rules:
 * - MAIN_TAB:    Top-level tabs (Feed, Explore, Ask AI, Connect). Both bars shown.
 * - DETAIL:      Sub-screens pushed onto the stack. Dock is hidden (WhatsApp-style),
 *                screen handles its own back navigation.
 * - FULL_SCREEN: Screens with a custom inner Scaffold or sticky bottom input bar
 *                (CoLab workspace, Chat room). They manage all insets internally.
 * - PRE_AUTH:    Onboarding, auth, profile setup. No chrome at all.
 */
enum class ScreenType {
    MAIN_TAB,
    DETAIL,
    FULL_SCREEN,
    PRE_AUTH
}

// ─────────────────────────────────────────────────────────────────────────────
// SkoLabScreen — canonical wrapper for all NavHost composable bodies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Drop-in wrapper for every `composable { }` block inside the NavHost.
 * Applies the correct top/bottom insets for [type] so route bodies never
 * need to reason about padding themselves.
 *
 * Usage:
 * ```kotlin
 * composable("paper_detail/{id}") { backStack ->
 *     SkoLabScreen(scaffoldPadding, ScreenType.DETAIL) {
 *         PaperDetailScreen(…)
 *     }
 * }
 * ```
 */
@Composable
fun SkoLabScreen(
    scaffoldPadding: PaddingValues,
    type: ScreenType,
    content: @Composable () -> Unit
) {
    val modifier = when (type) {
        ScreenType.MAIN_TAB -> Modifier
            .fillMaxSize()
            .padding(top = scaffoldPadding.calculateTopPadding())
            .padding(bottom = scaffoldPadding.calculateBottomPadding())

        ScreenType.DETAIL -> Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()

        ScreenType.FULL_SCREEN -> Modifier
            .fillMaxSize()
            // Screen manages its own statusBarsPadding / navigationBarsPadding
            // (typically via an inner Scaffold or a sticky bottom bar modifier)

        ScreenType.PRE_AUTH -> Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    }

    Box(modifier = modifier) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Legacy helpers — kept for backward compatibility
// ─────────────────────────────────────────────────────────────────────────────

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

    /**
     * @deprecated Use [SkoLabScreen] with [ScreenType.DETAIL] instead.
     * Kept for any legacy call sites not yet migrated.
     */
    @Deprecated("Use SkoLabScreen(type = ScreenType.DETAIL) in NavHost composable blocks")
    val bottomNavClearance: Dp = 72.dp
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
