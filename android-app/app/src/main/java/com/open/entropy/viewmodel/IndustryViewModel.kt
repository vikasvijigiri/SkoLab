package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IndustryOpportunity(
    val id: String,
    val type: OpportunityType,
    val title: String,
    val companyOrFunder: String,
    val tags: List<String>,
    val description: String,
    val postedAgo: String
)

enum class OpportunityType {
    JOB, FUNDING, REQUIREMENT
}

class IndustryViewModel : ViewModel() {

    private val _opportunities = MutableStateFlow<List<IndustryOpportunity>>(emptyList())
    val opportunities: StateFlow<List<IndustryOpportunity>> = _opportunities.asStateFlow()

    init {
        loadMockData()
    }

    private fun loadMockData() {
        _opportunities.value = listOf(
            IndustryOpportunity(
                id = "1",
                type = OpportunityType.JOB,
                title = "Quantum Algorithm Engineer",
                companyOrFunder = "IBM Quantum",
                tags = listOf("Qiskit", "Superconducting Qubits", "Error Correction"),
                description = "We are looking for a researcher to transition from academia to industry and build next-gen quantum circuits.",
                postedAgo = "2h ago"
            ),
            IndustryOpportunity(
                id = "2",
                type = OpportunityType.FUNDING,
                title = "Seed Grant: Neuromorphic Chips",
                companyOrFunder = "Intel Labs & NSF",
                tags = listOf("Hardware", "AI", "Grant: $500k"),
                description = "Joint funding opportunity seeking academic labs focused on memristor-based neuromorphic architectures.",
                postedAgo = "1d ago"
            ),
            IndustryOpportunity(
                id = "3",
                type = OpportunityType.REQUIREMENT,
                title = "Solving Battery Degradation",
                companyOrFunder = "Tesla Energy",
                tags = listOf("Materials Science", "Solid State", "Bounty: \$50k"),
                description = "Industry requirement: We need a scalable synthesis method for sulfide-based solid electrolytes to prevent dendrite formation.",
                postedAgo = "3d ago"
            ),
            IndustryOpportunity(
                id = "4",
                type = OpportunityType.JOB,
                title = "Senior AI Researcher",
                companyOrFunder = "DeepMind",
                tags = listOf("RL", "Agentic Systems", "Transformers"),
                description = "Join our Advanced Agentic Coding team to build the next generation of autonomous reasoning systems.",
                postedAgo = "5h ago"
            ),
            IndustryOpportunity(
                id = "5",
                type = OpportunityType.FUNDING,
                title = "Climate Tech Accelerator",
                companyOrFunder = "Y Combinator",
                tags = listOf("Carbon Capture", "Startup", "Investment: \$500k"),
                description = "Looking for academic spin-offs focusing on scalable direct air capture technologies.",
                postedAgo = "2d ago"
            )
        )
    }
}
