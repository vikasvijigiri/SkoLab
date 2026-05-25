package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.network.ApiService
import com.open.entropy.network.AuthorSuggestion
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import com.open.entropy.network.NetworkCollaborator

data class NexusCollabSuggestion(
    val id: String,
    val name: String,
    val institution: String,
    val field: String,
    val connectionPath: String,
    val overlapPct: Int
)

sealed class NexusUiState {
    object Loading : NexusUiState()
    data class Success(
        val suggestions: List<NexusCollabSuggestion>,
        val isPaginating: Boolean = false,
        val hasMore: Boolean = true
    ) : NexusUiState()
    data class Error(val message: String) : NexusUiState()
}

class NexusViewModel(private val api: ApiService = ApiService()) : ViewModel() {

    private val _uiState = MutableStateFlow<NexusUiState>(NexusUiState.Loading)
    val uiState: StateFlow<NexusUiState> = _uiState.asStateFlow()

    /** The field query used to surface similar researchers. Can be set from the user profile. */
    private val _fieldQuery = MutableStateFlow("")
    val fieldQuery: StateFlow<String> = _fieldQuery.asStateFlow()

    private val _suggestions = mutableListOf<NexusCollabSuggestion>()
    private var currentOffset = 0
    private var isFetching = false
    private var hasMoreItems = true

    private var targetId: String = "fallback_seed"
    private var userFocus: String = ""

    // Removed auto-init to wait for user context
    // init { loadSuggestions(isRefresh = true) }

    fun setUserContext(uid: String, name: String, focus: String) {
        viewModelScope.launch {
            userFocus = focus
            if (_fieldQuery.value.isBlank()) {
                _fieldQuery.value = focus
            }
            if (name.isNotBlank()) {
                val profile = api.searchAuthor(name)
                if (profile != null) {
                    targetId = profile.id
                }
            }
            loadSuggestions(isRefresh = true)
        }
    }

    fun setFieldQuery(query: String) {
        if (query.isNotBlank() && query != _fieldQuery.value) {
            _fieldQuery.value = query
            loadSuggestions(isRefresh = true)
        }
    }

    fun loadSuggestions(query: String = _fieldQuery.value, isRefresh: Boolean = false) {
        if (isFetching || (!hasMoreItems && !isRefresh)) return

        viewModelScope.launch {
            isFetching = true
            if (isRefresh) {
                currentOffset = 0
                _suggestions.clear()
                hasMoreItems = true
                _uiState.value = NexusUiState.Loading
            } else {
                _uiState.value = NexusUiState.Success(_suggestions.toList(), isPaginating = true, hasMore = true)
            }

            try {
                val fieldToUse = if (query == "All Fields" || query.isBlank()) userFocus else query
                val network = api.getNetworkCollaborators(
                    authorId = targetId,
                    limit = 10,
                    offset = currentOffset
                )
                
                if (network.isEmpty() && isRefresh) {
                    _uiState.value = NexusUiState.Error("Could not load network connections.")
                } else {
                    val newItems = network.map { it.toNexusItem() }
                    if (newItems.size < 10) {
                        hasMoreItems = false
                    }
                    _suggestions.addAll(newItems)
                    currentOffset += newItems.size
                    _uiState.value = NexusUiState.Success(
                        suggestions = _suggestions.toList(),
                        isPaginating = false,
                        hasMore = hasMoreItems
                    )
                }
            } catch (e: Exception) {
                if (isRefresh) {
                    _uiState.value = NexusUiState.Error("Network error: ${e.message}")
                } else {
                    _uiState.value = NexusUiState.Success(
                        suggestions = _suggestions.toList(),
                        isPaginating = false,
                        hasMore = hasMoreItems
                    ) // revert paginating state
                }
            } finally {
                isFetching = false
            }
        }
    }

    private fun NetworkCollaborator.toNexusItem(): NexusCollabSuggestion {
        return NexusCollabSuggestion(
            id = id,
            name = name,
            institution = institution,
            field = field.ifBlank { "Multidisciplinary" },
            connectionPath = connection_path,
            overlapPct = relevance_score.coerceIn(50, 99)
        )
    }
}
