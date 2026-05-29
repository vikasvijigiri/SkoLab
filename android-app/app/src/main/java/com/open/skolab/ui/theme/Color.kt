package com.open.skolab.ui.theme

import androidx.compose.ui.graphics.Color

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Warm Sand Color Palette — Parchment & natural tones for long reading ──────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val PAGE_BACKGROUND     = Color(0xFFF4F0E8) // warm parchment — every screen's root background
val SURFACE             = Color(0xFFFEFCF7) // warm cream — cards, bottom sheets, dialogs (not stark white)
val SURFACE_SUBTLE      = Color(0xFFEAE2D0) // warm tan — input fields, chips, inactive nav, skeleton loaders
val PRIMARY             = Color(0xFF2D6BE4) // ink blue — primary buttons, active nav, links (cool accent on warm bg)
val PRIMARY_DARK        = Color(0xFF1A4FA8) // deep ink — pressed states, progress tracks
val PRIMARY_DEEPER      = Color(0xFF0D2E6B) // darkest ink — app logo, major headings

val TEXT_PRIMARY        = Color(0xFF1C1208) // deep warm brown-black — headings, names, key values
val TEXT_SECONDARY      = Color(0xFF6B5440) // warm mid-brown — body copy, descriptions, card subtitles
val TEXT_MUTED          = Color(0xFFA08870) // warm tan — placeholders, timestamps, meta labels, helper text
val TEXT_ON_PRIMARY     = Color(0xFFFFFFFF) // white — text on ink blue surfaces
val TEXT_ON_PRIMARY_SUB = Color(0xFFBDD4F8) // light blue — subtitles on ink blue surfaces

val BORDER              = Color(0xFFD6C9B0) // warm tan border — card edges, dividers, input strokes

val NOTIFICATION_DOT    = Color(0xFFCC3333) // terracotta red — unread badge dots only
val STREAK_BAR          = Color(0xFFB8A832) // warm gold — progress bars and streak indicators
val MATCH_SCORE_BG      = Color(0xFFFFF0CC) // warm amber chip background
val MATCH_SCORE_TEXT    = Color(0xFF8A6400) // deep amber — text inside match % chips

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ── Semantic Variable Mappings (Ensures screen compatibility) ────────────────
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary         = PAGE_BACKGROUND
val BgCard            = SURFACE
val BgElevated        = SURFACE
val BgSubtle          = SURFACE_SUBTLE

// ── Accent Colors ────────────────────────────────────────────────
val AccentTeal        = PRIMARY
val AccentTealDark    = PRIMARY_DARK
val AccentTealLight   = PRIMARY.copy(alpha = 0.15f)
val AccentIndigo      = SURFACE_SUBTLE
val AccentIndigoLight = SURFACE_SUBTLE.copy(alpha = 0.15f)
val AccentEmerald     = PRIMARY
val AccentEmeraldLight= PRIMARY.copy(alpha = 0.15f)
val AccentAmber       = PRIMARY
val AccentAmberLight  = PRIMARY.copy(alpha = 0.15f)
val AccentRose        = NOTIFICATION_DOT
val AccentRoseLight   = NOTIFICATION_DOT.copy(alpha = 0.15f)
val AccentViolet      = PRIMARY
val AccentVioletLight = PRIMARY.copy(alpha = 0.15f)
val AccentOrange      = PRIMARY
val AccentOrangeLight = PRIMARY.copy(alpha = 0.15f)
val AccentCyan        = PRIMARY
val AccentCyanLight   = PRIMARY.copy(alpha = 0.15f)
val AccentSlate       = SURFACE_SUBTLE

// ── Text ─────────────────────────────────────────────────────────
val TextPrimary       = TEXT_PRIMARY
val TextSecondary     = TEXT_SECONDARY
val TextMuted         = TEXT_MUTED
val TextOnAccent      = TEXT_ON_PRIMARY

// ── Borders & Dividers ────────────────────────────────────────────
val BorderLight       = BORDER
val BorderMedium      = BORDER

// ── Shadows (No drop shadows - using border strokes) ──────────────
val ShadowColor       = Color.Transparent

// ── Metric-specific palette (10 research metrics) ────────────────
val MetricDisruptionColor    = PRIMARY
val MetricNoveltyColor       = PRIMARY
val MetricFutureImpactColor  = PRIMARY
val MetricInfluenceColor     = PRIMARY
val MetricCreativityColor    = PRIMARY
val MetricComplexityColor    = PRIMARY
val MetricOpenScienceColor   = PRIMARY
val MetricCollabColor        = PRIMARY
val MetricConsistencyColor   = PRIMARY
val MetricPolicyColor        = PRIMARY

// ── Gradient lists (Adapted to modern Blue system) ━━━━━━━━━━━━━━━━━━━
val HeroGradient      = listOf(PRIMARY, PRIMARY_DARK)
val TealGradient      = listOf(PRIMARY, PRIMARY_DARK)
val IndigoGradient    = listOf(PRIMARY, PRIMARY_DEEPER)
val WarmGradient      = listOf(PRIMARY, PRIMARY_DARK)

// ── Legacy Aliases ────────────────────────────────────────────────
val ObsidianBlack         = BgPrimary
val Graphite              = BgCard
val DeepNavy              = BgElevated
val MidnightBlue          = BgSubtle
val SkoLabBg              = BgPrimary
val SkoLabSurface         = BgCard
val SkoLabSurfaceElevated = BgElevated
val SkoLabDivider         = BorderLight
val SkoLabDisruption      = PRIMARY
val SkoLabNovelty         = PRIMARY
val SkoLabVelocity        = PRIMARY
val SkoLabAiInsight       = PRIMARY
val SkoLabCitations       = PRIMARY
val SkoLabWarning         = NOTIFICATION_DOT
val SkoLabPrimary         = PRIMARY
val SkoLabSecondary       = PRIMARY
val SkoLabGold            = PRIMARY
val SkoLabTextPrimary     = TextPrimary
val SkoLabTextSecondary   = TextSecondary
val SkoLabTextMuted       = TextMuted
val NeuralGradient        = listOf(BgPrimary, BgSubtle)
val DisruptionGlow        = listOf(PRIMARY.copy(alpha = 0.08f), Color.Transparent)
val NoveltyGlow           = listOf(PRIMARY.copy(alpha = 0.08f), Color.Transparent)
val VelocityGlow          = listOf(PRIMARY.copy(alpha = 0.08f), Color.Transparent)
val GlassBorder           = BorderLight
val GlassSurface          = BgCard
val SurfaceGlass          = BgCard
val SurfaceGlassElevated  = BgElevated
val MetricDisruption      = PRIMARY
val MetricNovelty         = PRIMARY
val MetricVelocity        = PRIMARY
val DeepSpace             = BgPrimary
val SurfaceDark           = BgCard
val BorderColor           = BorderLight
val ElectricCyan          = PRIMARY
val MutedGray             = TextMuted
val HighContrastWhite     = TextPrimary
val LogicBlue             = PRIMARY
val PlasmaPink            = PRIMARY
val InsightGold           = PRIMARY
val NovaOrange            = PRIMARY
val StellarTeal           = PRIMARY
val DiscoveryEmerald      = PRIMARY
val QuantumPurple         = PRIMARY
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
    val Gold1 = PRIMARY
    val Gold2 = PRIMARY_DARK
    val Blue1 = PRIMARY
    val Blue2 = PRIMARY_DARK
    val Purple1 = PRIMARY
    val Purple2 = PRIMARY_DEEPER
    val Cyan = PRIMARY
    val Green = PRIMARY
    val Red = NOTIFICATION_DOT
}
