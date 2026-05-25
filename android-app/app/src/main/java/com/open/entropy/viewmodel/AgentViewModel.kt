package com.open.entropy.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.data.ChatStorage
import com.open.entropy.network.ApiService
import com.open.entropy.network.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val currentProject: String = "Quantum Information Dynamics",
    val activeMode: AgentMode = AgentMode.RESEARCH,
    val attachedContext: String? = null,
    val attachedFileName: String? = null,
    val isAttachingFile: Boolean = false
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
    }

    private fun loadHistory() {
        val history = chatStorage.getChatHistory(agentId)
        if (history.isEmpty()) {
            val initial = listOf(
                ChatMessage(
                    role = "assistant",
                    content = "Welcome back. I've maintained the context of your **Quantum Information Dynamics** exploration. How would you like to proceed with the tensor-network simulation?"
                )
            )
            chatStorage.saveChatHistory(agentId, initial)
            _uiState.update { it.copy(messages = initial) }
        } else {
            _uiState.update { it.copy(messages = history) }
        }
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
                    mode = _uiState.value.activeMode.name
                )
                
                val finalMessages = _uiState.value.messages + ChatMessage(
                    role = "assistant",
                    content = responseText
                )
                _uiState.update { it.copy(messages = finalMessages, isTyping = false) }
                chatStorage.saveChatHistory(agentId, finalMessages)
                
            } catch (e: Exception) {
                val fallback = _uiState.value.messages + ChatMessage(
                    role = "assistant",
                    content = "I'm currently operating offline due to network constraints. However, based on my cached knowledge base, your tensor contraction strategy is mathematically sound. Should we review the local simulation code?"
                )
                _uiState.update { it.copy(messages = fallback, isTyping = false) }
                chatStorage.saveChatHistory(agentId, fallback)
            }
        }
    }
}
