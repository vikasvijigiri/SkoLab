package com.open.skolab.model

import kotlinx.serialization.Serializable

@Serializable
data class Conjecture(
    val id: String,
    val category: String,
    val title: String,
    val hypothesis: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String
)
