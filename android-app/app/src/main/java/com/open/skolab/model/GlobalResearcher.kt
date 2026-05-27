package com.open.skolab.model

import com.google.firebase.Timestamp

data class Work(
    val title: String = "",
    val year: Int = 0,
    val doi: String? = null,
    val citations: Int = 0,
    val creativity_score: Double = 0.0,
    val complexity_score: Double = 0.0,
    val impact_factor: Double = 0.0
)

data class GlobalResearcher(
    val openalex_id: String = "",
    val orcid: String? = null,
    val display_name: String = "",
    val current_institution: String = "",
    val field_of_study: String = "",
    val h_index: Int = 0,
    val i10_index: Int = 0,
    val works_count: Int = 0,
    val cited_by_count: Int = 0,
    val innovation_score: Double = 0.0,
    val average_creativity: Double = 0.0,
    val average_complexity: Double = 0.0,
    val average_skill_score: Double = 0.0,
    val average_impact: Double = 0.0,
    val average_activity: Double = 0.0,
    val academic_history: List<String> = emptyList(),
    val works: List<Work> = emptyList(),
    val is_verified: Boolean = false,
    val last_synced: Timestamp? = null
)
