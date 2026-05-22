package com.open.entropy.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// ResQit — Ultra-Modern Light Design System
// Inspired by Linear.app, Apple Health, Notion
// ═══════════════════════════════════════════════════════════════

// ── Backgrounds ──────────────────────────────────────────────────
val BgPrimary         = Color(0xFFF5F7FA)  // Near-white app background
val BgCard            = Color(0xFFFFFFFF)  // Pure white cards
val BgElevated        = Color(0xFFEEF2F7)  // Slightly tinted for contrast
val BgSubtle          = Color(0xFFF8FAFC)  // Very subtle off-white sections

// ── Accent Colors — Vibrant, Scientific ───────────────────────────
val AccentTeal        = Color(0xFF0EA5E9)  // Sky blue — primary brand
val AccentTealDark    = Color(0xFF0284C7)  // Pressed / deep state
val AccentTealLight   = Color(0xFFE0F2FE)  // Tint background for teal items
val AccentIndigo      = Color(0xFF6366F1)  // Indigo — AI / predictions
val AccentIndigoLight = Color(0xFFEEF2FF)  // Indigo tint
val AccentEmerald     = Color(0xFF10B981)  // Success / open science
val AccentEmeraldLight= Color(0xFFD1FAE5)  // Emerald tint
val AccentAmber       = Color(0xFFF59E0B)  // Gold / velocity / impact
val AccentAmberLight  = Color(0xFFFEF3C7)  // Amber tint
val AccentRose        = Color(0xFFF43F5E)  // Warning / disruption
val AccentRoseLight   = Color(0xFFFFE4E6)  // Rose tint
val AccentViolet      = Color(0xFF8B5CF6)  // Purple / novelty
val AccentVioletLight = Color(0xFFF5F3FF)  // Violet tint
val AccentOrange      = Color(0xFFEA580C)  // Orange / acceleration
val AccentOrangeLight = Color(0xFFFFF7ED)  // Orange tint
val AccentCyan        = Color(0xFF06B6D4)  // Cyan / collaboration
val AccentCyanLight   = Color(0xFFCFFAFE)  // Cyan tint
val AccentSlate       = Color(0xFF64748B)  // Neutral metric

// ── Text ─────────────────────────────────────────────────────────
val TextPrimary       = Color(0xFF0F172A)  // Almost-black headlines
val TextSecondary     = Color(0xFF475569)  // Slate medium — secondary text
val TextMuted         = Color(0xFF94A3B8)  // Muted slate — captions, labels
val TextOnAccent      = Color(0xFFFFFFFF)  // White on colored backgrounds

// ── Borders & Dividers ────────────────────────────────────────────
val BorderLight       = Color(0xFFE2E8F0)  // Hairline borders
val BorderMedium      = Color(0xFFCBD5E1)  // Slightly stronger dividers

// ── Shadows (used as overlay tints) ───────────────────────────────
val ShadowColor       = Color(0xFF0F172A)  // Dark for elevation shadows

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
val MetricPolicyColor        = Color(0xFF7C3AED)// Policy/patent score

// ── Gradient lists ────────────────────────────────────────────────
val HeroGradient      = listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))
val TealGradient      = listOf(AccentTeal, AccentCyan)
val IndigoGradient    = listOf(AccentIndigo, AccentViolet)
val WarmGradient      = listOf(AccentAmber, AccentOrange)

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
