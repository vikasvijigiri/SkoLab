package com.company.skolab.model

import com.company.skolab.network.DailyFeedItem
import com.company.skolab.network.GrantMatch
import com.company.skolab.network.JournalRecommendation
import com.company.skolab.ui.theme.*

/**
 * FeedModels — all pure data model types used by [FeedViewModel] and the Discovery UI.
 *
 * These were previously embedded inside FeedViewModel.kt, coupling the UI layer's
 * Color type into the model/domain layer. They are now separated here so the
 * ViewModel and model layer remain independent of Compose/UI artifacts.
 *
 * Industry note: The [ResearchFilter] enum still references [Color] because it is
 * used directly in the UI for tinting chips. A cleaner long-term solution is to
 * store a semantic color token (e.g. a hex string or an enum like FilterColor) and
 * resolve it to a Compose Color only in the composable layer.
 */

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

/**
 * ResearchFilter — enum of research domain tabs shown in the Discovery screen.
 *
 * The [color] field exists for UI tinting convenience. Consider moving this
 * into a UI-layer mapping in the future to keep models fully UI-agnostic.
 */
enum class ResearchFilter(val label: String, val color: androidx.compose.ui.graphics.Color) {
    ALL("All Fields", ResearchGold),
    AI("ML/AI", ResearchBlue),
    GENOMICS("Genomics", ResearchGenomics),
    NEURO("Neuroscience", AccentViolet),
    CLIMATE("Climate", ResearchClimate),
    PHYSICS("Physics", ResearchPhysics),
    CHEMISTRY("Chemistry", ResearchChemistry),
    MATERIALS("Materials", ResearchMaterials)
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

/**
 * ResearchArea — used for the tag cloud UI element.
 *
 * Same note as [ResearchFilter]: color should ideally be resolved at the UI layer.
 */
data class ResearchArea(
    val name: String,
    val color: androidx.compose.ui.graphics.Color
)

data class Connection(
    val author: Author,
    val depth: Int,            // 1, 2, or 3
    val mutualCount: Int,
    val tags: List<String> = emptyList(),
    val connectionPath: String = "",
    val openStatus: String = "Available for Collaboration",
    val sharedAreas: List<String> = emptyList(),
    val papersCollaborated: Int = 0,
    val totalPublications: Int = 0,
    val hIndex: Int = 0,
    /**
     * True if this researcher has a verified SkoLab account.
     * Heuristic: authors sourced from OpenAlex with a non-blank real ID are treated
     * as SkoLab members. Replace with a real DB lookup once a user registry is available.
     */
    val isOnSkoLab: Boolean = author.id.startsWith("https://openalex.org/")
)

/**
 * Institution — used for the "top institutions" leaderboard in the Discovery screen.
 *
 * Same note as [ResearchFilter]: color should be resolved at the UI layer.
 */
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
