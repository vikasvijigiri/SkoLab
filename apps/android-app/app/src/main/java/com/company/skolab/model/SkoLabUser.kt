package com.company.skolab.model

data class SkoLabUser(
    val uid: String = "",
    val name: String = "",
    val username: String = "",
    val authorName: String = "",
    val email: String = "",
    val phone: String = "",
    val researchFocus: String = "",
    val complexityScore: Float = 0f,
    val savedPapers: List<String> = emptyList(),
    val isOnline: Boolean = false,
    val emailVerified: Boolean = false,
    val academicStatus: String = "Researcher",
    val cvUri: String = "",
    val cvFileName: String = "",
    val about: String = "",
    val openAlexId: String = "",
    val lastActive: Long = 0L
) {
    val firstName: String
        get() = name.trim().split("\\s+".toRegex()).firstOrNull()?.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() 
        }?.ifBlank { "User" } ?: "User"
}