package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.network.ApiService
import com.open.entropy.network.AuthorSuggestion
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NexusCollabSuggestion(
    val id: String,
    val name: String,
    val institution: String,
    val field: String,
    /** Overlap percentage (50–95, derived from index position as we don't have a real score). */
    val overlapPct: Int
)

sealed class NexusUiState {
    object Loading : NexusUiState()
    data class Success(val suggestions: List<NexusCollabSuggestion>) : NexusUiState()
    data class Error(val message: String) : NexusUiState()
}

class NexusViewModel(private val api: ApiService = ApiService()) : ViewModel() {

    private val _uiState = MutableStateFlow<NexusUiState>(NexusUiState.Loading)
    val uiState: StateFlow<NexusUiState> = _uiState.asStateFlow()

    /** The field query used to surface similar researchers. Can be set from the user profile. */
    private val _fieldQuery = MutableStateFlow("artificial intelligence")
    val fieldQuery: StateFlow<String> = _fieldQuery.asStateFlow()

    init {
        loadSuggestions()
    }

    fun setFieldQuery(query: String) {
        if (query.isNotBlank() && query != _fieldQuery.value) {
            _fieldQuery.value = query
            loadSuggestions()
        }
    }

    fun loadSuggestions(query: String = _fieldQuery.value) {
        viewModelScope.launch {
            _uiState.value = NexusUiState.Loading
            try {
                val suggestions = api.getSimilarAuthors(query, limit = 5)
                if (suggestions.isEmpty()) {
                    // Fallback to broader AI query
                    val fallback = api.getSimilarAuthors("artificial intelligence", limit = 5)
                    _uiState.value = if (fallback.isEmpty()) {
                        NexusUiState.Error("Could not load collaboration suggestions")
                    } else {
                        NexusUiState.Success(fallback.mapIndexed { i, s -> s.toNexusItem(i) })
                    }
                } else {
                    _uiState.value = NexusUiState.Success(
                        suggestions.mapIndexed { i, s -> s.toNexusItem(i) }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = NexusUiState.Error("Network error: ${e.message}")
            }
        }
    }

    private fun AuthorSuggestion.toNexusItem(index: Int): NexusCollabSuggestion {
        // Derive an approximate overlap score based on ranking position (higher rank → higher overlap)
        // This is a heuristic since OpenAlex doesn't return an explicit similarity score
        val overlap = (92 - index * 7).coerceIn(50, 95)
        return NexusCollabSuggestion(
            id = id,
            name = display_name,
            institution = institution,
            field = field_of_study ?: "Multidisciplinary",
            overlapPct = overlap
        )
    }
}
