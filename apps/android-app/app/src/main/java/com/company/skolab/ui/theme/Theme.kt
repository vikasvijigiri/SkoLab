package com.company.skolab.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun SkoLabTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    isDarkThemeActiveState = darkThemeOverrideState ?: isSystemDark
    val isDark = isDarkThemeActiveState

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> lightColorScheme(
            primary              = PRIMARY,
            onPrimary            = TEXT_ON_PRIMARY,
            primaryContainer     = SURFACE_SUBTLE,
            onPrimaryContainer   = PRIMARY_DARK,
            secondary            = PRIMARY,
            onSecondary          = TEXT_ON_PRIMARY,
            secondaryContainer   = SURFACE_SUBTLE,
            onSecondaryContainer = PRIMARY_DARK,
            tertiary             = PRIMARY,
            onTertiary           = TEXT_ON_PRIMARY,
            tertiaryContainer    = SURFACE_SUBTLE,
            onTertiaryContainer  = PRIMARY_DARK,
            background           = PAGE_BACKGROUND,
            onBackground         = TEXT_PRIMARY,
            surface              = SURFACE,
            onSurface            = TEXT_PRIMARY,
            surfaceVariant       = SURFACE_SUBTLE,
            onSurfaceVariant     = TEXT_SECONDARY,
            surfaceTint          = PRIMARY,
            error                = NOTIFICATION_DOT,
            onError              = Color.White,
            errorContainer       = SURFACE_SUBTLE,
            onErrorContainer     = NOTIFICATION_DOT,
            outline              = BORDER,
            outlineVariant       = BORDER,
            scrim                = Color(0x80000000),
            inverseSurface       = TEXT_PRIMARY,
            inverseOnSurface     = PAGE_BACKGROUND,
            inversePrimary       = PRIMARY,
        )
    }

    CompositionLocalProvider(
        LocalSkoLabSpacing provides SkoLabSpacing(),
        LocalSkoLabMotion provides SkoLabMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content
        )
    }
}

