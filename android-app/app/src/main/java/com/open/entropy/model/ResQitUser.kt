package com.open.entropy.model

data class ResQitUser(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val researchFocus: String = "",
    val complexityScore: Float = 0f,
    val savedPapers: List<String> = emptyList()
)