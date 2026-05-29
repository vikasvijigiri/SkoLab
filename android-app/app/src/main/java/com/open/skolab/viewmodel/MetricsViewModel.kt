package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.skolab.network.ApiService
import com.open.skolab.network.AuthorMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

data class MetricsUiState(
    val isLoading: Boolean = false,
    val metrics: AuthorMetrics? = null,
    val error: String? = null
)

class MetricsViewModel : ViewModel() {
    private val apiService = com.open.skolab.di.AppDependencies.apiService
    private val _uiState = MutableStateFlow(MetricsUiState())
    val uiState: StateFlow<MetricsUiState> = _uiState.asStateFlow()

    private var currentAuthorName: String? = null
    private var openAlexId: String? = null

    fun setUserContext(name: String) {
        if (currentAuthorName == name) return
        currentAuthorName = name
        fetchMetrics()
    }

    fun retry() {
        fetchMetrics()
    }

    private fun fetchMetrics() {
        val name = currentAuthorName ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // 1. Resolve OpenAlex ID
                val authorProfile = apiService.searchAuthor(name)
                val targetId = authorProfile?.id

                if (targetId == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Could not find OpenAlex profile for \"$name\". Check your profile name in settings."
                    )
                    return@launch
                }

                openAlexId = targetId

                // 2. Fetch Metrics — throws on any failure, which we surface to the user
                val metrics = apiService.getAuthorMetrics(targetId)
                _uiState.value = _uiState.value.copy(isLoading = false, metrics = metrics)

            } catch (e: Exception) {
                val message = e.message ?: "An unexpected error occurred"
                Log.e("MetricsViewModel", "fetchMetrics failed: $message", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = message
                )
            }
        }
    }
}
