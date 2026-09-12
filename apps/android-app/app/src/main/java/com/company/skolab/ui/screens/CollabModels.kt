package com.company.skolab.ui.screens

/**
 * Shared CoLab/workspace data models. Split out of PaperCollabsScreen.kt
 * when that file's dead composable was removed (2026-09-12 no-slop audit)
 * -- these classes are genuinely used by ChatListScreen, FeedScreen,
 * HomeScreen, and WorkspacesTab, unlike the composable they used to live
 * alongside. Same package as those callers (no import changes needed).
 */

data class CollabMember(
    val uid: String = "",
    val name: String = "",
    val email: String = ""
)

data class ProjectCollab(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val members: List<CollabMember> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val recentEquations: String = "",
    val manuscriptProgress: Float = 0f,
    val createdAt: Long = 0L
) {
    val activeCoAuthors: List<String>
        @com.google.firebase.firestore.Exclude
        get() = members.map { it.name }.filter { name ->
            name.lowercase() != "you"
        }
}

data class CollabTask(
    val id: String = "",
    val title: String = "",
    val assignee: String = "",
    @field:JvmField val isCompleted: Boolean = false
)

data class CollabEvent(
    val author: String,
    val action: String,
    val time: String
)
