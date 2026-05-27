package com.open.entropy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val SkoLabColorScheme = lightColorScheme(
    primary              = AccentTeal,
    onPrimary            = TextOnAccent,
    primaryContainer     = AccentTealLight,
    onPrimaryContainer   = AccentTealDark,
    secondary            = AccentIndigo,
    onSecondary          = TextOnAccent,
    secondaryContainer   = AccentIndigoLight,
    onSecondaryContainer = AccentIndigo,
    tertiary             = AccentAmber,
    onTertiary           = TextOnAccent,
    tertiaryContainer    = AccentAmberLight,
    onTertiaryContainer  = AccentOrange,
    background           = BgPrimary,
    onBackground         = TextPrimary,
    surface              = BgCard,
    onSurface            = TextPrimary,
    surfaceVariant       = BgElevated,
    onSurfaceVariant     = TextSecondary,
    surfaceTint          = AccentTeal,
    error                = AccentRose,
    onError              = TextOnAccent,
    errorContainer       = AccentRoseLight,
    onErrorContainer     = AccentRose,
    outline              = BorderLight,
    outlineVariant       = BorderMedium,
    scrim                = Color(0x80000000),
    inverseSurface       = TextPrimary,
    inverseOnSurface     = BgPrimary,
    inversePrimary       = AccentTealLight,
)

@Composable
fun SkoLabTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSkoLabSpacing provides SkoLabSpacing(),
        LocalSkoLabMotion provides SkoLabMotion,
    ) {
        MaterialTheme(
            colorScheme = SkoLabColorScheme,
            typography  = Typography,
            content     = content
        )
    }
}
