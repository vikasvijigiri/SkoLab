package com.open.skolab.ui.theme

import androidx.compose.ui.graphics.Color

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary         = Color(0xFF111B21)  // WhatsApp dark background
val BgCard            = Color(0xFF202C33)  // WhatsApp card/bubble background
val BgElevated        = Color(0xFF222E35)  // WhatsApp elevated background
val BgSubtle          = Color(0xFF182229)  // WhatsApp subtle background

// ── Accent Colors ────────────────────────────────────────────────
val AccentTeal        = Color(0xFF00A884)  // WhatsApp Teal/Green accent
val AccentTealDark    = Color(0xFF005C4B)  // WhatsApp Dark Green bubble/accent
val AccentTealLight   = Color(0x2600A884)  // 15% opacity Teal tint
val AccentIndigo      = Color(0xFF5B78C2)  // Peaceful soft blue-slate — AI
val AccentIndigoLight = Color(0x1A5B78C2)  // Soft Indigo tint
val AccentEmerald     = Color(0xFF25D366)  // WhatsApp Bright Green
val AccentEmeraldLight= Color(0x1A25D366)  // Emerald tint
val AccentAmber       = Color(0xFFF4B400)  // Flipkart Gold/Yellow highlight
val AccentAmberLight  = Color(0x26F4B400)  // Amber/Gold tint
val AccentRose        = Color(0xFFE53935)  // Peaceful soft red
val AccentRoseLight   = Color(0x1AE53935)  // Rose tint
val AccentViolet      = Color(0xFF8E24AA)  // Peaceful soft purple
val AccentVioletLight = Color(0x1A8E24AA)  // Violet tint
val AccentOrange      = Color(0xFF1E88E5)  // Changed to Blue as requested
val AccentOrangeLight = Color(0x1A1E88E5)  // Blue tint
val AccentCyan        = Color(0xFF00ACC1)  // Peaceful soft cyan
val AccentCyanLight   = Color(0x1A00ACC1)  // Cyan tint
val AccentSlate       = Color(0xFF607D8B)  // Neutral slate

// ── Text ─────────────────────────────────────────────────────────
val TextPrimary       = Color(0xFFE9EDEF)  // WhatsApp Primary text
val TextSecondary     = Color(0xFF8696A0)  // WhatsApp Secondary text
val TextMuted         = Color(0xFF667781)  // WhatsApp Muted text
val TextOnAccent      = Color(0xFFFFFFFF)  // High contrast white text on accent badges

// ── Borders & Dividers ────────────────────────────────────────────
val BorderLight       = Color(0xFF222E35)  // WhatsApp Dark border
val BorderMedium      = Color(0xFF2F3B43)  // Dark divider

// ── Shadows (used as overlay tints) ───────────────────────────────
val ShadowColor       = Color(0xFF020617)  // Deep dark for elevation shadows

// ── Metric-specific palette (10 research metrics) ────────────────
val MetricDisruptionColor    = AccentRose       // Disruption score
val MetricNoveltyColor       = AccentViolet     // Semantic novelty
val MetricFutureImpactColor  = AccentIndigo     // Future impact
val MetricInfluenceColor     = AccentTeal       // Network centrality
val MetricCreativityColor    = AccentAmber      // Creativity
val MetricComplexityColor    = AccentCyan       // Complexity
val MetricOpenScienceColor   = AccentEmerald    // Open science
val MetricCollabColor        = AccentOrange     // Collaboration diversity
val MetricConsistencyColor   = AccentSlate      // Research consistency
val MetricPolicyColor        = Color(0xFFA78BFA)// Policy/patent score

// ── Gradient lists ────────────────────────────────────────────────
val HeroGradient      = listOf(Color(0xFF00E5FF), Color(0xFF818CF8))
val TealGradient      = listOf(AccentTeal, AccentCyan)
val IndigoGradient    = listOf(AccentIndigo, AccentViolet)
val WarmGradient      = listOf(AccentAmber, AccentTeal)

// ── Legacy Aliases (keep compilation of other screens) ────────────
// Map old dark-theme names → new light equivalents
val ObsidianBlack         = BgPrimary
val Graphite              = BgCard
val DeepNavy              = BgElevated
val MidnightBlue          = BgSubtle
val SkoLabBg              = BgPrimary
val SkoLabSurface         = BgCard
val SkoLabSurfaceElevated = BgElevated
val SkoLabDivider         = BorderLight
val SkoLabDisruption      = AccentCyan
val SkoLabNovelty         = AccentViolet
val SkoLabVelocity        = AccentAmber
val SkoLabAiInsight       = AccentEmerald
val SkoLabCitations       = AccentIndigo
val SkoLabWarning         = AccentRose
val SkoLabPrimary         = AccentViolet
val SkoLabSecondary       = AccentCyan
val SkoLabGold            = AccentAmber
val SkoLabTextPrimary     = TextPrimary
val SkoLabTextSecondary   = TextSecondary
val SkoLabTextMuted       = TextMuted
val NeuralGradient        = listOf(BgPrimary, BgElevated)
val DisruptionGlow        = listOf(AccentRose.copy(alpha = 0.08f), Color.Transparent)
val NoveltyGlow           = listOf(AccentViolet.copy(alpha = 0.08f), Color.Transparent)
val VelocityGlow          = listOf(AccentAmber.copy(alpha = 0.08f), Color.Transparent)
val GlassBorder           = BorderLight
val GlassSurface          = BgCard
val SurfaceGlass          = BgCard
val SurfaceGlassElevated  = BgElevated
val MetricDisruption      = MetricDisruptionColor
val MetricNovelty         = MetricNoveltyColor
val MetricVelocity        = AccentAmber
val DeepSpace             = BgPrimary
val SurfaceDark           = BgCard
val BorderColor           = BorderLight
val ElectricCyan          = AccentTeal
val MutedGray             = TextMuted
val HighContrastWhite     = TextPrimary
val LogicBlue             = AccentIndigo
val PlasmaPink            = AccentViolet
val InsightGold           = AccentAmber
val NovaOrange            = AccentOrange
val StellarTeal           = AccentCyan
val DiscoveryEmerald      = AccentEmerald
val QuantumPurple         = AccentViolet
val ProGradientSurface    = listOf(BgCard, BgElevated)
val ProGradientPrimary    = TealGradient

object EntropiColors {
    val Background = BgPrimary
    val Card = BgPrimary
    val Card2 = BgPrimary
    val Border = Color(0xFF222E35)
    val Text1 = Color(0xFFE8ECFF)
    val Text = Color(0xFFE8ECFF)
    val Text2 = Color(0xFF8696A0)
    val Text3 = Color(0xFF667781)
    val Gold1 = Color(0xFFC9A84C)
    val Gold2 = Color(0xFFE8C76A)
    val Blue1 = Color(0xFF00A884)
    val Blue2 = Color(0xFF25D366)
    val Purple1 = Color(0xFF7C3AED)
    val Purple2 = Color(0xFF9D5FFF)
    val Cyan = Color(0xFF00D4FF)
    val Green = Color(0xFF25D366)
    val Red = Color(0xFFFF4757)
}
