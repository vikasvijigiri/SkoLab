package com.open.entropy.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── Data models ───────────────────────────────────────────────────────────────

@Serializable
data class OpenAlexWork(
    val id: String,
    val doi: String? = null,
    val title: String? = null,
    val publication_year: Int? = null,
    val authorships: List<Authorship>? = null,
    val cited_by_count: Int? = 0,
    val primary_location: PrimaryLocation? = null,
    val abstract_inverted_index: Map<String, List<Int>>? = null,
    val primary_topic: OpenAlexTopic? = null
)

@Serializable
data class OpenAlexTopic(
    val display_name: String? = null,
    val field: OpenAlexTopicField? = null
)

@Serializable
data class OpenAlexTopicField(
    val display_name: String? = null
)

@Serializable data class Authorship(val author: AuthorInfo? = null)

@Serializable
data class AuthorInfo(
    val id: String? = null,
    val display_name: String? = null
)

@Serializable
data class PrimaryLocation(
    val source: SourceInfo? = null,
    val landing_page_url: String? = null,
    val pdf_url: String? = null
)

@Serializable data class SourceInfo(val display_name: String? = null)

@Serializable data class OpenAlexResponse(val results: List<OpenAlexWork>)

@Serializable
data class OpenAlexAuthor(
    val id: String,
    val display_name: String? = null,
    val last_known_institutions: List<OpenAlexInstitution>? = null
)

@Serializable
data class OpenAlexInstitution(
    val display_name: String? = null
)

@Serializable
data class OpenAlexAuthorsResponse(
    val results: List<OpenAlexAuthor>
)

@Serializable data class RandomResponse(val random_number: Int)

@Serializable
data class Work(
    val title: String? = null,
    val year: Int? = null,
    val doi: String? = null,
    val journal: String? = null,
    val is_open_access: Boolean? = false,
    val citations: Int? = 0,
    val creativity_score: Double? = 0.0,
    val complexity_score: Double? = 0.0,
    val impact_factor: Double? = 0.0,
    val disruption_score: Double? = 0.0,
    val semantic_novelty: Double? = 0.0,
    val open_science_score: Double? = 0.0,
    val authors: List<String>? = null
)

@Serializable
data class AuthorSuggestion(
    val id: String,
    val display_name: String,
    val institution: String,
    val field_of_study: String? = null
)

@Serializable
data class AuthorResponse(
    val id: String,
    val display_name: String,
    val orcid: String? = null,
    val h_index: Int,
    val i10_index: Int,
    val works_count: Int,
    val cited_by_count: Int,
    val institution: String,
    val field_of_study: String? = null,
    val expertise: List<String> = emptyList(),
    val academic_history: List<String>,
    val works: List<Work>,
    val average_creativity: Double = 0.0,
    val average_complexity: Double = 0.0,
    val average_activity: Double = 0.0,
    val average_skill_score: Double = 0.0,
    val average_impact: Double = 0.0,
    val innovation_score: Double? = 0.0,
    val disruption_score: Double = 0.0,
    val citation_acceleration: Double = 0.0,
    val future_impact_score: Double = 0.0,
    val network_centrality: Double = 0.0,
    val semantic_novelty: Double = 0.0,
    val interdisciplinary_index: Double = 0.0,
    val policy_patent_score: Double = 0.0,
    val open_science_score: Double = 0.0,
    val collaboration_diversity: Double = 0.0,
    val research_consistency: Double = 0.0,
    val next_prediction: String? = null,
    val top_experimental_tools: List<ToolUsage> = emptyList(),
    val similar_researchers: List<AuthorSuggestion> = emptyList()
)

@Serializable
data class ToolUsage(
    val name: String,
    val frequency: Int,
    val category: String
)

@Serializable
data class Metrics(
    val creativity: Int,
    val complexity: Int,
    val skill_set_score: Int
)

@Serializable
data class SummaryResponse(
    val bullets: List<String>,
    val metrics: Metrics,
    val top_skills: List<String>
)

// Mapped from PaperIntelligence.kt — using the model class directly
// (no duplicate serializable needed — PaperIntelligence is @Serializable)

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * ApiService — all network calls to the ResQit backend.
 *
 * The backend URL is never hardcoded here.  It is read from [ServerLocator.baseUrl]
 * on every request.  If the server has not been discovered yet, calls return a
 * sensible empty/null result so the UI can show a "Searching for backend…" state.
 */
class ApiService {

    private val tag = "ApiService"

    companion object {
        // Caches suggestions (display name / key -> Suggestions list)
        private val authorSuggestionsCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, List<AuthorSuggestion>>(100, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AuthorSuggestion>>?): Boolean {
                    return size > 100
                }
            }
        )

        // Caches paper search results (search term -> Paper list)
        private val paperSearchCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, List<OpenAlexWork>>(50, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OpenAlexWork>>?): Boolean {
                    return size > 50
                }
            }
        )

        // Caches trending papers (TTL-like: keyed by date string)
        @Volatile private var trendingPapersCache: List<OpenAlexWork>? = null
        @Volatile private var trendingPapersCacheTime: Long = 0L
        private const val TRENDING_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

        // Caches similar authors by field query
        private val similarAuthorsCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, List<AuthorSuggestion>>(30, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AuthorSuggestion>>?): Boolean {
                    return size > 30
                }
            }
        )
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the current backend base URL, or null if not yet discovered.
     * Callers should treat null as "server unavailable" and return empty/null.
     */
    private fun baseUrl(): String? = ServerLocator.baseUrl.value

    // ── Backend endpoints ─────────────────────────────────────────────────────

    suspend fun getRandomNumber(): Int {
        val base = baseUrl() ?: run {
            Log.w(tag, "getRandomNumber: backend not yet discovered")
            return -1
        }
        return try {
            val response: RandomResponse = httpClient.get("$base/random").body()
            response.random_number
        } catch (e: Exception) {
            Log.e(tag, "getRandomNumber failed", e)
            -1
        }
    }

    suspend fun getAuthorSuggestions(query: String): List<AuthorSuggestion> {
        if (query.length < 3) return emptyList()
        val cacheKey = query.trim().lowercase()
        val cached = authorSuggestionsCache[cacheKey]
        if (cached != null) {
            Log.d(tag, "Cache hit for author suggestions: $cacheKey")
            return cached
        }

        val base = baseUrl()
        val results = if (base != null) {
            try {
                httpClient.get("$base/author_suggestions") {
                    parameter("query", query)
                }.body()
            } catch (e: Exception) {
                Log.e(tag, "getAuthorSuggestions via backend failed, falling back to direct OpenAlex query", e)
                fetchDirectAuthorSuggestions(query)
            }
        } else {
            Log.w(tag, "getAuthorSuggestions: backend not yet discovered, falling back to direct OpenAlex query")
            fetchDirectAuthorSuggestions(query)
        }

        if (results.isNotEmpty()) {
            authorSuggestionsCache[cacheKey] = results
        }
        return results
    }

    private suspend fun fetchDirectAuthorSuggestions(query: String): List<AuthorSuggestion> {
        return try {
            val response: OpenAlexAuthorsResponse = httpClient.get("https://api.openalex.org/authors") {
                parameter("search", query)
                parameter("per_page", 10)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            
            response.results.map { author ->
                AuthorSuggestion(
                    id = author.id,
                    display_name = author.display_name ?: "Unknown",
                    institution = author.last_known_institutions?.firstOrNull()?.display_name ?: "Independent Researcher"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "getAuthorSuggestions (OpenAlex fallback) failed", e)
            emptyList()
        }
    }

    suspend fun searchAuthor(name: String, id: String? = null): AuthorResponse? {
        val base = baseUrl() ?: run {
            Log.w(tag, "searchAuthor: backend not yet discovered")
            return null
        }
        return try {
            Log.d(tag, "Searching author: $name, id: $id @ $base")
            httpClient.get("$base/search_author") {
                parameter("name", name)
                if (id != null) {
                    parameter("id", id)
                }
            }.body()
        } catch (e: Exception) {
            Log.e(tag, "searchAuthor failed", e)
            null
        }
    }

    suspend fun summarizeWork(title: String, doi: String?): SummaryResponse? {
        val base = baseUrl() ?: run {
            Log.w(tag, "summarizeWork: backend not yet discovered")
            return null
        }
        return try {
            httpClient.get("$base/summarize_work") {
                parameter("title", title)
                doi?.let { parameter("doi", it) }
            }.body()
        } catch (e: Exception) {
            Log.e(tag, "summarizeWork failed", e)
            null
        }
    }

    suspend fun refreshAuthor(name: String): Boolean {
        val base = baseUrl() ?: run {
            Log.w(tag, "refreshAuthor: backend not yet discovered")
            return false
        }
        return try {
            httpClient.get("$base/refresh_author") {
                parameter("name", name)
            }.status.value == 200
        } catch (e: Exception) {
            Log.e(tag, "refreshAuthor failed", e)
            false
        }
    }

    // ── External APIs (OpenAlex — no backend needed) ──────────────────────────

    suspend fun searchPapers(query: String): List<OpenAlexWork> {
        if (query.isBlank()) return emptyList()
        val cacheKey = query.trim().lowercase()
        val cached = paperSearchCache[cacheKey]
        if (cached != null) {
            Log.d(tag, "Cache hit for paper search: $cacheKey")
            return cached
        }

        val results = try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                parameter("search", query)
            }.body()
            response.results
        } catch (e: Exception) {
            Log.e(tag, "searchPapers (OpenAlex) failed", e)
            emptyList()
        }

        if (results.isNotEmpty()) {
            paperSearchCache[cacheKey] = results
        }
        return results
    }

    suspend fun getPaperDetails(openAlexId: String): OpenAlexWork? {
        return try {
            val id = openAlexId.substringAfterLast("/")
            httpClient.get("https://api.openalex.org/works/$id").body()
        } catch (e: Exception) {
            Log.e(tag, "getPaperDetails (OpenAlex) failed", e)
            null
        }
    }

    /**
     * Calls the backend /analyze_paper endpoint which:
     *   1. Downloads the full paper PDF (open-access sources)
     *   2. Reads the complete text with pdfplumber
     *   3. Runs the Research Intelligence Agent LLM (9-section structured extraction)
     *
     * Falls back to abstract-only analysis when no PDF is accessible.
     * Results are cached server-side for 6 hours.
     */
    suspend fun analyzePaper(
        title: String,
        doi: String? = null,
        openAlexId: String? = null
    ): com.open.entropy.model.PaperIntelligence? {
        val base = baseUrl() ?: run {
            Log.w(tag, "analyzePaper: backend not reachable, skipping")
            return null
        }
        return try {
            httpClient.get("$base/analyze_paper") {
                parameter("title", title)
                if (!doi.isNullOrBlank())        parameter("doi", doi)
                if (!openAlexId.isNullOrBlank()) parameter("openalex_id", openAlexId)
            }.body()
        } catch (e: Exception) {
            Log.e(tag, "analyzePaper failed", e)
            null
        }
    }

    /**
     * Fetches trending/highly-cited papers from OpenAlex for this year.
     * Results are cached in-memory for 30 minutes.
     */
    suspend fun getTrendingPapers(limit: Int = 8): List<OpenAlexWork> {
        val now = System.currentTimeMillis()
        val cached = trendingPapersCache
        if (cached != null && (now - trendingPapersCacheTime) < TRENDING_CACHE_TTL_MS) {
            Log.d(tag, "Cache hit for trending papers")
            return cached
        }
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                parameter("filter", "publication_year:$year")
                parameter("sort", "cited_by_count:desc")
                parameter("per_page", limit)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            val results = response.results
            trendingPapersCache = results
            trendingPapersCacheTime = now
            results
        } catch (e: Exception) {
            Log.e(tag, "getTrendingPapers (OpenAlex) failed", e)
            // Fallback: search for highly-cited ML papers as a graceful degradation
            try {
                val fallback: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                    parameter("search", "machine learning deep learning")
                    parameter("sort", "cited_by_count:desc")
                    parameter("per_page", limit)
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body()
                fallback.results
            } catch (e2: Exception) {
                Log.e(tag, "getTrendingPapers fallback failed", e2)
                emptyList()
            }
        }
    }

    /**
     * Fetches researchers with a similar field as the given query.
     * Used to populate the Nexus Collaboration Radar card.
     */
    suspend fun getSimilarAuthors(fieldQuery: String, limit: Int = 5): List<AuthorSuggestion> {
        val cacheKey = fieldQuery.trim().lowercase()
        similarAuthorsCache[cacheKey]?.let { return it }

        // Try backend first (it has the field-aware query logic)
        val base = baseUrl()
        val results: List<AuthorSuggestion> = if (base != null) {
            try {
                val list: List<AuthorSuggestion> = httpClient.get("$base/author_suggestions") {
                    parameter("query", fieldQuery)
                }.body()
                list.take(limit)
            } catch (e: Exception) {
                Log.w(tag, "getSimilarAuthors via backend failed, falling back to OpenAlex", e)
                fetchSimilarAuthorsFromOpenAlex(fieldQuery, limit)
            }
        } else {
            fetchSimilarAuthorsFromOpenAlex(fieldQuery, limit)
        }

        if (results.isNotEmpty()) {
            similarAuthorsCache[cacheKey] = results
        }
        return results
    }

    private suspend fun fetchSimilarAuthorsFromOpenAlex(query: String, limit: Int): List<AuthorSuggestion> {
        return try {
            val response: OpenAlexAuthorsResponse = httpClient.get("https://api.openalex.org/authors") {
                parameter("search", query)
                parameter("per_page", limit + 2) // fetch extra in case we need to filter
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            response.results.take(limit).map { author ->
                AuthorSuggestion(
                    id = author.id,
                    display_name = author.display_name ?: "Unknown",
                    institution = author.last_known_institutions?.firstOrNull()?.display_name
                        ?: "Independent Researcher"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "fetchSimilarAuthorsFromOpenAlex failed", e)
            emptyList()
        }
    }
}
