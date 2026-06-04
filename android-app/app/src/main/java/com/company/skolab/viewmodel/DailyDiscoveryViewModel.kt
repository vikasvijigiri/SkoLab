package com.company.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.ApiService
import com.company.skolab.network.reconstructAbstract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.company.skolab.network.getJournalOrFallback

data class DiscoveryItem(
    val id: String,
    val title: String,
    val authors: String,
    val abstractText: String,
    val tags: List<String>,
    val journal: String = "Academic Publication",
    val year: Int = 2026,
    val relevanceScore: Int = 85,
    val pdfUrl: String? = null
)

data class DailyDiscoveryUiState(
    val discoveryItems: List<DiscoveryItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class DailyDiscoveryViewModel(
    private val apiService: ApiService = AppDependencies.apiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyDiscoveryUiState())
    val uiState: StateFlow<DailyDiscoveryUiState> = _uiState.asStateFlow()

    init {
        loadDiscoveryItems()
    }

    fun loadDiscoveryItems() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val trending = apiService.getTrendingPapers(focus = "Artificial Intelligence", limit = 10)
                if (trending.isEmpty()) {
                    throw Exception("No trending academic papers found from OpenAlex")
                }
                val items = trending.map { work ->
                    val authorNames = work.authorships?.mapNotNull { it.author?.display_name } ?: emptyList()
                    val authorsText = if (authorNames.isNotEmpty()) {
                        if (authorNames.size > 2) "${authorNames[0]} et al." else authorNames.joinToString(", ")
                    } else {
                        "Unknown Author"
                    }
                    val abstractText = work.reconstructAbstract().ifBlank { 
                        "No abstract available. You can view the paper's full details or open it directly."
                    }
                    val tags = mutableListOf<String>()
                    work.primary_topic?.display_name?.let { tags.add(it) }
                    work.primary_topic?.field?.display_name?.let { tags.add(it) }
                    if (tags.isEmpty()) {
                        tags.add("Research")
                        tags.add("Paper")
                    }
                    val journalText = work.getJournalOrFallback()
                    val yearValue = work.publication_year ?: 2026
                    val relevanceVal = (80..99).random()
                    val pdfLink = work.primary_location?.pdf_url
                    DiscoveryItem(
                        id = work.id,
                        title = work.title ?: "Untitled Paper",
                        authors = authorsText,
                        abstractText = abstractText,
                        tags = tags.take(3),
                        journal = journalText,
                        year = yearValue,
                        relevanceScore = relevanceVal,
                        pdfUrl = pdfLink
                    )
                }
                _uiState.update { 
                    it.copy(
                        discoveryItems = items,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to fetch discovery papers"
                    )
                }
            }
        }
    }
}
