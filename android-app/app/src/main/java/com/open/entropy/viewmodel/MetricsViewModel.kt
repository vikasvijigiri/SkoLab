package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open.entropy.network.ApiService
import com.open.entropy.network.AuthorMetrics
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
    private val apiService = ApiService()
    private val _uiState = MutableStateFlow(MetricsUiState())
    val uiState: StateFlow<MetricsUiState> = _uiState.asStateFlow()

    private var currentAuthorName: String? = null
    private var openAlexId: String? = null

    fun setUserContext(name: String) {
        if (currentAuthorName == name) return
        currentAuthorName = name
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
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Could not find OpenAlex profile for $name")
                    return@launch
                }
                
                openAlexId = targetId

                // 2. Fetch Metrics
                val metrics = apiService.getAuthorMetrics(targetId)
                if (metrics != null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, metrics = metrics)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load metrics")
                }
            } catch (e: Exception) {
                Log.e("MetricsViewModel", "Exception: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }
}
