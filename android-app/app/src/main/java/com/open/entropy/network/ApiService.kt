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
import io.ktor.client.statement.bodyAsText
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

@Serializable data class Authorship(val author: AuthorInfo? = null, val institutions: List<OpenAlexInstitution>? = null)

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

@Serializable
data class OpenAlexSummaryStats(
    val h_index: Int = 0,
    val i10_index: Int = 0,
    val cited_by_count: Int = 0
)

@Serializable
data class OpenAlexConcept(
    val display_name: String? = null,
    val level: Int? = null,
    val score: Double? = null
)

@Serializable
data class OpenAlexAffiliationInstitution(
    val display_name: String? = null
)

@Serializable
data class OpenAlexAffiliation(
    val institution: OpenAlexAffiliationInstitution? = null,
    val years: List<Int>? = null
)

/** Full author detail from /authors/{id} — used for direct OpenAlex fallback */
@Serializable
data class OpenAlexAuthorDetail(
    val id: String,
    val display_name: String? = null,
    val orcid: String? = null,
    val works_count: Int? = null,
    val cited_by_count: Int? = null,
    val summary_stats: OpenAlexSummaryStats? = null,
    val x_concepts: List<OpenAlexConcept>? = null,
    val last_known_institutions: List<OpenAlexInstitution>? = null,
    val affiliations: List<OpenAlexAffiliation>? = null
)


@Serializable data class RandomResponse(val random_number: Int)

@Serializable
data class Work(
    val id: String? = null,
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
    val field_of_study: String? = null,
    val h_index: Int? = null,
    val innovation_score: Int? = null
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
    // False = LLM pipeline hasn't run yet; UI should show N/A for computed metrics
    val metrics_computed: Boolean = false,
    val llm_active: Boolean = true,
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

@Serializable
data class DailyFeedItem(
    val id: String,
    val title: String,
    val authors: List<String>,
    val journal: String,
    val year: Int,
    val relevance_score: Int,
    val recommendation_reason: String,
    val doi: String? = null
)

@Serializable
data class GrantMatch(
    val title: String,
    val agency: String,
    val agency_color: String,
    val days_left: Int,
    val amount: String,
    val field: String,
    val match_score: Int,
    val url: String,
    val rationale: String
)

@Serializable
data class CollaboratorSynergy(
    val synergy_score: Int,
    val joint_proposal_title: String,
    val co_authorship_direction: String,
    val strategic_action_plan: List<String>
)

@Serializable
data class CitationHeatmap(
    val years: List<Int>,
    val citations: List<Int>,
    val works: List<Int>,
    val institutional_reach: Int,
    val h_index: Int
)

@Serializable
data class JournalRecommendation(
    val journal_name: String,
    val estimated_impact_factor: Double,
    val match_score: Int,
    val submission_tips: String
)

@Serializable
data class NetworkCollaborator(
    val id: String,
    val name: String,
    val institution: String,
    val field: String,
    val connection_path: String,
    val relevance_score: Int,
    val papers_collaborated: Int? = null,
    val total_publications: Int? = null,
    val h_index: Int? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ChatRequest(
    val author_id: String,
    val paper_title: String,
    val user_message: String,
    val history: List<ChatMessage>
)

@Serializable
data class ChatResponse(
    val author_id: String,
    val author_name: String,
    val reply: String
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

    private fun handleNetworkException(e: Exception, base: String?) {
        if (base != null && (e is java.io.IOException || 
            e.javaClass.name.contains("Timeout") || 
            e.javaClass.name.contains("Connect"))) {
            ServerLocator.reportFailure(base)
        }
    }

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
            handleNetworkException(e, base)
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
                handleNetworkException(e, base)
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
        val base = baseUrl()
        // Try backend first
        if (base != null) {
            try {
                Log.d(tag, "Searching author via backend: $name, id: $id @ $base")
                val response = httpClient.get("$base/search_author") {
                    parameter("name", name)
                    if (id != null) parameter("id", id)
                }
                val bodyText = response.bodyAsText()
                Log.d(tag, "searchAuthor raw body: $bodyText")
                if (bodyText.contains("\"error\"")) {
                    Log.w(tag, "searchAuthor backend returned error: $bodyText, falling back to direct OpenAlex query")
                    return fetchAuthorFromOpenAlex(name, id)
                }
                val result: AuthorResponse = Json { ignoreUnknownKeys = true }.decodeFromString(bodyText)
                return result
            } catch (e: Exception) {
                handleNetworkException(e, base)
                Log.w(tag, "searchAuthor backend failed, falling back to OpenAlex direct", e)
            }
        } else {
            Log.w(tag, "searchAuthor: backend not yet discovered, using OpenAlex direct")
        }
        // Fallback: fetch directly from OpenAlex — real data, no hallucination
        return fetchAuthorFromOpenAlex(name, id)
    }

    /**
     * Fetches an author profile directly from OpenAlex when the backend is unreachable.
     * Returns real data (name, institution, works) with metrics_computed=false.
     */
    private suspend fun fetchAuthorFromOpenAlex(name: String, id: String? = null): AuthorResponse? {
        return try {
            val authorData = if (id != null) {
                val cleanId = id.substringAfterLast("/")
                httpClient.get("https://api.openalex.org/authors/$cleanId") {
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body<OpenAlexAuthorDetail>()
            } else {
                val resp = httpClient.get("https://api.openalex.org/authors") {
                    parameter("search", name)
                    parameter("per_page", 1)
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body<OpenAlexAuthorsResponse>()
                val first = resp.results.firstOrNull() ?: return null
                // Re-fetch full detail by ID for complete data
                val cleanId = first.id.substringAfterLast("/")
                httpClient.get("https://api.openalex.org/authors/$cleanId") {
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body()
            }

            // Fetch recent works
            val cleanId = authorData.id.substringAfterLast("/")
            val worksResp = try {
                httpClient.get("https://api.openalex.org/works") {
                    parameter("filter", "authorships.author.id:$cleanId")
                    parameter("per_page", 20)
                    parameter("sort", "publication_year:desc")
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body<OpenAlexResponse>()
            } catch (e: Exception) {
                Log.w(tag, "fetchAuthorFromOpenAlex: works fetch failed", e)
                OpenAlexResponse(emptyList())
            }

            val institution = authorData.last_known_institutions?.firstOrNull()?.display_name
                ?: "Independent Researcher"
            val concepts = authorData.x_concepts ?: emptyList()
            val fieldOfStudy = concepts.firstOrNull { it.level == 1 }?.display_name
                ?: concepts.firstOrNull()?.display_name
                ?: "Multidisciplinary"
            val expertise = concepts.filter { it.level in listOf(1, 2) }.take(6).mapNotNull { it.display_name }

            // Build affiliations history from OpenAlex affiliations
            val affiliations = authorData.affiliations ?: emptyList()
            val histMap = mutableMapOf<String, Pair<Int, Int>>()
            affiliations.forEach { aff ->
                val instName = aff.institution?.display_name ?: return@forEach
                val years = aff.years ?: return@forEach
                if (years.isEmpty()) return@forEach
                val existing = histMap[instName]
                histMap[instName] = if (existing == null) {
                    Pair(years.min(), years.max())
                } else {
                    Pair(minOf(existing.first, years.min()), maxOf(existing.second, years.max()))
                }
            }
            val academicHistory = histMap.entries
                .sortedBy { it.value.first }
                .map { (name, years) ->
                    if (years.first == years.second) "$name (${years.first})"
                    else "$name (${years.first}\u2013${years.second})"
                }

            val works = worksResp.results.map { w ->
                Work(
                    title = w.title,
                    year = w.publication_year,
                    doi = w.doi,
                    journal = w.primary_location?.source?.display_name,
                    is_open_access = false,
                    citations = w.cited_by_count ?: 0,
                    creativity_score = 0.0,
                    complexity_score = 0.0,
                    impact_factor = 0.0,
                    disruption_score = 0.0,
                    semantic_novelty = 0.0,
                    open_science_score = 0.0,
                    authors = w.authorships?.mapNotNull { authship ->
                        val authInfo = authship.author ?: return@mapNotNull null
                        val dispName = authInfo.display_name ?: return@mapNotNull null
                        val authId = authInfo.id ?: return@mapNotNull null
                        "$dispName|$authId"
                    } ?: emptyList()
                )
            }

            val stats = authorData.summary_stats
            AuthorResponse(
                id = authorData.id,
                display_name = authorData.display_name ?: name,
                orcid = authorData.orcid,
                h_index = stats?.h_index ?: 0,
                i10_index = stats?.i10_index ?: 0,
                works_count = authorData.works_count ?: 0,
                cited_by_count = authorData.cited_by_count ?: 0,
                institution = institution,
                field_of_study = fieldOfStudy,
                expertise = expertise,
                academic_history = academicHistory,
                works = works,
                metrics_computed = false,  // LLM pipeline did not run
                next_prediction = null,
                similar_researchers = emptyList()
            )
        } catch (e: Exception) {
            Log.e(tag, "fetchAuthorFromOpenAlex failed", e)
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
            handleNetworkException(e, base)
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
            handleNetworkException(e, base)
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
            handleNetworkException(e, base)
            Log.e(tag, "analyzePaper failed", e)
            null
        }
    }

    /**
     * Fetches trending/highly-cited papers from OpenAlex for this year.
     * Results are cached in-memory for 30 minutes.
     */
    suspend fun getTrendingPapers(focus: String? = null, limit: Int = 8): List<OpenAlexWork> {
        val now = System.currentTimeMillis()
        val cached = trendingPapersCache
        if (focus == null && cached != null && (now - trendingPapersCacheTime) < TRENDING_CACHE_TTL_MS) {
            Log.d(tag, "Cache hit for trending papers")
            return cached
        }
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val prevYear = year - 1
        return try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                parameter("filter", "publication_year:$prevYear|$year")
                if (!focus.isNullOrBlank()) {
                    parameter("search", focus)
                }
                parameter("sort", "cited_by_count:desc")
                parameter("per_page", limit)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            val results = response.results
            if (focus == null) {
                trendingPapersCache = results
                trendingPapersCacheTime = now
            }
            results
        } catch (e: Exception) {
            Log.e(tag, "getTrendingPapers (OpenAlex) failed", e)
            // Fallback: search for highly-cited ML papers as a graceful degradation
            try {
                val fallback: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                    parameter("search", focus ?: "machine learning deep learning")
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
     * Fetches recent works of a specific author directly from OpenAlex.
     */
    suspend fun getAuthorWorks(authorId: String, limit: Int = 3): List<OpenAlexWork> {
        val cleanId = authorId.substringAfterLast("/")
        return try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                parameter("filter", "authorships.author.id:$cleanId")
                parameter("per_page", limit)
                parameter("sort", "publication_year:desc,cited_by_count:desc")
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            response.results
        } catch (e: Exception) {
            Log.e(tag, "getAuthorWorks (OpenAlex) failed for authorId: $authorId", e)
            emptyList()
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
                handleNetworkException(e, base)
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
        val nonPersonKeywords = setOf(
            "collaboration", "group", "consortium", "committee", "team", "network", "project", 
            "society", "association", "institute", "university", "department", "lab", "laboratory", 
            "center", "centre", "foundation", "quantum", "topology", "invariants", "materials", 
            "systems", "physics", "biology", "chemistry", "science", "computing", "theory", 
            "applications", "methods", "frontiers", "research"
        )
        
        fun isNonPerson(name: String): Boolean {
            val lower = name.lowercase()
            return nonPersonKeywords.any { lower.contains(it) }
        }
        
        fun isValidName(name: String): Boolean {
            val trimmed = name.trim()
            val words = trimmed.split(Regex("\\s+"))
            return words.size in 2..4 && !isNonPerson(trimmed)
        }

        val suggestions = mutableListOf<AuthorSuggestion>()
        try {
            // 1. Try direct authors search first
            val authorResponse: OpenAlexAuthorsResponse = httpClient.get("https://api.openalex.org/authors") {
                parameter("search", query)
                parameter("per_page", limit + 10)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            
            for (author in authorResponse.results) {
                val dispName = author.display_name ?: continue
                if (!isValidName(dispName)) continue
                
                val inst = author.last_known_institutions?.firstOrNull()?.display_name ?: "Independent Researcher"
                suggestions.add(
                    AuthorSuggestion(
                        id = author.id,
                        display_name = dispName,
                        institution = inst
                    )
                )
            }
            
            // 2. If not enough individual researchers, search works and extract authors
            if (suggestions.size < 4) {
                Log.i(tag, "fetchSimilarAuthorsFromOpenAlex: insufficient human authors for '$query', searching works...")
                val worksResponse: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                    parameter("search", query)
                    parameter("per_page", 20)
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body()
                
                val seenIds = suggestions.map { it.id }.toMutableSet()
                for (work in worksResponse.results) {
                    val authorships = work.authorships ?: continue
                    for (authorship in authorships) {
                        val authInfo = authorship.author ?: continue
                        val authId = authInfo.id ?: continue
                        val authName = authInfo.display_name ?: continue
                        
                        if (authId in seenIds || !isValidName(authName)) continue
                        
                        // Extract institution name safely
                        val inst = authorship.institutions?.firstOrNull()?.display_name ?: "Independent Researcher"
                        
                        seenIds.add(authId)
                        suggestions.add(
                            AuthorSuggestion(
                                id = authId,
                                display_name = authName,
                                institution = inst
                            )
                        )
                        if (suggestions.size >= limit) break
                    }
                    if (suggestions.size >= limit) break
                }
            }
            
            return suggestions.take(limit)
        } catch (e: Exception) {
            Log.e(tag, "fetchSimilarAuthorsFromOpenAlex failed", e)
            return emptyList()
        }
    }

    suspend fun getDailyFeed(authorId: String?, queryFallback: String? = null): List<DailyFeedItem> {
        val base = baseUrl() ?: return emptyList()
        return try {
            httpClient.get("$base/daily_feed") {
                authorId?.let { parameter("author_id", it) }
                queryFallback?.let { parameter("query_fallback", it) }
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getDailyFeed failed", e)
            emptyList()
        }
    }

    suspend fun matchGrants(authorId: String): List<GrantMatch> {
        val base = baseUrl() ?: return emptyList()
        return try {
            httpClient.get("$base/match_grants") {
                parameter("author_id", authorId)
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "matchGrants failed", e)
            emptyList()
        }
    }

    suspend fun getCollaboratorSynergy(authorId: String, collaboratorId: String): CollaboratorSynergy? {
        val base = baseUrl() ?: return null
        return try {
            httpClient.get("$base/collaborator_synergy") {
                parameter("author_id", authorId)
                parameter("collaborator_id", collaboratorId)
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getCollaboratorSynergy failed", e)
            null
        }
    }

    suspend fun getCitationHeatmap(authorId: String): CitationHeatmap? {
        val base = baseUrl() ?: return null
        return try {
            httpClient.get("$base/citation_heatmap") {
                parameter("author_id", authorId)
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getCitationHeatmap failed", e)
            null
        }
    }

    suspend fun getJournalAdvisor(authorId: String): List<JournalRecommendation> {
        val base = baseUrl() ?: return emptyList()
        return try {
            httpClient.get("$base/journal_advisor") {
                parameter("author_id", authorId)
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getJournalAdvisor failed", e)
            emptyList()
        }
    }

    suspend fun getNetworkCollaborators(authorId: String, limit: Int = 50, excludeIds: List<String> = emptyList()): List<NetworkCollaborator> {
        val base = baseUrl() ?: return emptyList()
        return try {
            httpClient.get("$base/network_collaborators") {
                parameter("author_id", authorId)
                parameter("limit", limit)
                if (excludeIds.isNotEmpty()) {
                    parameter("exclude_ids", excludeIds.joinToString(","))
                }
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getNetworkCollaborators failed", e)
            emptyList()
        }
    }

    suspend fun chatWithAuthor(
        authorId: String,
        paperTitle: String,
        userMessage: String,
        history: List<ChatMessage>
    ): ChatResponse? {
        val base = baseUrl() ?: return null
        return try {
            httpClient.post("$base/chat_with_author") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(
                    author_id = authorId,
                    paper_title = paperTitle,
                    user_message = userMessage,
                    history = history
                ))
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "chatWithAuthor failed", e)
            null
        }
    }
}
