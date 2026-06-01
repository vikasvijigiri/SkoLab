package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.open.skolab.di.AppDependencies
import com.open.skolab.model.IndustryOpportunity
import com.open.skolab.model.AssistantProfessorRoadmap

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

    fun loadOpportunities(focus: String, name: String? = null) {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val results = apiService.getIndustryOpportunities(focus, name)
                _opportunities.value = results
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadRoadmap(authorId: String?, name: String, focus: String) {
        if (_isLoadingRoadmap.value) return
        _isLoadingRoadmap.value = true
        viewModelScope.launch {
            try {
                val result = apiService.getAssistantProfessorRoadmap(authorId, name, focus)
                _roadmap.value = result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                _isLoadingRoadmap.value = false
            }
        }
    }
}
