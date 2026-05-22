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

sealed class PaperUiState {
    object Loading : PaperUiState()
    data class Success(val paper: Paper) : PaperUiState()
    data class Error(val message: String) : PaperUiState()
}

class PaperViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow<PaperUiState>(PaperUiState.Loading)
    val uiState: StateFlow<PaperUiState> = _uiState.asStateFlow()

    fun fetchPaperDetails(paperId: String) {
        viewModelScope.launch {
            _uiState.value = PaperUiState.Loading
            val work = apiService.getPaperDetails(paperId)
            if (work != null) {
                _uiState.value = PaperUiState.Success(mapOpenAlexToPaper(work))
            } else {
                _uiState.value = PaperUiState.Error("Failed to fetch paper details")
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
            domain = "General Science", // OpenAlex has topics, mapping to domain for simplicity
            subDomain = "Research",
            abstractText = "Abstract not available in this view.", // OpenAlex abstract is inverted index
            disruptionScore = 0.5f, // OpenAlex doesn't provide this directly
            noveltyScore = 0.5f,
            citationVelocity = 0f,
            hIndex = 0,
            citationCount = work.cited_by_count ?: 0,
            journalImpactFactor = 0f,
            aiSummary = "No AI summary available yet.",
            keyInsight = "Key insight being generated...",
            bulletPoints = emptyList(),
            methodology = emptyList(),
            latexFormula = null,
            isRetracted = false,
            doi = work.doi ?: "",
            pdfUrl = work.primary_location?.pdf_url ?: work.primary_location?.landing_page_url
        )
    }
}
