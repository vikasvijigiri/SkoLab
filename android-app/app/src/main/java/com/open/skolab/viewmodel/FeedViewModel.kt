package com.open.skolab.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.open.skolab.model.Paper
import com.open.skolab.model.Author
import com.open.skolab.model.Conjecture

import com.open.skolab.network.ApiService
import com.open.skolab.network.DailyFeedItem
import com.open.skolab.network.GrantMatch
import com.open.skolab.network.NetworkCollaborator
import com.open.skolab.network.AuthorSuggestion
import com.open.skolab.network.JournalRecommendation
import com.open.skolab.network.OpenAlexWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class User(
    val id: String,
    val name: String,
    val initials: String,
    val researchFocus: String
)

data class FrontierMetrics(
    val dIndex: Float,
    val sIndex: Float,
    val papersCount: Int,
    val dIndexDelta: Float,
    val sIndexDelta: Float,
    val papersDelta: Int
)

enum class ResearchFilter(val label: String, val color: androidx.compose.ui.graphics.Color) {
    ALL("All Fields", androidx.compose.ui.graphics.Color(0xFFC9A84C)),
    AI("ML/AI", androidx.compose.ui.graphics.Color(0xFF3D6FFF)),
    GENOMICS("Genomics", androidx.compose.ui.graphics.Color(0xFF00D4FF)),
    NEURO("Neuroscience", androidx.compose.ui.graphics.Color(0xFF7C3AED)),
    CLIMATE("Climate", androidx.compose.ui.graphics.Color(0xFF00E676)),
    PHYSICS("Physics", androidx.compose.ui.graphics.Color(0xFFFF4757)),
    CHEMISTRY("Chemistry", androidx.compose.ui.graphics.Color(0xFFFBBF24)),
    MATERIALS("Materials", androidx.compose.ui.graphics.Color(0xFFFB923C))
}

data class Country(
    val code: String,
    val name: String,
    val flag: String,
    val paperCount: Int
)

data class Discipline(
    val name: String,
    val emoji: String,
    val subCount: String,
    val gradientStart: String,
    val gradientEnd: String
)

data class ResearchArea(
    val name: String,
    val color: androidx.compose.ui.graphics.Color
)

data class Connection(
    val author: Author,
    val depth: Int,              // 1, 2, or 3
    val mutualCount: Int,
    val tags: List<String> = emptyList(),
    val connectionPath: String = "",
    val openStatus: String = "Available for Collaboration",
    val sharedAreas: List<String> = emptyList(),
    val papersCollaborated: Int = 0,
    val totalPublications: Int = 0,
    val hIndex: Int = 0,
    /** True if this researcher has a verified SkoLab account.
     *  Heuristic: authors sourced from OpenAlex with a non-blank real ID are treated
     *  as SkoLab members. Mock/local authors are treated as "Not on SkoLab".
     *  Replace with a real DB lookup once a user registry is available. */
    val isOnSkoLab: Boolean = author.id.startsWith("https://openalex.org/")
)

data class Institution(
    val name: String,
    val initials: String,
    val color: androidx.compose.ui.graphics.Color
)

data class ReadingProgress(
    val paper: Paper,
    val progressPercent: Int
)

data class FeedUiState(
    val user: User = User("", "Researcher", "R", "Research"),
    val frontierMetrics: FrontierMetrics = FrontierMetrics(0.85f, 0.79f, 24, 0.06f, 0.04f, 3),
    val aiBriefText: String = "",
    val selectedFilter: ResearchFilter = ResearchFilter.ALL,
    val topCountries: List<Country> = emptyList(),
    val disciplines: List<Discipline> = emptyList(),
    val researchAreas: List<ResearchArea> = emptyList(),
    val trendingPapers: List<Paper> = emptyList(),
    val suggestedResearchers: List<Author> = emptyList(),
    val suggestedConnections: List<Connection> = emptyList(),
    val dailyFeedItems: List<DailyFeedItem> = emptyList(),
    val grantMatches: List<GrantMatch> = emptyList(),
    val industryOpportunities: List<IndustryOpportunity> = emptyList(),
    val journalRecommendations: List<JournalRecommendation> = emptyList(),
    val dailyConjecture: Conjecture? = null,
    val hotPapers: List<Paper> = emptyList(),
    val topInstitutions: List<Institution> = emptyList(),
    val openAccessPapers: List<Paper> = emptyList(),
    val continueReading: List<ReadingProgress> = emptyList(),
    val collaboratorsArticles: List<Paper> = emptyList(),
    val suggestedPeersArticles: List<Paper> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMoreConnections: Boolean = false,
    val hasMoreConnections: Boolean = true,
    val error: String? = null
)

class FeedViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private var userAuthorProfile: com.open.skolab.network.AuthorResponse? = null

    private fun appendError(msg: String) {
        val currentErr = _uiState.value.error
        _uiState.value = _uiState.value.copy(
            error = if (currentErr.isNullOrBlank()) msg else "$currentErr\n$msg"
        )
    }

    init {
        loadAllFeedData()
        viewModelScope.launch {
            com.open.skolab.network.ServerLocator.baseUrl.collect { url ->
                if (url != null) {
                    Log.i("FeedViewModel", "ServerLocator base URL discovered → $url. Refreshing feed...")
                    loadAllFeedData()
                }
            }
        }
    }

    fun setUserContext(uid: String, name: String, focus: String) {
        val current = _uiState.value.user
        val validFocus = if (focus.isNotBlank() && focus != "Researcher" && focus != "General Research") focus else ""
        if (current.id == uid && current.name == name && current.researchFocus == validFocus) {
            return
        }
        viewModelScope.launch {
            val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").take(2)
            _uiState.value = _uiState.value.copy(
                user = User(uid, name, initials, validFocus)
            )
            loadAllFeedData()
        }
    }

    fun setResearchFilter(filter: ResearchFilter) {
        if (_uiState.value.selectedFilter == filter) return
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
        loadAllFeedData()
    }

    fun loadMoreConnections() {
        val currentState = _uiState.value
        if (currentState.isLoadingMoreConnections || !currentState.hasMoreConnections) return
        
        val connections = currentState.suggestedConnections
        if (connections.isEmpty() || currentState.user.id.isEmpty()) return

        val expandId = userAuthorProfile?.id ?: return


        _uiState.value = currentState.copy(isLoadingMoreConnections = true)

        viewModelScope.launch {
            try {
                val currentOffset = connections.size
                val newNetwork = apiService.getNetworkCollaborators(
                    authorId = expandId,
                    limit = 10,
                    offset = currentOffset,
                    excludeIds = emptyList(),
                    excludeName = currentState.user.name
                )
                
                if (newNetwork.isNotEmpty()) {
                    val focus = currentState.user.researchFocus
                    val newConnections = newNetwork.map { collab ->
                        Connection(
                            author = Author(
                                id = collab.id,
                                name = collab.name,
                                institution = collab.institution,
                                country = "US",
                                orcidId = null,
                                fingerprintType = collab.field.ifBlank { focus },
                                radarScores = mapOf("Disruption" to (collab.relevance_score / 100f), "Novelty" to (collab.relevance_score / 100f * 0.9f)),
                                careerArc = emptyList(),
                                topPapers = emptyList(),
                                collaborators = emptyList(),
                                totalPapers = collab.total_publications ?: 10,
                                avgDisruptionScore = collab.relevance_score / 100f
                            ),
                            depth = if (collab.connection_path.contains("Co-authored")) 1 else 2,
                            mutualCount = (collab.relevance_score / 10),
                            sharedAreas = listOf(focus)
                        )
                    }

                    // Concurrently fetch works for the new connections
                    val updatedNewConnections = newConnections.map { conn ->
                        async(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val rawWorks = apiService.getAuthorWorks(conn.author.id, limit = 2)
                                val papers = rawWorks.map { mapOpenAlexToPaper(it) }
                                if (papers.isNotEmpty()) {
                                    conn.copy(author = conn.author.copy(topPapers = papers))
                                } else {
                                    val fallbackPaper = currentState.trendingPapers.firstOrNull()
                                    conn.copy(author = conn.author.copy(topPapers = if (fallbackPaper != null) listOf(fallbackPaper) else emptyList()))
                                }
                            } catch (e: Exception) {
                                conn
                            }
                        }
                    }.awaitAll()

                    val finalConnections = currentState.suggestedConnections + updatedNewConnections
                    
                    _uiState.value = currentState.copy(
                        suggestedConnections = finalConnections,
                        isLoadingMoreConnections = false,
                        hasMoreConnections = newNetwork.size == 10
                    )
                } else {
                    _uiState.value = currentState.copy(
                        isLoadingMoreConnections = false,
                        hasMoreConnections = false
                    )
                }
            } catch (e: Exception) {
                Log.e("FeedViewModel", "Failed to load more connections", e)
                _uiState.value = currentState.copy(isLoadingMoreConnections = false)
            }
        }
    }

    fun loadAllFeedData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val name = _uiState.value.user.name
                val baseFocus = _uiState.value.user.researchFocus
                val currentFilter = _uiState.value.selectedFilter

                // Static/Local structured definitions
                val countries = listOf(
                    Country("US", "USA", "🇺🇸", 1420),
                    Country("CN", "China", "🇨🇳", 1105),
                    Country("GB", "UK", "🇬🇧", 645),
                    Country("DE", "Germany", "🇩🇪", 498),
                    Country("JP", "Japan", "🇯🇵", 387),
                    Country("IN", "India", "🇮🇳", 312),
                    Country("FR", "France", "🇫🇷", 280),
                    Country("CA", "Canada", "🇨🇦", 245),
                    Country("AU", "Australia", "🇦🇺", 192),
                    Country("BR", "Brazil", "🇧🇷", 115)
                )

                // 2. Search for the logged-in user profile to extract actual collaborators (co-authors)
                try {
                    Log.i("FeedViewModel", "Pre-searching author profile for name: $name")
                    userAuthorProfile = apiService.searchAuthor(name)
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Pre-search failed", e)
                    appendError("Author Profile Search: ${e.localizedMessage ?: e.toString()}")
                }

                val profile = userAuthorProfile
                
                // ── Research Focus Resolution ──────────────────────────────────────
                // Priority: (1) filter chip → (2) Firestore researchFocus → (3) OpenAlex expertise → (4) OpenAlex field_of_study → (5) fallback
                // Firestore researchFocus is source-of-truth to prevent a mismatched OpenAlex profile
                // (e.g. a different researcher with the same name in OpenAlex returning a different top concept)
                val focus = if (currentFilter.name != "ALL") {
                    currentFilter.label // User explicitly clicked a filter chip
                } else if (baseFocus.isNotBlank() && baseFocus.lowercase() != "research"
                    && baseFocus != "General Research" && baseFocus != "Researcher") {
                    baseFocus // ← Firestore researchFocus wins: what the user explicitly set
                } else if (profile != null && profile.expertise.isNotEmpty()) {
                    profile.expertise.first() // OpenAlex enrichment (fallback only when Firestore is blank)
                } else if (profile != null && !profile.field_of_study.isNullOrBlank()) {
                    profile.field_of_study
                } else {
                    "" // Last-resort fallback
                }
                
                val displayFocus = focus
                
                val isMismatch = profile != null && !profile.expertise.any {
                    it.contains(displayFocus, ignoreCase = true) || displayFocus.contains(it, ignoreCase = true)
                }
                val displayExpertise = if (isMismatch || profile == null || profile.expertise.isEmpty()) {
                    getSubConceptsForFocus(displayFocus)
                } else {
                    profile.expertise
                }
                
                val dynamicDisciplines = mutableListOf<Discipline>()
                if (displayExpertise.isNotEmpty()) {
                    displayExpertise.take(6).forEachIndexed { idx, exp ->
                        val emoji = when {
                            exp.contains("physics", ignoreCase = true) || exp.contains("quantum", ignoreCase = true) -> "⚛️"
                            exp.contains("chemistry", ignoreCase = true) -> "🧪"
                            exp.contains("biology", ignoreCase = true) || exp.contains("gen", ignoreCase = true) -> "🧬"
                            exp.contains("computer", ignoreCase = true) || exp.contains("machine", ignoreCase = true) || exp.contains("ai", ignoreCase = true) -> "🤖"
                            exp.contains("math", ignoreCase = true) -> "📐"
                            exp.contains("neuro", ignoreCase = true) || exp.contains("brain", ignoreCase = true) -> "🧠"
                            else -> "🔬"
                        }
                        val color1 = when (idx % 3) {
                            0 -> "#0F172A"
                            1 -> "#1E1B4B"
                            else -> "#052E16"
                        }
                        val color2 = when (idx % 3) {
                            0 -> "#3D6FFF"
                            1 -> "#7C3AED"
                            else -> "#0EA5E9"
                        }
                        dynamicDisciplines.add(
                            Discipline(
                                name = exp,
                                emoji = emoji,
                                subCount = "${kotlin.random.Random.nextInt(100, 500)} papers",
                                gradientStart = color1,
                                gradientEnd = color2
                            )
                        )
                    }
                }
                if (dynamicDisciplines.isEmpty()) {
                    dynamicDisciplines.add(Discipline(displayFocus.ifBlank { "General Research" }, "🔬", "0 papers", "#0F172A", "#3D6FFF"))
                }
                val disciplines = dynamicDisciplines

                val dynamicResearchAreas = mutableListOf<ResearchArea>()
                if (displayExpertise.isNotEmpty()) {
                    val colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFF22D3EE), 
                        androidx.compose.ui.graphics.Color(0xFF3D6FFF), 
                        androidx.compose.ui.graphics.Color(0xFF00E676), 
                        androidx.compose.ui.graphics.Color(0xFFA78BFA), 
                        androidx.compose.ui.graphics.Color(0xFFFF4757), 
                        androidx.compose.ui.graphics.Color(0xFFFBBF24)
                    )
                    displayExpertise.forEachIndexed { index, exp ->
                        dynamicResearchAreas.add(ResearchArea(exp, colors[index % colors.size]))
                    }
                } else {
                    dynamicResearchAreas.add(ResearchArea(displayFocus.ifBlank { "General Research" }, androidx.compose.ui.graphics.Color(0xFF3D6FFF)))
                }
                val researchAreas = dynamicResearchAreas

                val institutions = listOf(
                    Institution("MIT", "MIT", androidx.compose.ui.graphics.Color(0xFFFF4757)),
                    Institution("Stanford", "SU", androidx.compose.ui.graphics.Color(0xFFFBBF24)),
                    Institution("Oxford", "OX", androidx.compose.ui.graphics.Color(0xFF3D6FFF)),
                    Institution("ETH Zurich", "ETH", androidx.compose.ui.graphics.Color(0xFF00E676)),
                    Institution("Harvard", "HU", androidx.compose.ui.graphics.Color(0xFFBE123C)),
                    Institution("Caltech", "CIT", androidx.compose.ui.graphics.Color(0xFFFB923C)),
                    Institution("Cambridge", "CAM", androidx.compose.ui.graphics.Color(0xFF22D3EE)),
                    Institution("IIT", "IIT", androidx.compose.ui.graphics.Color(0xFFA78BFA)),
                    Institution("CERN", "CRN", androidx.compose.ui.graphics.Color(0xFF34D399)),
                    Institution("NASA", "NSA", androidx.compose.ui.graphics.Color(0xFF3D6FFF))
                )
                var connections: List<Connection> = emptyList()
                try {
                    val targetId = profile?.id ?: "fallback_seed"
                    Log.i("FeedViewModel", "Fetching network collaborators for id: $targetId")
                    val networkList = apiService.getNetworkCollaborators(
                        authorId = targetId,
                        limit = 10,
                        offset = 0,
                        excludeName = name
                    )
                    if (networkList.isNotEmpty()) {
                        val initialConnections = networkList.map { collab ->
                            val isDepth1 = collab.connection_path.contains("Co-authored")
                            Connection(
                                author = Author(
                                    id = collab.id,
                                    name = collab.name,
                                    institution = collab.institution,
                                    country = "US",
                                    orcidId = null,
                                    fingerprintType = collab.field.ifBlank { displayFocus },
                                    radarScores = mapOf("Disruption" to (collab.relevance_score / 100f), "Novelty" to (collab.relevance_score / 100f * 0.9f)),
                                    careerArc = emptyList(),
                                    topPapers = emptyList(),
                                    collaborators = emptyList(),
                                    totalPapers = collab.total_publications ?: 10,
                                    avgDisruptionScore = collab.relevance_score / 100f
                                ),
                                depth = if (isDepth1) 1 else 2,
                                mutualCount = collab.relevance_score,
                                tags = listOf(collab.field.ifBlank { "Collaborator" }),
                                connectionPath = collab.connection_path,
                                openStatus = "Available for Collaboration",
                                papersCollaborated = collab.papers_collaborated ?: 0,
                                totalPublications = collab.total_publications ?: 0,
                                hIndex = collab.h_index ?: 0
                            )
                        }

                        // Concurrently fetch works for initial connections
                        connections = initialConnections.map { conn ->
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val rawWorks = apiService.getAuthorWorks(conn.author.id, limit = 2)
                                    val papers = rawWorks.map { mapOpenAlexToPaper(it) }
                                    if (papers.isNotEmpty()) {
                                        conn.copy(author = conn.author.copy(topPapers = papers))
                                    } else {
                                        conn
                                    }
                                } catch (e: Exception) {
                                    conn
                                }
                            }
                        }.awaitAll()
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Error fetching connections from OpenAlex API", e)
                    appendError("Collaborators: ${e.localizedMessage ?: e.toString()}")
                }

                // AI Daily Brief Text - Fetches from live backend Daily Feed
                var aiText = "Your research brief is live for **$displayFocus**."
                var dailyFeedItems: List<DailyFeedItem> = emptyList()
                var grantMatches: List<GrantMatch> = emptyList()
                var industryOpportunities: List<IndustryOpportunity> = emptyList()
                var journalRecommendations: List<JournalRecommendation> = emptyList()
                var dailyConjecture: Conjecture? = null
                try {
                    val authorId = profile?.id?.takeIf { it.isNotBlank() }
                    val dailyFeedDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { apiService.getDailyFeed(authorId, displayFocus) }.getOrElse { emptyList() }
                    }
                    val grantsDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { authorId?.let { apiService.matchGrants(it) }.orEmpty() }.getOrElse { emptyList() }
                    }
                    val industryDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { apiService.getIndustryOpportunities(displayFocus) }.getOrElse { emptyList() }
                    }
                    val journalsDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { authorId?.let { apiService.getJournalAdvisor(it) }.orEmpty() }.getOrElse { emptyList() }
                    }
                    val conjectureDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { if (authorId != null) apiService.getDailyConjecture(authorId, name) else null }.getOrNull()
                    }

                    val dailyFeed = dailyFeedDeferred.await()
                    grantMatches = grantsDeferred.await()
                    industryOpportunities = industryDeferred.await()
                    journalRecommendations = journalsDeferred.await()
                    dailyConjecture = conjectureDeferred.await()
                    dailyFeedItems = dailyFeed

                    if (dailyFeed.isNotEmpty()) {
                        val first = dailyFeed.first()
                        val briefLines = mutableListOf<String>()
                        briefLines.add("Based on your focus in **$displayFocus**, the strongest live lead is **${first.title}** in *${first.journal}* (${first.year}).")
                        briefLines.add("Why it matters: **${first.recommendation_reason}**")
                        val topGrant = grantMatches.firstOrNull()
                        if (topGrant != null) {
                            briefLines.add("Funding radar: **${topGrant.title}** from ${topGrant.agency} closes in ${topGrant.days_left} days.")
                        }
                        val topIndustry = industryOpportunities.firstOrNull()
                        if (topIndustry != null) {
                            briefLines.add("Industry pulse: **${topIndustry.title}** at ${topIndustry.companyOrFunder}.")
                        }
                        val topJournal = journalRecommendations.firstOrNull()
                        if (topJournal != null) {
                            briefLines.add("Best journal fit: **${topJournal.journal_name}** (${topJournal.match_score}% match).")
                        }
                        dailyConjecture?.let {
                            briefLines.add("Today's challenge: **${it.title}**.")
                        }
                        aiText = briefLines.joinToString("\n\n")
                    } else {
                        val fallbackLines = mutableListOf<String>()
                        fallbackLines.add("Your live signals for **$displayFocus** are syncing now.")
                        grantMatches.firstOrNull()?.let {
                            fallbackLines.add("Funding radar: **${it.title}** from ${it.agency} closes in ${it.days_left} days.")
                        }
                        industryOpportunities.firstOrNull()?.let {
                            fallbackLines.add("Industry pulse: **${it.title}** at ${it.companyOrFunder}.")
                        }
                        journalRecommendations.firstOrNull()?.let {
                            fallbackLines.add("Best journal fit: **${it.journal_name}** (${it.match_score}% match).")
                        }
                        dailyConjecture?.let {
                            fallbackLines.add("Today's challenge: **${it.title}**.")
                        }
                        aiText = fallbackLines.joinToString("\n\n")
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Failed to load live AI daily feed", e)
                    appendError("AI Daily Brief: ${e.localizedMessage ?: e.toString()}")
                }

                                // Fetch trending, hot, and open access papers, and rising researchers concurrently
                val trendingDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val queryFocus = if (focus.isBlank()) null else focus
                        apiService.getTrendingPapers(queryFocus, limit = 5).map { mapOpenAlexToPaper(it) }
                    } catch (e: Exception) {
                        Log.e("FeedViewModel", "Failed to fetch trending papers", e)
                        appendError("Trending Papers: ${e.localizedMessage ?: e.toString()}")
                        emptyList<Paper>()
                    }
                }

                val hotDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        apiService.getTrendingPapers(null, limit = 6).map { mapOpenAlexToPaper(it) }
                    } catch (e: Exception) {
                        Log.e("FeedViewModel", "Failed to fetch hot papers", e)
                        appendError("Hot Papers: ${e.localizedMessage ?: e.toString()}")
                        emptyList<Paper>()
                    }
                }

                val openAccessDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val queryFocus = if (displayFocus.isBlank()) null else displayFocus
                        apiService.getTrendingPapers(queryFocus, limit = 8)
                            .filter { !it.primary_location?.pdf_url.isNullOrBlank() }
                            .map { mapOpenAlexToPaper(it) }
                            .map { it.copy(pdfUrl = it.pdfUrl ?: "https://arxiv.org/pdf/1706.03762.pdf") }
                            .take(5)
                    } catch (e: Exception) {
                        Log.e("FeedViewModel", "Failed to fetch open access papers", e)
                        appendError("Open Access Papers: ${e.localizedMessage ?: e.toString()}")
                        emptyList<Paper>()
                    }
                }

                val risingDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (displayFocus.isBlank()) {
                            emptyList<Author>()
                        } else {
                            apiService.getSimilarAuthors(displayFocus, limit = 6).map { collab ->
                                Author(
                                    id = collab.id,
                                    name = collab.display_name,
                                    institution = collab.institution,
                                    country = "US",
                                    orcidId = null,
                                    fingerprintType = collab.field_of_study ?: displayFocus,
                                    radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                                    careerArc = emptyList(),
                                    topPapers = emptyList(),
                                    collaborators = emptyList(),
                                    totalPapers = collab.h_index ?: 24,
                                    avgDisruptionScore = (collab.innovation_score ?: 80) / 100f
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FeedViewModel", "Failed to fetch rising researchers", e)
                        appendError("Rising Researchers: ${e.localizedMessage ?: e.toString()}")
                        emptyList<Author>()
                    }
                }

                val trending = trendingDeferred.await()
                val hot = hotDeferred.await()
                val openAccess = openAccessDeferred.await()
                val rising = risingDeferred.await()
                val continueReadingList = emptyList<ReadingProgress>()

                val finalDIndex = if (profile != null && profile.disruption_score > 0.0) profile.disruption_score.toFloat() else 0.85f
                val finalSIndex = if (profile != null && profile.average_skill_score > 0.0) profile.average_skill_score.toFloat() else 0.79f
                val finalPapersCount = if (profile != null && profile.works_count > 0) profile.works_count else 24

                _uiState.value = FeedUiState(
                    user = User(_uiState.value.user.id, name, name.take(2).uppercase(), displayFocus),
                    frontierMetrics = FrontierMetrics(
                        dIndex = finalDIndex,
                        sIndex = finalSIndex,
                        papersCount = finalPapersCount,
                        dIndexDelta = 0.06f,
                        sIndexDelta = -0.02f,
                        papersDelta = 2
                    ),
                    aiBriefText = aiText,
                    selectedFilter = _uiState.value.selectedFilter,
                    topCountries = countries,
                    disciplines = disciplines,
                    researchAreas = researchAreas,
                    suggestedConnections = connections.shuffled(),
                    suggestedResearchers = rising,
                    dailyFeedItems = dailyFeedItems,
                    grantMatches = grantMatches,
                    industryOpportunities = industryOpportunities,
                    journalRecommendations = journalRecommendations,
                    dailyConjecture = dailyConjecture,
                    trendingPapers = trending,
                    hotPapers = hot,
                    openAccessPapers = openAccess,
                    continueReading = continueReadingList,
                    collaboratorsArticles = emptyList(),
                    suggestedPeersArticles = emptyList(),
                    topInstitutions = institutions,
                    hasMoreConnections = connections.size == 10,
                    isLoading = false,
                    error = null
                )

            } catch (e: Exception) {
                Log.e("FeedViewModel", "Failed to load Feed Screen metrics", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load feed metrics. Please pull to refresh."
                )
            }
        }
    }

    private suspend fun buildConnectionGraph(authorId: String, paperPool: List<Paper>): List<Connection> = kotlinx.coroutines.coroutineScope {
        val visited = mutableSetOf(authorId)
        val result = mutableListOf<Connection>()
        
        try {
            // depth 1 (limit to 6 to keep it fast)
            val depth1 = apiService.getNetworkCollaborators(authorId, limit = 6)
            
            // Map depth 1 instantly
            depth1.forEach { collab ->
                if (visited.add(collab.id)) {
                    val author = mapCollabToAuthor(collab, paperPool)
                    result.add(Connection(author, depth = 1, mutualCount = 0, sharedAreas = listOf(collab.field)))
                }
            }
            
            // Perform depth 2 fetches in parallel for the top 3 direct collaborators
            val depth2Deferreds = depth1.take(3).map { collab ->
                this@coroutineScope.async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        collab.id to apiService.getNetworkCollaborators(collab.id, limit = 3)
                    } catch (e: Exception) {
                        collab.id to emptyList()
                    }
                }
            }
            
            val depth2Results = kotlinx.coroutines.awaitAll(*depth2Deferreds.toTypedArray())
            
            depth2Results.forEach { (parentCollabId, collab2List) ->
                collab2List.forEach { collab2 ->
                    if (visited.add(collab2.id)) {
                        val author2 = mapCollabToAuthor(collab2, paperPool)
                        result.add(Connection(author2, depth = 2, mutualCount = 3, sharedAreas = listOf(collab2.field)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FeedViewModel", "buildConnectionGraph failed", e)
        }
        
        result
            .sortedWith(compareByDescending<Connection> { it.mutualCount }
                .thenByDescending { it.author.avgDisruptionScore })
            .take(50)
    }

    private fun mapCollabToAuthor(collab: NetworkCollaborator, paperPool: List<Paper>): Author {
        return Author(
            id = collab.id,
            name = collab.name,
            institution = collab.institution,
            country = "US",
            orcidId = null,
            fingerprintType = collab.field,
            radarScores = mapOf("Disruption" to 0.72f, "Novelty" to 0.68f),
            careerArc = emptyList(),
            topPapers = paperPool.take(1),
            collaborators = emptyList(),
            totalPapers = 28,
            avgDisruptionScore = collab.relevance_score / 100f
        )
    }

    private fun mapOpenAlexToPaper(work: OpenAlexWork): Paper {
        return Paper(
            id = work.id,
            title = work.title ?: "Unknown Title",
            authors = work.authorships?.mapNotNull { authorship ->
                val name = authorship.author?.display_name
                val id = authorship.author?.id
                if (name != null) {
                    if (id != null) "$name|$id" else name
                } else null
            } ?: emptyList(),
            journal = work.primary_location?.source?.display_name ?: "Unknown Journal",
            year = work.publication_year ?: 2026,
            domain = "General Science",
            subDomain = "Research",
            abstractText = "",
            disruptionScore = 0.82f,
            noveltyScore = 0.76f,
            citationVelocity = 12.0f,
            hIndex = 34,
            citationCount = work.cited_by_count ?: 0,
            journalImpactFactor = 4.2f,
            aiSummary = "",
            keyInsight = "",
            bulletPoints = emptyList(),
            methodology = emptyList(),
            latexFormula = null,
            isRetracted = false,
            doi = work.doi ?: "",
            pdfUrl = work.primary_location?.pdf_url
        )
    }

    private fun getSubConceptsForFocus(focus: String): List<String> {
        if (focus.isBlank()) return emptyList()
        val f = focus.lowercase()
        return when {
            f.contains("neuro") || f.contains("brain") -> listOf(
                "Computational Neuroscience", "Neural Coding", "Synaptic Plasticity",
                "Brain-Computer Interfaces", "Cognitive Modeling", "Neuroimaging"
            )
            f.contains("machine learning") || f.contains("ml") || f.contains("ai") || f.contains("artificial intelligence") || f.contains("computer science") -> listOf(
                "Deep Learning", "Natural Language Processing", "Computer Vision",
                "Reinforcement Learning", "Generative AI", "Neural Networks"
            )
            f.contains("genom") || f.contains("bio") || f.contains("gene") -> listOf(
                "Bioinformatics", "Gene Editing (CRISPR)", "Transcriptomics",
                "Epigenetics", "Functional Genomics", "Metagenomics"
            )
            f.contains("physics") || f.contains("quantum") -> listOf(
                "Quantum Mechanics", "Condensed Matter Physics", "Astrophysics",
                "High-Energy Particle Physics", "Quantum Computing", "Statistical Mechanics"
            )
            f.contains("chemistry") || f.contains("chemical") -> listOf(
                "Organic Chemistry", "Biochemistry", "Physical Chemistry",
                "Computational Chemistry", "Materials Science", "Spectroscopy"
            )
            f.contains("climate") || f.contains("environment") || f.contains("earth") -> listOf(
                "Climate Modeling", "Meteorology", "Oceanography",
                "Renewable Energy Systems", "Environmental Science", "Glaciology"
            )
            f.contains("material") || f.contains("nano") -> listOf(
                "Nanotechnology", "Semiconductors", "Superconductors",
                "Polymer Science", "Crystallography", "Metamaterials"
            )
            else -> {
                listOf(
                    focus,
                    "$focus Systems",
                    "Advanced $focus",
                    "Applied $focus",
                    "Computational $focus",
                    "Theoretical $focus"
                )
            }
        }
    }
}

