package com.open.entropy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import android.util.Log

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
    val sharedAreas: List<String>
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
    val error: String? = null
)

class FeedViewModel(private val apiService: ApiService = ApiService()) : ViewModel() {
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadAllFeedData()
    }

    fun setFilter(filter: ResearchFilter) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
        // Optionally trigger filter loading if needed
    }

    fun loadAllFeedData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
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

                // Dynamic Paper fetching from API (Trending and OpenAccess)
                var trendingList = emptyList<Paper>()
                var hotList = emptyList<Paper>()
                var openAccessList = emptyList<Paper>()
                
                try {
                    val rawTrending = apiService.getTrendingPapers(limit = 6)
                    trendingList = rawTrending.map { mapOpenAlexToPaper(it) }
                    
                    // Hot papers have high citations
                    hotList = trendingList.sortedByDescending { it.citationCount }
                    
                    // Open Access filtering
                    openAccessList = trendingList.filter { it.pdfUrl != null }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Failed to fetch from apiService", e)
                }

                // fallback to mock papers if lists are empty
                if (trendingList.isEmpty()) {
                    val mockList = MockData.authors.flatMap { it.topPapers }
                    trendingList = mockList.take(6)
                    hotList = mockList.sortedByDescending { it.citationCount }.take(6)
                    openAccessList = mockList.filter { it.pdfUrl != null }.take(6)
                }

                // Rising researchers (from MockData or suggestions API)
                var suggestedPeers = emptyList<Author>()
                try {
                    val rawPeers = apiService.getSimilarAuthors("Quantum Topology", limit = 6)
                    suggestedPeers = rawPeers.map { peer ->
                        val topPaper = trendingList.firstOrNull()
                        Author(
                            id = peer.id,
                            name = peer.display_name,
                            institution = peer.institution,
                            country = "US",
                            orcidId = null,
                            fingerprintType = "Disruptive Quantum Theory",
                            radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                            careerArc = emptyList(),
                            topPapers = if (topPaper != null) listOf(topPaper) else emptyList(),
                            collaborators = emptyList(),
                            totalPapers = 42,
                            avgDisruptionScore = 0.81f
                        )
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Rising researchers retrieval failed", e)
                }

                if (suggestedPeers.isEmpty()) {
                    suggestedPeers = MockData.authors.take(6)
                }

                // Suggested Connections (Recursive depth-1, 2, 3 Network Graph)
                var connections = emptyList<Connection>()
                if (suggestedPeers.isNotEmpty()) {
                    connections = buildConnectionGraph(suggestedPeers.first().id, trendingList)
                }
                if (connections.isEmpty()) {
                    connections = suggestedPeers.take(4).mapIndexed { i, author ->
                        Connection(author, depth = (i % 3) + 1, mutualCount = i * 2, sharedAreas = listOf("Physics", "Materials"))
                    }
                }

                // 1. Collaborators' New Articles
                val collabsArticlesList = mutableListOf<Paper>()
                if (connections.isNotEmpty()) {
                    connections.take(4).forEach { conn ->
                        collabsArticlesList.addAll(conn.author.topPapers)
                    }
                }
                
                // 2. Suggested Peers' (Friends) Articles
                val friendsArticlesList = mutableListOf<Paper>()
                if (suggestedPeers.isNotEmpty()) {
                    suggestedPeers.take(4).forEach { peer ->
                        friendsArticlesList.addAll(peer.topPapers)
                    }
                }

                // AI Daily Brief Text
                val aiText = "Emerging Quantum Topology node convergence shows a **24% increase** in Disruption metric driven by Moire superlattice protected states. Coherence maps show **12 stable states** across twist domains."

                // Continue Reading progress
                val continueReadingList = trendingList.take(3).mapIndexed { idx, paper ->
                    ReadingProgress(paper, progressPercent = (idx + 1) * 30)
                }

                _uiState.value = FeedUiState(
                    user = User("user_vikas", "Vikas Vijigiri", "VV", "Quantum Topology"),
                    frontierMetrics = FrontierMetrics(0.85f, 0.79f, 24, 0.06f, 0.04f, 3),
                    aiBriefText = aiText,
                    selectedFilter = ResearchFilter.ALL,
                    topCountries = countries,
                    disciplines = disciplines,
                    researchAreas = researchAreas,
                    trendingPapers = trendingList,
                    suggestedResearchers = suggestedPeers,
                    suggestedConnections = connections,
                    hotPapers = hotList,
                    topInstitutions = institutions,
                    openAccessPapers = openAccessList,
                    continueReading = continueReadingList,
                    collaboratorsArticles = collabsArticlesList,
                    suggestedPeersArticles = friendsArticlesList,
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
