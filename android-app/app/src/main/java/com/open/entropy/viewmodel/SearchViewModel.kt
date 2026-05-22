package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.model.Paper
import com.open.entropy.network.ApiService
import com.open.entropy.network.OpenAlexWork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(val results: List<Paper>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

class SearchViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        if (query.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            delay(150) // Debounce
            try {
                val results = apiService.searchPapers(query)
                _uiState.value = SearchUiState.Success(results.map { mapOpenAlexToPaper(it) })
            } catch (e: Exception) {
                _uiState.value = SearchUiState.Error(
                    e.message ?: "Could not reach OpenAlex. Check your connection."
                )
            }
        }
    }

    private fun mapOpenAlexToPaper(work: OpenAlexWork): Paper {
        return Paper(
            id = work.id,
            title = work.title ?: "Unknown Title",
            authors = work.authorships?.mapNotNull { it.author?.display_name } ?: emptyList(),
            journal = work.primary_location?.source?.display_name ?: "Unknown Journal",
            year = work.publication_year ?: 0,
            domain = "General Science",
            subDomain = "Research",
            abstractText = "",
            disruptionScore = 0f,
            noveltyScore = 0f,
            citationVelocity = 0f,
            hIndex = 0,
            citationCount = work.cited_by_count ?: 0,
            journalImpactFactor = 0f,
            aiSummary = "",
            keyInsight = "",
            bulletPoints = emptyList(),
            methodology = emptyList(),
            latexFormula = null,
            isRetracted = false,
            doi = work.doi ?: "",
            pdfUrl = work.primary_location?.pdf_url
        )
    }
}
