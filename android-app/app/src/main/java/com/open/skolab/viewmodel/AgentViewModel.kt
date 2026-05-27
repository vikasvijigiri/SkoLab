package com.open.skolab.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.data.ActivityEvent
import com.open.skolab.data.ChatStorage
import com.open.skolab.data.UserActivityTracker
import com.open.skolab.data.UserMemoryProfile
import com.open.skolab.network.ApiService
import com.open.skolab.network.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val currentProject: String = "",
    val activeMode: AgentMode = AgentMode.RESEARCH,
    val attachedContext: String? = null,
    val attachedFileName: String? = null,
    val isAttachingFile: Boolean = false,
    // ── Memory fields ─────────────────────────────────────────────────────────
    val memoryProfile: UserMemoryProfile = UserMemoryProfile(),
    val proactiveReminders: List<String> = emptyList(),
    val personalizedGreeting: String = "",
    val personalizedQuickPrompts: List<String> = emptyList(),
    val isMemoryLoaded: Boolean = false
)

enum class AgentMode {
    RESEARCH, CODING
}

class AgentViewModel(private val context: Context, private val userUid: String) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val apiService = ApiService()
    private val chatStorage = ChatStorage(context, userUid)
    private val agentId = "jarvis_agent_001"

    init {
        loadHistory()
        loadMemoryProfile()
        trackSessionStart()
        schedulePeriodicalSync()
    }

    // ── Init: load chat history ───────────────────────────────────────────────
    private fun loadHistory() {
        val history = chatStorage.getChatHistory(agentId)
        _uiState.update { it.copy(messages = history) }
    }

    // ── Init: load memory profile (local first, then backend) ─────────────────
    private fun loadMemoryProfile() {
        viewModelScope.launch {
            // 1. Immediately apply local cached profile (fast, offline)
            val local = UserActivityTracker.readMemory(context)
            applyMemoryToState(local)

            // 2. Try fetching fresher profile from backend
            try {
                val remote = apiService.getUserMemory(userUid)
                if (remote != null) {
                    // Merge remote into a UserMemoryProfile and update
                    val merged = local.copy(
                        topTopics = remote.top_topics.ifEmpty { local.topTopics },
                        activeHours = remote.active_hours.ifEmpty { local.activeHours },
                        readingPace = remote.reading_pace.takeIf { it != "unknown" } ?: local.readingPace,
                        researchStyle = remote.research_style,
                        avgReadMinutes = if (remote.avg_read_minutes > 0f) remote.avg_read_minutes else local.avgReadMinutes,
                        unfinishedPapers = remote.unfinished_papers.ifEmpty { local.unfinishedPapers },
                        recentlyReadPapers = remote.recently_read_papers.ifEmpty { local.recentlyReadPapers },
                        frequentCollaborators = remote.frequent_collaborators.ifEmpty { local.frequentCollaborators },
                        frequentSearchTerms = remote.frequent_search_terms.ifEmpty { local.frequentSearchTerms },
                        lastActiveTopic = remote.last_active_topic.ifEmpty { local.lastActiveTopic },
                        totalPapersRead = remote.total_papers_read.coerceAtLeast(local.totalPapersRead),
                        lastUpdated = remote.last_updated
                    )
                    UserActivityTracker.saveMemory(context, merged)
                    applyMemoryToState(merged)
                }
            } catch (_: Exception) {
                // Backend unreachable — stay with local profile, no crash
            }
        }
    }

    private fun applyMemoryToState(profile: UserMemoryProfile) {
        val greeting = profile.personalizedGreeting()
        val reminders = profile.proactiveReminders()
        val quickPrompts = profile.personalizedQuickPrompts().ifEmpty {
            listOf(
                "Summarize my latest papers",
                "Find grant opportunities",
                "Who should I collaborate with?",
                "Analyze citation trends",
                "Write an abstract",
                "Compare methodologies"
            )
        }
        // Update current project label from top topic
        val project = profile.lastActiveTopic.ifEmpty {
            profile.topTopics.firstOrNull() ?: "Research Skolar"
        }

        _uiState.update { s ->
            s.copy(
                memoryProfile = profile,
                proactiveReminders = reminders,
                personalizedGreeting = greeting,
                personalizedQuickPrompts = quickPrompts,
                currentProject = project,
                isMemoryLoaded = true
            )
        }
    }

    // ── Periodic background sync of buffered events ───────────────────────────
    private fun schedulePeriodicalSync() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(5 * 60 * 1000L) // every 5 minutes
                syncEventsToBackend()
            }
        }
    }

    private suspend fun syncEventsToBackend() {
        val buffer = UserActivityTracker.readBuffer(context)
        if (buffer.isEmpty()) return
        val success = apiService.syncMemoryEvents(userUid, buffer)
        if (success) {
            UserActivityTracker.clearBuffer(context)
            // Refresh memory from backend
            try {
                val remote = apiService.getUserMemory(userUid)
                if (remote != null) {
                    val current = _uiState.value.memoryProfile
                    val updated = current.copy(
                        topTopics = remote.top_topics.ifEmpty { current.topTopics },
                        unfinishedPapers = remote.unfinished_papers.ifEmpty { current.unfinishedPapers },
                        recentlyReadPapers = remote.recently_read_papers.ifEmpty { current.recentlyReadPapers },
                        frequentCollaborators = remote.frequent_collaborators.ifEmpty { current.frequentCollaborators },
                        frequentSearchTerms = remote.frequent_search_terms.ifEmpty { current.frequentSearchTerms },
                        lastActiveTopic = remote.last_active_topic.ifEmpty { current.lastActiveTopic },
                        lastUpdated = remote.last_updated
                    )
                    UserActivityTracker.saveMemory(context, updated)
                    applyMemoryToState(updated)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Track session start ───────────────────────────────────────────────────
    private fun trackSessionStart() {
        UserActivityTracker.track(context, ActivityEvent.sessionStart())
    }

    // ── Public: track agent query topic ──────────────────────────────────────
    fun trackQuery(query: String) {
        UserActivityTracker.track(context, ActivityEvent.agentQuery(query))
    }

    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(activeMode = mode) }
    }

    fun clearAttachment() {
        _uiState.update { it.copy(attachedContext = null, attachedFileName = null) }
    }

    fun uploadFile(uri: Uri, fileName: String) {
        _uiState.update { it.copy(isAttachingFile = true) }
        viewModelScope.launch {
            val response = apiService.uploadDocument(uri, context, fileName)
            if (response != null) {
                _uiState.update {
                    it.copy(
                        attachedContext = response.extracted_text,
                        attachedFileName = response.filename,
                        isAttachingFile = false
                    )
                }
            } else {
                _uiState.update { it.copy(isAttachingFile = false) }
            }
        }
    }

    fun sendMessage(text: String) {
        val attached = _uiState.value.attachedContext
        val fileName = _uiState.value.attachedFileName
        val finalQuery = if (attached != null) {
            "Attached Context ($fileName):\n$attached\n\nUser Query:\n$text"
        } else text

        // Track the agent query for memory
        trackQuery(text)

        val userMsg = ChatMessage(role = "user", content = finalQuery)
        val updatedMessages = _uiState.value.messages + userMsg

        _uiState.update {
            it.copy(
                messages = updatedMessages,
                isTyping = true,
                attachedContext = null,
                attachedFileName = null
            )
        }
        chatStorage.saveChatHistory(agentId, updatedMessages)

        viewModelScope.launch {
            try {
                val responseText = apiService.chatWithAgent(
                    message = finalQuery,
                    history = updatedMessages.takeLast(10),
                    mode = _uiState.value.activeMode.name,
                    userMemory = _uiState.value.memoryProfile.takeIf { it.topTopics.isNotEmpty() }
                )
                val finalMessages = _uiState.value.messages + ChatMessage(
                    role = "assistant",
                    content = responseText
                )
                _uiState.update { it.copy(messages = finalMessages, isTyping = false) }
                chatStorage.saveChatHistory(agentId, finalMessages)
            } catch (e: Exception) {
                val errMsg = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "⏱ Request timed out. The model may be busy — try again in a moment."
                    e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("connect", ignoreCase = true) == true ->
                        "📡 I can't reach the server right now. Check that the SkoLab backend is running on the same network."
                    else ->
                        "⚠️ Something went wrong: ${e.message?.take(120) ?: "Unknown error"}"
                }
                val fallbackMessages = _uiState.value.messages + ChatMessage(
                    role = "assistant",
                    content = errMsg
                )
                _uiState.update { it.copy(messages = fallbackMessages, isTyping = false) }
                chatStorage.saveChatHistory(agentId, fallbackMessages)
            }
        }
    }
}
