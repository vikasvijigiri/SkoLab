package com.open.skolab.model

import androidx.compose.ui.graphics.Color

data class Paper(
    val id: String,
    val title: String,
    val authors: List<String>,
    val journal: String,
    val year: Int,
    val domain: String,
    val subDomain: String,
    val abstractText: String,
    val disruptionScore: Float,
    val noveltyScore: Float,
    val citationVelocity: Float,
    val hIndex: Int,
    val citationCount: Int,
    val journalImpactFactor: Float,
    val aiSummary: String,
    val keyInsight: String,
    val bulletPoints: List<String>,
    val methodology: List<String>,
    val latexFormula: String?,
    val isRetracted: Boolean,
    val doi: String,
    val pdfUrl: String?
)

data class Author(
    val id: String,
    val name: String,
    val institution: String,
    val country: String,
    val orcidId: String?,
    val fingerprintType: String,
    val radarScores: Map<String, Float>,
    val careerArc: List<Pair<Int, Float>>,
    val topPapers: List<Paper>,
    val collaborators: List<String>,
    val totalPapers: Int,
    val avgDisruptionScore: Float
)

data class FieldEntropy(
    val field: String,
    val subFields: List<String>,
    val entropyScore: Float,
    val weeklyDelta: Float,
    val phaseStatus: String,
    val historicalEntropy: List<Pair<Int, Float>>,
    val topPapers: List<Paper>,
    val topAuthors: List<Author>
)

data class Collection(
    val id: String,
    val name: String,
    val emoji: String,
    val accentColor: Color,
    val papers: List<Paper>,
    val createdAt: Long,
    val updatedAt: Long
)

data class Alert(
    val id: String,
    val type: String,
    val target: String,
    val threshold: Float?,
    val frequency: String,
    val isActive: Boolean,
    val lastTriggered: Long?
)

data class Annotation(
    val id: String,
    val paperId: String,
    val startOffset: Int,
    val endOffset: Int,
    val highlightColor: Color,
    val noteText: String,
    val createdAt: Long
)
