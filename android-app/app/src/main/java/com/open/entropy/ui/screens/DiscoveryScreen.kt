package com.open.entropy.ui.screens

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.open.entropy.auth.AuthManager
import com.open.entropy.network.*
import com.open.entropy.ui.components.*
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onTabNavigate: (String) -> Unit = {}
) {
    val apiService = remember { ApiService() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)

    val userName = cachedUser?.name ?: "Researcher"
    val researchFocus = cachedUser?.researchFocus ?: "General Research"

    var authorQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AuthorSuggestion>>(emptyList()) }
    var authorData by remember { mutableStateOf<AuthorResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }

    // Debounced suggestions
    LaunchedEffect(authorQuery) {
        if (authorQuery.length >= 3) {
            isSearchingSuggestions = true
            delay(400)
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
                .padding(bottom = com.open.entropy.ui.layout.ScreenInsets.bottomNavClearance)
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
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    com.open.entropy.ui.components.primitives.GlassSearchBar(
                        value = authorQuery,
                        onValueChange = { authorQuery = it },
                        placeholder = "Search researchers, authors…",
                        isLoading = isSearchingSuggestions,
                        onClear = { authorQuery = ""; suggestions = emptyList(); authorData = null },
                        onSearch = {
                            scope.launch {
                                isLoading = true
                                suggestions = emptyList()
                                authorData = apiService.searchAuthor(authorQuery)
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
                        visible = suggestions.isNotEmpty(),
                        enter = fadeIn(tween(150)) + expandVertically(tween(220), expandFrom = androidx.compose.ui.Alignment.Top),
                        exit  = fadeOut(tween(100)) + shrinkVertically(tween(160), shrinkTowards = androidx.compose.ui.Alignment.Top),
                    ) {
                        LightSuggestionsDropdown(
                            suggestions = suggestions,
                            onSelect = { suggestion ->
                                authorQuery = suggestion.display_name
                                suggestions = emptyList()
                                scope.launch {
                                    isLoading = true
                                    authorData = apiService.searchAuthor(suggestion.display_name)
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
                            onSelectResearcher = { name ->
                                authorQuery = name
                                scope.launch {
                                    isLoading = true
                                    authorData = apiService.searchAuthor(name)
                                    isLoading = false
                                }
                            }
                        )
                        else -> DiscoveryDashboard(
                            userName = userName,
                            researchFocus = researchFocus,
                            onCategoryClick = { category ->
                                when (category.title) {
                                    "Authors"  -> authorQuery = ""
                                    "Papers"   -> onTabNavigate("search")
                                    "Vault"    -> onTabNavigate("library")
                                    "Trends"   -> onTabNavigate("nexus")
                                }
                            },
                            onPaperClick = onPaperClick
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
fun LightSuggestionsDropdown(
    suggestions: List<AuthorSuggestion>,
    onSelect: (AuthorSuggestion) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column {
            // Header — shows how many matches
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgElevated)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${suggestions.size} researchers found",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "via OpenAlex",
                    color = AccentTeal,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider(color = BorderLight, thickness = 0.5.dp)

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
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Avatar initials circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.1f)),
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
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = DisplayFontFamily
                            )
                            if (suggestion.institution.isNotBlank() && suggestion.institution != "Independent Researcher") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AccountBalance,
                                        null,
                                        tint = AccentTeal,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = suggestion.institution,
                                        color = AccentTeal,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (!suggestion.field_of_study.isNullOrBlank()) {
                                Text(
                                    text = suggestion.field_of_study!!,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Tap arrow
                        Surface(
                            shape = CircleShape,
                            color = color.copy(alpha = 0.08f)
                        ) {
                            Icon(
                                Icons.Default.ArrowForwardIos,
                                contentDescription = "View profile",
                                tint = color,
                                modifier = Modifier.size(26.dp).padding(7.dp)
                            )
                        }
                    }

                    if (index < suggestions.lastIndex) {
                        HorizontalDivider(
                            color = BorderLight,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 66.dp)
                        )
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
    onSelectResearcher: (String) -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                apiService.searchAuthor(author.display_name)?.let { onUpdateData(it) }
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(BgPrimary),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Hero Header ──────────────────────────────────────
            item { ResearcherHeroCard(author = author, apiService = apiService, scope = scope, onUpdateData = onUpdateData) }

            // ── Stats Row (4 compact numbers) ────────────────────
            item {
                StatsQuadRow(author = author)
            }

            // ── Metrics Radar Ring ────────────────────────────────
            item {
                MetricsRadarSection(author = author)
            }

            // ── Frontier Metric Pills (Flipkart-style grid) ───────
            item {
                FrontierMetricPillsSection(author = author)
            }

            // ── Similar Researchers ──────────────────────────────
            if (author.similar_researchers.isNotEmpty()) {
                item {
                    SimilarResearchersSection(
                        similar = author.similar_researchers,
                        onSelect = onSelectResearcher
                    )
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
                LightPublicationCard(work = work, apiService = apiService, scope = scope, onNavigateToReader = onNavigateToReader)
            }

            // ── Next Prediction ───────────────────────────────────
            if (!author.next_prediction.isNullOrBlank()) {
                item { PredictionCard(prediction = author.next_prediction!!) }
            }

            item { Spacer(Modifier.height(32.dp)) }
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
                .padding(16.dp)
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
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)) {
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
                                    fontFamily = DisplayFontFamily,
                                    lineHeight = 28.sp
                                )
                                Spacer(Modifier.height(4.dp))
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
                                        text = author.field_of_study!!,
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
                                            apiService.searchAuthor(author.display_name)?.let { onUpdateData(it) }
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
                            val buttonColor = if (isRequested) BorderLight else AccentTeal
                            val contentColor = if (isRequested) TextSecondary else TextOnAccent
                            
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
                                    contentColor = AccentTealDark
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
        color = AccentTealLight,
        border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            color = AccentTealDark,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
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
        listOf(
            MetricPill("Disruption",      "${author.disruption_score.toInt()}%",       "Research Disruption",    MetricDisruptionColor,   Icons.Default.FlashOn),
            MetricPill("Novelty",         "${author.semantic_novelty.toInt()}%",        "Semantic Novelty",       MetricNoveltyColor,      Icons.Default.AutoGraph),
            MetricPill("Future Impact",   "${author.future_impact_score.toInt()}%",     "Predicted Impact",       MetricFutureImpactColor, Icons.Default.Psychology),
            MetricPill("Influence",       "${author.network_centrality.toInt()}%",      "Network Centrality",     MetricInfluenceColor,    Icons.Default.Hub),
            MetricPill("Creativity",      "${author.average_creativity.toInt()}%",      "Avg Creativity",         MetricCreativityColor,   Icons.Default.Lightbulb),
            MetricPill("Complexity",      "${author.average_complexity.toInt()}%",      "Avg Complexity",         MetricComplexityColor,   Icons.Default.Science),
            MetricPill("Open Science",    "${author.open_science_score.toInt()}%",      "Openness Score",         MetricOpenScienceColor,  Icons.Default.Public),
            MetricPill("Collaboration",   "${author.collaboration_diversity.toInt()}%", "Diversity Index",        MetricCollabColor,       Icons.Default.Groups),
            MetricPill("Cit. Accel.",     "${author.citation_acceleration.toInt()}%",   "Citation Growth",        MetricInfluenceColor,    Icons.AutoMirrored.Filled.TrendingUp),
            MetricPill("Consistency",     "${author.research_consistency.toInt()}%",    "Research Consistency",   MetricConsistencyColor,  Icons.Default.Timeline),
            MetricPill("Interdiscipl.",   "${author.interdisciplinary_index.toInt()}%", "Cross-domain Reach",     AccentViolet,            Icons.Default.AccountTree),
            MetricPill("Policy Impact",   "${author.policy_patent_score.toInt()}%",     "Policy & Patent Score",  MetricPolicyColor,       Icons.Default.Gavel),
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
    onNavigateToReader: (String, String) -> Unit
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
            Text(
                text = formatScientificTitle(work.title ?: "Untitled"),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = BodyFontFamily
            )

            // Journal
            if (!work.journal.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = work.journal!!,
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
                if ((work.disruption_score ?: 0.0) > 0) PubChip("Disruption ${work.disruption_score?.toInt()}%", AccentRose)
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
                                    Spacer(Modifier.width(8.dp))
                                    Text(bullet, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val url = work.doi?.let { if (it.startsWith("http")) it else "https://doi.org/$it" }
                                        ?: "https://scholar.google.com/scholar?q=${work.title}"
                                    onNavigateToReader(url, work.title ?: "Article")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Read Paper", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

@Composable
fun PredictionCard(prediction: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LightSectionHeader("Next Prediction", "AI forecast", Icons.Default.TipsAndUpdates, AccentIndigo)
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = AccentIndigoLight,
            border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f))
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentIndigo, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(prediction, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// SIMILAR RESEARCHERS SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun SimilarResearchersSection(
    similar: List<AuthorSuggestion>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Similar Researchers",
                subtitle = "Overlapping fields",
                icon = Icons.Default.Science,
                color = AccentTeal
            )
        }
        Spacer(Modifier.height(10.dp))
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(similar) { index, suggestion ->
                val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
                val color = avatarColors[index % avatarColors.size]
                
                Surface(
                    modifier = Modifier
                        .width(180.dp)
                        .height(150.dp)
                        .shadow(2.dp, RoundedCornerShape(16.dp))
                        .clickable { onSelect(suggestion.display_name) },
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Avatar initials
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = suggestion.display_name.take(1).uppercase(),
                                    color = color,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = DisplayFontFamily
                                )
                            }
                            
                            // Name
                            Text(
                                text = suggestion.display_name,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = DisplayFontFamily,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                        ) {
                            // Institution
                            if (suggestion.institution.isNotBlank() && suggestion.institution != "Independent Researcher") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalance,
                                        null,
                                        tint = AccentTeal,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = suggestion.institution,
                                        color = AccentTeal,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            
                            // Field
                            if (!suggestion.field_of_study.isNullOrBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Science,
                                        null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = suggestion.field_of_study!!,
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        
                        // Action View Button inside card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(color.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "View Profile",
                                    color = color,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    null,
                                    tint = color,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
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
    onPaperClick: (String) -> Unit
) {
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcome hero
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = BgCard,
                shadowElevation = 2.dp
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Brush.horizontalGradient(HeroGradient))
                    )
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Welcome back,",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = userName,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontFamily = DisplayFontFamily
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AccentTealLight,
                            border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "🔬 $researchFocus",
                                color = AccentTealDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        // Search prompt
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BgElevated,
                            border = BorderStroke(1.dp, BorderLight),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Search, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
                                Column {
                                    Text("Search any researcher", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Type 3+ characters above for instant suggestions", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Category tiles
        item {
            Text(
                text = "EXPLORE",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
        }

        item {
            val rows = categories.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { cat ->
                            DashboardTile(cat, onClick = { onCategoryClick(cat) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // Featured papers strip
        item {
            Text(
                text = "FEATURED RESEARCH",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
        }

        item {
            ConferenceMatchRow()
        }

        item {
            Text(
                text = "AI GAP FINDER",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
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
        color = BgCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, category.color.copy(alpha = 0.12f))
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

// ─────────────────────────────────────────────────────────────────
// UTILITY COMPOSABLES (keep backward compat)
// ─────────────────────────────────────────────────────────────────

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = 13.sp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }

    val markwon = remember(context, fontSizePx, color) {
        val colorInt = color.toArgb()
        val accentInt = AccentTeal.toArgb()
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(fontSizePx) { builder ->
                builder.inlinesEnabled(true)
                builder.theme().textColor(colorInt)
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(StrongEmphasis::class.java) { _, _ ->
                        arrayOf<CharacterStyle>(
                            StyleSpan(Typeface.BOLD),
                            ForegroundColorSpan(accentInt)
                        )
                    }
                }
            })
            .build()
    }

    AndroidView(
        factory = { ctx -> TextView(ctx).apply { setLayerType(View.LAYER_TYPE_SOFTWARE, null) } },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            val processed = markdown
                .replace(Regex("(?<!\\\\)mathrm\\{"), "\\\\mathrm{")
                .replace(Regex("(?<!\\\\)text\\{"), "\\\\text{")
            markwon.setMarkdown(textView, processed)
        },
        modifier = modifier
    )
}

fun formatScientificTitle(title: String): String = title
    .replace(Regex("<mml:math.*?>"), "")
    .replace("</mml:math>", "")
    .replace(Regex("<mml:mrow.*?>"), "")
    .replace("</mml:mrow>", "")
    .replace(Regex("<mml:mi.*?>"), "")
    .replace("</mml:mi>", "")
    .replace(Regex("<mml:mn.*?>"), "")
    .replace("</mml:mn>", "")
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
        Text("Powered by ResQit", modifier = Modifier.padding(16.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = TextMuted, fontSize = 12.sp)
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
    onSelectResearcher = {}
)

data class ResearchMetric(val label: String, val value: Int, val color: Color, val icon: ImageVector, val isPercentage: Boolean = true)
