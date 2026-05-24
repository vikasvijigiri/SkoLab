package com.open.entropy.model

import kotlinx.serialization.Serializable

@Serializable
data class UserConnection(
    val id: String,
    val name: String,
    val institution: String,
    val field: String = "",
    val connectedAt: Long = System.currentTimeMillis()
)
