package com.company.skolab.viewmodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.company.skolab.model.AssistantProfessorRoadmap
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.network.ChatMessage
import com.company.skolab.di.AppDependencies
import com.company.skolab.repository.DefaultIndustryRepository
import com.company.skolab.repository.IndustryRepository
import com.company.skolab.utils.IndustryMatchUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.bookmarkStore: DataStore<Preferences>
    by preferencesDataStore(name = "industry_bookmarks")

private val BOOKMARKED_IDS_KEY = stringPreferencesKey("bookmarked_opportunity_ids")

class IndustryViewModel(
    private val repository: IndustryRepository = DefaultIndustryRepository()
) : ViewModel() {

    private val apiService = AppDependencies.apiService

    // ── Opportunities ─────────────────────────────────────────────────────────
    private val _opportunities = MutableStateFlow<List<IndustryOpportunity>>(emptyList())
    val opportunities: StateFlow<List<IndustryOpportunity>> = _opportunities.asStateFlow()

    // ── Roadmap ───────────────────────────────────────────────────────────────
    private val _roadmap = MutableStateFlow<AssistantProfessorRoadmap?>(null)
    val roadmap: StateFlow<AssistantProfessorRoadmap?> = _roadmap.asStateFlow()

    // ── Loading / Error ───────────────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingRoadmap = MutableStateFlow(false)
    val isLoadingRoadmap: StateFlow<Boolean> = _isLoadingRoadmap.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Bookmarks (persisted via DataStore) ───────────────────────────────────
    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds.asStateFlow()

    private var bookmarkDataStore: DataStore<Preferences>? = null

    fun initBookmarks(context: Context) {
        if (bookmarkDataStore != null) return
        bookmarkDataStore = context.bookmarkStore
        viewModelScope.launch {
            val prefs = context.bookmarkStore.data.first()
            val raw = prefs[BOOKMARKED_IDS_KEY] ?: ""
            _bookmarkedIds.value = raw.split(",").filter { it.isNotBlank() }.toSet()
        }
    }

    fun toggleBookmark(id: String) {
        val store = bookmarkDataStore ?: return
        viewModelScope.launch {
            val updated = _bookmarkedIds.value.toMutableSet()
            if (id in updated) updated.remove(id) else updated.add(id)
            _bookmarkedIds.value = updated
            store.edit { prefs ->
                prefs[BOOKMARKED_IDS_KEY] = updated.joinToString(",")
            }
        }
    }

    // ── AI Draft (real Groq call via backend agent endpoint) ──────────────────
    private val _aiDraft = MutableStateFlow<String?>(null)
    val aiDraft: StateFlow<String?> = _aiDraft.asStateFlow()

    private val _isGeneratingDraft = MutableStateFlow(false)
    val isGeneratingDraft: StateFlow<Boolean> = _isGeneratingDraft.asStateFlow()

    private val _aiDraftError = MutableStateFlow<String?>(null)
    val aiDraftError: StateFlow<String?> = _aiDraftError.asStateFlow()

    fun clearAiDraft() {
        _aiDraft.value = null
        _aiDraftError.value = null
    }

    fun generateAiDraft(
        type: String,
        opp: IndustryOpportunity,
        userFocus: String,
        userName: String
    ) {
        if (_isGeneratingDraft.value) return
        _isGeneratingDraft.value = true
        _aiDraft.value = null
        _aiDraftError.value = null

        viewModelScope.launch {
            try {
                val prompt = buildPrompt(type, opp, userFocus, userName)
                val reply = apiService.chatWithAgent(
                    message = prompt,
                    history = emptyList<ChatMessage>(),
                    mode = "RESEARCH"
                )
                _aiDraft.value = reply.trim()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _aiDraftError.value = "Could not generate draft: ${e.localizedMessage}"
            } finally {
                _isGeneratingDraft.value = false
            }
        }
    }

    private fun buildPrompt(
        type: String,
        opp: IndustryOpportunity,
        userFocus: String,
        userName: String
    ): String {
        val posLabel = opp.positionLevel.displayLabel().ifBlank { opp.type.name.lowercase() }
        val skills = opp.requiredSkills.take(5).joinToString(", ")
            .ifBlank { "research, analysis, academic writing" }
        val descSnippet = opp.description.take(400)
            .ifBlank { "Conduct advanced research in ${opp.companyOrFunder}." }

        return if (type == "cover") {
            """Write a concise, professional academic cover letter for a $posLabel position.

Position: ${opp.title}
Organization: ${opp.companyOrFunder}
Required Skills: $skills
Description: $descSnippet

Candidate:
- Name: ${userName.ifBlank { "the applicant" }}
- Research Focus: $userFocus

Instructions:
- 3 paragraphs: introduction, research fit, closing
- Specific to this role and organization — do not use generic filler
- Academic tone, first-person
- Output only the letter body — no subject line, no "Dear Hiring Committee" header"""
        } else {
            """Write a Statement of Purpose (SOP) research goal section for a $posLabel position.

Position: ${opp.title}
Organization: ${opp.companyOrFunder}
Required Skills: $skills
Description: $descSnippet

Candidate Research Focus: $userFocus

Instructions:
- 3-4 bullet points covering: research motivation, relevant background, specific goals at this organization, long-term vision
- Academic and specific — no generic statements
- Output only the SOP section content"""
        }
    }

    // ── Match Score (real, deterministic) ─────────────────────────────────────

    fun computeMatchScore(userFocus: String, opp: IndustryOpportunity): Int =
        IndustryMatchUtils.computeMatchScore(userFocus, opp)

    // ── Data Loading ───────────────────────────────────────────────────────────

    fun setError(message: String?) {
        _error.value = message
    }

    fun loadOpportunities(focus: String, name: String? = null) {
        if (focus.isBlank()) {
            _error.value = "Research focus not configured. Set it in profile settings."
            return
        }
        _error.value = null
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val results = repository.getOpportunities(focus, name)
                _opportunities.value = results
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "Failed to load opportunities: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadRoadmap(authorId: String?, name: String, focus: String) {
        if (focus.isBlank()) {
            _error.value = "Research focus not configured. Set it in profile settings."
            return
        }
        if (_isLoadingRoadmap.value) return
        _isLoadingRoadmap.value = true
        viewModelScope.launch {
            try {
                val result = repository.getRoadmap(authorId, name, focus)
                _roadmap.value = result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "Failed to load roadmap: ${e.localizedMessage}"
            } finally {
                _isLoadingRoadmap.value = false
            }
        }
    }
}
