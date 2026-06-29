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

@Composable
fun DiscoveryDashboard(
    userName: String,
    researchFocus: String,
    onCategoryClick: (DiscoveryCategory) -> Unit,
    onPaperClick: (String) -> Unit,
    suggestedPeers: List<AuthorSuggestion>,
    suggestedPeersArticles: List<Work>,
    collaboratorsArticles: List<Work>,
    trendingPapers: List<Work>,
    isLoadingPeers: Boolean,
    onSelectResearcher: (String, String) -> Unit,
    onNavigateToReader: (String, String) -> Unit,
    onNavigateToChat: (String, String) -> Unit
) {
    val context = LocalContext.current
    val categories = remember {
        listOf(
            DiscoveryCategory("Authors",  "Find Researchers", Icons.Default.PersonSearch,   AccentTeal),
            DiscoveryCategory("Papers",   "Literature",       Icons.Default.Description,     AccentIndigo),
            DiscoveryCategory("Vault",    "Curated Lists",    Icons.Default.Bookmarks,       AccentEmerald),
            DiscoveryCategory("Trends",   "Rising Fields",    Icons.AutoMirrored.Filled.TrendingUp, AccentAmber)
        )
    }
    // Time-based greeting computed locally in this composable
    val greeting = remember {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good morning,"
            hour < 17 -> "Good afternoon,"
            else      -> "Good evening,"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgPrimary),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Welcome hero
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Brush.linearGradient(colors = listOf(AccentTeal.copy(alpha = 0.25f), AccentIndigo.copy(alpha = 0.05f))))
            ) {
                Box(
                    modifier = Modifier.background(Brush.verticalGradient(colors = listOf(BgCard, BgSubtle)))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = greeting,
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = userName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontFamily = DisplayFontFamily
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AccentTeal.copy(alpha = 0.08f),
                            border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "🔬 $researchFocus",
                                color = AccentTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // Search prompt
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BgElevated,
                            border = BorderStroke(1.dp, BorderLight),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Search, null, tint = AccentTeal, modifier = Modifier.size(18.dp))
                                Column {
                                    Text("Search any researcher", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("Type 3+ characters above for instant suggestions", color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Gamified Streak Card
        item {
            StreakCard()
        }

        // Tinder-style Paper Swiper
        if (trendingPapers.isNotEmpty()) {
            item {
                SwipeVaultCard(
                    papers = trendingPapers,
                    onSavePaper = { paper ->
                        android.widget.Toast.makeText(context, "Saved to Vault: ${paper.title}", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onSkipPaper = { paper ->
                        android.widget.Toast.makeText(context, "Skipped: ${paper.title}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // Category tiles
        item {
            Text(
                text = "EXPLORE",
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
        }

        item {
            val rows = categories.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { cat ->
                            DashboardTile(cat, onClick = { onCategoryClick(cat) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Suggested Peer Connections (Friend Suggestions) ──
        if (isLoadingPeers) {
            item {
                Text(
                    text = "SUGGESTED PEER CONNECTIONS",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(4.dp))
                SuggestedPeersShimmer()
            }
        } else if (suggestedPeers.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "SUGGESTED PEER CONNECTIONS",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suggestedPeers, key = { "peer-" + it.id }) { peer ->
                            DashboardPeerSuggestionCard(
                                peer = peer,
                                onClick = { onSelectResearcher(peer.display_name, peer.id) },
                                onMessageClick = { onNavigateToChat(peer.display_name, peer.id) }
                            )
                        }
                    }
                }
            }
        }

        // ── Collaborators' New Publications ──
        if (collaboratorsArticles.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "COLLABORATORS' NEW PUBLICATIONS",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(collaboratorsArticles, key = { if (!it.id.isNullOrBlank()) it.id else "work_${it.title.orEmpty()}_${it.year ?: 0}_${it.hashCode()}" }) { work ->
                            DashboardFriendArticleCard(
                                work = work,
                                onOpenPaper = {
                                    val url = work.doi ?: "https://doi.org"
                                    onNavigateToReader(url, work.title ?: "Paper")
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Recent Friends' New Articles ──
        if (suggestedPeersArticles.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "RECENT FRIENDS' NEW ARTICLES",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suggestedPeersArticles, key = { if (!it.id.isNullOrBlank()) it.id else "work_${it.title.orEmpty()}_${it.year ?: 0}_${it.hashCode()}" }) { work ->
                            DashboardFriendArticleCard(
                                work = work,
                                onOpenPaper = {
                                    val url = work.doi ?: "https://doi.org"
                                    onNavigateToReader(url, work.title ?: "Paper")
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Trending Papers ──
        if (trendingPapers.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "TRENDING PAPERS THIS WEEK",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(trendingPapers, key = { if (!it.id.isNullOrBlank()) it.id else "work_${it.title.orEmpty()}_${it.year ?: 0}_${it.hashCode()}" }) { work ->
                            DashboardFriendArticleCard(
                                work = work,
                                onOpenPaper = {
                                    val url = work.doi ?: "https://doi.org"
                                    onNavigateToReader(url, work.title ?: "Paper")
                                }
                            )
                        }
                    }
                }
            }
        }

        // Featured papers strip
        item {
            Text(
                text = "FEATURED RESEARCH",
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf(
                    Triple("Room-Temperature Superconductivity in Nitrogen-Doped Lutetium Hydride", "Dasenbrock-Gammon et al.", AccentTeal),
                    Triple("Observation of Quantum Gravitational Anomaly in Synthetic Matter", "Zhao et al.", AccentIndigo),
                    Triple("Direct Observation of Dark Matter Wind with Cryogenic Phonon Detectors", "Akerib et al.", AccentAmber),
                )) { (title, authors, color) ->
                    FeaturedResearchCard(title = title, authors = authors, color = color, onClick = { onPaperClick(title) })
                }
            }
        }

        item {
            Text(
                text = "CONFERENCE MATCHES",
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
        }

        item {
            ConferenceMatchRow()
        }

        item {
            Text(
                text = "AI GAP FINDER",
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
        }

        item {
            ResearchGapCard(researchFocus = researchFocus)
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}
