package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.model.Paper
import com.open.entropy.network.ApiService
import com.open.entropy.network.OpenAlexWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class FeedUiState {
    object Loading : FeedUiState()
    data class Success(val papers: List<Paper>) : FeedUiState()
    data class Error(val message: String) : FeedUiState()
}

class FeedViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        fetchFeed()
    }

    fun fetchFeed(query: String = "disruptive science") {
        viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            val results = apiService.searchPapers(query)
            if (results.isNotEmpty()) {
                _uiState.value = FeedUiState.Success(results.map { mapOpenAlexToPaper(it) })
            } else {
                _uiState.value = FeedUiState.Error("No papers found.")
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
            disruptionScore = (work.cited_by_count ?: 0) / 1000f, // Fake score for UI
            noveltyScore = 0.5f,
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
