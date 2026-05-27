package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.network.ApiService
import com.open.skolab.network.OpenAlexWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrendingPaperItem(
    val id: String,
    val title: String,
    val journal: String,
    /** Raw citation count — shown as the metric in the card. */
    val citedByCount: Int,
    val year: Int,
    /** Top-level concept/field from the work's topics (best-effort). */
    val field: String
)

sealed class TrendingUiState {
    object Loading : TrendingUiState()
    data class Success(val papers: List<TrendingPaperItem>) : TrendingUiState()
    data class Error(val message: String) : TrendingUiState()
}

class HomeViewModel(private val api: ApiService = ApiService()) : ViewModel() {

    private val _trendingState = MutableStateFlow<TrendingUiState>(TrendingUiState.Loading)
    val trendingState: StateFlow<TrendingUiState> = _trendingState.asStateFlow()

    init {
        loadTrending()
    }

    fun loadTrending() {
        viewModelScope.launch {
            _trendingState.value = TrendingUiState.Loading
            try {
                val works = api.getTrendingPapers(limit = 8)
                if (works.isEmpty()) {
                    _trendingState.value = TrendingUiState.Error("Could not load trending papers")
                } else {
                    _trendingState.value = TrendingUiState.Success(
                        works.filter { !it.title.isNullOrBlank() }
                            .map { it.toTrendingItem() }
                    )
                }
            } catch (e: Exception) {
                _trendingState.value = TrendingUiState.Error("Network error: ${e.message}")
            }
        }
    }

    private fun OpenAlexWork.toTrendingItem(): TrendingPaperItem {
        return TrendingPaperItem(
            id = id,
            title = title ?: "Untitled",
            journal = primary_location?.source?.display_name ?: "Unknown Journal",
            citedByCount = cited_by_count ?: 0,
            year = publication_year ?: 0,
            field = authorships?.firstOrNull()?.author?.display_name?.let { "Research" } ?: "Science"
        )
    }
}
