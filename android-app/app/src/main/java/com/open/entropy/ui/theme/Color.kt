package com.open.entropy.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// ReQit — Professional Corporate Grade Design System
// Inspired by Flipkart & Professional Corporate Dashboards
// ═══════════════════════════════════════════════════════════════

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary         = Color(0xFF131F24)  // Duolingo Dark Slate background
val BgCard            = Color(0xFF202F36)  // Duolingo Dark Slate card
val BgElevated        = Color(0xFF202F36)  // Duolingo Dark Slate elevated
val BgSubtle          = Color(0xFF1B282F)  // Subtle section

// ── Accent Colors — Professional Flipkart Blue & Gold ─────────────────────
val AccentTeal        = Color(0xFF2874F0)  // Peaceful corporate Flipkart Blue
val AccentTealDark    = Color(0xFF1B59C4)  // Pressed state Flipkart Blue
val AccentTealLight   = Color(0x262874F0)  // 15% opacity Blue tint
val AccentIndigo      = Color(0xFF5B78C2)  // Peaceful soft blue-slate — AI
val AccentIndigoLight = Color(0x1A5B78C2)  // Soft Indigo tint
val AccentEmerald     = Color(0xFF4CAF50)  // Peaceful soft success green
val AccentEmeraldLight= Color(0x1A4CAF50)  // Emerald tint
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
val TextPrimary       = Color(0xFFF1F5F9)  // Crisp white/slate headlines
val TextSecondary     = Color(0xFF94A3B8)  // Peaceful slate secondary text
val TextMuted         = Color(0xFF475569)  // Muted slate captions
val TextOnAccent      = Color(0xFFFFFFFF)  // High contrast white text on accent badges

// ── Borders & Dividers ────────────────────────────────────────────
val BorderLight       = Color(0xFF1E293B)  // Slate hairline borders
val BorderMedium      = Color(0xFF334155)  // Slate dividers

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
val ResQitBg              = BgPrimary
val ResQitSurface         = BgCard
val ResQitSurfaceElevated = BgElevated
val ResQitDivider         = BorderLight
val ResQitDisruption      = AccentTeal
val ResQitNovelty         = AccentViolet
val ResQitVelocity        = AccentAmber
val ResQitAiInsight       = AccentEmerald
val ResQitCitations       = AccentIndigo
val ResQitWarning         = AccentRose
val ResQitPrimary         = AccentTeal
val ResQitSecondary       = AccentViolet
val ResQitGold            = AccentAmber
val ResQitTextPrimary     = TextPrimary
val ResQitTextSecondary   = TextSecondary
val ResQitTextMuted       = TextMuted
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
