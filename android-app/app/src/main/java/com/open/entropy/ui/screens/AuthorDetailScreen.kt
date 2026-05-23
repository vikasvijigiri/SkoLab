package com.open.entropy.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open.entropy.model.Author
import com.open.entropy.model.MockData
import com.open.entropy.network.ApiService
import com.open.entropy.network.AuthorResponse
import com.open.entropy.network.AuthorSuggestion
import com.open.entropy.network.ToolUsage
import com.open.entropy.ui.components.primitives.EmptyState
import com.open.entropy.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorDetailScreen(
    authorName: String,
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    apiService: ApiService = remember { ApiService() }
) {
    var authorData by remember { mutableStateOf<AuthorResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    LaunchedEffect(authorName) {
        isLoading = true
        try {
            val parts = authorName.split("|")
            val displayName = parts.firstOrNull() ?: authorName
            val authorId = if (parts.size > 1) parts[1] else null

            // Attempt dynamic OpenAlex / backend database lookup
            var data: com.open.entropy.network.AuthorResponse? = null
            try {
                data = apiService.searchAuthor(displayName, authorId)
            } catch (e: Exception) {
                Log.e("AuthorDetailScreen", "API searchAuthor failed", e)
            }
            
            if (data == null) {
                // Show a short warning/Toast to handle errors in an industry-standard way
                android.widget.Toast.makeText(context, "ResQit server unreachable. Using local fallback database.", android.widget.Toast.LENGTH_LONG).show()
                // Graceful mock database fallback
                val mockAuthor = MockData.authors.find {
                    it.name.equals(displayName, ignoreCase = true) ||
                    it.name.contains(displayName, ignoreCase = true) ||
                    displayName.contains(it.name, ignoreCase = true)
                }
                data = if (mockAuthor != null) {
                    mockAuthor.toAuthorResponse()
                } else {
                    MockData.generateDynamicMockAuthor(displayName, authorId)
                }
            }
            authorData = data
        } catch (e: Exception) {
            Log.e("AuthorDetailScreen", "Failed to retrieve researcher metrics", e)
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    val parts = authorName.split("|")
                    val displayName = parts.firstOrNull() ?: authorName
                    Text(
                        text = authorData?.display_name ?: displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    LoadingResearcherSkeleton()
                }
                authorData != null -> {
                    ResearcherProfileView(
                        author = authorData!!,
                        apiService = apiService,
                        scope = scope,
                        onNavigateToReader = { _, doi ->
                            onPaperClick(doi)
                        },
                        onUpdateData = { authorData = it },
                        onSelectResearcher = { name, _ ->
                            isLoading = true
                            scope.launch {
                                var newData: com.open.entropy.network.AuthorResponse? = null
                                try {
                                    newData = apiService.searchAuthor(name)
                                } catch (e: Exception) {
                                    Log.e("AuthorDetailScreen", "onSelectResearcher API searchAuthor failed", e)
                                }
                                if (newData == null) {
                                    android.widget.Toast.makeText(context, "ResQit server unreachable. Using local fallback.", android.widget.Toast.LENGTH_LONG).show()
                                    val mockAuthor = MockData.authors.find {
                                        it.name.equals(name, ignoreCase = true) ||
                                        it.name.contains(name, ignoreCase = true) ||
                                        name.contains(it.name, ignoreCase = true)
                                    }
                                    newData = if (mockAuthor != null) {
                                        mockAuthor.toAuthorResponse()
                                    } else {
                                        MockData.generateDynamicMockAuthor(name)
                                    }
                                }
                                if (newData != null) {
                                    authorData = newData
                                }
                                isLoading = false
                            }
                        }
                    )
                }
                else -> {
                    EmptyState(
                        title = "Researcher not found",
                        message = "No record found for \"$authorName\". Check connection and try again.",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Maps local high-fidelity MockData Author to dynamic network AuthorResponse.
 */
fun Author.toAuthorResponse(): AuthorResponse {
    val computedHIndex = this.topPapers.maxOfOrNull { it.hIndex } ?: 45
    val computedCitationCount = this.topPapers.sumOf { it.citationCount }
    return AuthorResponse(
        id = this.id,
        display_name = this.name,
        orcid = this.orcidId,
        h_index = computedHIndex,
        i10_index = maxOf(0, computedHIndex - 4),
        works_count = this.totalPapers,
        cited_by_count = computedCitationCount,
        institution = this.institution,
        field_of_study = null,
        expertise = listOf("Quantum Frontiers", "Disruptive Mechanics", "Mathematical Modeling"),
        academic_history = listOf("Academic Portfolio — $institution"),
        works = this.topPapers.map { paper ->
            com.open.entropy.network.Work(
                title = paper.title,
                year = paper.year,
                doi = paper.doi,
                journal = paper.journal,
                is_open_access = true,
                citations = paper.citationCount,
                creativity_score = 0.82,
                complexity_score = 0.76,
                impact_factor = paper.journalImpactFactor.toDouble(),
                disruption_score = paper.disruptionScore.toDouble(),
                semantic_novelty = paper.noveltyScore.toDouble(),
                open_science_score = 0.95,
                authors = paper.authors
            )
        },
        average_creativity = 0.85,
        average_complexity = 0.74,
        average_activity = 0.88,
        average_skill_score = 0.84,
        average_impact = 0.91,
        innovation_score = this.avgDisruptionScore.toDouble() * 100,
        disruption_score = this.avgDisruptionScore.toDouble(),
        citation_acceleration = 14.2,
        future_impact_score = 90.5,
        network_centrality = 0.85,
        semantic_novelty = 0.80,
        interdisciplinary_index = 0.72,
        policy_patent_score = 38.0,
        open_science_score = 0.90,
        collaboration_diversity = 0.65,
        research_consistency = 0.88,
        next_prediction = "Significant shift towards scalable Topological phase gates in upcoming works.",
        top_experimental_tools = listOf(
            ToolUsage("Qiskit Runtime", 32, "Software"),
            ToolUsage("Dilution Refrigerator", 12, "Hardware")
        ),
        similar_researchers = emptyList()
    )
}
