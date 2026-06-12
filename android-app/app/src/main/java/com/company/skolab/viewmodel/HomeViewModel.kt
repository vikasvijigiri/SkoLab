package com.company.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.company.skolab.network.ApiService
import com.company.skolab.network.AuthorSuggestion
import com.company.skolab.network.NetworkCollaborator
import com.company.skolab.network.OpenAlexWork
import com.company.skolab.network.OrbitMetrics
import com.company.skolab.network.getJournalOrFallback
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrendingPaperItem(
    val id: String,
    val title: String,
    val journal: String,
    val citedByCount: Int,
    val year: Int,
    val field: String
)

sealed class TrendingUiState {
    object Loading : TrendingUiState()
    data class Success(val papers: List<TrendingPaperItem>) : TrendingUiState()
    data class Error(val message: String) : TrendingUiState()
}

data class NetworkUiState(
    val networkCollaborators: List<NetworkCollaborator> = emptyList(),
    val suggestedCollaborators: List<AuthorSuggestion> = emptyList(),
    val orbitMetrics: OrbitMetrics? = null,
    val isLoadingNetwork: Boolean = false,
    val isLoadingSuggestions: Boolean = false
)

class HomeViewModel(private val api: ApiService = com.company.skolab.di.AppDependencies.apiService) : ViewModel() {

    private val _trendingState = MutableStateFlow<TrendingUiState>(TrendingUiState.Loading)
    val trendingState: StateFlow<TrendingUiState> = _trendingState.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkUiState())
    val networkState: StateFlow<NetworkUiState> = _networkState.asStateFlow()

    // Track which identity we last fetched for — avoids redundant re-fetches
    private var lastNetworkUser: String = ""
    private var lastSuggestionsField: String = ""

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
                        works.filter { !it.title.isNullOrBlank() }.map { it.toTrendingItem() }
                    )
                }
            } catch (e: Exception) {
                _trendingState.value = TrendingUiState.Error("Network error: ${e.message}")
            }
        }
    }

    /**
     * Loads orbit metrics, network collaborators, and suggestions.
     * Skips re-fetching if already cached for the same identity.
     */
    fun loadNetworkData(userName: String, userFocus: String, excludeName: String = userName) {
        val key = "$userName|$userFocus"
        if (key == lastNetworkUser && _networkState.value.networkCollaborators.isNotEmpty()) return

        lastNetworkUser = key
        viewModelScope.launch {
            _networkState.value = _networkState.value.copy(isLoadingNetwork = true)
            try {
                val profile = api.searchAuthor(userName, focus = userFocus.ifBlank { null })
                val authorId = profile?.id ?: ""
                if (authorId.isNotBlank()) {
                    // Fetch orbit metrics and collaborators in parallel
                    val metricsDeferred = async { runCatching { api.getOrbitMetrics(authorId) }.getOrNull() }
                    val collabsDeferred = async {
                        runCatching {
                            api.getNetworkCollaborators(authorId = authorId, limit = 6, excludeName = excludeName)
                        }.getOrElse { emptyList() }
                    }
                    _networkState.value = _networkState.value.copy(
                        orbitMetrics = metricsDeferred.await(),
                        networkCollaborators = collabsDeferred.await(),
                        isLoadingNetwork = false
                    )
                } else {
                    _networkState.value = _networkState.value.copy(isLoadingNetwork = false)
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to load network data", e)
                _networkState.value = _networkState.value.copy(isLoadingNetwork = false)
            }
        }
    }

    fun loadSuggestions(userFocus: String) {
        if (userFocus == lastSuggestionsField && _networkState.value.suggestedCollaborators.isNotEmpty()) return
        if (userFocus.isBlank() || userFocus == "Researcher" || userFocus == "General Research") return

        lastSuggestionsField = userFocus
        viewModelScope.launch {
            _networkState.value = _networkState.value.copy(isLoadingSuggestions = true)
            try {
                val list = api.getSimilarAuthors(userFocus, limit = 8)
                _networkState.value = _networkState.value.copy(
                    suggestedCollaborators = list.take(8),
                    isLoadingSuggestions = false
                )
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to load suggestions", e)
                _networkState.value = _networkState.value.copy(isLoadingSuggestions = false)
            }
        }
    }

    private fun OpenAlexWork.toTrendingItem(): TrendingPaperItem {
        return TrendingPaperItem(
            id = id,
            title = title ?: "Untitled",
            journal = getJournalOrFallback(),
            citedByCount = cited_by_count ?: 0,
            year = publication_year ?: 0,
            field = authorships?.firstOrNull()?.author?.display_name?.let { "Research" } ?: "Science"
        )
    }
}
