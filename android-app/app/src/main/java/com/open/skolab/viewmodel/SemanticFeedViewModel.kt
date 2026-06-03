package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.model.Paper
import com.open.skolab.network.OpenAlexWork
import com.open.skolab.network.reconstructAbstract
import com.open.skolab.network.SemanticOpenAlexService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SemanticFeedUiState(
    val userResearchFocus: String = "Quantum computing and artificial intelligence in genomics",
    val semanticPapers: List<Paper> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class SemanticFeedViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SemanticFeedUiState())
    val uiState: StateFlow<SemanticFeedUiState> = _uiState.asStateFlow()
    
    private val semanticService = SemanticOpenAlexService()

    init {
        loadSemanticPapers()
    }
    
    fun setUserFocus(focus: String) {
        _uiState.value = _uiState.value.copy(userResearchFocus = focus)
        loadSemanticPapers()
    }

    private fun loadSemanticPapers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val focus = _uiState.value.userResearchFocus
            
            try {
                val rawWorks = semanticService.getSemanticPapers(focus, limit = 15)
                val mappedPapers = rawWorks.map { mapOpenAlexToPaper(it) }
                _uiState.value = _uiState.value.copy(
                    semanticPapers = mappedPapers,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load semantic papers."
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
            year = work.publication_year ?: 2026,
            domain = "General Science",
            subDomain = "Research",
            abstractText = work.reconstructAbstract(),
            disruptionScore = 0.82f,
            noveltyScore = 0.76f,
            citationVelocity = 12.0f,
            hIndex = 34,
            citationCount = work.cited_by_count ?: 0,
            journalImpactFactor = 4.2f,
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
