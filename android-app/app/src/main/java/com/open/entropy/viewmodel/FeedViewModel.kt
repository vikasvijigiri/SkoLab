package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.open.entropy.model.Paper
import com.open.entropy.model.Author
import com.open.entropy.model.MockData
import com.open.entropy.network.ApiService
import com.open.entropy.network.NetworkCollaborator
import com.open.entropy.network.AuthorSuggestion
import com.open.entropy.network.DailyFeedItem
import com.open.entropy.network.OpenAlexWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val hIndex: Int = 0
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
    val user: User = User("user_vikas", "Vikas Vijigiri", "VV", "Quantum Topology"),
    val frontierMetrics: FrontierMetrics = FrontierMetrics(0.85f, 0.79f, 24, 0.06f, 0.04f, 3),
    val aiBriefText: String = "",
    val selectedFilter: ResearchFilter = ResearchFilter.ALL,
    val topCountries: List<Country> = emptyList(),
    val disciplines: List<Discipline> = emptyList(),
    val researchAreas: List<ResearchArea> = emptyList(),
    val trendingPapers: List<Paper> = emptyList(),
    val suggestedResearchers: List<Author> = emptyList(),
    val suggestedConnections: List<Connection> = emptyList(),
    val hotPapers: List<Paper> = emptyList(),
    val topInstitutions: List<Institution> = emptyList(),
    val openAccessPapers: List<Paper> = emptyList(),
    val continueReading: List<ReadingProgress> = emptyList(),
    val collaboratorsArticles: List<Paper> = emptyList(),
    val suggestedPeersArticles: List<Paper> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMoreConnections: Boolean = false,
    val error: String? = null
)

class FeedViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadAllFeedData()
        viewModelScope.launch {
            com.open.entropy.network.ServerLocator.baseUrl.collect { url ->
                if (url != null) {
                    Log.i("FeedViewModel", "ServerLocator base URL discovered → $url. Refreshing feed...")
                    loadAllFeedData()
                }
            }
        }
    }

    fun setUserContext(name: String, focus: String) {
        val current = _uiState.value.user
        if (current.name == name && current.researchFocus == focus) {
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                user = User("user_vikas", name, name.take(2).uppercase(), focus)
            )
            loadAllFeedData()
        }
    }

    fun setResearchFilter(filter: ResearchFilter) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
    }

    fun loadMoreConnections() {
        if (_uiState.value.isLoadingMoreConnections) return
        
        val currentState = _uiState.value
        val connections = currentState.suggestedConnections
        if (connections.isEmpty()) return

        // Pick the last connection to expand from
        val lastConnection = connections.last()
        val expandId = lastConnection.author.id

        _uiState.value = currentState.copy(isLoadingMoreConnections = true)

        viewModelScope.launch {
            try {
                val excludeIds = connections.map { it.author.id }
                val newNetwork = apiService.getNetworkCollaborators(expandId, limit = 10, excludeIds = excludeIds)
                
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
                                fingerprintType = collab.field ?: focus,
                                radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                                careerArc = emptyList(),
                                topPapers = emptyList(),
                                collaborators = emptyList(),
                                totalPapers = 18,
                                avgDisruptionScore = 0.75f
                            ),
                            depth = lastConnection.depth + 1,
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
                        isLoadingMoreConnections = false
                    )
                } else {
                    _uiState.value = currentState.copy(isLoadingMoreConnections = false)
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
                val focus = _uiState.value.user.researchFocus

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

                val disciplines = listOf(
                    Discipline("AI/ML", "🤖", "840 papers", "#1D4ED8", "#7C3AED"),
                    Discipline("Biology", "🧬", "562 papers", "#065F46", "#0284C7"),
                    Discipline("Physics", "⚛️", "420 papers", "#0F172A", "#3D6FFF"),
                    Discipline("Chemistry", "🧪", "315 papers", "#7C2D12", "#DC2626"),
                    Discipline("Neuroscience", "🧠", "290 papers", "#581C87", "#EC4899"),
                    Discipline("Climate", "🌍", "245 papers", "#14532D", "#0369A1"),
                    Discipline("Medicine", "💊", "180 papers", "#BE123C", "#7C3AED"),
                    Discipline("Mathematics", "📐", "165 papers", "#1E1B4B", "#4F46E5"),
                    Discipline("Astronomy", "🔭", "110 papers", "#0C1020", "#2563EB"),
                    Discipline("Computing", "💻", "95 papers", "#052E16", "#0EA5E9")
                )

                val researchAreas = listOf(
                    ResearchArea("Protein Folding", androidx.compose.ui.graphics.Color(0xFF22D3EE)),
                    ResearchArea("Large Language Models", androidx.compose.ui.graphics.Color(0xFF3D6FFF)),
                    ResearchArea("Gene Editing", androidx.compose.ui.graphics.Color(0xFF00E676)),
                    ResearchArea("Dark Matter", androidx.compose.ui.graphics.Color(0xFFA78BFA)),
                    ResearchArea("Quantum Computing", androidx.compose.ui.graphics.Color(0xFF3D6FFF)),
                    ResearchArea("Cancer Immunotherapy", androidx.compose.ui.graphics.Color(0xFFFF4757)),
                    ResearchArea("Climate Modeling", androidx.compose.ui.graphics.Color(0xFF00E5FF)),
                    ResearchArea("Brain-Computer Interface", androidx.compose.ui.graphics.Color(0xFFEC4899)),
                    ResearchArea("Materials Science", androidx.compose.ui.graphics.Color(0xFFFBBF24)),
                    ResearchArea("Fusion Energy", androidx.compose.ui.graphics.Color(0xFFFB923C)),
                    ResearchArea("Neuromorphic AI", androidx.compose.ui.graphics.Color(0xFFA78BFA)),
                    ResearchArea("Synthetic Biology", androidx.compose.ui.graphics.Color(0xFF34D399))
                )

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

                // 2. Search for the logged-in user profile to extract actual collaborators (co-authors)
                var userAuthorProfile: com.open.entropy.network.AuthorResponse? = null
                var connections = emptyList<Connection>()
                try {
                    Log.i("FeedViewModel", "Searching author profile for name: $name")
                    userAuthorProfile = apiService.searchAuthor(name)
                    if (userAuthorProfile != null) {
                        Log.i("FeedViewModel", "Fetching network collaborators for id: ${userAuthorProfile.id}")
                        val networkList = apiService.getNetworkCollaborators(userAuthorProfile.id, limit = 10)
                        connections = networkList.map { collab ->
                            val isDepth1 = collab.connection_path.contains("Co-authored")
                            Connection(
                                author = Author(
                                    id = collab.id,
                                    name = collab.name,
                                    institution = collab.institution,
                                    country = "US",
                                    orcidId = null,
                                    fingerprintType = collab.field ?: focus,
                                    radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                                    careerArc = emptyList(),
                                    topPapers = emptyList(),
                                    collaborators = emptyList(),
                                    totalPapers = 18,
                                    avgDisruptionScore = 0.75f
                                ),
                                depth = if (isDepth1) 1 else 2,
                                mutualCount = collab.relevance_score,
                                tags = listOf(collab.field ?: "Collaborator"),
                                connectionPath = collab.connection_path,
                                openStatus = "Available for Collaboration",
                                papersCollaborated = collab.papers_collaborated ?: 0,
                                totalPublications = collab.total_publications ?: 0,
                                hIndex = collab.h_index ?: 0
                            )
                        }
                    } else {
                        Log.w("FeedViewModel", "Failed to find author profile for $name, falling back to mock")
                        connections = MockData.authors.take(4).mapIndexed { i, author ->
                            Connection(
                                author = author,
                                depth = (i % 3) + 1,
                                mutualCount = 75 + (i * 5), // e.g. 75, 80, 85, 90 relevance
                                tags = listOf(focus, "Basic Science"),
                                connectionPath = "Fallback Connection",
                                openStatus = "Available",
                                papersCollaborated = (i + 1) * 3, // e.g. 3, 6, 9, 12
                                totalPublications = 42 + (i * 15), // e.g. 42, 57, 72, 87
                                hIndex = 12 + (i * 4) // e.g. 12, 16, 20, 24
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Error fetching connections from OpenAlex API", e)
                    connections = MockData.authors.take(4).mapIndexed { i, author ->
                        Connection(
                            author = author,
                            depth = (i % 3) + 1,
                            mutualCount = 75 + (i * 5),
                            tags = listOf(focus, "Basic Science"),
                            connectionPath = "Fallback Connection",
                            openStatus = "Available",
                            papersCollaborated = (i + 1) * 3,
                            totalPublications = 42 + (i * 15),
                            hIndex = 12 + (i * 4)
                        )
                    }
                }

                // AI Daily Brief Text - Fetches from live backend Daily Feed
                var aiText = "Emerging Quantum Topology node convergence shows a **24% increase** in Disruption metric driven by Moire superlattice protected states. Coherence maps show **12 stable states** across twist domains."
                try {
                    val dailyFeed = apiService.getDailyFeed(userAuthorProfile?.id, focus)
                    if (dailyFeed.isNotEmpty()) {
                        val first = dailyFeed.first()
                        aiText = "Based on your focus in **$focus**, we recommend the new breakthrough paper **${first.title}** published in *${first.journal}* (${first.year}) by ${first.authors.firstOrNull() ?: "Unknown"}. Recommendation: **${first.recommendation_reason}**"
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Failed to load live AI daily feed", e)
                }

                val finalDIndex = if (userAuthorProfile != null && userAuthorProfile.disruption_score > 0.0) userAuthorProfile.disruption_score.toFloat() else 0.85f
                val finalSIndex = if (userAuthorProfile != null && userAuthorProfile.average_skill_score > 0.0) userAuthorProfile.average_skill_score.toFloat() else 0.79f
                val finalPapersCount = if (userAuthorProfile != null && userAuthorProfile.works_count > 0) userAuthorProfile.works_count else 24

                _uiState.value = FeedUiState(
                    user = User("user_vikas", name, name.take(2).uppercase(), focus),
                    frontierMetrics = FrontierMetrics(
                        dIndex = finalDIndex,
                        sIndex = finalSIndex,
                        papersCount = finalPapersCount,
                        dIndexDelta = 0.06f,
                        sIndexDelta = -0.02f,
                        papersDelta = 2
                    ),
                    aiBriefText = aiText,
                    selectedFilter = ResearchFilter.ALL,
                    topCountries = countries,
                    disciplines = disciplines,
                    researchAreas = researchAreas,
                    suggestedConnections = connections,
                    suggestedResearchers = emptyList(),
                    trendingPapers = emptyList(),
                    hotPapers = emptyList(),
                    openAccessPapers = emptyList(),
                    continueReading = emptyList(),
                    collaboratorsArticles = emptyList(),
                    suggestedPeersArticles = emptyList(),
                    topInstitutions = institutions,
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
}
