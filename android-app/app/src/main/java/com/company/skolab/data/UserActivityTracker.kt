package com.company.skolab.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────────────────
// Activity Event Model
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ActivityEvent(
    val type: String,           // PAPER_OPENED, PAPER_SAVED, AUTHOR_VISITED, etc.
    val timestamp: Long = System.currentTimeMillis(),
    val hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    // Paper events
    val paperTitle: String? = null,
    val paperDomain: String? = null,
    val paperJournal: String? = null,
    val durationSeconds: Int? = null,
    // Author events
    val authorName: String? = null,
    val authorInstitution: String? = null,
    // Search/query events
    val query: String? = null,
    // Filter events
    val filterValue: String? = null,
    // Collaboration events
    val collaboratorName: String? = null,
    val collaboratorField: String? = null
) {
    companion object {
        fun paperOpened(title: String, domain: String, journal: String) = ActivityEvent(
            type = "PAPER_OPENED",
            paperTitle = title,
            paperDomain = domain,
            paperJournal = journal
        )

        fun paperClosed(title: String, domain: String, durationSeconds: Int) = ActivityEvent(
            type = "PAPER_CLOSED",
            paperTitle = title,
            paperDomain = domain,
            durationSeconds = durationSeconds
        )

        fun paperSaved(title: String, domain: String) = ActivityEvent(
            type = "PAPER_SAVED",
            paperTitle = title,
            paperDomain = domain
        )

        fun authorVisited(name: String, institution: String) = ActivityEvent(
            type = "AUTHOR_VISITED",
            authorName = name,
            authorInstitution = institution
        )

        fun searchQuery(query: String) = ActivityEvent(
            type = "SEARCH_QUERY",
            query = query
        )

        fun agentQuery(query: String) = ActivityEvent(
            type = "AGENT_QUERY",
            query = query
        )

        fun feedFilter(filter: String) = ActivityEvent(
            type = "FEED_FILTER",
            filterValue = filter
        )

        fun collabConnected(name: String, field: String) = ActivityEvent(
            type = "COLLAB_CONNECTED",
            collaboratorName = name,
            collaboratorField = field
        )

        fun sessionStart() = ActivityEvent(type = "SESSION_START")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Derived Memory Profile
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class UserMemoryProfile(
    val userId: String = "",
    val topTopics: List<String> = emptyList(),
    val activeHours: List<Int> = emptyList(),         // hours 0–23 most active in
    val readingPace: String = "unknown",              // "deep_reader", "quick_scanner", "moderate"
    val researchStyle: String = "unknown",            // "focused", "interdisciplinary", "exploratory"
    val avgReadMinutes: Float = 0f,
    val unfinishedPapers: List<String> = emptyList(), // titles read < 2 min
    val recentlyReadPapers: List<String> = emptyList(),
    val frequentCollaborators: List<String> = emptyList(),
    val frequentSearchTerms: List<String> = emptyList(),
    val lastActiveTopic: String = "",
    val streakDays: Int = 0,
    val totalPapersRead: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /** Formats memory as a compact block to inject into the agent system prompt. */
    fun toSystemPromptBlock(): String {
        if (topTopics.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[USER MEMORY CONTEXT — use this to personalize all responses]")
        if (topTopics.isNotEmpty()) sb.appendLine("Research focus: ${topTopics.take(4).joinToString(", ")}")
        if (lastActiveTopic.isNotEmpty()) sb.appendLine("Currently exploring: $lastActiveTopic")
        if (readingPace != "unknown") sb.appendLine("Reading style: $readingPace (avg ${avgReadMinutes.toInt()} min/paper)")
        if (frequentCollaborators.isNotEmpty()) sb.appendLine("Key collaborators: ${frequentCollaborators.take(3).joinToString(", ")}")
        if (unfinishedPapers.isNotEmpty()) sb.appendLine("Unfinished papers: ${unfinishedPapers.take(2).joinToString("; ")}")
        if (frequentSearchTerms.isNotEmpty()) sb.appendLine("Recent searches: ${frequentSearchTerms.take(3).joinToString(", ")}")
        if (streakDays > 0) sb.appendLine("Research streak: $streakDays days")
        sb.appendLine("Total papers read in session history: $totalPapersRead")
        sb.appendLine("[END CONTEXT — respond naturally, referencing context only when relevant]")
        return sb.toString()
    }

    fun proactiveReminders(): List<String> {
        val reminders = mutableListOf<String>()
        if (unfinishedPapers.isNotEmpty()) {
            reminders.add("📄 Continue: ${unfinishedPapers.first().take(40)}…")
        }
        if (streakDays >= 3) {
            reminders.add("🔥 ${streakDays}-day streak — keep it up!")
        }
        if (lastActiveTopic.isNotEmpty()) {
            reminders.add("🔬 Digest: $lastActiveTopic")
        }
        return reminders
    }

    fun personalizedGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreet = when {
            hour < 12 -> "Good morning ☀️"
            hour < 17 -> "Good afternoon 🌤"
            else -> "Good evening 🌙"
        }
        return if (topTopics.isNotEmpty()) {
            "$timeGreet — $topTopics results in ${topTopics.first()} since you were last here."
                .replace("results in", "new papers in")  // friendlier phrasing
        } else {
            "$timeGreet — What are we researching today?"
        }
    }

    fun personalizedQuickPrompts(): List<String> {
        val prompts = mutableListOf<String>()
        topTopics.take(2).forEach { topic ->
            prompts.add("Latest in $topic")
        }
        if (unfinishedPapers.isNotEmpty()) prompts.add("Summarize my unread papers")
        if (frequentCollaborators.isNotEmpty()) prompts.add("New work by ${frequentCollaborators.first().split(" ").last()}")
        prompts.add("Find grant opportunities")
        prompts.add("Write an abstract")
        return prompts.take(6)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tracker — call from any screen/viewmodel
// ─────────────────────────────────────────────────────────────────────────────

object UserActivityTracker {
    private const val TAG = "ActivityTracker"
    private const val BUFFER_FILE = "activity_buffer.json"
    private const val MEMORY_FILE = "user_memory.json"
    private const val MAX_BUFFER_EVENTS = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Track a single activity event. Thread-safe, non-blocking. */
    fun track(context: Context, event: ActivityEvent) {
        scope.launch {
            try {
                val file = File(context.filesDir, BUFFER_FILE)
                val current: MutableList<ActivityEvent> = if (file.exists()) {
                    try { json.decodeFromString(file.readText()) }
                    catch (_: Exception) { mutableListOf() }
                } else mutableListOf()

                current.add(event)
                // Keep buffer bounded
                val trimmed = if (current.size > MAX_BUFFER_EVENTS) current.takeLast(MAX_BUFFER_EVENTS) else current
                file.writeText(json.encodeToString(trimmed))
                Log.d(TAG, "Tracked: ${event.type}")

                // Re-derive memory profile locally after every event
                deriveAndSaveMemory(context, trimmed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to track event: ${e.message}")
            }
        }
    }

    /** Read buffered events to send to backend. */
    fun readBuffer(context: Context): List<ActivityEvent> {
        val file = File(context.filesDir, BUFFER_FILE)
        if (!file.exists()) return emptyList()
        return try { json.decodeFromString(file.readText()) }
        catch (_: Exception) { emptyList() }
    }

    /** Clear buffer after successful backend sync. */
    fun clearBuffer(context: Context) {
        File(context.filesDir, BUFFER_FILE).delete()
    }

    /** Clear all memory and activity buffers locally. */
    fun clearMemory(context: Context) {
        try {
            File(context.filesDir, BUFFER_FILE).delete()
            File(context.filesDir, MEMORY_FILE).delete()
            Log.d(TAG, "Memory and activity buffer cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear memory: ${e.message}")
        }
    }

    /** Read last derived memory profile (fast, no network). */
    fun readMemory(context: Context): UserMemoryProfile {
        val file = File(context.filesDir, MEMORY_FILE)
        if (!file.exists()) return UserMemoryProfile()
        return try { json.decodeFromString(file.readText()) }
        catch (_: Exception) { UserMemoryProfile() }
    }

    /** Save a memory profile received from the backend. */
    fun saveMemory(context: Context, profile: UserMemoryProfile) {
        try {
            File(context.filesDir, MEMORY_FILE).writeText(json.encodeToString(profile))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory: ${e.message}")
        }
    }

    // ── Local derivation (no network required) ────────────────────────────────
    private fun deriveAndSaveMemory(context: Context, events: List<ActivityEvent>) {
        try {
            val existing = readMemory(context)

            // Topic frequency from paper domains + agent queries + searches
            val topicFreq = mutableMapOf<String, Int>()
            events.forEach { e ->
                listOfNotNull(e.paperDomain, e.query, e.filterValue).forEach { raw ->
                    if (raw.isNotBlank() && raw.length > 3) {
                        val cleaned = raw.trim().lowercase().replaceFirstChar { it.uppercase() }
                        topicFreq[cleaned] = (topicFreq[cleaned] ?: 0) + 1
                    }
                }
            }
            val topTopics = topicFreq.entries.sortedByDescending { it.value }
                .map { it.key }.take(6)

            // Active hours
            val hourFreq = events.groupBy { it.hourOfDay }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
                .map { it.key }.take(4)

            // Paper read sessions: PAPER_OPENED followed by PAPER_CLOSED
            val paperSessions = mutableListOf<Pair<String, Int>>() // title to seconds
            events.filter { it.type == "PAPER_CLOSED" && it.paperTitle != null }.forEach { e ->
                paperSessions.add(Pair(e.paperTitle!!, e.durationSeconds ?: 0))
            }
            val avgReadSec = if (paperSessions.isEmpty()) 0f
            else paperSessions.map { it.second }.average().toFloat()
            val avgReadMin = avgReadSec / 60f

            val readingPace = when {
                avgReadMin >= 5f -> "deep_reader"
                avgReadMin >= 2f -> "moderate_reader"
                avgReadMin > 0f -> "quick_scanner"
                else -> "unknown"
            }

            // Unfinished: opened but session < 90 seconds
            val unfinished = paperSessions
                .filter { it.second in 1..89 }
                .map { it.first }
                .takeLast(3)

            val recentlyRead = paperSessions
                .filter { it.second >= 90 }
                .map { it.first }
                .takeLast(5)

            // Distinct domains: interdisciplinary if 3+
            val domainSet = events.mapNotNull { it.paperDomain }.toSet()
            val researchStyle = when {
                domainSet.size >= 4 -> "interdisciplinary"
                domainSet.size >= 2 -> "focused"
                else -> "exploratory"
            }

            // Collaborators
            val collaborators = events
                .filter { it.type == "COLLAB_CONNECTED" || it.type == "AUTHOR_VISITED" }
                .mapNotNull { it.collaboratorName ?: it.authorName }
                .groupBy { it }.entries.sortedByDescending { it.value.size }
                .map { it.key }.take(5)

            // Frequent searches
            val searches = events.filter { it.type == "SEARCH_QUERY" || it.type == "AGENT_QUERY" }
                .mapNotNull { it.query }
                .groupBy { it }.entries.sortedByDescending { it.value.size }
                .map { it.key }.take(5)

            val lastTopic = topTopics.firstOrNull() ?: existing.lastActiveTopic

            val profile = existing.copy(
                topTopics = topTopics.ifEmpty { existing.topTopics },
                activeHours = hourFreq.ifEmpty { existing.activeHours },
                readingPace = if (readingPace != "unknown") readingPace else existing.readingPace,
                researchStyle = researchStyle,
                avgReadMinutes = if (avgReadMin > 0f) avgReadMin else existing.avgReadMinutes,
                unfinishedPapers = unfinished.ifEmpty { existing.unfinishedPapers },
                recentlyReadPapers = recentlyRead.ifEmpty { existing.recentlyReadPapers },
                frequentCollaborators = collaborators.ifEmpty { existing.frequentCollaborators },
                frequentSearchTerms = searches.ifEmpty { existing.frequentSearchTerms },
                lastActiveTopic = lastTopic,
                totalPapersRead = events.count { it.type == "PAPER_CLOSED" && (it.durationSeconds ?: 0) > 30 },
                lastUpdated = System.currentTimeMillis()
            )

            saveMemory(context, profile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive memory: ${e.message}")
        }
    }
}
