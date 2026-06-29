package com.company.skolab.repository

import com.company.skolab.di.AppDependencies
import com.company.skolab.model.AssistantProfessorRoadmap
import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.network.ApiService

interface IndustryRepository {
    suspend fun getOpportunities(focus: String, name: String?): List<IndustryOpportunity>
    suspend fun getRoadmap(authorId: String?, name: String, focus: String): AssistantProfessorRoadmap?
}

class DefaultIndustryRepository(
    private val apiService: ApiService = AppDependencies.apiService
) : IndustryRepository {

    override suspend fun getOpportunities(focus: String, name: String?): List<IndustryOpportunity> =
        apiService.getIndustryOpportunities(focus, name)

    override suspend fun getRoadmap(
        authorId: String?,
        name: String,
        focus: String
    ): AssistantProfessorRoadmap? =
        apiService.getAssistantProfessorRoadmap(authorId, name, focus)
}
