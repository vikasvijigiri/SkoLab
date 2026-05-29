package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.model.Paper
import com.open.skolab.model.PaperIntelligence
import com.open.skolab.network.ApiService
import com.open.skolab.network.OpenAlexWork
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PaperUiState {
    object Loading : PaperUiState()
    data class Success(val paper: Paper) : PaperUiState()
    data class Error(val message: String) : PaperUiState()
}

sealed class IntelligenceUiState {
    /** Awaiting the /analyze_paper response (PDF download + LLM can take 5-30 sec) */
    object Loading : IntelligenceUiState()
    data class Success(val data: PaperIntelligence) : IntelligenceUiState()
    /** Backend unreachable or analysis failed */
    data class Unavailable(val reason: String = "") : IntelligenceUiState()
}

class PaperViewModel(private val apiService: ApiService = com.open.skolab.di.AppDependencies.apiService) : ViewModel() {

    // ── Paper basics (from OpenAlex — fast) ────────────────────────────────────
    private val _uiState = MutableStateFlow<PaperUiState>(PaperUiState.Loading)
    val uiState: StateFlow<PaperUiState> = _uiState.asStateFlow()

    // ── AI Intelligence (from backend — slower, PDF read + LLM) ───────────────
    private val _intelligenceState = MutableStateFlow<IntelligenceUiState>(IntelligenceUiState.Loading)
    val intelligenceState: StateFlow<IntelligenceUiState> = _intelligenceState.asStateFlow()

    fun fetchPaperDetails(paperId: String) {
        viewModelScope.launch {
            _uiState.value = PaperUiState.Loading
            _intelligenceState.value = IntelligenceUiState.Loading

            // ── Fetch paper basics first (OpenAlex, typically <1 sec) ──────────
            val work = apiService.getPaperDetails(paperId)
            if (work == null) {
                _uiState.value = PaperUiState.Error("Failed to fetch paper details")
                _intelligenceState.value = IntelligenceUiState.Unavailable("Paper not found")
                return@launch
            }

            val paper = mapOpenAlexToPaper(work)
            _uiState.value = PaperUiState.Success(paper)

            // ── Now trigger deep AI analysis (async — paper UI is already shown) ─
            // The backend will: download PDF → extract text → run LLM (9 sections)
            // This can take 5–40 seconds depending on PDF size and network
            loadIntelligence(
                title = paper.title,
                doi = paper.doi.ifBlank { null },
                openAlexId = paperId
            )
        }
    }

    fun retryIntelligence(paper: Paper, openAlexId: String) {
        _intelligenceState.value = IntelligenceUiState.Loading
        viewModelScope.launch {
            loadIntelligence(
                title = paper.title,
                doi = paper.doi.ifBlank { null },
                openAlexId = openAlexId
            )
        }
    }

    private suspend fun loadIntelligence(
        title: String,
        doi: String?,
        openAlexId: String
    ) {
        val intelligence = apiService.analyzePaper(
            title = title,
            doi = doi,
            openAlexId = openAlexId
        )
        _intelligenceState.value = if (intelligence != null) {
            IntelligenceUiState.Success(intelligence)
        } else {
            IntelligenceUiState.Unavailable("Backend not reachable")
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
            domain = work.primary_topic?.field?.display_name ?: "General Science",
            subDomain = work.primary_topic?.display_name ?: "Research",
            abstractText = "",
            disruptionScore = minOf(1f, (work.cited_by_count ?: 0) / 5000f),
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
            pdfUrl = work.primary_location?.pdf_url ?: work.primary_location?.landing_page_url
        )
    }
}
