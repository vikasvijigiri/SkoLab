package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.open.skolab.di.AppDependencies
import com.open.skolab.model.IndustryOpportunity


class IndustryViewModel : ViewModel() {

    private val apiService = AppDependencies.apiService
    
    private val _opportunities = MutableStateFlow<List<IndustryOpportunity>>(emptyList())
    val opportunities: StateFlow<List<IndustryOpportunity>> = _opportunities.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadOpportunities(focus: String) {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val results = apiService.getIndustryOpportunities(focus)
                _opportunities.value = results
            } catch (e: Exception) {
                // Keep old opportunities if fetch fails
            } finally {
                _isLoading.value = false
            }
        }
    }
}
