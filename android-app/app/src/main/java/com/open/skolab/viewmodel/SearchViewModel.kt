package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.model.Paper
import com.open.skolab.network.ApiService
import com.open.skolab.network.OpenAlexWork
import com.open.skolab.network.reconstructAbstract
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

class SearchViewModel(private val apiService: ApiService = com.open.skolab.di.AppDependencies.apiService) : ViewModel() {
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
            authors = work.authorships?.mapNotNull { authorship ->
                val name = authorship.author?.display_name
                val id = authorship.author?.id
                if (name != null) {
                    if (id != null) "$name|$id" else name
                } else null
            } ?: emptyList(),
            journal = work.primary_location?.source?.display_name ?: "Unknown Journal",
            year = work.publication_year ?: 0,
            domain = "General Science",
            subDomain = "Research",
            abstractText = work.reconstructAbstract(),
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
