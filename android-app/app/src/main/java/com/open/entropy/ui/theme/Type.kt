package com.open.entropy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.open.entropy.R

val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val DisplayFontName = GoogleFont("Space Grotesk")
val MetricsFontName = GoogleFont("IBM Plex Mono")
val BodyFontName = GoogleFont("Inter")
val SyneFontName = GoogleFont("Syne")
val SpaceGroteskFontName = GoogleFont("Space Grotesk")
val JetBrainsMonoFontName = GoogleFont("JetBrains Mono")

val DisplayFontFamily = FontFamily(
    Font(googleFont = DisplayFontName, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = DisplayFontName, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = DisplayFontName, fontProvider = provider, weight = FontWeight.Medium)
)

val SyneFontFamily = FontFamily(
    Font(googleFont = SyneFontName, fontProvider = provider, weight = FontWeight.ExtraBold),
    Font(googleFont = SyneFontName, fontProvider = provider, weight = FontWeight.Bold)
)

val SpaceGroteskFontFamily = FontFamily(
    Font(googleFont = SpaceGroteskFontName, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = SpaceGroteskFontName, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = SpaceGroteskFontName, fontProvider = provider, weight = FontWeight.SemiBold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(googleFont = JetBrainsMonoFontName, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMonoFontName, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = JetBrainsMonoFontName, fontProvider = provider, weight = FontWeight.Bold)
)

val MonoFontFamily = FontFamily(
    Font(googleFont = MetricsFontName, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = MetricsFontName, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = MetricsFontName, fontProvider = provider, weight = FontWeight.Bold)
)

val BodyFontFamily = FontFamily(
    Font(googleFont = BodyFontName, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = BodyFontName, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = BodyFontName, fontProvider = provider, weight = FontWeight.SemiBold)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
)
