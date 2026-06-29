package com.company.skolab.ui.screens.discovery.components

import com.company.skolab.ui.screens.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import androidx.compose.runtime.collectAsState
import com.company.skolab.network.*
import com.company.skolab.state.ActiveResearcherState
import com.company.skolab.ui.components.*
import com.company.skolab.ui.theme.*
import com.company.skolab.analytics.SkoLabAnalytics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import android.util.TypedValue
import android.view.View
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import org.commonmark.node.StrongEmphasis
import android.text.style.ForegroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResearcherProfileView(
    author: AuthorResponse,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateToReader: (String, String) -> Unit,
    onUpdateData: (AuthorResponse) -> Unit,
    onSelectResearcher: (String, String) -> Unit
) {
    val context = LocalContext.current
    val authManager = com.company.skolab.di.AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val userName = cachedUser?.name ?: "Researcher"

    var activeChatPaperTitle by remember { mutableStateOf<String?>(null) }
    var activeSynergyCollab by remember { mutableStateOf<Pair<String, String>?>(null) }
    var networkCollaborators by remember { mutableStateOf<List<NetworkCollaborator>>(emptyList()) }
    val visibleCollaborators = remember { mutableStateListOf<NetworkCollaborator>() }
    val backupCollaborators = remember { mutableStateListOf<NetworkCollaborator>() }
    var citationHeatmap by remember { mutableStateOf<CitationHeatmap?>(null) }
    var journalRecommendations by remember { mutableStateOf<List<JournalRecommendation>>(emptyList()) }

    LaunchedEffect(author.id) {
        ActiveResearcherState.setActiveAuthor(author)
        visibleCollaborators.clear()
        backupCollaborators.clear()
        citationHeatmap = null
        journalRecommendations = emptyList()
        
        launch {
            val collabs = apiService.getNetworkCollaborators(author.id, limit = 50, excludeName = userName)
            networkCollaborators = collabs
            if (collabs.size > 20) {
                visibleCollaborators.addAll(collabs.take(20))
                backupCollaborators.addAll(collabs.drop(20))
            } else {
                visibleCollaborators.addAll(collabs)
            }
        }
        
        launch {
            val result = apiService.getCitationHeatmap(author.id)
            citationHeatmap = result
            if (result != null) {
                SkoLabAnalytics.logCitationHeatmapOpened(author.id)
            }
        }
        
        launch {
            journalRecommendations = apiService.getJournalAdvisor(author.id)
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    apiService.searchAuthor(author.display_name, author.id)?.let { onUpdateData(it) }
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(BgPrimary),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // ── Hero Header ──────────────────────────────────────
                item { ResearcherHeroCard(author = author, apiService = apiService, scope = scope, onUpdateData = onUpdateData) }

                // ── Stats Row (4 compact numbers) ────────────────────
                item {
                    StatsQuadRow(author = author)
                }

                // ── Suggested Connections ────────────────────────────
                item {
                    SuggestedConnectionsSection(
                        visibleCollaborators = visibleCollaborators,
                        onConnect = { collaborator ->
                            android.widget.Toast.makeText(
                                context,
                                "Connection request sent to ${collaborator.name}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            
                            visibleCollaborators.remove(collaborator)
                            if (backupCollaborators.isNotEmpty()) {
                                val nextCollab = backupCollaborators.removeAt(0)
                                visibleCollaborators.add(nextCollab)
                            }
                        },
                        onDismiss = { collaborator ->
                            visibleCollaborators.remove(collaborator)
                            if (backupCollaborators.isNotEmpty()) {
                                val nextCollab = backupCollaborators.removeAt(0)
                                visibleCollaborators.add(nextCollab)
                            }
                        },
                        onSelectProfile = onSelectResearcher
                    )
                }

                // ── Collaborators' New Articles ──────────────────────
                if (networkCollaborators.isNotEmpty()) {
                    item {
                        CollaboratorsNewArticlesSection(
                            collaborators = networkCollaborators.take(8),
                            onPaperClick = { title ->
                                android.widget.Toast.makeText(context, "Explore: $title", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                // ── Metrics Radar Ring ────────────────────────────────
                item {
                    if (author.metrics_computed) {
                        MetricsRadarSection(author = author)
                    } else {
                        MetricsAnalysisPendingCard(isLlmActive = author.llm_active)
                    }
                }

                // ── Frontier Metric Pills (Flipkart-style grid) ───────
                if (author.metrics_computed) {
                    item {
                        FrontierMetricPillsSection(author = author)
                    }
                }

                // ── Citation Heatmap ──────────────────────────────────
                citationHeatmap?.let { heatmap ->
                    item {
                        CitationHeatmapSection(heatmap = heatmap)
                    }
                }

                // ── Journal Advisor ───────────────────────────────────
                if (journalRecommendations.isNotEmpty()) {
                    item {
                        JournalAdvisorSection(recommendations = journalRecommendations)
                    }
                }


                // ── Publications ─────────────────────────────────────
                item {
                    LightSectionHeader(
                        title = "Publications",
                        subtitle = "${author.works_count} works",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        color = AccentIndigo
                    )
                }

                items(author.works.sortedByDescending { it.year ?: 0 }, key = { it.id ?: it.hashCode() }) { work ->
                    LightPublicationCard(
                        work = work,
                        apiService = apiService,
                        scope = scope,
                        onNavigateToReader = onNavigateToReader,
                        onDiscussClick = { paperTitle ->
                            activeChatPaperTitle = paperTitle
                        }
                    )
                }

                // ── Next Prediction ───────────────────────────────────
                if (author.metrics_computed && !author.next_prediction.isNullOrBlank()) {
                    item { PredictionCard(prediction = author.next_prediction) }
                } else if (!author.metrics_computed) {
                    item { PredictionCard(prediction = "**Next Frontier**: Metrics Unavailable\n\n**Toolkit**: N/A\n\n**Logic**: AI brief is currently unavailable due to limited credits or pending analysis.") }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        // ── WhatsApp Bottom Sheet ──
        activeChatPaperTitle?.let { paperTitle ->
            AuthorChatBottomSheet(
                authorId = author.id,
                authorName = author.display_name,
                paperTitle = paperTitle,
                onDismissRequest = { activeChatPaperTitle = null },
                apiService = apiService
            )
        }

        // ── Collaborator Synergy Bottom Sheet ──
        activeSynergyCollab?.let { (collabId, collabName) ->
            CollaboratorSynergyBottomSheet(
                collaboratorId = collabId,
                collaboratorName = collabName,
                onDismissRequest = { activeSynergyCollab = null },
                apiService = apiService
            )
        }
    }
}
