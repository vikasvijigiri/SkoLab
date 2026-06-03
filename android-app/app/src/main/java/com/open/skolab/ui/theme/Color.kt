package com.open.skolab.ui.theme

import androidx.compose.ui.graphics.Color

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Warm Sand Color Palette — Parchment & natural tones for long reading ──────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val PAGE_BACKGROUND     = Color(0xFFF4F0E8) // warm parchment — every screen's root background
val SURFACE             = Color(0xFFFEFCF7) // warm cream — cards, bottom sheets, dialogs
val SURFACE_SUBTLE      = Color(0xFFEAE2D0) // warm tan — input fields, chips, inactive nav
val PRIMARY             = Color(0xFF2D6BE4) // ink blue — primary buttons, active nav, links
val PRIMARY_DARK        = Color(0xFF1A4FA8) // deep ink — pressed states, progress tracks
val PRIMARY_DEEPER      = Color(0xFF0D2E6B) // darkest ink — app logo, major headings

val TEXT_PRIMARY        = Color(0xFF1C1208) // deep warm brown-black — headings, names, key values
val TEXT_SECONDARY      = Color(0xFF6B5440) // warm mid-brown — body copy, descriptions
val TEXT_MUTED          = Color(0xFF8A725C) // warm tan — placeholders, timestamps, meta labels (WCAG AA compliant contrast)
val TEXT_ON_PRIMARY     = Color(0xFFFFFFFF) // white — text on ink blue surfaces
val TEXT_ON_PRIMARY_SUB = Color(0xFFDCE7FC) // light blue — subtitles on ink blue surfaces (WCAG AA compliant contrast)

val BORDER              = Color(0xFFD6C9B0) // warm tan border — card edges, dividers, input strokes

val NOTIFICATION_DOT    = Color(0xFFCC3333) // terracotta red — unread badge dots, error states
val STREAK_BAR          = Color(0xFFB8A832) // warm gold — progress bars and streak indicators
val MATCH_SCORE_BG      = Color(0xFFFFF0CC) // warm amber chip background
val MATCH_SCORE_TEXT    = Color(0xFF8A6400) // deep amber — text inside match % chips

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Semantic Accent Palette — each accent has its own distinct hue ────────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// Cool teal — "connected", collaboration, chat, links
private val TEAL         = Color(0xFF0D9488)  // teal-600
private val TEAL_DARK    = Color(0xFF0F766E)  // teal-700

// Emerald green — "success", open science, growth
private val EMERALD      = Color(0xFF059669)  // emerald-600
private val EMERALD_DARK = Color(0xFF047857)  // emerald-700

// Amber gold — "value", citations, streak, rewards
private val AMBER        = Color(0xFFD97706)  // amber-600
private val AMBER_DARK   = Color(0xFFB45309)  // amber-700

// Violet purple — "insight", AI, analysis, future
private val VIOLET       = Color(0xFF7C3AED)  // violet-600
private val VIOLET_DARK  = Color(0xFF6D28D9)  // violet-700

// Orange — "disruption", innovation, impact
private val ORANGE       = Color(0xFFEA580C)  // orange-600
private val ORANGE_DARK  = Color(0xFFC2410C)  // orange-700

// Cyan — "novelty", discovery, search, fresh
private val CYAN         = Color(0xFF0891B2)  // cyan-600
private val CYAN_DARK    = Color(0xFF0E7490)  // cyan-700

// Rose/Red — "urgency", errors, unread
private val ROSE         = Color(0xFFCC3333)  // terracotta red
private val ROSE_DARK    = Color(0xFFAA2222)

// Pink — "creativity", collaboration spark
private val PINK         = Color(0xFFDB2777)  // pink-600

// Indigo — "complexity", depth, structured thinking
private val INDIGO       = Color(0xFF4F46E5)  // indigo-600

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Semantic Variable Mappings ────────────────────────────────────────────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary         = PAGE_BACKGROUND
val BgCard            = SURFACE
val BgElevated        = SURFACE
val BgSubtle          = SURFACE_SUBTLE

// ── Accent Colors — each with a distinct hue ─────────────────────
val AccentTeal        = TEAL
val AccentTealDark    = TEAL_DARK
val AccentTealLight   = TEAL.copy(alpha = 0.15f)
val AccentIndigo      = INDIGO
val AccentIndigoLight = INDIGO.copy(alpha = 0.15f)
val AccentEmerald     = EMERALD
val AccentEmeraldLight= EMERALD.copy(alpha = 0.15f)
val AccentAmber       = AMBER
val AccentAmberLight  = AMBER.copy(alpha = 0.15f)
val AccentRose        = ROSE
val AccentRoseLight   = ROSE.copy(alpha = 0.15f)
val AccentViolet      = VIOLET
val AccentVioletLight = VIOLET.copy(alpha = 0.15f)
val AccentOrange      = ORANGE
val AccentOrangeLight = ORANGE.copy(alpha = 0.15f)
val AccentCyan        = CYAN
val AccentCyanLight   = CYAN.copy(alpha = 0.15f)
val AccentSlate       = SURFACE_SUBTLE

// ── Text ─────────────────────────────────────────────────────────
val TextPrimary       = TEXT_PRIMARY
val TextSecondary     = TEXT_SECONDARY
val TextMuted         = TEXT_MUTED
val TextOnAccent      = TEXT_ON_PRIMARY

// ── Borders & Dividers ────────────────────────────────────────────
val BorderLight       = BORDER
val BorderMedium      = BORDER

// ── Shadows ───────────────────────────────────────────────────────
val ShadowColor       = Color.Transparent

// ── Research Metric Colors (10 distinct hues for MetricsScreen) ──
val MetricDisruptionColor    = ORANGE        // disruption = bold, breaking change
val MetricNoveltyColor       = CYAN          // novelty = fresh discovery
val MetricFutureImpactColor  = VIOLET        // future = visionary, deep purple
val MetricInfluenceColor     = PRIMARY       // influence = authority, ink blue
val MetricCreativityColor    = PINK          // creativity = expressive, vibrant
val MetricComplexityColor    = INDIGO        // complexity = dense, deep indigo
val MetricOpenScienceColor   = EMERALD       // open science = growth, access
val MetricCollabColor        = TEAL          // collaboration = connection
val MetricConsistencyColor   = AMBER         // consistency = steady, warm gold
val MetricPolicyColor        = Color(0xFF475569) // policy = slate/structured grey-blue

// ── Gradient lists ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
val HeroGradient      = listOf(PRIMARY, PRIMARY_DARK)
val TealGradient      = listOf(TEAL, TEAL_DARK)
val IndigoGradient    = listOf(INDIGO, VIOLET)
val WarmGradient      = listOf(AMBER, ORANGE)

// ── Legacy Aliases ────────────────────────────────────────────────
val ObsidianBlack         = BgPrimary
val Graphite              = BgCard
val DeepNavy              = BgElevated
val MidnightBlue          = BgSubtle
val SkoLabBg              = BgPrimary
val SkoLabSurface         = BgCard
val SkoLabSurfaceElevated = BgElevated
val SkoLabDivider         = BorderLight
val SkoLabDisruption      = ORANGE
val SkoLabNovelty         = CYAN
val SkoLabVelocity        = VIOLET
val SkoLabAiInsight       = VIOLET
val SkoLabCitations       = AMBER
val SkoLabWarning         = NOTIFICATION_DOT
val SkoLabPrimary         = PRIMARY
val SkoLabSecondary       = TEAL
val SkoLabGold            = AMBER
val SkoLabTextPrimary     = TextPrimary
val SkoLabTextSecondary   = TextSecondary
val SkoLabTextMuted       = TextMuted
val NeuralGradient        = listOf(BgPrimary, BgSubtle)
val DisruptionGlow        = listOf(ORANGE.copy(alpha = 0.08f), Color.Transparent)
val NoveltyGlow           = listOf(CYAN.copy(alpha = 0.08f), Color.Transparent)
val VelocityGlow          = listOf(VIOLET.copy(alpha = 0.08f), Color.Transparent)
val GlassBorder           = BorderLight
val GlassSurface          = BgCard
val SurfaceGlass          = BgCard
val SurfaceGlassElevated  = BgElevated
val MetricDisruption      = ORANGE
val MetricNovelty         = CYAN
val MetricVelocity        = VIOLET
val DeepSpace             = BgPrimary
val SurfaceDark           = BgCard
val BorderColor           = BorderLight
val ElectricCyan          = CYAN
val MutedGray             = TextMuted
val HighContrastWhite     = TextPrimary
val LogicBlue             = PRIMARY
val PlasmaPink            = PINK
val InsightGold           = AMBER
val NovaOrange            = ORANGE
val StellarTeal           = TEAL
val DiscoveryEmerald      = EMERALD
val QuantumPurple         = VIOLET
val ProGradientSurface    = listOf(BgCard, BgSubtle)
val ProGradientPrimary    = TealGradient

// ── EntropiColors (for PaperCollabs / Ask Skolar components) ───────────
object EntropiColors {
    val Background = BgPrimary
    val Card = BgCard
    val Card2 = BgSubtle
    val Border = BORDER
    val Text1 = TEXT_PRIMARY
    val Text = TEXT_PRIMARY
    val Text2 = TEXT_SECONDARY
    val Text3 = TEXT_MUTED
    val Gold1 = AMBER
    val Gold2 = AMBER_DARK
    val Blue1 = PRIMARY
    val Blue2 = PRIMARY_DARK
    val Purple1 = VIOLET
    val Purple2 = VIOLET_DARK
    val Cyan = CYAN
    val Green = EMERALD
    val Red = NOTIFICATION_DOT
}
