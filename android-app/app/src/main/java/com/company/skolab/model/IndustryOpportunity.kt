package com.company.skolab.model

import kotlinx.serialization.Serializable

/**
 * IndustryOpportunity — represents a job, funding, or requirement posting.
 *
 * Moved here from [com.company.skolab.viewmodel.IndustryViewModel] to keep
 * model classes in the model layer, not in the ViewModel layer.
 */
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
    val relevanceExplanation: String? = null
)

@Serializable
enum class OpportunityType {
    JOB, FUNDING, REQUIREMENT
}
