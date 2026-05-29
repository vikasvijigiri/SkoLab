package com.open.skolab.model

import kotlinx.serialization.Serializable

/**
 * IndustryOpportunity — represents a job, funding, or requirement posting.
 *
 * Moved here from [com.open.skolab.viewmodel.IndustryViewModel] to keep
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
    val url: String = ""
)

@Serializable
enum class OpportunityType {
    JOB, FUNDING, REQUIREMENT
}
