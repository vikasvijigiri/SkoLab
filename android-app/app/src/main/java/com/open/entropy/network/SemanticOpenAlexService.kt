package com.open.entropy.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.open.entropy.network.OpenAlexWork
import com.open.entropy.network.OpenAlexResponse

class SemanticOpenAlexService {
    private val tag = "SemanticOpenAlexService"
    
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(lenientJson)
        }
    }

    suspend fun getSemanticPapers(query: String, limit: Int = 10): List<OpenAlexWork> {
        return try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                // Semantic Search via openalex
                parameter("search.semantic", query)
                parameter("per_page", limit)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            response.results
        } catch (e: Exception) {
            Log.e(tag, "getSemanticPapers failed", e)
            
            // Fallback to default search if search.semantic fails (e.g. requires premium API key or is down)
            try {
                val fallbackResponse: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                    parameter("filter", "default.search:\"$query\"")
                    parameter("per_page", limit)
                    parameter("sort", "cited_by_count:desc")
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body()
                fallbackResponse.results
            } catch (ex: Exception) {
                 Log.e(tag, "getSemanticPapers fallback failed", ex)
                 emptyList()
            }
        }
    }
}
