package com.company.skolab.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── SkoLab Spacing Scale ─────────────────────────────────────────────────────
// Follows a 4dp base grid — same scale used by Google, Meta, Apple HIG
data class SkoLabSpacing(
    val xs:  Dp = 4.dp,
    val sm:  Dp = 8.dp,
    val md:  Dp = 12.dp,
    val lg:  Dp = 16.dp,
    val xl:  Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

// ─── Icon Sizes ───────────────────────────────────────────────────────────────
// Consistent icon sizes across the entire app — no more magic numbers in screens
object SkoLabIconSize {
    val xs:   Dp = 12.dp  // micro badges, inline metadata
    val sm:   Dp = 14.dp  // list row secondary actions
    val md:   Dp = 16.dp  // standard inline icons
    val lg:   Dp = 20.dp  // primary action icons, nav bar
    val xl:   Dp = 24.dp  // prominent icons, empty states
    val xxl:  Dp = 32.dp  // hero / feature icons
    val hero: Dp = 48.dp  // splash, onboarding
}

// ─── Icon Button Touch Target Sizes ──────────────────────────────────────────
// Material 3 minimum touch target is 48dp; compact variants for dense UIs
object SkoLabIconButtonSize {
    val compact: Dp = 28.dp  // ultra-compact rows (contacts list)
    val small:   Dp = 32.dp  // dense list rows
    val medium:  Dp = 40.dp  // standard Material 3
    val large:   Dp = 48.dp  // accessible, prominent actions
}

// ─── Border Radius Scale ─────────────────────────────────────────────────────
object SkoLabRadius {
    val xs:   Dp = 4.dp   // tags, badges, chips
    val sm:   Dp = 8.dp   // small cards, inputs
    val md:   Dp = 12.dp  // standard cards
    val lg:   Dp = 16.dp  // large cards, sheets
    val xl:   Dp = 20.dp  // modals, bottom sheets
    val pill: Dp = 50.dp  // fully rounded: buttons, avatars
}

// ─── Typography Size Constants ────────────────────────────────────────────────
// Named semantic sizes — use these instead of hardcoded sp values in composables
object SkoLabFontSize {
    val micro:    TextUnit = 9.sp   // legal text, timestamps
    val xxs:      TextUnit = 10.sp  // badges, compact metadata
    val xs:       TextUnit = 11.sp  // caption, sub-labels
    val sm:       TextUnit = 12.sp  // secondary body, chips
    val body:     TextUnit = 13.sp  // primary list body
    val bodyLg:   TextUnit = 14.sp  // standard body text
    val subtitle: TextUnit = 15.sp  // card subtitles
    val title:    TextUnit = 16.sp  // section titles
    val heading:  TextUnit = 18.sp  // screen headings
    val display:  TextUnit = 22.sp  // large display text
    val hero:     TextUnit = 28.sp  // hero/onboarding text
}

val LocalSkoLabSpacing = staticCompositionLocalOf { SkoLabSpacing() }
