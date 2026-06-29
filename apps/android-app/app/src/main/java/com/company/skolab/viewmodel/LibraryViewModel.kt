package com.company.skolab.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.data.UserPreferences
import com.company.skolab.network.ApiService
import com.company.skolab.network.OpenAlexWork
import com.company.skolab.network.getJournalOrFallback
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Represents the full detail of a saved paper once fetched from OpenAlex. */
data class SavedPaperItem(
    val id: String,
    val title: String,
    val authors: List<String>,
    val journal: String,
    val year: Int,
    val citedByCount: Int,
    val doi: String?
)

sealed class SavedPapersUiState {
    object Loading : SavedPapersUiState()
    data class Success(val papers: List<SavedPaperItem>) : SavedPapersUiState()
    data class Empty(val message: String = "No saved papers yet") : SavedPapersUiState()
    data class Error(val message: String) : SavedPapersUiState()
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    var selectedTab = 0

    var savedListIndex = 0
    var savedListOffset = 0

    fun updateSavedScroll(index: Int, offset: Int) {
        savedListIndex = index
        savedListOffset = offset
    }

    var dailyFeedListIndex = 0
    var dailyFeedListOffset = 0

    fun updateDailyFeedScroll(index: Int, offset: Int) {
        dailyFeedListIndex = index
        dailyFeedListOffset = offset
    }

    private val prefs = UserPreferences(application)
    private val api = com.company.skolab.di.AppDependencies.apiService

    private val _uiState = MutableStateFlow<SavedPapersUiState>(SavedPapersUiState.Loading)
    val uiState: StateFlow<SavedPapersUiState> = _uiState.asStateFlow()

    /** Reactive set of saved IDs (for bookmark button state in PaperDetail). */
    val savedIds: StateFlow<Set<String>> = prefs.savedPaperIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        observeSavedPapers()
    }

    fun refresh() {
        observeSavedPapers()
    }

    private fun observeSavedPapers() {
        viewModelScope.launch {
            prefs.savedPaperIds.collectLatest { ids ->
                if (ids.isEmpty()) {
                    _uiState.value = SavedPapersUiState.Empty()
                    return@collectLatest
                }
                _uiState.value = SavedPapersUiState.Loading
                // Fetch details for each saved ID concurrently
                val papers = ids.mapNotNull { id ->
                    try {
                        api.getPaperDetails(id)?.toSavedItem()
                    } catch (e: Exception) {
                        null
                    }
                }
                _uiState.value = if (papers.isEmpty()) {
                    SavedPapersUiState.Error("Could not load saved papers")
                } else {
                    SavedPapersUiState.Success(papers)
                }
            }
        }
    }

    /**
     * Toggle the bookmark state of [paperId].
     * @return true if it was saved, false if it was removed.
     */
    suspend fun toggleSaved(paperId: String): Boolean {
        val isSavedNow = prefs.toggleSavedPaper(paperId)
        if (isSavedNow) {
            // Pass screen name so this event is distinguishable from FeedScreen saves
            // in Firebase Analytics dashboards and BigQuery exports.
            SkoLabAnalytics.logPaperSaved(paperId, screenName = "library_screen")
        }
        return isSavedNow
    }

    private fun OpenAlexWork.toSavedItem() = SavedPaperItem(
        id = id,
        title = title ?: "Untitled",
        authors = authorships?.mapNotNull { it.author?.display_name }
            ?.take(3) ?: emptyList(),
        journal = getJournalOrFallback(),
        year = publication_year ?: 0,
        citedByCount = cited_by_count ?: 0,
        doi = doi
    )
}
