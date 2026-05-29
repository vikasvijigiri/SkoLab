package com.open.skolab.network

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
import kotlinx.coroutines.*
import android.content.Context
import android.net.Uri
import io.ktor.client.request.forms.*
import com.open.skolab.viewmodel.IndustryOpportunity

// ── Data models ───────────────────────────────────────────────────────────────

@Serializable
data class AiStatusResponse(
    val groq_api_configured: Boolean,
    val llm_active: Boolean,
    val model: String,
    val key_prefix: String
)

@Serializable
data class UploadDocumentResponse(
    val filename: String,
    val extracted_text: String
)

@Serializable
data class AuthorMetrics(
    val overall_score: Int = 0,
    val topic_toughness: Int = 0,
    val velocity: Int = 0,
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val analysis: String = ""
)

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

@Serializable data class OpenAlexResponse(val results: List<OpenAlexWork> = emptyList())

@Serializable
data class OpenAlexAuthor(
    val id: String,
    val display_name: String? = null,
    val last_known_institutions: List<OpenAlexInstitution>? = null,
    val summary_stats: OpenAlexSummaryStats? = null,
    val works_count: Int? = null
)

@Serializable
data class OpenAlexInstitution(
    val display_name: String? = null
)

@Serializable data class OpenAlexAuthorsResponse(
    val results: List<OpenAlexAuthor> = emptyList()
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
    val timestamp: Long = System.currentTimeMillis(),
    val reaction: String? = null,
    val isStarred: Boolean = false
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

@Serializable
data class AgentChatRequest(
    val message: String,
    val history: List<Map<String, String>>,
    val mode: String,
    val user_memory: Map<String, kotlinx.serialization.json.JsonElement>? = null
)

// ── User Memory Models ────────────────────────────────────────────────────────

@Serializable
data class ActivityEventRequest(
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
    val paperTitle: String? = null,
    val paperDomain: String? = null,
    val paperJournal: String? = null,
    val durationSeconds: Int? = null,
    val authorName: String? = null,
    val authorInstitution: String? = null,
    val query: String? = null,
    val filterValue: String? = null,
    val collaboratorName: String? = null,
    val collaboratorField: String? = null
)

@Serializable
data class UserMemoryEventsPayload(
    val user_id: String,
    val events: List<ActivityEventRequest>
)

@Serializable
data class UserMemoryProfileResponse(
    val user_id: String = "",
    val top_topics: List<String> = emptyList(),
    val active_hours: List<Int> = emptyList(),
    val reading_pace: String = "unknown",
    val research_style: String = "unknown",
    val avg_read_minutes: Float = 0f,
    val unfinished_papers: List<String> = emptyList(),
    val recently_read_papers: List<String> = emptyList(),
    val frequent_collaborators: List<String> = emptyList(),
    val frequent_search_terms: List<String> = emptyList(),
    val last_active_topic: String = "",
    val total_papers_read: Int = 0,
    val last_updated: Long = 0
)

@Serializable
data class AgentChatResponse(
    val reply: String
)

// Mapped from PaperIntelligence.kt — using the model class directly
// (no duplicate serializable needed — PaperIntelligence is @Serializable)

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * ApiService — all network calls to the SkoLab backend.
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

        // Caches author profile details (authorId/displayName -> AuthorResponse)
        val authorProfileCache: MutableMap<String, AuthorResponse> = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, AuthorResponse>(50, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AuthorResponse>?): Boolean {
                    return size > 50
                }
            }
        )
    }

    fun getCachedAuthorProfile(key: String): AuthorResponse? {
        return null
    }

    fun cacheAuthorProfile(key: String, profile: AuthorResponse) {
        // no-op: caching is disabled
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(lenientJson)
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
        
        ServerLocator.appContext?.let { context ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                val msg = "Backend Exception: ${e.localizedMessage ?: e.toString()}"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Backend endpoints ─────────────────────────────────────────────────────

    suspend fun checkAiStatus(): Boolean {
        val base = baseUrl() ?: return true
        return try {
            val response = httpClient.get("$base/ai_status")
            val decoded = lenientJson.decodeFromString<AiStatusResponse>(response.bodyAsText())
            decoded.llm_active
        } catch (e: Exception) {
            handleNetworkException(e, base)
            true // default to true if backend is unreachable so we don't spam errors
        }
    }

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
        val base = baseUrl() ?: throw Exception("SkoLab backend services are currently unreachable.")
        try {
            return httpClient.get("$base/author_suggestions") {
                parameter("query", query)
            }.body()
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getAuthorSuggestions failed", e)
            throw e
        }
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

    suspend fun searchAuthor(name: String, id: String? = null, forceRefresh: Boolean = false): AuthorResponse? {
        val mappedName = name
        val base = baseUrl()
        if (base == null) {
            Log.i(tag, "searchAuthor: backend base URL is null. Querying OpenAlex directly...")
            return fetchAuthorFromOpenAlex(mappedName, id)
        }
        try {
            Log.d(tag, "Searching author via backend: $mappedName, id: $id @ $base")
            val response = httpClient.get("$base/search_author") {
                parameter("name", mappedName)
                if (id != null) parameter("id", id)
            }
            val bodyText = response.bodyAsText()
            Log.d(tag, "searchAuthor raw body: $bodyText")
            val decoded: AuthorResponse = lenientJson.decodeFromString(bodyText)
            val sanitizedExpertise = decoded.expertise.filter { !it.contains("psychology", ignoreCase = true) }
            val sanitizedField = if (decoded.field_of_study?.contains("psychology", ignoreCase = true) == true) {
                null
            } else {
                decoded.field_of_study
            }
            return decoded.copy(
                field_of_study = sanitizedField,
                expertise = sanitizedExpertise
            )
        } catch (e: Exception) {
            Log.e(tag, "searchAuthor backend failed, falling back to direct OpenAlex query", e)
            try {
                return fetchAuthorFromOpenAlex(mappedName, id)
            } catch (ex: Exception) {
                handleNetworkException(e, base)
                throw e
            }
        }
    }

    /**
     * Fetches an author profile directly from OpenAlex when the backend is unreachable.
     * Returns real data (name, institution, works) with metrics_computed=false.
     */
    private suspend fun fetchAuthorFromOpenAlex(name: String, id: String? = null): AuthorResponse? {
        val mappedName = name
        return try {
            val authorData = if (id != null) {
                val cleanId = id.substringAfterLast("/")
                httpClient.get("https://api.openalex.org/authors/$cleanId") {
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body<OpenAlexAuthorDetail>()
            } else {
                val resp = httpClient.get("https://api.openalex.org/authors") {
                    parameter("search", mappedName)
                    parameter("per_page", 1)
                    parameter("mailto", "vikki.4me@gmail.com")
                }.body<OpenAlexAuthorsResponse>()
                val first = resp.results.firstOrNull() ?: return null
                
                // Name Mismatch Collision Check
                // Ensure the returned author name contains all distinct long tokens of the search query
                val queryTokens = mappedName.lowercase().split(" ").map { it.trim() }.filter { it.length > 2 }
                val returnedNameLower = (first.display_name ?: "").lowercase()
                val matchValid = if (queryTokens.isNotEmpty()) {
                    queryTokens.all { returnedNameLower.contains(it) }
                } else {
                    true
                }
                
                if (!matchValid) {
                    Log.i(tag, "fetchAuthorFromOpenAlex: Name mismatch collision! Queried: $mappedName, Returned: ${first.display_name}. Ignoring profile.")
                    return null
                }
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
            val concepts = (authorData.x_concepts ?: emptyList()).filter { 
                !it.display_name.isNullOrBlank() && !it.display_name.contains("psychology", ignoreCase = true)
            }
            val rawField = concepts.firstOrNull { it.level == 1 }?.display_name
                ?: concepts.firstOrNull()?.display_name
            val fieldOfStudy = if (rawField?.contains("psychology", ignoreCase = true) == true) null else rawField
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
    ): com.open.skolab.model.PaperIntelligence? {
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
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val prevYear = year - 1
        return try {
            val response: OpenAlexResponse = httpClient.get("https://api.openalex.org/works") {
                parameter("filter", "publication_year:$prevYear|$year")
                if (!focus.isNullOrBlank()) {
                    // OpenAlex semantic search: use default.search param and don't force cited_by sort
                    parameter("default.search", focus)
                    parameter("sort", "relevance_score:desc")
                } else {
                    // Fallback to highest cited for global generic lists
                    parameter("sort", "cited_by_count:desc")
                }
                parameter("per_page", limit)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body()
            response.results
        } catch (e: Exception) {
            Log.e(tag, "getTrendingPapers (OpenAlex) failed", e)
            throw e
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

    suspend fun getDailyConjecture(authorId: String, name: String): com.open.skolab.model.Conjecture? {
        val base = baseUrl() ?: throw Exception("SkoLab backend services are currently unreachable.")
        try {
            Log.d(tag, "Fetching daily conjecture from backend for name: $name, id: $authorId")
            val response = httpClient.get("$base/daily_conjecture") {
                parameter("author_id", authorId)
                parameter("name", name)
            }
            if (response.status.value == 200) {
                return response.body<com.open.skolab.model.Conjecture>()
            } else {
                throw Exception("Failed to fetch daily conjecture (status: ${response.status.value})")
            }
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getDailyConjecture backend failed", e)
            throw e
        }
    }


    /**
     * Fetches researchers with a similar field as the given query.
     * Used to populate the Nexus Collaboration Radar card.
     */
    suspend fun getSimilarAuthors(fieldQuery: String, limit: Int = 5): List<AuthorSuggestion> {
        val base = baseUrl() ?: throw Exception("SkoLab backend services are currently unreachable.")
        try {
            val list: List<AuthorSuggestion> = httpClient.get("$base/author_suggestions") {
                parameter("query", fieldQuery)
            }.body()
            return list.take(limit)
        } catch (e: Exception) {
            handleNetworkException(e, base)
            Log.e(tag, "getSimilarAuthors failed", e)
            throw e
        }
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

    suspend fun getNetworkCollaborators(
        authorId: String,
        limit: Int = 10,
        offset: Int = 0,
        excludeIds: List<String> = emptyList(),
        excludeName: String? = null
    ): List<NetworkCollaborator> = coroutineScope {
        val cleanId = authorId.substringAfterLast("/")
        if (cleanId.isBlank() || cleanId == "fallback_seed") {
            return@coroutineScope emptyList<NetworkCollaborator>()
        }

        try {
            // 1. Fetch works of primary author from OpenAlex
            val worksUrl = "https://api.openalex.org/works"
            val worksResponse = httpClient.get(worksUrl) {
                parameter("filter", "authorships.author.id:$cleanId")
                parameter("per_page", 50)
                parameter("mailto", "vikki.4me@gmail.com")
            }.body<OpenAlexResponse>()

            val excludeSet = (excludeIds.map { it.substringAfterLast("/") } + cleanId).toSet()

            // Depth 1: clean_id -> properties
            val depth1Map = mutableMapOf<String, MutableMap<String, Any>>()

            for (work in worksResponse.results) {
                val workTitle = work.title ?: "Research Paper"
                val workField = work.primary_topic?.display_name 
                    ?: work.primary_topic?.field?.display_name 
                    ?: "Research"

                val authorships = work.authorships ?: emptyList()
                for (authShip in authorships) {
                    val author = authShip.author ?: continue
                    val authId = author.id ?: continue
                    val authClean = authId.substringAfterLast("/")
                    if (authClean !in excludeSet) {
                        val name = author.display_name ?: "Unknown"
                        val instName = authShip.institutions?.firstOrNull()?.display_name 
                            ?: "Independent Researcher"

                        val existing = depth1Map[authClean]
                        if (existing == null) {
                            depth1Map[authClean] = mutableMapOf(
                                "id" to authId,
                                "name" to name,
                                "institution" to instName,
                                "field" to workField,
                                "shared_paper" to workTitle,
                                "joint_count" to 1
                            )
                        } else {
                            existing["joint_count"] = (existing["joint_count"] as Int) + 1
                        }
                    }
                }
            }

            val d1List = depth1Map.values.toList()

            // Fetch works for top 5 co-authors in parallel to get Depth 2
            val d1Subset = d1List.sortedByDescending { it["joint_count"] as Int }.take(5)
            val depth2Map = mutableMapOf<String, MutableMap<String, Any>>()

            if (d1Subset.isNotEmpty()) {
                val d2Deferred = d1Subset.map { d1 ->
                    val d1CleanId = (d1["id"] as String).substringAfterLast("/")
                    async(Dispatchers.IO) {
                        try {
                            httpClient.get(worksUrl) {
                                parameter("filter", "authorships.author.id:$d1CleanId")
                                parameter("per_page", 15)
                                parameter("mailto", "vikki.4me@gmail.com")
                            }.body<OpenAlexResponse>().results
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }

                val d2ResultsList = d2Deferred.awaitAll()

                for (i in d1Subset.indices) {
                    val d1 = d1Subset[i]
                    val d1Name = d1["name"] as String
                    val works = d2ResultsList[i]
                    for (work in works) {
                        val workField = work.primary_topic?.display_name 
                            ?: work.primary_topic?.field?.display_name 
                            ?: "Collaborator"

                        val authorships = work.authorships ?: emptyList()
                        for (authShip in authorships) {
                            val author = authShip.author ?: continue
                            val authId = author.id ?: continue
                            val authClean = authId.substringAfterLast("/")
                            if (authClean !in excludeSet && authClean !in depth1Map.keys) {
                                val name = author.display_name ?: "Unknown"
                                val instName = authShip.institutions?.firstOrNull()?.display_name 
                                    ?: "Independent Researcher"

                                val existing = depth2Map[authClean]
                                if (existing == null) {
                                    depth2Map[authClean] = mutableMapOf(
                                        "id" to authId,
                                        "name" to name,
                                        "institution" to instName,
                                        "field" to workField,
                                        "connection_path" to "Collaborates with $d1Name (connected via you)",
                                        "joint_count" to 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Gather all author IDs to fetch h-index and works count in batches of 50
            val allCollaborators = depth1Map.values.toList() + depth2Map.values.toList()
            val allIds = allCollaborators.map { (it["id"] as String).substringAfterLast("/") }
            val statsMap = mutableMapOf<String, Pair<Int, Int>>()

            if (allIds.isNotEmpty()) {
                val chunks = allIds.chunked(50)
                val statsDeferred = chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        try {
                            val filterVal = "openalex:" + chunk.joinToString("|")
                            httpClient.get("https://api.openalex.org/authors") {
                                parameter("filter", filterVal)
                                parameter("per_page", 50)
                                parameter("mailto", "vikki.4me@gmail.com")
                            }.body<OpenAlexAuthorsResponse>().results
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }

                val statsResults = statsDeferred.awaitAll()
                for (results in statsResults) {
                    for (author in results) {
                        val authClean = author.id.substringAfterLast("/")
                        val hIndex = author.summary_stats?.h_index ?: 0
                        val worksCount = author.works_count ?: 0
                        statsMap[authClean] = Pair(hIndex, worksCount)
                    }
                }
            }

            // Map maps to NetworkCollaborator list
            val collaboratorsPool = mutableListOf<NetworkCollaborator>()

            for (d1 in depth1Map.values) {
                val authId = d1["id"] as String
                val name = d1["name"] as String
                if (excludeName != null) {
                    val normCollab = name.trim().lowercase()
                    val normExclude = excludeName.trim().lowercase()
                    if (normCollab == normExclude || 
                        (normExclude.length > 3 && normCollab.contains(normExclude)) || 
                        (normCollab.length > 3 && normExclude.contains(normCollab))) {
                        continue
                    }
                }
                val authClean = authId.substringAfterLast("/")
                val stats = statsMap[authClean]
                val worksCount = stats?.second ?: 10
                val hIndex = stats?.first ?: 2

                collaboratorsPool.add(NetworkCollaborator(
                    id = authId,
                    name = name,
                    institution = d1["institution"] as String,
                    field = d1["field"] as String,
                    connection_path = "Co-authored '${d1["shared_paper"] as String}' with you",
                    relevance_score = minOf(99, 70 + (d1["joint_count"] as Int) * 5),
                    papers_collaborated = d1["joint_count"] as Int,
                    total_publications = worksCount,
                    h_index = hIndex
                ))
            }

            for (d2 in depth2Map.values) {
                val authId = d2["id"] as String
                val name = d2["name"] as String
                if (excludeName != null) {
                    val normCollab = name.trim().lowercase()
                    val normExclude = excludeName.trim().lowercase()
                    if (normCollab == normExclude || 
                        (normExclude.length > 3 && normCollab.contains(normExclude)) || 
                        (normCollab.length > 3 && normExclude.contains(normCollab))) {
                        continue
                    }
                }
                val authClean = authId.substringAfterLast("/")
                val stats = statsMap[authClean]
                val worksCount = stats?.second ?: 10
                val hIndex = stats?.first ?: 2

                collaboratorsPool.add(NetworkCollaborator(
                    id = authId,
                    name = name,
                    institution = d2["institution"] as String,
                    field = d2["field"] as String,
                    connection_path = d2["connection_path"] as String,
                    relevance_score = 65,
                    papers_collaborated = 1,
                    total_publications = worksCount,
                    h_index = hIndex
                ))
            }

            // Sort by relevance score
            val sortedList = collaboratorsPool.sortedByDescending { it.relevance_score }
            val startIndex = minOf(sortedList.size, offset)
            val endIndex = minOf(sortedList.size, offset + limit)
            sortedList.subList(startIndex, endIndex)

        } catch (e: Exception) {
            handleNetworkException(e, null)
            Log.e("ApiService", "getNetworkCollaborators failed", e)
            emptyList<NetworkCollaborator>()
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

    // ── Agent Integration ──────────────────────────────────────────────────────────
    suspend fun chatWithAgent(
        message: String,
        history: List<ChatMessage>,
        mode: String = "RESEARCH",
        userMemory: com.open.skolab.data.UserMemoryProfile? = null
    ): String {
        return withContext(Dispatchers.IO) {
            val base = baseUrl() ?: throw Exception("SkoLab backend services are currently unreachable.")
            try {
                val histMaps = history.map { mapOf("role" to it.role, "content" to it.content) }
                // Serialize memory profile to a flat JSON map if present
                val memoryMap: Map<String, kotlinx.serialization.json.JsonElement>? = userMemory?.let { mem ->
                    try {
                        val json = kotlinx.serialization.json.Json
                        val jsonObj = json.encodeToJsonElement(
                            com.open.skolab.data.UserMemoryProfile.serializer(), mem
                        )
                        (jsonObj as? kotlinx.serialization.json.JsonObject)?.toMap()
                    } catch (_: Exception) { null }
                }
                val request = AgentChatRequest(
                    message = message,
                    history = histMaps,
                    mode = mode,
                    user_memory = memoryMap
                )
                val chatResp = httpClient.post("$base/agent/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                    timeout { requestTimeoutMillis = 60000 }
                }.body<AgentChatResponse>()
                chatResp.reply
            } catch (e: Exception) {
                Log.e("ApiService", "Error in chatWithAgent: ${e.message}")
                throw e
            }
        }
    }

    /** Syncs a batch of local activity events to the backend memory engine. Fire-and-forget safe. */
    suspend fun syncMemoryEvents(
        userId: String,
        events: List<com.open.skolab.data.ActivityEvent>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext false
            try {
                val payload = UserMemoryEventsPayload(
                    user_id = userId,
                    events = events.map { e ->
                        ActivityEventRequest(
                            type = e.type,
                            timestamp = e.timestamp,
                            hourOfDay = e.hourOfDay,
                            paperTitle = e.paperTitle,
                            paperDomain = e.paperDomain,
                            paperJournal = e.paperJournal,
                            durationSeconds = e.durationSeconds,
                            authorName = e.authorName,
                            authorInstitution = e.authorInstitution,
                            query = e.query,
                            filterValue = e.filterValue,
                            collaboratorName = e.collaboratorName,
                            collaboratorField = e.collaboratorField
                        )
                    }
                )
                httpClient.post("$base/user_memory/events") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }.status == io.ktor.http.HttpStatusCode.OK
            } catch (e: Exception) {
                Log.e("ApiService", "syncMemoryEvents failed: ${e.message}")
                false
            }
        }
    }

    /** Fetches the server-side memory profile for a user. Returns null if not yet created. */
    suspend fun getUserMemory(userId: String): UserMemoryProfileResponse? {
        return withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext null
            try {
                val resp: io.ktor.client.statement.HttpResponse =
                    httpClient.get("$base/user_memory/$userId")
                if (resp.status == io.ktor.http.HttpStatusCode.OK) resp.body()
                else null
            } catch (e: Exception) {
                Log.e("ApiService", "getUserMemory failed: ${e.message}")
                null
            }
        }
    }

    suspend fun uploadDocument(uri: Uri, context: Context, fileName: String): UploadDocumentResponse? {
        return withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext null
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val response = httpClient.post("$base/agent/upload_document") {
                    setBody(MultiPartFormDataContent(
                        formData {
                            append("file", bytes, Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            })
                        }
                    ))
                    timeout {
                        requestTimeoutMillis = 60000
                    }
                }.body<UploadDocumentResponse>()
                
                response
            } catch (e: Exception) {
                Log.e("ApiService", "Error in uploadDocument: ${e.message}")
                null
            }
        }
    }

    suspend fun getAuthorMetrics(authorId: String): AuthorMetrics {
        val baseUrl = ServerLocator.baseUrl.value
            ?: throw Exception("Backend server not discovered. Make sure the SkoLab server is running and the device is on the same network.")
        val response: io.ktor.client.statement.HttpResponse = httpClient.get("$baseUrl/author_metrics") {
            parameter("author_id", authorId)
        }
        if (response.status == HttpStatusCode.OK) {
            return response.body()
        } else {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw Exception("Server returned ${response.status.value}: ${body.take(200).ifBlank { "No details" }}")
        }
    }

    suspend fun getIndustryOpportunities(focus: String): List<IndustryOpportunity> {
        return try {
            val baseUrl = ServerLocator.baseUrl.value
            if (baseUrl == null) {
                Log.e("ApiService", "Server base URL is not discovered yet")
                return emptyList()
            }
            val response: io.ktor.client.statement.HttpResponse = httpClient.get("$baseUrl/industry_opportunities") {
                parameter("focus", focus)
            }
            if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                Log.e("ApiService", "Failed to fetch industry opportunities: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ApiService", "Error fetching industry opportunities: ${e.message}")
            emptyList()
        }
    }
}
