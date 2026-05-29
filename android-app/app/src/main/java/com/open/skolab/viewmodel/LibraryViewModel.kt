package com.open.skolab.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.data.UserPreferences
import com.open.skolab.network.ApiService
import com.open.skolab.network.OpenAlexWork
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
    private val prefs = UserPreferences(application)
    private val api = com.open.skolab.di.AppDependencies.apiService

    private val _uiState = MutableStateFlow<SavedPapersUiState>(SavedPapersUiState.Loading)
    val uiState: StateFlow<SavedPapersUiState> = _uiState.asStateFlow()

    /** Reactive set of saved IDs (for bookmark button state in PaperDetail). */
    val savedIds: StateFlow<Set<String>> = prefs.savedPaperIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
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
        return prefs.toggleSavedPaper(paperId)
    }

    private fun OpenAlexWork.toSavedItem() = SavedPaperItem(
        id = id,
        title = title ?: "Untitled",
        authors = authorships?.mapNotNull { it.author?.display_name }
            ?.take(3) ?: emptyList(),
        journal = primary_location?.source?.display_name ?: "Unknown Journal",
        year = publication_year ?: 0,
        citedByCount = cited_by_count ?: 0,
        doi = doi
    )
}
