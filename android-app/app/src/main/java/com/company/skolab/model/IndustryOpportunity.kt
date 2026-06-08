package com.company.skolab.model

import kotlinx.serialization.Serializable

@Serializable
enum class OpportunityType {
    JOB, FUNDING, REQUIREMENT
}

@Serializable
enum class PositionLevel {
    PHD_STUDENT, POSTDOC, FACULTY, INDUSTRY_RESEARCHER, RESEARCH_ENGINEER, UNSPECIFIED;

    fun displayLabel(): String = when (this) {
        PHD_STUDENT        -> "PhD Student"
        POSTDOC            -> "Postdoc"
        FACULTY            -> "Faculty"
        INDUSTRY_RESEARCHER -> "Industry Researcher"
        RESEARCH_ENGINEER  -> "Research Engineer"
        UNSPECIFIED        -> ""
    }
}

@Serializable
enum class RemoteType {
    ON_SITE, REMOTE, HYBRID, UNSPECIFIED;

    fun displayLabel(): String = when (this) {
        ON_SITE    -> "On-site"
        REMOTE     -> "Remote"
        HYBRID     -> "Hybrid"
        UNSPECIFIED -> ""
    }
}

@Serializable
data class IndustryOpportunity(
    val id: String = "",
    val type: OpportunityType = OpportunityType.JOB,
    val title: String = "",
    val companyOrFunder: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
    val postedAgo: String = "",
    val url: String = "",
    val eligibility: String = "",
    val amount: String = "",
    val procedureSteps: List<String> = emptyList(),
    val deadline: String = "",
    val status: String = "Active",
    val requiredSkills: List<String> = emptyList(),
    val matchScore: Int? = null,
    val relevanceExplanation: String? = null,
    // Location enrichment — backend may omit these; defaults keep old API responses valid
    val location: String = "",
    val positionLevel: PositionLevel = PositionLevel.UNSPECIFIED,
    val remoteType: RemoteType = RemoteType.UNSPECIFIED,
)
