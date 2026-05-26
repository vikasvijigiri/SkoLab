package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.open.entropy.network.ApiService

import kotlinx.serialization.Serializable

@Serializable
data class IndustryOpportunity(
    val id: String = "",
    val type: OpportunityType = OpportunityType.JOB,
    val title: String = "",
    val companyOrFunder: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
    val postedAgo: String = "",
    val url: String = ""
)

@Serializable
enum class OpportunityType {
    JOB, FUNDING, REQUIREMENT
}

class IndustryViewModel : ViewModel() {

    private val apiService = ApiService()
    
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
