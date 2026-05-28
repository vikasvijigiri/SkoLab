package com.open.skolab.model

data class SkoLabUser(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val researchFocus: String = "",
    val complexityScore: Float = 0f,
    val savedPapers: List<String> = emptyList(),
    val isOnline: Boolean = false,
    val emailVerified: Boolean = false
)