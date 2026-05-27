package com.open.entropy.ui.screens

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
import com.open.entropy.auth.AuthManager
import com.open.entropy.network.*
import com.open.entropy.state.ActiveResearcherState
import com.open.entropy.ui.components.*
import com.open.entropy.ui.theme.*
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

// ─────────────────────────────────────────────────────────────────
// MAIN DISCOVERY SCREEN
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun DiscoveryScreen(
    onNavigateToReader: (String, String) -> Unit,
    onPaperClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onTabNavigate: (String) -> Unit = {},
    onNavigateToChat: (String, String) -> Unit = { _, _ -> }
) {
    val apiService = remember { ApiService() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)

    val userName = cachedUser?.name ?: "Researcher"
    val researchFocus = cachedUser?.researchFocus ?: "General Research"

    var authorQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AuthorSuggestion>>(emptyList()) }
    var authorData by remember { mutableStateOf<AuthorResponse?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }
    var shouldSearchSuggestions by remember { mutableStateOf(true) }

    BackHandler(enabled = authorData != null) {
        authorData = null
        authorQuery = ""
        shouldSearchSuggestions = true
    }
    var lastProgrammaticQuery by remember { mutableStateOf<String?>(null) }
    
    // Suggested peers, articles, and trending papers for the main dashboard
    var suggestedPeers by remember { mutableStateOf<List<AuthorSuggestion>>(emptyList()) }
    var suggestedPeersArticles by remember { mutableStateOf<List<Work>>(emptyList()) }
    var collaboratorsArticles by remember { mutableStateOf<List<Work>>(emptyList()) }
    var trendingPapers by remember { mutableStateOf<List<Work>>(emptyList()) }
    var isLoadingPeers by remember { mutableStateOf(false) }

    LaunchedEffect(researchFocus) {
        isLoadingPeers = true
        try {
            val peers = apiService.getSimilarAuthors(researchFocus, limit = 5)
            suggestedPeers = peers

            val mapToWork: (OpenAlexWork) -> Work = { w ->
                Work(
                    title = w.title ?: "Untitled Paper",
                    year = w.publication_year ?: 2025,
                    doi = w.doi ?: "",
                    journal = w.primary_location?.source?.display_name ?: "Scientific Journal",
                    is_open_access = w.primary_location?.pdf_url != null,
                    citations = w.cited_by_count ?: 0,
                    authors = w.authorships?.mapNotNull { it.author?.display_name } ?: emptyList()
                )
            }
            
            // Friends' articles (suggested peers) - Concurrently fetched from live OpenAlex
            val peerArticlesDeferred = peers.take(3).map { peer ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val rawWorks = apiService.getAuthorWorks(peer.id, limit = 1)
                        rawWorks.firstOrNull()?.let { mapToWork(it) }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            suggestedPeersArticles = peerArticlesDeferred.awaitAll().filterNotNull()

            // Collaborators' new articles - Concurrently fetched from live OpenAlex
            val collabsArticlesList = mutableListOf<Work>()
            if (peers.isNotEmpty()) {
                val topPeer = peers.first()
                val collabs = apiService.getNetworkCollaborators(topPeer.id, limit = 4, excludeName = userName)
                val collabsDeferred = collabs.map { collab ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val rawWorks = apiService.getAuthorWorks(collab.id, limit = 1)
                            rawWorks.firstOrNull()?.let { mapToWork(it) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                collabsArticlesList.addAll(
                    collabsDeferred.awaitAll().filterNotNull()
                )
            }
            
            // Trending papers - Concurrently fetched from live OpenAlex
            val trending = apiService.getTrendingPapers(limit = 6)
            val mappedTrending = trending.map { mapToWork(it) }
            trendingPapers = mappedTrending

            // Fallback for collaborators articles if they are empty
            if (collabsArticlesList.isEmpty() && mappedTrending.isNotEmpty()) {
                collabsArticlesList.addAll(mappedTrending.take(3))
            }
            collaboratorsArticles = collabsArticlesList

        } catch (e: Exception) {
            Log.e("DiscoveryScreen", "Failed to load dashboard suggestions", e)
        } finally {
            isLoadingPeers = false
        }
    }

    // Debounced suggestions
    LaunchedEffect(authorQuery) {
        if (!shouldSearchSuggestions) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        if (authorQuery.length >= 3) {
            isSearchingSuggestions = true
            delay(300) // Industry-standard 300ms debounce
            suggestions = apiService.getAuthorSuggestions(authorQuery)
            isSearchingSuggestions = false
        } else {
            suggestions = emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // ── Top Bar ─────────────────────────────────────────
            LightTopBar(userName = userName, onProfileClick = onProfileClick)

            // ── Search + floating dropdown ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(20f)
            ) {
                // Search bar row (fixed height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    com.open.entropy.ui.components.primitives.GlassSearchBar(
                        value = authorQuery,
                        onValueChange = { newValue ->
                            val trimmedNew = newValue.trim()
                            val trimmedLast = lastProgrammaticQuery?.trim()
                            if (trimmedNew == trimmedLast) {
                                // Programmatic update or trailing space/IME echo - do not clear or search
                            } else {
                                shouldSearchSuggestions = true
                                lastProgrammaticQuery = null
                                authorData = null // Clear previous profile when user starts editing query
                            }
                            authorQuery = newValue
                        },
                        placeholder = "Search researchers, authors…",
                        isLoading = isSearchingSuggestions,
                        onClear = { 
                            shouldSearchSuggestions = false
                            lastProgrammaticQuery = null
                            authorQuery = ""
                            suggestions = emptyList()
                            authorData = null 
                            keyboardController?.hide()
                        },
                        onSearch = {
                            shouldSearchSuggestions = false
                            lastProgrammaticQuery = authorQuery
                            keyboardController?.hide()
                            scope.launch {
                                isLoading = true
                                suggestions = emptyList()
                                val data: com.open.entropy.network.AuthorResponse? = try {
                                    apiService.searchAuthor(authorQuery)
                                } catch (e: Exception) {
                                    Log.e("DiscoveryScreen", "searchAuthor failed", e)
                                    null
                                }
                                if (data == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Researcher not found. Check the name and try again.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                authorData = data
                                isLoading = false
                            }
                        }
                    )
                }

                // Dropdown floats BELOW the search bar — positioned absolutely using Column layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp)                 // sits just below the search bar
                        .padding(horizontal = 16.dp)
                        .zIndex(21f)
                ) {
                    AnimatedVisibility(
                        visible = authorQuery.length >= 3 && shouldSearchSuggestions && !isLoading && authorData == null,
                        enter = fadeIn(tween(150)) + expandVertically(tween(220), expandFrom = androidx.compose.ui.Alignment.Top),
                        exit  = fadeOut(tween(100)) + shrinkVertically(tween(160), shrinkTowards = androidx.compose.ui.Alignment.Top),
                    ) {
                        LightSuggestionsDropdown(
                            suggestions = suggestions,
                            isLoading = isSearchingSuggestions,
                            shouldSearchSuggestions = shouldSearchSuggestions,
                            onSelect = { suggestion ->
                                shouldSearchSuggestions = false
                                lastProgrammaticQuery = suggestion.display_name
                                authorQuery = suggestion.display_name
                                suggestions = emptyList()
                                keyboardController?.hide()
                                scope.launch {
                                    isLoading = true
                                    val data: com.open.entropy.network.AuthorResponse? = try {
                                        apiService.searchAuthor(suggestion.display_name, suggestion.id)
                                    } catch (e: Exception) {
                                        Log.e("DiscoveryScreen", "searchAuthor on suggestion select failed", e)
                                        null
                                    }
                                    if (data == null) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Could not load profile for ${suggestion.display_name}.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    authorData = data
                                    isLoading = false
                                }
                            }
                        )
                    }
                }
            }


            // ── Main Content ────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = Triple(isLoading, authorData, authorQuery),
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInVertically(tween(300)) { 40 })
                            .togetherWith(fadeOut(tween(200)))
                    }
                ) { (loading, data, _) ->
                    when {
                        loading -> LoadingResearcherSkeleton()
                        data != null -> ResearcherProfileView(
                            author = data,
                            apiService = apiService,
                            scope = scope,
                            onNavigateToReader = onNavigateToReader,
                            onUpdateData = { authorData = it },
                            onSelectResearcher = { name, id ->
                                shouldSearchSuggestions = false
                                lastProgrammaticQuery = name
                                authorQuery = name
                                keyboardController?.hide()
                                scope.launch {
                                    isLoading = true
                                    val data: com.open.entropy.network.AuthorResponse? = try {
                                        apiService.searchAuthor(name, id)
                                    } catch (e: Exception) {
                                        Log.e("DiscoveryScreen", "searchAuthor on similar researcher select failed", e)
                                        null
                                    }
                                    if (data == null) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Could not load profile for $name.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    authorData = data
                                    isLoading = false
                                }
                            }
                        )
                        else -> DiscoveryDashboard(
                            userName = userName,
                            researchFocus = researchFocus,
                            onCategoryClick = { category ->
                                when (category.title) {
                                    "Authors"  -> {
                                        shouldSearchSuggestions = false
                                        lastProgrammaticQuery = ""
                                        authorQuery = ""
                                    }
                                    "Papers"   -> onTabNavigate("search")
                                    "Vault"    -> onTabNavigate("library")
                                    "Trends"   -> onTabNavigate("nexus")
                                }
                            },
                            onPaperClick = onPaperClick,
                            suggestedPeers = suggestedPeers,
                            suggestedPeersArticles = suggestedPeersArticles,
                            collaboratorsArticles = collaboratorsArticles,
                            trendingPapers = trendingPapers,
                            isLoadingPeers = isLoadingPeers,
                            onSelectResearcher = { name, id ->
                                shouldSearchSuggestions = false
                                lastProgrammaticQuery = name
                                authorQuery = name
                                keyboardController?.hide()
                                scope.launch {
                                    isLoading = true
                                    val data: com.open.entropy.network.AuthorResponse? = try {
                                        apiService.searchAuthor(name, id)
                                    } catch (e: Exception) {
                                        Log.e("DiscoveryScreen", "searchAuthor on suggested peer select failed", e)
                                        null
                                    }
                                    if (data == null) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Could not load profile for $name.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    authorData = data
                                    isLoading = false
                                }
                            },
                            onNavigateToReader = onNavigateToReader,
                            onNavigateToChat = onNavigateToChat
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// LIGHT TOP BAR
// ─────────────────────────────────────────────────────────────────

@Composable
fun LightTopBar(userName: String, onProfileClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BgCard,
        shadowElevation = 1.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.open.entropy.ui.components.BrandMark(style = MaterialTheme.typography.titleLarge)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = TextSecondary
                    )
                }
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(HeroGradient))
                        .clickable { onProfileClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userName.take(1).uppercase(),
                        color = TextOnAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// SUGGESTIONS DROPDOWN — scrollable white card, up to 10 results
// ─────────────────────────────────────────────────────────────────

@Composable
fun DropdownSkeletonRow(shimmerAlpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Avatar circle pulse
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(BorderLight.copy(alpha = shimmerAlpha))
        )
        // Two line text pulse
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
        }
    }
}

@Composable
fun LightSuggestionsDropdown(
    suggestions: List<AuthorSuggestion>,
    isLoading: Boolean,
    shouldSearchSuggestions: Boolean = true,
    onSelect: (AuthorSuggestion) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dropdownShimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dropdownShimmerAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(20.dp),
                clip = false
            ),
        shape = RoundedCornerShape(20.dp),
        color = BgCard.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.6f))
    ) {
        Column {
            // Header — shows how many matches with soft gradient
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                BgElevated,
                                BgCard.copy(alpha = 0.4f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        isLoading -> "Searching researchers..."
                        !shouldSearchSuggestions -> ""
                        suggestions.isEmpty() -> "No researchers found"
                        else -> "${suggestions.size} researchers found"
                    },
                    color = TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                if (shouldSearchSuggestions || isLoading) {
                    Text(
                        text = "via OpenAlex",
                        color = AccentTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
            HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)

            if (isLoading) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    repeat(3) { index ->
                        DropdownSkeletonRow(shimmerAlpha = shimmerAlpha)
                        if (index < 2) {
                            HorizontalDivider(
                                color = BorderLight.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                    }
                }
            } else if (suggestions.isEmpty()) {
                if (shouldSearchSuggestions) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AccentTeal.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = AccentTeal.copy(alpha = 0.6f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No matching researchers found",
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DisplayFontFamily
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Double-check the spelling or try a different name",
                            color = TextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Scrollable list — max height so it doesn't fill whole screen
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp)
                ) {
                    itemsIndexed(suggestions) { index, suggestion ->
                        val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
                        val color = avatarColors[index % avatarColors.size]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(suggestion) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Avatar initials circle with dynamic soft ring
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                color.copy(alpha = 0.15f),
                                                color.copy(alpha = 0.03f)
                                            )
                                        )
                                    )
                                    .border(BorderStroke(1.dp, color.copy(alpha = 0.25f)), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = suggestion.display_name.take(1).uppercase(),
                                    color = color,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    fontFamily = DisplayFontFamily
                                )
                            }

                            // Name + institution + field
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = suggestion.display_name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = DisplayFontFamily
                                )
                                if (suggestion.institution.isNotBlank() && suggestion.institution != "Independent Researcher") {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.AccountBalance,
                                            null,
                                            tint = color.copy(alpha = 0.8f),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = suggestion.institution,
                                            color = color.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (!suggestion.field_of_study.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = suggestion.field_of_study,
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Tap arrow - Enclosed in sleek pill icon
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = "View profile",
                                    tint = color,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        if (index < suggestions.lastIndex) {
                            HorizontalDivider(
                                color = BorderLight.copy(alpha = 0.4f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────
// LOADING SKELETON
// ─────────────────────────────────────────────────────────────────

@Composable
fun LoadingResearcherSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            // Hero skeleton
            Box(
                Modifier.fillMaxWidth().height(140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) {
                    Box(
                        Modifier.weight(1f).height(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BorderLight.copy(alpha = shimmerAlpha))
                    )
                }
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().height(200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
        }
        items(3) {
            Box(
                Modifier.fillMaxWidth().height(90.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// RESEARCHER PROFILE VIEW
// ─────────────────────────────────────────────────────────────────

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
    val authManager = remember { com.open.entropy.auth.AuthManager(context) }
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
            citationHeatmap = apiService.getCitationHeatmap(author.id)
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
                contentPadding = PaddingValues(bottom = 0.dp)
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

                items(author.works.sortedByDescending { it.year ?: 0 }) { work ->
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

// ─────────────────────────────────────────────────────────────────
// HERO CARD
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearcherHeroCard(
    author: AuthorResponse,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onUpdateData: (AuthorResponse) -> Unit
) {
    var isRefreshingInner by remember { mutableStateOf(false) }
    // Animated entry
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -30 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = BgCard,
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Box {
                    // Gradient top strip
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Brush.horizontalGradient(HeroGradient))
                    )
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = author.display_name,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontFamily = DisplayFontFamily
                                )
                                Spacer(Modifier.height(1.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AccountBalance,
                                        null,
                                        tint = AccentTeal,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = author.institution,
                                        fontSize = 13.sp,
                                        color = AccentTeal,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!author.field_of_study.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = author.field_of_study,
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                            // Refresh + avatar column
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Initials avatar
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(HeroGradient)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = author.display_name.take(1).uppercase(),
                                        color = TextOnAccent,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                // Refresh button
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            isRefreshingInner = true
                                            apiService.refreshAuthor(author.display_name)
                                            apiService.searchAuthor(author.display_name, author.id)?.let { onUpdateData(it) }
                                            isRefreshingInner = false
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = AccentTealLight
                                    )
                                ) {
                                    if (isRefreshingInner) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentTeal)
                                    } else {
                                        Icon(Icons.Default.Refresh, null, tint = AccentTeal, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        // Expertise chips
                        if (author.expertise.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                author.expertise.take(5).forEach { tag ->
                                    ExpertiseChip(tag)
                                }
                            }
                        }

                        // Academic history
                        if (author.academic_history.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "CAREER PATH",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            author.academic_history.take(3).forEach { hist ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(AccentTeal)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(hist, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        // ── LinkedIn style action buttons ───────────────────
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))
                        
                        var connectionState by remember { mutableStateOf("Connect") }
                        var showMessageDialog by remember { mutableStateOf(false) }
                        var showCollaborateDialog by remember { mutableStateOf(false) }
                        val context = LocalContext.current
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isRequested = connectionState == "Requested"
                            val buttonColor = if (isRequested) BorderLight else Color(0xFF2E7D32) // Dark Green
                            val contentColor = if (isRequested) TextSecondary else Color.White
                            
                            Button(
                                onClick = {
                                    connectionState = if (isRequested) "Connect" else "Requested"
                                    val msg = if (isRequested) "Connection request cancelled" else "Connection request sent to ${author.display_name}!"
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonColor,
                                    contentColor = contentColor
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isRequested) Icons.Default.Check else Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isRequested) "Requested" else "Connect",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            
                            OutlinedButton(
                                onClick = { showMessageDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentTeal
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Mail, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Message", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            
                            OutlinedButton(
                                onClick = { showCollaborateDialog = true },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentIndigo
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Groups, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Collaborate", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }

                        // Message Dialog
                        if (showMessageDialog) {
                            var messageText by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showMessageDialog = false },
                                title = {
                                    Text(
                                        "Message ${author.display_name}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontFamily = DisplayFontFamily
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Send a direct professional message. They will receive it in their peer inbox.",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        OutlinedTextField(
                                            value = messageText,
                                            onValueChange = { messageText = it },
                                            placeholder = { Text("Write your message here…", fontSize = 13.sp) },
                                            modifier = Modifier.fillMaxWidth().height(100.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentTeal,
                                                unfocusedBorderColor = BorderLight
                                            ),
                                            maxLines = 4
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (messageText.isNotBlank()) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Message sent successfully!",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                showMessageDialog = false
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Please type a message first",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                                    ) {
                                        Text("Send Message", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showMessageDialog = false }) {
                                        Text("Cancel", color = TextSecondary)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                containerColor = BgCard
                            )
                        }

                        // Collaborate Dialog
                        if (showCollaborateDialog) {
                            val templates = listOf(
                                "Co-author Paper: Requesting partnership on upcoming publication in ${author.field_of_study ?: "your field"}.",
                                "Joint Grant Proposal: Collaborate on national or global grant funding applications.",
                                "Guest Lecture Invitation: Invite ${author.display_name} to speak at your host institution."
                            )
                            var selectedIndex by remember { mutableStateOf(0) }
                            var collabDetails by remember { mutableStateOf("") }
                            
                            AlertDialog(
                                onDismissRequest = { showCollaborateDialog = false },
                                title = {
                                    Text(
                                        "Collaborate with ${author.display_name}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontFamily = DisplayFontFamily
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Select a collaboration track to build your proposal invitation:",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        
                                        templates.forEachIndexed { index, template ->
                                            val isSelected = selectedIndex == index
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) AccentIndigo.copy(alpha = 0.08f) else Color.Transparent)
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) AccentIndigo else BorderLight,
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { selectedIndex = index }
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { selectedIndex = index },
                                                    colors = RadioButtonDefaults.colors(selectedColor = AccentIndigo)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = template.substringBefore(":"),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) AccentIndigo else TextPrimary
                                                    )
                                                    Text(
                                                        text = template.substringAfter(": "),
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                        }
                                        
                                        OutlinedTextField(
                                            value = collabDetails,
                                            onValueChange = { collabDetails = it },
                                            placeholder = { Text("Add any personal note or proposal link (optional)…", fontSize = 12.sp) },
                                            modifier = Modifier.fillMaxWidth().height(70.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentIndigo,
                                                unfocusedBorderColor = BorderLight
                                            )
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Collaboration proposal sent to ${author.display_name}!",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            showCollaborateDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                                    ) {
                                        Text("Send Proposal", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCollaborateDialog = false }) {
                                        Text("Cancel", color = TextSecondary)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                containerColor = BgCard
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpertiseChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentTeal.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            color = AccentTeal,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// METRICS ANALYSIS PENDING — shown when LLM hasn't computed metrics yet
// ─────────────────────────────────────────────────────────────────

@Composable
fun MetricsAnalysisPendingCard(isLlmActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        border = BorderStroke(1.dp, if (isLlmActive) AccentTeal.copy(alpha = 0.25f) else BorderLight)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isLlmActive) AccentTeal.copy(alpha = 0.12f) else BorderLight.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Science, null,
                    tint = if (isLlmActive) AccentTeal.copy(alpha = alpha) else TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLlmActive) "Deep Analysis Queued" else "AI Metrics Unavailable",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = if (isLlmActive) 
                        "AI metrics (Disruption, Novelty, Future Impact\u2026) are computing in the background. Pull down to refresh."
                    else 
                        "The AI service is currently out of limit or unconfigured. Displaying verified base metadata from OpenAlex.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// STATS QUAD ROW — 4 key numbers
// ─────────────────────────────────────────────────────────────────


@Composable
fun StatsQuadRow(author: AuthorResponse) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); visible = true }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(400)) + expandVertically(tween(400))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("H-Index", author.h_index.toString(), AccentTeal),
                Triple("i10-Index", author.i10_index.toString(), AccentIndigo),
                Triple("Works", author.works_count.toString(), AccentEmerald),
                Triple("Citations", formatCitationsCount(author.cited_by_count), AccentAmber)
            ).forEach { (label, value, color) ->
                StatCard(label = label, value = value, color = color, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = DisplayFontFamily
            )
            Text(
                text = label,
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

fun formatCitationsCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000     -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else               -> count.toString()
}

// ─────────────────────────────────────────────────────────────────
// METRICS RADAR RING — animated arc chart for 8 scores
// ─────────────────────────────────────────────────────────────────

data class MetricArcEntry(val label: String, val value: Float, val color: Color, val icon: ImageVector)

@Composable
fun MetricsRadarSection(author: AuthorResponse) {
    val metrics = remember(author) {
        listOf(
            MetricArcEntry("Disruption",  (author.disruption_score.toFloat() / 100f).coerceIn(0f, 1f),    MetricDisruptionColor, Icons.Default.FlashOn),
            MetricArcEntry("Novelty",     (author.semantic_novelty.toFloat() / 100f).coerceIn(0f, 1f),    MetricNoveltyColor,    Icons.Default.AutoGraph),
            MetricArcEntry("Fut. Impact", (author.future_impact_score.toFloat() / 100f).coerceIn(0f, 1f), MetricFutureImpactColor, Icons.Default.Psychology),
            MetricArcEntry("Influence",   (author.network_centrality.toFloat() / 100f).coerceIn(0f, 1f),  MetricInfluenceColor,  Icons.Default.Hub),
            MetricArcEntry("Creativity",  (author.average_creativity.toFloat() / 100f).coerceIn(0f, 1f),  MetricCreativityColor, Icons.Default.Lightbulb),
            MetricArcEntry("Complexity",  (author.average_complexity.toFloat() / 100f).coerceIn(0f, 1f),  MetricComplexityColor, Icons.Default.Science),
            MetricArcEntry("Open Sci.",   (author.open_science_score.toFloat() / 100f).coerceIn(0f, 1f),  MetricOpenScienceColor, Icons.Default.Public),
            MetricArcEntry("Collab.",     (author.collaboration_diversity.toFloat() / 100f).coerceIn(0f, 1f), MetricCollabColor, Icons.Default.Groups),
        )
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }

    Column(modifier = Modifier.padding(16.dp)) {
        LightSectionHeader("Frontier Scores", "8 research dimensions", Icons.Default.BubbleChart, AccentTeal)
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, BorderLight)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Ring arc chart
                AnimatedVisibility(visible = visible, enter = fadeIn(tween(600))) {
                    MetricOctagonChart(metrics = metrics)
                }
                Spacer(Modifier.height(16.dp))
                // Legend grid 2x4
                val chunked = metrics.chunked(2)
                chunked.forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { m ->
                            Row(
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(m.color))
                                Spacer(Modifier.width(6.dp))
                                Text(m.label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "${(m.value * 100).toInt()}",
                                    color = m.color,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = DisplayFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricOctagonChart(metrics: List<MetricArcEntry>) {
    val animProgress by rememberInfiniteTransition(label = "").let {
        // We use a once-animated approach instead
        remember { mutableStateOf(0f) }
    }.let { state ->
        // Animate to 1.0 once
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, animationSpec = tween(1200, easing = FastOutSlowInEasing)) }
        anim.asState()
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val radius = minOf(cx, cy) - 24.dp.toPx()
        val n = metrics.size
        val angleStep = 2 * PI.toFloat() / n
        val startAngle = -PI.toFloat() / 2

        // Background polygon
        val bgPath = Path()
        metrics.indices.forEach { i ->
            val angle = startAngle + i * angleStep
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            if (i == 0) bgPath.moveTo(x, y) else bgPath.lineTo(x, y)
        }
        bgPath.close()
        drawPath(bgPath, color = BgElevated)
        drawPath(bgPath, color = BorderLight, style = Stroke(1.dp.toPx()))

        // Grid rings
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { fraction ->
            val gridPath = Path()
            metrics.indices.forEach { i ->
                val angle = startAngle + i * angleStep
                val r = radius * fraction
                val x = cx + r * cos(angle)
                val y = cy + r * sin(angle)
                if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
            }
            gridPath.close()
            drawPath(gridPath, color = BorderLight.copy(alpha = 0.6f), style = Stroke(0.5.dp.toPx()))
        }

        // Spokes
        metrics.indices.forEach { i ->
            val angle = startAngle + i * angleStep
            drawLine(
                color = BorderLight,
                start = Offset(cx, cy),
                end = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // Data polygon (animated)
        val dataPath = Path()
        metrics.forEachIndexed { i, m ->
            val angle = startAngle + i * angleStep
            val r = radius * m.value * animProgress
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, brush = Brush.radialGradient(
            colors = listOf(AccentTeal.copy(alpha = 0.25f), AccentIndigo.copy(alpha = 0.1f)),
            center = Offset(cx, cy),
            radius = radius
        ))
        drawPath(dataPath, color = AccentTeal.copy(alpha = 0.8f), style = Stroke(2.dp.toPx()))

        // Metric dots
        metrics.forEachIndexed { i, m ->
            val angle = startAngle + i * angleStep
            val r = radius * m.value * animProgress
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            drawCircle(color = m.color, radius = 5.dp.toPx(), center = Offset(x, y))
            drawCircle(color = BgCard, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// FRONTIER METRIC PILLS — Flipkart-style compact cards
// ─────────────────────────────────────────────────────────────────

@Composable
fun FrontierMetricPillsSection(author: AuthorResponse) {
    data class MetricPill(val label: String, val value: String, val sub: String, val color: Color, val icon: ImageVector)

    val pills = remember(author) {
        val accelVal = author.citation_acceleration.toInt()
        val accelStr = if (accelVal >= 0) "+$accelVal" else accelVal.toString()
        listOf(
            MetricPill("Disruption",      "${author.disruption_score.toInt()}%",       "Research Disruption",    MetricDisruptionColor,   Icons.Default.FlashOn),
            MetricPill("Novelty",         "${author.semantic_novelty.toInt()}%",        "Semantic Novelty",       MetricNoveltyColor,      Icons.Default.AutoGraph),
            MetricPill("Future Impact",   "${author.future_impact_score.toInt()}%",     "Predicted Impact",       MetricFutureImpactColor, Icons.Default.Psychology),
            MetricPill("Influence",       "${author.network_centrality.toInt()}%",      "Network Centrality",     MetricInfluenceColor,    Icons.Default.Hub),
            MetricPill("Creativity",      "${author.average_creativity.toInt()}%",      "Avg Creativity",         MetricCreativityColor,   Icons.Default.Lightbulb),
            MetricPill("Complexity",      "${author.average_complexity.toInt()}%",      "Avg Complexity",         MetricComplexityColor,   Icons.Default.Science),
            MetricPill("Open Science",    "${author.open_science_score.toInt()}%",      "Openness Score",         MetricOpenScienceColor,  Icons.Default.Public),
            MetricPill("Collaboration",   "${author.collaboration_diversity.toInt()}%", "Diversity Index",        MetricCollabColor,       Icons.Default.Groups),
            MetricPill("Cit. Accel.",     accelStr,                                     "Citation Growth",        MetricInfluenceColor,    Icons.AutoMirrored.Filled.TrendingUp),
            MetricPill("Consistency",     "${author.research_consistency.toInt()}%",    "Research Consistency",   MetricConsistencyColor,  Icons.Default.Timeline),
            MetricPill("Interdiscipl.",   "${author.interdisciplinary_index.toInt()}%", "Cross-domain Reach",     AccentViolet,            Icons.Default.AccountTree),
            MetricPill("Policy Impact",   "${author.policy_patent_score.toInt()}",     "Policy & Patent Score",  MetricPolicyColor,       Icons.Default.Gavel),
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        LightSectionHeader("Metrics Breakdown", "12 dimensions", Icons.Default.Dashboard, AccentIndigo)
        Spacer(Modifier.height(10.dp))

        // 2-column grid with staggered animation
        val pairs = pills.chunked(2)
        pairs.forEachIndexed { rowIdx, pair ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(rowIdx * 80L); visible = true }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 20 }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { pill ->
                        MetricPillCard(pill.label, pill.value, pill.sub, pill.color, pill.icon, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MetricPillCard(
    label: String,
    value: String,
    subtitle: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = BgCard,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Icon box
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    color = color,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = DisplayFontFamily
                )
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                // Mini progress bar
                Spacer(Modifier.height(4.dp))
                val pct = value.replace("%", "").toFloatOrNull()?.div(100f) ?: 0f
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = color,
                    trackColor = color.copy(alpha = 0.12f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// PUBLICATION CARD — light, expandable
// ─────────────────────────────────────────────────────────────────

@Composable
fun LightPublicationCard(
    work: com.open.entropy.network.Work,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateToReader: (String, String) -> Unit,
    onDiscussClick: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var summaryData by remember { mutableStateOf<SummaryResponse?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                isExpanded = !isExpanded
                if (isExpanded && summaryData == null && !isSummarizing) {
                    scope.launch {
                        isSummarizing = true
                        summaryData = apiService.summarizeWork(work.title ?: "", work.doi)
                        isSummarizing = false
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title
            MarkdownText(
                markdown = formatScientificTitle(work.title ?: "Untitled"),
                color = TextPrimary,
                fontSize = 13.sp
            )

            // Journal
            if (!work.journal.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = work.journal,
                    color = AccentTeal,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    fontFamily = BodyFontFamily
                )
            }

            // Chips row
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                work.year?.let { PubChip(it.toString(), AccentTeal) }
                PubChip("${work.citations ?: 0} cites", AccentIndigo)
                if ((work.impact_factor ?: 0.0) > 0) PubChip("IF ${work.impact_factor}", AccentAmber)
                if ((work.creativity_score ?: 0.0) > 0) PubChip("Creativity ${work.creativity_score?.toInt()}", AccentViolet)
                val dVal = work.disruption_score ?: 0.0
                val dPct = if (dVal > 0.0 && dVal <= 1.0) (dVal * 100).toInt() else dVal.toInt()
                if (dPct > 0) PubChip("Disruption $dPct%", AccentRose)
                if (work.is_open_access == true) PubChip("Open Access", AccentEmerald)
            }

            // Expand arrow
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(Modifier.height(10.dp))
                    when {
                        isSummarizing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentTeal)
                                Spacer(Modifier.width(8.dp))
                                Text("Loading AI summary…", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                        summaryData != null -> {
                            summaryData!!.bullets.take(3).forEach { bullet ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .offset(y = 5.dp)
                                            .clip(CircleShape)
                                            .background(AccentTeal)
                                    )
                                    MarkdownText(
                                        markdown = bullet,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val url = work.doi?.let { if (it.startsWith("http")) it else "https://doi.org/$it" }
                                            ?: "https://scholar.google.com/scholar?q=${work.title}"
                                        onNavigateToReader(url, work.title ?: "Article")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Read Paper", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { onDiscussClick(work.title ?: "Article") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF128C7E)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Discuss", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PubChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// PREDICTION CARD
// ─────────────────────────────────────────────────────────────────

data class ParsedPrediction(
    val frontier: String?,
    val toolkit: List<String>?,
    val toolkitRaw: String?,
    val logic: String?,
    val isParsedSuccessfully: Boolean
)

private fun parsePrediction(raw: String): ParsedPrediction {
    var frontier: String? = null
    var toolkit: List<String>? = null
    var toolkitRaw: String? = null
    var logic: String? = null

    val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
        val cleanLine = line.replace("**", "").replace("*", "").trim()
        if (cleanLine.startsWith("Next Frontier:", ignoreCase = true)) {
            frontier = cleanLine.substring("Next Frontier:".length).trim()
        } else if (cleanLine.startsWith("Toolkit:", ignoreCase = true)) {
            toolkitRaw = cleanLine.substring("Toolkit:".length).trim()
            toolkit = toolkitRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else if (cleanLine.startsWith("Logic:", ignoreCase = true)) {
            logic = cleanLine.substring("Logic:".length).trim()
        }
    }

    if (frontier == null || toolkit == null || logic == null) {
        val frontierRegex = Regex("""(?i)(?:\*+|)Next\s+Frontier(?:\*+|):\s*(.*?)(?=(?:\*+|)Toolkit(?:\*+|):|(?:\*+|)Logic(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)
        val toolkitRegex = Regex("""(?i)(?:\*+|)Toolkit(?:\*+|):\s*(.*?)(?=(?:\*+|)Next\s+Frontier(?:\*+|):|(?:\*+|)Logic(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)
        val logicRegex = Regex("""(?i)(?:\*+|)Logic(?:\*+|):\s*(.*?)(?=(?:\*+|)Next\s+Frontier(?:\*+|):|(?:\*+|)Toolkit(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)

        frontierRegex.find(raw)?.let { match ->
            val value = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (value.isNotEmpty()) frontier = value
        }
        toolkitRegex.find(raw)?.let { match ->
            val toolsStr = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (toolsStr.isNotEmpty()) {
                toolkitRaw = toolsStr
                toolkit = toolsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        logicRegex.find(raw)?.let { match ->
            val value = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (value.isNotEmpty()) logic = value
        }
    }

    val isParsedSuccessfully = frontier != null || logic != null || toolkit != null
    return ParsedPrediction(
        frontier = frontier,
        toolkit = toolkit,
        toolkitRaw = toolkitRaw,
        logic = logic,
        isParsedSuccessfully = isParsedSuccessfully
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PredictionCard(prediction: String) {
    val parsed = remember(prediction) { parsePrediction(prediction) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LightSectionHeader("Next Prediction", "AI forecast", Icons.Default.TipsAndUpdates, AccentIndigo)
        Spacer(Modifier.height(10.dp))

        if (parsed.isParsedSuccessfully) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 1. Next Frontier Section
                    parsed.frontier?.let { frontierText ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = AccentIndigoLight,
                            border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Explore,
                                        contentDescription = null,
                                        tint = AccentIndigo,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "RESEARCH FRONTIER",
                                        style = Typography.labelSmall,
                                        color = AccentIndigo,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                MarkdownText(
                                    markdown = frontierText,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // 2. Toolkit Section
                    parsed.toolkit?.let { tools ->
                        if (parsed.frontier != null) {
                            Spacer(Modifier.height(16.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = null,
                                    tint = AccentTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "TECHNICAL TOOLKIT",
                                    style = Typography.labelSmall,
                                    color = AccentTeal,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            
                            val allToolsConcise = remember(tools) { tools.all { it.length < 35 } }
                            if (allToolsConcise) {
                                FlowRow(
                                    modifier = Modifier.padding(start = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    tools.forEach { tool ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = AccentTealLight,
                                            border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .background(AccentTeal, CircleShape)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = tool,
                                                    style = Typography.bodySmall,
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                parsed.toolkitRaw?.let { rawToolkit ->
                                    MarkdownText(
                                        markdown = rawToolkit,
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. Logic Section
                    parsed.logic?.let { logicText ->
                        if (parsed.frontier != null || parsed.toolkit != null) {
                            Spacer(Modifier.height(16.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = AccentViolet,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "STRATEGIC LOGIC",
                                    style = Typography.labelSmall,
                                    color = AccentViolet,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp)
                                    .height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(AccentViolet.copy(alpha = 0.4f), RoundedCornerShape(1.5.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                MarkdownText(
                                    markdown = logicText,
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Fallback to legacy style if parsing fails
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = AccentIndigoLight,
                border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = AccentIndigo,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    MarkdownText(
                        markdown = prediction,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────

@Composable
fun LightSectionHeader(title: String, subtitle: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = DisplayFontFamily)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DISCOVERY DASHBOARD — shown before search
// ─────────────────────────────────────────────────────────────────

data class DiscoveryCategory(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

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

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgPrimary),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
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
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .align(Alignment.TopEnd)
                            .background(Brush.radialGradient(colors = listOf(AccentTeal.copy(alpha = 0.07f), Color.Transparent)))
                    )
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Welcome back,",
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
                        items(collaboratorsArticles) { work ->
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
                        items(suggestedPeersArticles) { work ->
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
                        items(trendingPapers) { work ->
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

@Composable
fun ConferenceMatchRow() {
    val conferences = remember {
        listOf(
            Triple("NeurIPS 2026", "ML & Computational Neuroscience", "Deadline: 14d"),
            Triple("CVPR 2026", "Computer Vision & Pattern Recog.", "Deadline: 45d"),
            Triple("ICML 2026", "International Conf. on ML", "Deadline: 60d"),
            Triple("KDD 2026", "Knowledge Discovery & Data Mining", "Deadline: 90d")
        )
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        itemsIndexed(conferences) { index, (name, scope, deadline) ->
            val colors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet)
            val color = colors[index % colors.size]
            Surface(
                modifier = Modifier.width(220.dp).height(120.dp),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                            .background(color)
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = name,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = DisplayFontFamily
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = scope,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = color.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = deadline,
                                    color = color,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "${98 - (index * 4)}% match",
                                color = AccentEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResearchGapCard(researchFocus: String) {
    var isGenerating by remember { mutableStateOf(false) }
    var generatedGap by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AccentIndigoLight,
        border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentIndigo.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Psychology, null, tint = AccentIndigo, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        text = "AI Research Gap Finder",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = DisplayFontFamily
                    )
                    Text(
                        text = "Trained on $researchFocus literature",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (generatedGap == null) {
                Text(
                    text = "Analyze recent OpenAlex & Google Scholar publications to discover untapped research domains and missing links in your field.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            delay(1500)
                            generatedGap = "At the intersection of $researchFocus and deep neural systems, there is an unexplored gap in multi-scale molecular dynamics simulation using transformer-based physical priors under high pressure. Existing literature focuses heavily on standard pressure models without thermodynamic extrapolation."
                            isGenerating = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextOnAccent)
                        Spacer(Modifier.width(8.dp))
                        Text("Analyzing publications...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Find Research Gaps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    text = generatedGap!!,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { generatedGap = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = AccentIndigo),
                        border = BorderStroke(1.dp, AccentIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { /* Search gap in app */ },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Explore Papers", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
fun DashboardTile(category: DiscoveryCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "tileScale")

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(110.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, category.color.copy(alpha = 0.20f))
    ) {
        Box(
            modifier = Modifier.background(Brush.verticalGradient(colors = listOf(BgCard, category.color.copy(alpha = 0.03f))))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(category.color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(category.icon, null, tint = category.color, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(category.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = DisplayFontFamily)
                    Text(category.subtitle, color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun FeaturedResearchCard(title: String, authors: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(220.dp).height(120.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Box {
            // Color strip left
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(color)
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                Text(authors, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


fun formatScientificTitle(title: String): String = title
    .replace("&nbsp;", " ")
    .trim()

// Legacy compat stubs so other screens referencing old composables still compile
@Composable
fun ProCard(modifier: Modifier = Modifier, gradient: List<Color> = listOf(BgCard), content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = BgCard, shadowElevation = 2.dp) { content() }
}

@Composable
fun SectionHeader(text: String, color: Color) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
fun TagLabel(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.08f), shape = RoundedCornerShape(4.dp), border = BorderStroke(0.5.dp, color.copy(alpha = 0.15f))) {
        Text(text, color = color.copy(alpha = 0.9f), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
fun CompanyFooter() {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), shape = RoundedCornerShape(16.dp), color = BgCard, shadowElevation = 1.dp) {
        Text("Powered by SkoLab", modifier = Modifier.padding(16.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
fun FooterLink(text: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(text, color = AccentTeal, fontSize = 11.sp, modifier = Modifier.clickable { uriHandler.openUri(url) })
}

@Composable
fun MiniMetric(label: String, value: Int, color: Color, modifier: Modifier = Modifier, customValueText: String? = null) {
    Surface(color = BgCard, shape = RoundedCornerShape(12.dp), shadowElevation = 1.dp, modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label.uppercase(), color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text(customValueText ?: value.toString(), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DisplayFontFamily)
        }
    }
}

@Composable
fun InteractiveIconButton(icon: ImageVector, isLoading: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = !isLoading, modifier = Modifier.size(36.dp)) {
        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        else Icon(icon, null, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun ProButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, gradient: List<Color> = TealGradient) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = AccentTeal), shape = RoundedCornerShape(10.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

// Legacy AuthorDetailView kept for any navigation back-compat
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuthorDetailView(
    author: AuthorResponse,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateToReader: (String, String) -> Unit,
    onUpdateData: (AuthorResponse) -> Unit
) = ResearcherProfileView(
    author = author,
    apiService = apiService,
    scope = scope,
    onNavigateToReader = onNavigateToReader,
    onUpdateData = onUpdateData,
    onSelectResearcher = { _, _ -> }
)

data class ResearchMetric(val label: String, val value: Int, val color: Color, val icon: ImageVector, val isPercentage: Boolean = true)

// ─────────────────────────────────────────────────────────────────
// SUGGESTED CONNECTIONS SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun SuggestedConnectionsSection(
    visibleCollaborators: List<NetworkCollaborator>,
    onConnect: (NetworkCollaborator) -> Unit,
    onDismiss: (NetworkCollaborator) -> Unit,
    onSelectProfile: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Suggested Connections",
                subtitle = "Co-authors & collaborators",
                icon = Icons.Default.People,
                color = AccentTeal
            )
        }
        Spacer(Modifier.height(10.dp))
        
        if (visibleCollaborators.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No more suggested connections at this time.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(visibleCollaborators, key = { it.id }) { collaborator ->
                    SuggestedConnectionCard(
                        collaborator = collaborator,
                        onConnect = { onConnect(collaborator) },
                        onDismiss = { onDismiss(collaborator) },
                        onCardClick = { onSelectProfile(collaborator.name, collaborator.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestedConnectionCard(
    collaborator: NetworkCollaborator,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    onCardClick: () -> Unit
) {
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(collaborator.id.hashCode()) % avatarColors.size]

    Surface(
        modifier = Modifier
            .width(220.dp)
            .height(180.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clickable { onCardClick() },
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = collaborator.name.take(1).uppercase(),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = DisplayFontFamily
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = collaborator.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = DisplayFontFamily
                    )
                    if (collaborator.institution.isNotBlank() && collaborator.institution != "Independent Researcher") {
                        Text(
                            text = collaborator.institution,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (collaborator.field.isNotBlank()) {
                        Text(
                            text = collaborator.field,
                            color = color,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = AccentTeal,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = collaborator.connection_path,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Match",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                    LinearProgressIndicator(
                        progress = { collaborator.relevance_score / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = AccentTeal,
                        trackColor = BorderLight
                    )
                    Text(
                        text = "${collaborator.relevance_score}%",
                        color = AccentTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF128C7E),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Connect",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// COLLABORATORS' NEW ARTICLES SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun CollaboratorsNewArticlesSection(
    collaborators: List<NetworkCollaborator>,
    onPaperClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Collaborators' New Articles",
                subtitle = "Recent works from your research network",
                icon = Icons.AutoMirrored.Filled.Feed,
                color = AccentIndigo
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(collaborators, key = { "art-" + it.id }) { collaborator ->
                val rawPath = collaborator.connection_path
                val paperTitle = if (rawPath.contains("'")) {
                    rawPath.substringAfter("'").substringBefore("'")
                } else {
                    "Analysis of " + collaborator.field + " Dynamics"
                }

                Surface(
                    onClick = { onPaperClick(paperTitle) },
                    modifier = Modifier
                        .width(280.dp)
                        .height(130.dp)
                        .shadow(2.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = collaborator.name,
                                    color = AccentTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = DisplayFontFamily
                                )
                                Surface(
                                    color = AccentIndigo.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "NEW ARTICLE",
                                        color = AccentIndigo,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = paperTitle,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = BodyFontFamily,
                                lineHeight = 16.sp
                            )
                        }
                        Text(
                            text = collaborator.institution,
                            color = TextMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// CITATION HEATMAP SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun CitationHeatmapSection(heatmap: CitationHeatmap) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LightSectionHeader(
            title = "Citation & Work Trend",
            subtitle = "Annual impact analysis",
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            color = AccentIndigo
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            border = BorderStroke(1.dp, BorderLight),
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = AccentIndigo.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "H-INDEX",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = heatmap.h_index.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = AccentIndigo
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = AccentTeal.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "INSTITUTIONS REACHED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = heatmap.institutional_reach.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = AccentTeal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                val maxVal = kotlin.math.max(
                    1,
                    kotlin.math.max(heatmap.citations.maxOrNull() ?: 1, heatmap.works.maxOrNull() ?: 1)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    heatmap.years.forEachIndexed { idx, year ->
                        val citations = heatmap.citations.getOrElse(idx) { 0 }
                        val works = heatmap.works.getOrElse(idx) { 0 }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(10.dp)
                                        .fillMaxHeight(fraction = (citations.toFloat() / maxVal).coerceIn(0.02f, 1f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(AccentTeal)
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(10.dp)
                                        .fillMaxHeight(fraction = (works.toFloat() / maxVal).coerceIn(0.02f, 1f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(AccentIndigo)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = year.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentTeal)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Citations",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentIndigo)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Publications",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// JOURNAL ADVISOR SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun JournalAdvisorSection(recommendations: List<JournalRecommendation>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Journal Venue Advisor",
                subtitle = "Optimized matching venues",
                icon = Icons.Default.Science,
                color = AccentIndigo
            )
        }
        Spacer(Modifier.height(10.dp))

        if (recommendations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No journal recommendations available.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recommendations) { recommendation ->
                    JournalRecommendationCard(recommendation = recommendation)
                }
            }
        }
    }
}

@Composable
fun JournalRecommendationCard(recommendation: JournalRecommendation) {
    Surface(
        modifier = Modifier
            .width(260.dp)
            .height(200.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = recommendation.journal_name,
                        style = Typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = DisplayFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AccentIndigo.copy(alpha = 0.08f))
                            .border(BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${recommendation.match_score}%",
                            color = AccentIndigo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Impact Factor",
                        tint = AccentAmber,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "IF: ${recommendation.estimated_impact_factor}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "SUBMISSION ADVICE",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            markdown = recommendation.submission_tips,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DASHBOARD PEER SUGGESTION & FRIENDS' ARTICLES COMPOSABLES
// ─────────────────────────────────────────────────────────────────

@Composable
fun DashboardPeerSuggestionCard(
    peer: AuthorSuggestion,
    onClick: () -> Unit,
    onMessageClick: () -> Unit
) {
    var connectionState by remember { mutableStateOf("Connect") }
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(peer.id.hashCode()) % avatarColors.size]
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .height(168.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Brush.verticalGradient(colors = listOf(BorderLight, color.copy(alpha = 0.15f))))
    ) {
        Box(
            modifier = Modifier.background(Brush.verticalGradient(colors = listOf(BgCard, color.copy(alpha = 0.02f))))
        ) {
            Column(
                modifier = Modifier.padding(12.dp).fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.1f))
                            .border(BorderStroke(1.dp, color.copy(alpha = 0.25f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.display_name.take(1).uppercase(),
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            fontFamily = DisplayFontFamily
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.display_name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = DisplayFontFamily
                        )
                        Text(
                            text = peer.institution,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                if (!peer.field_of_study.isNullOrBlank()) {
                    Text(
                        text = peer.field_of_study,
                        color = color,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                
                val isRequested = connectionState == "Requested"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isRequested) SolidColor(BorderLight) 
                                else Brush.horizontalGradient(listOf(AccentTeal, AccentIndigo))
                            )
                            .clickable {
                                connectionState = if (isRequested) "Connect" else "Requested"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isRequested) Icons.Default.Check else Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = if (isRequested) TextSecondary else TextOnAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isRequested) "Requested" else "Connect",
                                color = if (isRequested) TextSecondary else TextOnAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onMessageClick,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentTeal.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Message",
                            tint = AccentTeal,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardFriendArticleCard(
    work: Work,
    onOpenPaper: () -> Unit
) {
    val friendName = work.authors?.firstOrNull() ?: "Researcher"
    
    Surface(
        onClick = onOpenPaper,
        modifier = Modifier
            .width(260.dp)
            .height(130.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = friendName,
                        color = AccentTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DisplayFontFamily
                    )
                    Surface(
                        color = AccentIndigo.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "NEW ARTICLE",
                            color = AccentIndigo,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = work.title ?: "Untitled Paper",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = BodyFontFamily,
                    lineHeight = 16.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${work.journal ?: "Research Journal"} (${work.year ?: ""})",
                    color = TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = onOpenPaper,
                    color = AccentTeal.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "Open Reader",
                        color = AccentTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestedPeersShimmer() {
    val infiniteTransition = rememberInfiniteTransition(label = "peersShimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Surface(
                modifier = Modifier.width(180.dp).height(168.dp),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BorderLight.copy(alpha = alpha))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(BorderLight.copy(alpha = alpha))
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(BorderLight.copy(alpha = alpha))
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BorderLight.copy(alpha = alpha))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BorderLight.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}
