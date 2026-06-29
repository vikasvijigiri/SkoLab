package com.company.skolab.model

import kotlinx.serialization.Serializable

@Serializable
data class AssistantProfessorRoadmap(
    val userName: String = "",
    val researchFocus: String = "",
    val userMetrics: AcademicMetrics = AcademicMetrics(),
    val targetMetrics: AcademicMetrics = AcademicMetrics(),
    val milestones: List<RoadmapMilestone> = emptyList(),
    val checklist: List<RoadmapChecklistItem> = emptyList(),
    val peerCoauthors: List<PeerCoauthor> = emptyList(),
    val templates: List<AcademicTemplate> = emptyList()
)

@Serializable
data class AcademicMetrics(
    val hIndex: Int = 0,
    val worksCount: Int = 0,
    val citationCount: Int = 0,
    val disruptionScore: Float = 0f
)

@Serializable
data class RoadmapMilestone(
    val title: String = "",
    val status: String = "", // "Completed" / "Current" / "Upcoming" / "Target"
    val date: String = "",
    val description: String = ""
)

@Serializable
data class RoadmapChecklistItem(
    val task: String = "",
    val status: String = "", // "Completed" / "In Progress" / "Pending"
    val priority: String = "" // "High" / "Medium" / "Low"
)

@Serializable
data class PeerCoauthor(
    val name: String = "",
    val institution: String = "",
    val field: String = "",
    val match: String = ""
)

@Serializable
data class AcademicTemplate(
    val name: String = "",
    val description: String = "",
    val downloadUrl: String = ""
)
