package com.company.skolab.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.company.skolab.di.AppDependencies
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.model.AssistantProfessorRoadmap

class IndustryViewModel : ViewModel() {

    private val apiService = AppDependencies.apiService
    
    private val _opportunities = MutableStateFlow<List<IndustryOpportunity>>(emptyList())
    val opportunities: StateFlow<List<IndustryOpportunity>> = _opportunities.asStateFlow()

    private val _roadmap = MutableStateFlow<AssistantProfessorRoadmap?>(null)
    val roadmap: StateFlow<AssistantProfessorRoadmap?> = _roadmap.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingRoadmap = MutableStateFlow(false)
    val isLoadingRoadmap: StateFlow<Boolean> = _isLoadingRoadmap.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setError(message: String?) {
        _error.value = message
    }

    fun loadOpportunities(focus: String, name: String? = null) {
        if (focus.isBlank()) {
            _error.value = "Profile research focus is not configured. Please set your area of research in profile settings."
            return
        }
        _error.value = null
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val results = apiService.getIndustryOpportunities(focus, name)
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
            _error.value = "Profile research focus is not configured. Please set your area of research in profile settings."
            return
        }
        _error.value = null
        if (_isLoadingRoadmap.value) return
        _isLoadingRoadmap.value = true
        viewModelScope.launch {
            try {
                val result = apiService.getAssistantProfessorRoadmap(authorId, name, focus)
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
