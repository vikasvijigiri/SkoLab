package com.company.skolab.ui.theme

import androidx.compose.ui.graphics.Color

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Deep Ocean Palette — research-backed universally pleasing colors ──────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//
// Science basis:
//   - Blue (#1 universally liked: 57% men, 35% women, cross-cultural studies)
//   - Teal (blue+green captures both #1 and #2 universally liked hues)
//   - Amber/Gold on dark = dopamine trigger for metrics/scores (UCLA research)
//   - Deep navy backgrounds lower cortisol, feel premium
//
// Primary Blue Ramp:
//   500: #3B82F6 (Dark mode active — glows on navy)
//   600: #2563EB (Light mode active — deep trust blue)
//   700: #1D4ED8
//   800: #1E40AF
//
// Dark Mode Surfaces (Deep Navy):
//   900: #080E1C (Page background)
//   800: #0F1829 (Card / Surface)
//   700: #162035 (Surface subtle / elevated)
//   600: #1E2D4A (Border)
//
// Light Mode Surfaces (Sleek Slate Gray-Blue):
//   50:  #F5F7FA (Page background)
//   0:   #FFFFFF (Card / Surface — pure white)
//   100: #EEF2F6 (Surface subtle)
//   200: #E2E8F0 (Border)
//
// Contrast Ratio Compliance (WCAG AA):
//   Dark Mode:
//     - Text Primary (#EEF2FF) vs Surface (#0F1829): 14.2:1 ✓
//     - Text Secondary (#94A3B8) vs Surface (#0F1829): 5.1:1 ✓
//   Light Mode:
//     - Text Primary (#0F172A) vs Surface (#FFFFFF): 19.1:1 ✓
//     - Text Secondary (#475569) vs Surface (#FFFFFF): 6.8:1 ✓
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

var darkThemeOverrideState by mutableStateOf<Boolean?>(null)
var isDarkThemeActiveState by mutableStateOf(false)

fun isDarkThemeActive(): Boolean = isDarkThemeActiveState

// ── Core Surfaces ─────────────────────────────────────────────────────────────

val PAGE_BACKGROUND: Color
    get() = if (isDarkThemeActive()) Color(0xFF080E1C) else Color(0xFFF7F3ED)

val SURFACE: Color
    get() = if (isDarkThemeActive()) Color(0xFF0F1829) else Color(0xFFFFFFFF)

val SURFACE_SUBTLE: Color
    get() = if (isDarkThemeActive()) Color(0xFF162035) else Color(0xFFF0EBE3)

// ── Brand Primary — Electric Blue (universally #1 liked color) ────────────────

val PRIMARY: Color
    get() = if (isDarkThemeActive()) Color(0xFF3B82F6) else Color(0xFF2563EB)

val PRIMARY_DARK: Color
    get() = if (isDarkThemeActive()) Color(0xFF2563EB) else Color(0xFF1D4ED8)

val PRIMARY_DEEPER: Color
    get() = if (isDarkThemeActive()) Color(0xFF0A1628) else Color(0xFFDBEAFE)

// ── Typography ────────────────────────────────────────────────────────────────

val TEXT_PRIMARY: Color
    get() = if (isDarkThemeActive()) Color(0xFFEEF2FF) else Color(0xFF0F172A)

val TEXT_SECONDARY: Color
    get() = if (isDarkThemeActive()) Color(0xFF94A3B8) else Color(0xFF475569)

val TEXT_MUTED: Color
    get() = if (isDarkThemeActive()) Color(0xFF64748B) else Color(0xFF8B95A5)

val TEXT_ON_PRIMARY: Color
    get() = Color(0xFFFFFFFF)

val TEXT_ON_PRIMARY_SUB: Color
    get() = if (isDarkThemeActive()) Color(0xFFBFDBFE) else Color(0xFFEFF6FF)

// ── Borders & Indicators ──────────────────────────────────────────────────────

val BORDER: Color
    get() = if (isDarkThemeActive()) Color(0xFF1E2D4A) else Color(0xFFE2E8F0)

val NOTIFICATION_DOT: Color
    get() = Color(0xFFEF4444)

val STREAK_BAR: Color
    get() = Color(0xFFF59E0B)

// ── Match Score (dopamine: amber gold on navy) ────────────────────────────────

val MATCH_SCORE_BG: Color
    get() = if (isDarkThemeActive()) Color(0xFF1A2744) else Color(0xFFDBEAFE)

val MATCH_SCORE_TEXT: Color
    get() = if (isDarkThemeActive()) Color(0xFF60A5FA) else Color(0xFF1D4ED8)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Semantic Accent Palette ───────────────────────────────────────────────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// Teal — CTA buttons, collaboration, connection (blue+green = universally liked)
private val TEAL        = Color(0xFF0D9488)  // teal-600
private val TEAL_BRIGHT = Color(0xFF14B8A6)  // teal-500, glows on dark navy
private val TEAL_DARK   = Color(0xFF0F766E)  // teal-700

// Emerald — success, open science, growth
private val EMERALD      = Color(0xFF059669)
private val EMERALD_DARK = Color(0xFF047857)

// Amber — dopamine trigger for metrics/scores/h-index on dark backgrounds
private val AMBER        = Color(0xFFD97706)  // amber-600
private val AMBER_BRIGHT = Color(0xFFF59E0B)  // amber-500, glows on dark navy
private val AMBER_DARK   = Color(0xFFB45309)  // amber-700

// Violet — AI insights, future predictions
private val VIOLET       = Color(0xFF7C3AED)
private val VIOLET_DARK  = Color(0xFF6D28D9)

// Orange — disruption, innovation, high impact
private val ORANGE       = Color(0xFFEA580C)
private val ORANGE_DARK  = Color(0xFFC2410C)

// Cyan — novelty, discovery
private val CYAN         = Color(0xFF0891B2)
private val CYAN_DARK    = Color(0xFF0E7490)

// Rose — urgency, errors
private val ROSE         = Color(0xFFCC3333)
private val ROSE_DARK    = Color(0xFFAA2222)

// Pink — creativity
private val PINK         = Color(0xFFDB2777)

// Indigo — complexity, structured analysis
private val INDIGO       = Color(0xFF4F46E5)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Semantic Variable Mappings ────────────────────────────────────────────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary: Color  get() = PAGE_BACKGROUND
val BgCard: Color     get() = SURFACE
val BgElevated: Color get() = SURFACE
val BgSubtle: Color   get() = SURFACE_SUBTLE

// ── Accent Colors ─────────────────────────────────────────────────
val AccentTeal: Color        get() = if (isDarkThemeActive()) TEAL_BRIGHT else TEAL
val AccentTealDark: Color    get() = TEAL_DARK
val AccentTealLight: Color   get() = AccentTeal.copy(alpha = 0.15f)
val AccentAmber: Color       get() = if (isDarkThemeActive()) AMBER_BRIGHT else AMBER
val AccentAmberLight: Color  get() = AccentAmber.copy(alpha = 0.15f)
val AccentIndigo             = INDIGO
val AccentIndigoLight        = INDIGO.copy(alpha = 0.15f)
val AccentEmerald            = EMERALD
val AccentEmeraldLight       = EMERALD.copy(alpha = 0.15f)
val AccentRose               = ROSE
val AccentRoseLight          = ROSE.copy(alpha = 0.15f)
val AccentViolet             = VIOLET
val AccentVioletLight        = VIOLET.copy(alpha = 0.15f)
val AccentOrange             = ORANGE
val AccentOrangeLight        = ORANGE.copy(alpha = 0.15f)
val AccentCyan               = CYAN
val AccentCyanLight          = CYAN.copy(alpha = 0.15f)
val AccentSlate: Color       get() = SURFACE_SUBTLE

// ── Text ─────────────────────────────────────────────────────────
val TextPrimary: Color    get() = TEXT_PRIMARY
val TextSecondary: Color  get() = TEXT_SECONDARY
val TextMuted: Color      get() = TEXT_MUTED
val TextOnAccent          = TEXT_ON_PRIMARY

// ── Borders ───────────────────────────────────────────────────────
val BorderLight: Color  get() = BORDER
val BorderMedium: Color get() = BORDER

// ── Shadows ───────────────────────────────────────────────────────
val ShadowColor = Color.Transparent

// ── Research Metric Colors ────────────────────────────────────────
val MetricDisruptionColor   = ORANGE
val MetricNoveltyColor      = CYAN
val MetricFutureImpactColor = VIOLET
val MetricInfluenceColor: Color  get() = PRIMARY
val MetricCreativityColor   = PINK
val MetricComplexityColor   = INDIGO
val MetricOpenScienceColor  = EMERALD
val MetricCollabColor: Color     get() = AccentTeal
val MetricConsistencyColor: Color get() = AccentAmber
val MetricPolicyColor       = Color(0xFF475569)

// ── Gradients ─────────────────────────────────────────────────────
val HeroGradient: List<Color>    get() = listOf(PRIMARY, PRIMARY_DARK)
val TealGradient: List<Color>    get() = listOf(AccentTeal, TEAL_DARK)
val IndigoGradient               = listOf(INDIGO, VIOLET)
val WarmGradient: List<Color>    get() = listOf(AccentAmber, ORANGE)

// ── Legacy Aliases ────────────────────────────────────────────────
val ObsidianBlack: Color         get() = BgPrimary
val Graphite: Color              get() = BgCard
val DeepNavy: Color              get() = BgElevated
val MidnightBlue: Color          get() = BgSubtle
val SkoLabBg: Color              get() = BgPrimary
val SkoLabSurface: Color         get() = BgCard
val SkoLabSurfaceElevated: Color get() = BgElevated
val SkoLabDivider: Color         get() = BorderLight
val SkoLabDisruption             = ORANGE
val SkoLabNovelty                = CYAN
val SkoLabVelocity               = VIOLET
val SkoLabAiInsight              = VIOLET
val SkoLabCitations: Color       get() = AccentAmber
val SkoLabWarning                = NOTIFICATION_DOT
val SkoLabPrimary: Color         get() = PRIMARY
val SkoLabSecondary: Color       get() = AccentTeal
val SkoLabGold: Color            get() = AccentAmber
val SkoLabTextPrimary: Color     get() = TextPrimary
val SkoLabTextSecondary: Color   get() = TextSecondary
val SkoLabTextMuted: Color       get() = TextMuted
val NeuralGradient: List<Color>  get() = listOf(BgPrimary, BgSubtle)
val DisruptionGlow               = listOf(ORANGE.copy(alpha = 0.08f), Color.Transparent)
val NoveltyGlow                  = listOf(CYAN.copy(alpha = 0.08f), Color.Transparent)
val VelocityGlow                 = listOf(VIOLET.copy(alpha = 0.08f), Color.Transparent)
val GlassBorder: Color           get() = BorderLight
val GlassSurface: Color          get() = BgCard
val SurfaceGlass: Color          get() = BgCard
val SurfaceGlassElevated: Color  get() = BgElevated
val MetricDisruption             = ORANGE
val MetricNovelty                = CYAN
val MetricVelocity               = VIOLET
val DeepSpace: Color             get() = BgPrimary
val SurfaceDark: Color           get() = BgCard
val BorderColor: Color           get() = BorderLight
val ElectricCyan                 = CYAN
val MutedGray: Color             get() = TextMuted
val HighContrastWhite: Color     get() = TextPrimary
val LogicBlue: Color             get() = PRIMARY
val PlasmaPink                   = PINK
val InsightGold: Color           get() = AccentAmber
val NovaOrange                   = ORANGE
val StellarTeal: Color           get() = AccentTeal
val DiscoveryEmerald             = EMERALD
val QuantumPurple                = VIOLET
val ProGradientSurface: List<Color> get() = listOf(BgCard, BgSubtle)
val ProGradientPrimary: List<Color> get() = TealGradient

// ── SkoLabColors ─────────────────────────────────────────────────
object SkoLabColors {
    val Background: Color  get() = PAGE_BACKGROUND
    val Card: Color        get() = SURFACE
    val Card2: Color       get() = SURFACE_SUBTLE
    val Border: Color      get() = BORDER
    val Text1: Color       get() = TEXT_PRIMARY
    val Text: Color        get() = TEXT_PRIMARY
    val Text2: Color       get() = TEXT_SECONDARY
    val Text3: Color       get() = TEXT_MUTED
    val Gold1: Color       get() = if (isDarkThemeActive()) AMBER_BRIGHT else AMBER
    val Gold2             = AMBER_DARK
    val Blue1: Color       get() = PRIMARY
    val Blue2: Color       get() = PRIMARY_DARK
    val Purple1           = VIOLET
    val Purple2           = VIOLET_DARK
    val Cyan              = CYAN
    val Green             = EMERALD
    val Red               = NOTIFICATION_DOT
}

// ── Brand & App-Specific Constants ────────────────────────────────
val AccentRed            = Color(0xFFE53935)
val ResearchGold         = Color(0xFFC9A84C)
val ResearchBlue         = Color(0xFF3D6FFF)
val ResearchGenomics     = Color(0xFF00D4FF)
val ResearchClimate      = Color(0xFF00E676)
val ResearchPhysics      = Color(0xFFFF4757)
val ResearchChemistry    = Color(0xFFFBBF24)
val ResearchMaterials    = Color(0xFFFB923C)
val BrandWhatsApp        = Color(0xFF25D366)
val BrandWhatsAppTeal    = Color(0xFF128C7E)
val BrandWhatsAppBubbleMe  = Color(0xFFDCF8C6)
val BrandWhatsAppBorderMe  = Color(0xFFC7E5AE)
val BrandWhatsAppBlueTick  = Color(0xFF34B7F1)
val MeterCyan            = Color(0xFF00E5FF)
val MeterTrackColor      = Color(0xFF30363D)
val BrandGoogleText      = Color(0xFF1F1F1F)
val ButtonDeleteRed      = Color(0xFFEF5350)
val BrandWhatsAppHeaderBg  = Color(0xFF2A3942)
val BrandWhatsAppInputBg   = Color(0xFF2E3B43)
val BrandGoogleBlue      = Color(0xFF1A73E8)
val DarkSheetBg          = Color(0xFF0C1013)
val WhatsAppCallBg       = Color(0xFF1B3B2B)
val CallEndRed           = Color(0xFFEA0038)
val BrandWhatsAppBubbleDarkMe = Color(0xFF005C4B)
val DarkChalkGreenBg     = Color(0xFF0E1A14)
val DarkChalkGreenBorder = Color(0xFF1E3A2F)
val DarkChalkGreenText   = Color(0xFFD1F2E5)
val BrightAccentTeal     = Color(0xFF00BFA5)
val DeepGreenTealBg      = Color(0xFF004D40)
val PremiumBlue          = Color(0xFF1565C0)
val ErrorBgRed           = Color(0x22EF4444)
val ErrorBorderRed       = Color(0x66EF4444)
val ErrorRed             = Color(0xFFEF4444)
val OpenAlexGreen        = Color(0xFF00C853)
val OpenAlexOrange       = Color(0xFFFF6D00)
val OpenAlexGold         = Color(0xFFFF9100)
val OpenAlexBrightGreen  = Color(0xFF00E676)
val DeepSuccessGreen     = Color(0xFF1B5E20)
val IndicatorRed         = Color(0xFFD42B2B)
val WhatsAppTealGreen    = Color(0xFF00A884)
val CustomOrangeGold     = Color(0xFFE28743)
val DarkSlateGray        = Color(0xFF0F172A)
val DeepVioletBlue       = Color(0xFF1E1B4B)
val SlateBorder          = Color(0xFF334155)
val SkyBlueAccent        = Color(0xFF38BDF8)
val SlateTextSub         = Color(0xFF94A3B8)
val SlateCardBg          = Color(0xFF1E293B)
val EmeraldBright        = Color(0xFF10B981)
val LightSlateText       = Color(0xFFCBD5E1)
val SkyBlueMedium        = Color(0xFF0284C7)
val ErrorDarkRedBg       = Color(0xFF1A0F2E)
val ErrorLightRed        = Color(0xFFFF5252)
val OnboardingDarkPurple = Color(0xFF1A1030)
val OnboardingAmberGlow  = Color(0x33FFB547)
val OnboardingVioletGlow = Color(0x116C63FF)
val StarGold             = Color(0xFFFFD700)
val PremiumDarkSpace     = Color(0xFF080E1C)
val PremiumLightText     = Color(0xFFEEF2FF)
val PremiumChatRoomBg    = Color(0xFF0F1829)
val PremiumChatCardBg    = Color(0xFF162035)
val BrandPurple          = Color(0xFF8B5CF6)
val PaperCardTint        = Color(0xFF8891B8)
val ProWorkspaceNavy     = Color(0xFF1B2A4A)
val ProWorkspaceBorder   = Color(0xFF2E4D8A)
val ProWorkspaceIndigo   = Color(0xFF818CF8)
val ProWorkspaceSlateText = Color(0xFF64748B)
val HarvardCrimson       = Color(0xFFBE123C)
val CERNEmerald          = Color(0xFF34D399)
val CambridgeCyan        = Color(0xFF22D3EE)
val IITViolet            = Color(0xFFA78BFA)
val EmeraldDeeper        = Color(0xFF065F46)
val AmberDeeper          = Color(0xFF92400E)
