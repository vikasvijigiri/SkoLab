package com.company.skolab.model

data class SparkSession(
    val id: String = "",
    val seekerId: String = "",
    val seekerName: String = "",
    val helperId: String? = null,
    val helperName: String? = null,
    val topic: String = "",
    val bounty: String = "", // e.g. "10 Tokens", "Co-Authorship"
    val tags: List<String> = emptyList(),
    val status: String = "PENDING", // PENDING, ACTIVE, RESOLVED, CANCELLED
    val createdAt: Long = 0L,
    val acceptedAt: Long? = null,
    val scratchpadContent: String = "",
    val timerRemainingSeconds: Int = 600
)

data class SparkMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val messageText: String = "",
    val timestamp: Long = 0L
)
