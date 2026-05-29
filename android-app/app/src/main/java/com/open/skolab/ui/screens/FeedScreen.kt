package com.open.skolab.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.model.Paper
import com.open.skolab.model.Author
import com.open.skolab.model.UserConnection
import com.open.skolab.model.Conjecture
import com.open.skolab.model.Connection
import com.open.skolab.model.Country
import com.open.skolab.model.Discipline
import com.open.skolab.model.FeedUiState
import com.open.skolab.model.FrontierMetrics
import com.open.skolab.model.Institution
import com.open.skolab.model.ReadingProgress
import com.open.skolab.model.ResearchArea
import com.open.skolab.model.ResearchFilter
import com.open.skolab.model.User
import androidx.compose.material.icons.automirrored.filled.Article
import com.open.skolab.ui.components.ScoreArcMeter
import com.open.skolab.ui.components.MarkdownText
import com.open.skolab.ui.components.StreakCard
import com.open.skolab.ui.components.SwipeVaultCard
import com.open.skolab.ui.components.PaperCard
import com.open.skolab.ui.components.primitives.EmptyState
import com.open.skolab.ui.components.primitives.ErrorState
import com.open.skolab.ui.components.primitives.GlassSearchBar
import com.open.skolab.ui.screens.toAuthorResponse
import com.open.skolab.ui.theme.*
import com.open.skolab.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// ── ReQit Professional Flipkart Colors ─────────────────────────────────────
object EntropiColors {
    val Background = com.open.skolab.ui.theme.BgPrimary
    val Card = com.open.skolab.ui.theme.BgCard
    val Card2 = com.open.skolab.ui.theme.BgElevated
    val Border = com.open.skolab.ui.theme.BorderLight
    val Gold1 = com.open.skolab.ui.theme.AccentAmber
    val Gold2 = com.open.skolab.ui.theme.AccentAmber
    val Blue1 = com.open.skolab.ui.theme.AccentTeal
    val Blue2 = com.open.skolab.ui.theme.AccentTeal
    val Cyan = com.open.skolab.ui.theme.AccentCyan
    val Purple1 = com.open.skolab.ui.theme.AccentViolet
    val Purple2 = com.open.skolab.ui.theme.AccentViolet
    val Red = com.open.skolab.ui.theme.AccentRose
    val Green = com.open.skolab.ui.theme.AccentEmerald
    val Text = com.open.skolab.ui.theme.TextPrimary
    val Text2 = com.open.skolab.ui.theme.TextSecondary
    val Text3 = com.open.skolab.ui.theme.TextMuted
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onPaperClick: (String) -> Unit,
    onProfileClick: () -> Unit = {},
    onNavigateToChat: (String, String) -> Unit = { _, _ -> },
    onNavigateToChatList: () -> Unit = {},
    onNavigateToReader: (String, String) -> Unit = { _, _ -> },
    onTabNavigate: (String) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onLoadingStateChanged: (Boolean) -> Unit = {},
    onNavigateToLogicEngine: () -> Unit = {},
    onNavigateToDailyDiscovery: () -> Unit = {},
    viewModel: FeedViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var selectedCountryFilter by remember { mutableStateOf("Global") }
    var showSetupFocusDialog by remember { mutableStateOf(false) }
    var setupNameText by remember { mutableStateOf("") }
    var setupFocusText by remember { mutableStateOf("") }


    val context = LocalContext.current
    val authManager = com.open.skolab.di.AppDependencies.authManager
    val userPrefs = remember { com.open.skolab.data.UserPreferences(context) }
    val apiService = com.open.skolab.di.AppDependencies.apiService
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val connectionsList by userPrefs.userConnections.collectAsState(initial = emptyList())

    val isInitialLoad = remember { mutableStateOf(true) }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isInitialLoad.value = false
        }
        onLoadingStateChanged(uiState.isLoading && isInitialLoad.value)
    }


    LaunchedEffect(cachedUser) {
        val uid = cachedUser?.uid ?: "user_default"
        val name = cachedUser?.name ?: ""
        val focus = cachedUser?.researchFocus ?: ""
        
        val isNameInvalid = name.isBlank() || name.equals("SkoLab User", ignoreCase = true) || name.equals("Researcher", ignoreCase = true)
        val isFocusInvalid = focus.isBlank() || focus.equals("Researcher", ignoreCase = true) || focus.equals("General Research", ignoreCase = true)
        
        if ((isNameInvalid || isFocusInvalid) && cachedUser != null) {
            setupNameText = if (isNameInvalid) "" else name
            setupFocusText = if (isFocusInvalid) "" else focus
            showSetupFocusDialog = true
        }
        
        val validName = if (isNameInvalid) "SkoLab User" else name
        val validFocus = if (isFocusInvalid) "" else focus
        viewModel.setUserContext(uid, validName, validFocus)
    }

    LaunchedEffect(uiState.suggestedConnections.size) {
        android.util.Log.d("FeedScreen", "suggestedConnections size is now: ${uiState.suggestedConnections.size}")
        android.util.Log.d("FeedScreen", "isLoading is now: ${uiState.isLoading}")
    }

    // Scroll tracking moved directly to the items list below

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {


        // Main LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                if (uiState.error != null) {
                    item {
                        Surface(
                            color = Color(0x22EF4444),
                            border = BorderStroke(1.dp, Color(0x66EF4444)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = "API Request Failed",
                                        color = Color(0xFFEF4444),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = uiState.error ?: "",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }



                if (uiState.suggestedConnections.isNotEmpty()) {
                    item {
                        PeerMomentumStrip(
                            peers = uiState.suggestedConnections.take(4),
                            onAuthorClick = onAuthorClick
                        )
                    }
                }

                // Gamified Streak Check-In Touchpoint
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        StreakCard(
                            onClick = onNavigateToLogicEngine
                        )
                    }
                }



                // Continue Reading
                if (uiState.continueReading.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "📖 Continue Reading",
                            onSeeAll = {}
                        )
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(uiState.continueReading) { progress ->
                                ResumeCard(
                                    progress = progress,
                                    onClick = { onPaperClick(progress.paper.id) },
                                    onResume = {
                                        onNavigateToReader(progress.paper.title, progress.paper.pdfUrl ?: "https://arxiv.org/pdf/1706.03762.pdf")
                                    }
                                )
                            }
                        }
                    }
                }

                // Vertical Infinite Feed of Collaborators
                
                // NEW: People You May Know Header + Filter Chips
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.People, contentDescription = null, tint = EntropiColors.Blue1, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "People You May Know",
                                color = EntropiColors.Text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("Global", "USA", "UK", "India", "Germany", "Canada", "Australia", "France", "Japan")) { country ->
                                val isSelected = selectedCountryFilter == country
                                Surface(
                                    onClick = { selectedCountryFilter = country },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) EntropiColors.Blue1 else EntropiColors.Card2,
                                    border = BorderStroke(1.dp, if (isSelected) Color.Transparent else EntropiColors.Border)
                                ) {
                                    Text(
                                        text = country,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        color = if (isSelected) Color.White else EntropiColors.Text2,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                val filteredConnections = if (selectedCountryFilter == "Global") {
                    uiState.suggestedConnections
                } else {
                    uiState.suggestedConnections.filter { 
                        it.author.country.contains(selectedCountryFilter, ignoreCase = true) || 
                        it.author.institution.contains(selectedCountryFilter, ignoreCase = true) 
                    }
                }

                if (uiState.isLoading && filteredConnections.isEmpty()) {
                    items(5) {
                        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                            PaperShimmerCard() // Shimmer loader
                        }
                    }
                } else if (filteredConnections.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No connections found.",
                                color = EntropiColors.Text3,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    val chunkedList = filteredConnections.chunked(2)
                    itemsIndexed(chunkedList) { index, rowItems ->
                        // Trigger load more when reaching the end of the connections list
                        if (index >= chunkedList.size - 1 && !uiState.isLoadingMoreConnections && !uiState.isLoading) {
                            LaunchedEffect(index) {
                                viewModel.loadMoreConnections()
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            for (conn in rowItems) {
                                val isConnected = connectionsList.any { it.id == conn.author.id }
                                Box(modifier = Modifier.weight(1f)) {
                                    ConnectionCard(
                                        connection = conn,
                                        isConnectedExternal = isConnected,
                                        onConnect = {
                                            scope.launch {
                                                val newConn = UserConnection(
                                                    id = conn.author.id,
                                                    name = conn.author.name,
                                                    institution = conn.author.institution,
                                                    field = conn.sharedAreas.firstOrNull() ?: ""
                                                )
                                                userPrefs.addConnection(newConn)
                                                authManager.addConnectionToFirestore(newConn)
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onChatClick = {
                                            onNavigateToChat(conn.author.name, conn.author.id)
                                        },
                                        onAuthorClick = {
                                            try {
                                                val preview = conn.author.toAuthorResponse()
                                                apiService.cacheAuthorProfile(conn.author.id, preview)
                                            } catch (e: Exception) {
                                                android.util.Log.e("FeedScreen", "Failed to cache author profile preview", e)
                                            }
                                            onAuthorClick("${conn.author.name}|${conn.author.id}")
                                        }
                                    )
                                }
                            }
                            if (rowItems.size < 2) {
                                Spacer(modifier = Modifier.weight((2 - rowItems.size).toFloat()))
                            }
                        }
                    }
                    if (uiState.isLoadingMoreConnections) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = EntropiColors.Blue1,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                }
        }

        // Scroll to Top FAB (Section K)
        val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
        ) {
            AnimatedVisibility(
                visible = showFab,
                enter = scaleIn(spring(stiffness = Spring.StiffnessLow)),
                exit = scaleOut(spring(stiffness = Spring.StiffnessLow))
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = EntropiColors.Gold1,
                    contentColor = EntropiColors.Background,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Scroll to top",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        if (showSetupFocusDialog) {
            val suggestions = listOf("Physics", "Computational Neuroscience", "Machine Learning", "Genomics", "Quantum Computing")
            AlertDialog(
                onDismissRequest = { /* Non-cancelable */ },
                title = {
                    Text(
                        text = "Complete Your Researcher Profile",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = EntropiColors.Gold1
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "To personalize your Pulse feed, match papers, and discover collaborators, please define your profile name and research focus area.",
                            color = EntropiColors.Text2,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        
                        OutlinedTextField(
                            value = setupNameText,
                            onValueChange = { setupNameText = it },
                            label = { Text("Full Name", color = EntropiColors.Text3) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = EntropiColors.Text,
                                unfocusedTextColor = EntropiColors.Text,
                                focusedBorderColor = EntropiColors.Gold1,
                                unfocusedBorderColor = EntropiColors.Border,
                                focusedContainerColor = EntropiColors.Card2,
                                unfocusedContainerColor = EntropiColors.Card2
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        
                        OutlinedTextField(
                            value = setupFocusText,
                            onValueChange = { setupFocusText = it },
                            label = { Text("Research Focus / Discipline", color = EntropiColors.Text3) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = EntropiColors.Text,
                                unfocusedTextColor = EntropiColors.Text,
                                focusedBorderColor = EntropiColors.Gold1,
                                unfocusedBorderColor = EntropiColors.Border,
                                focusedContainerColor = EntropiColors.Card2,
                                unfocusedContainerColor = EntropiColors.Card2
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        
                        Text(
                            text = "Focus Suggestions:",
                            color = EntropiColors.Text3,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            suggestions.forEach { suggestion ->
                                Surface(
                                    onClick = { setupFocusText = suggestion },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (setupFocusText == suggestion) EntropiColors.Gold1.copy(alpha = 0.15f) else EntropiColors.Card2,
                                    border = BorderStroke(
                                        1.dp,
                                        if (setupFocusText == suggestion) EntropiColors.Gold1 else EntropiColors.Border
                                    )
                                ) {
                                    Text(
                                        text = suggestion,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = if (setupFocusText == suggestion) EntropiColors.Gold1 else EntropiColors.Text2,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmedName = setupNameText.trim()
                            val trimmedFocus = setupFocusText.trim()
                            if (trimmedName.isNotBlank() && trimmedFocus.isNotBlank()) {
                                scope.launch {
                                    val uid = cachedUser?.uid ?: "user_default"
                                    authManager.updateUserProfile(trimmedName, trimmedFocus)
                                    viewModel.setUserContext(uid, trimmedName, trimmedFocus)
                                    showSetupFocusDialog = false
                                }
                            }
                        },
                        enabled = setupNameText.trim().isNotBlank() && setupFocusText.trim().isNotBlank(),
                        colors = ButtonDefaults.textButtonColors(contentColor = EntropiColors.Gold1)
                    ) {
                        Text("Save Profile", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = EntropiColors.Card,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}



// ── COMPONENT 2: FrontierPulseCard ───────────────────────────────────────────
@Composable
fun FrontierPulseCard(metrics: FrontierMetrics) {
    val dProgress by animateFloatAsState(
        targetValue = metrics.dIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "dProgress"
    )
    val sProgress by animateFloatAsState(
        targetValue = metrics.sIndex,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "sProgress"
    )
    val influenceScore = ((dProgress + sProgress) / 2f).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Gold glow top-right
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Gold1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.9f, size.height * 0.1f),
                            radius = size.width * 0.4f
                        )
                    )
                    // radial Blue glow bottom-left
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Blue1.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.9f),
                            radius = size.width * 0.4f
                        )
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(EntropiColors.Gold1.copy(alpha = alpha), CircleShape)
                    )
                    Text(
                        text = "FRONTIER PULSE · LIVE",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Gold1,
                        letterSpacing = 1.2.sp
                    )
                }

                // Three Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // D-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.dIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Gold2
                        )
                        Text("D-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.dIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(EntropiColors.Border))

                    // S-Index
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = String.format("%.2f", metrics.sIndex),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Blue2
                        )
                        Text("S-INDEX", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${String.format("%.2f", metrics.sIndexDelta)} delta", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }

                    Box(modifier = Modifier.size(width = 1.dp, height = 36.dp).background(EntropiColors.Border))

                    // Total Papers
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = metrics.papersCount.toString(),
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = EntropiColors.Purple2
                        )
                        Text("PAPERS", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, color = EntropiColors.Text3, fontWeight = FontWeight.Bold)
                        Text("+${metrics.papersDelta} new", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green)
                    }
                }

                // Arc meters row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = dProgress, label = "Disruption", size = 52.dp, color = EntropiColors.Gold1)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = sProgress, label = "Novelty", size = 52.dp, color = EntropiColors.Cyan)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(EntropiColors.Card2, RoundedCornerShape(8.dp))
                            .border(1.dp, EntropiColors.Border, RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ScoreArcMeter(score = influenceScore, label = "Influence", size = 52.dp, color = EntropiColors.Purple2)
                    }
                }
            }
        }
    }
}

// ── COMPONENT 3: AIDailyBriefCard ────────────────────────────────────────────
@Composable
fun AIDailyBriefCard(
    briefText: String,
    isLoading: Boolean,
    userId: String
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Purple1.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .drawBehind {
                    // radial Purple glow top-right
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(EntropiColors.Purple1.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.15f),
                            radius = size.width * 0.45f
                        )
                    )
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(EntropiColors.Purple1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Brain",
                            tint = EntropiColors.Purple2,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "AI DAILY BRIEF",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Purple2,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Today",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        color = EntropiColors.Text3,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isLoading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShimmerBar(Modifier.fillMaxWidth().height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.85f).height(12.dp))
                        ShimmerBar(Modifier.fillMaxWidth(0.5f).height(12.dp))
                    }
                } else {
                    val annotatedString = buildAnnotatedString {
                        val parts = briefText.split("**")
                        parts.forEachIndexed { index, part ->
                            if (index % 2 == 1) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = EntropiColors.Gold2)) {
                                    append(part)
                                }
                            } else {
                                append(part)
                            }
                        }
                    }

                    Text(
                        text = annotatedString,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = EntropiColors.Text
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerBar(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmerBar")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.12f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(EntropiColors.Text3.copy(alpha = alpha))
    )
}

// ── COMPONENT 4: QuickFilterRail ─────────────────────────────────────────────
@Composable
fun QuickFilterRail(
    selectedFilter: ResearchFilter,
    onFilterSelect: (ResearchFilter) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(ResearchFilter.values()) { filter ->
            val isActive = selectedFilter == filter
            Surface(
                onClick = { onFilterSelect(filter) },
                shape = RoundedCornerShape(20.dp),
                color = if (isActive) EntropiColors.Gold1.copy(alpha = 0.12f) else EntropiColors.Card2,
                border = BorderStroke(1.dp, if (isActive) EntropiColors.Gold1 else EntropiColors.Border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(filter.color, CircleShape)
                    )
                    Text(
                        text = filter.label,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) EntropiColors.Gold2 else EntropiColors.Text2
                    )
                }
            }
        }
    }
}

// ── COUNTRY TILE ─────────────────────────────────────────────────────────────
@Composable
fun CountryTile(country: Country, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(80.dp, 90.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(14.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, if (isPressed) EntropiColors.Gold1 else EntropiColors.Border)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = country.flag, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = country.name,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                color = EntropiColors.Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${country.paperCount} papers",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = EntropiColors.Gold2
            )
        }
    }
}

// ── DISCIPLINE TILE ──────────────────────────────────────────────────────────
@Composable
fun DisciplineTile(discipline: Discipline, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(72.dp, 88.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(android.graphics.Color.parseColor(discipline.gradientStart)),
                            Color(android.graphics.Color.parseColor(discipline.gradientEnd))
                        )
                    )
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = discipline.emoji, fontSize = 26.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = discipline.name,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = discipline.subCount,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 8.sp,
                    color = EntropiColors.Text2,
                    maxLines = 1
                )
            }
        }
    }
}

// ── RESEARCH AREA PILL ───────────────────────────────────────────────────────
@Composable
fun ResearchAreaPill(area: ResearchArea, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = area.color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, area.color.copy(alpha = 0.35f)),
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = area.name,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = area.color
            )
        }
    }
}

// ── COMPONENT: PaperFeedCard ─────────────────────────────────────────────────
@Composable
fun PaperFeedCard(
    paper: Paper,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, if (isPressed) EntropiColors.Gold1 else EntropiColors.Border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left Accent Bar (Gold -> Blue)
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically)
                    .background(Brush.verticalGradient(colors = listOf(EntropiColors.Gold1, EntropiColors.Blue1)))
            )

            Column(modifier = Modifier.padding(14.dp)) {
                // Header tags row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = EntropiColors.Card2,
                            border = BorderStroke(0.5.dp, EntropiColors.Border)
                        ) {
                            Text(
                                text = paper.journal.take(18),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = EntropiColors.Gold2,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = EntropiColors.Purple1.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = "D·${String.format("%.2f", paper.disruptionScore)}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = EntropiColors.Purple2,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Score Badge
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(EntropiColors.Gold1.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (paper.noveltyScore * 100).toInt().toString(),
                            color = EntropiColors.Gold2,
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Title
                Text(
                    text = paper.title,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                // Authors
                Text(
                    text = paper.authors.firstOrNull()?.split("|")?.firstOrNull() ?: "Unknown Author",
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 11.sp,
                    color = EntropiColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(10.dp))

                // Bottom stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.RemoveRedEye, null, tint = EntropiColors.Text3, modifier = Modifier.size(12.dp))
                        Text(text = "1.2k views", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Text3)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.FormatQuote, null, tint = EntropiColors.Green, modifier = Modifier.size(12.dp))
                        Text(text = "${paper.citationCount} citations", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = EntropiColors.Green, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── COMPONENT: ResearcherCard ────────────────────────────────────────────────
@Composable
fun ResearcherCard(
    researcher: Author,
    onConnect: () -> Unit,
    onViewProfile: () -> Unit
) {
    var isConnected by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(EntropiColors.Gold1, EntropiColors.Blue1)
                        )
                    )
                    .padding(2.dp)
                    .background(EntropiColors.Card, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = researcher.name.take(1).uppercase(),
                    color = EntropiColors.Gold2,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // Name
            Text(
                text = researcher.name,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = EntropiColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Inst
            Text(
                text = researcher.institution.take(16),
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 10.sp,
                color = EntropiColors.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            // Stats Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EntropiColors.Card2, RoundedCornerShape(6.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "D·${(researcher.avgDisruptionScore * 100).toInt()}",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Gold2
                    )
                    Text("Disruption", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = EntropiColors.Text3)
                }
                Box(modifier = Modifier.size(width = 0.5.dp, height = 18.dp).background(EntropiColors.Border))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "6.2k",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EntropiColors.Green
                    )
                    Text("Citations", fontFamily = SpaceGroteskFontFamily, fontSize = 8.sp, color = EntropiColors.Text3)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Connect
                Button(
                    onClick = {
                        isConnected = !isConnected
                        onConnect()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) EntropiColors.Card2 else Color(0xFF2E7D32), // Dark Green
                        contentColor = if (isConnected) EntropiColors.Gold1 else Color.White
                    ),
                    border = BorderStroke(1.dp, if (isConnected) EntropiColors.Border else Color.Transparent)
                ) {
                    Text(
                        text = if (isConnected) "Pending" else "Connect",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // View profile
                Button(
                    onClick = onViewProfile,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EntropiColors.Card2,
                        contentColor = EntropiColors.Text2
                    ),
                    border = BorderStroke(1.dp, EntropiColors.Border)
                ) {
                    Text(
                        text = "View",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── SUGGESTED CONNECTIONS ────────────────────────────────────────────────────
@Composable
fun ConnectionCard(
    connection: Connection,
    isConnectedExternal: Boolean,
    onConnect: () -> Unit,
    onChatClick: () -> Unit = {},
    onInvite: () -> Unit = {},
    onAuthorClick: () -> Unit
) {
    var showEmailInviteDialog by remember { mutableStateOf(false) }
    var showSMSInviteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onAuthorClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Row 1: Avatar and Metadata Top Row ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Avatar with SkoLab status overlay
                Box(modifier = Modifier.size(32.dp)) {
                    // Initials circle (compact and elegant)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(EntropiColors.Card2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connection.author.name.take(2).uppercase(),
                            color = EntropiColors.Text2,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    // SkoLab status badge — bottom-right corner
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(
                                if (connection.isOnSkoLab) Color(0xFF00E676)
                                else EntropiColors.Border
                            )
                            .border(1.2.dp, EntropiColors.Card, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (connection.isOnSkoLab) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "On SkoLab",
                                tint = Color.Black,
                                modifier = Modifier.size(6.dp)
                            )
                        } else {
                            Text(
                                text = "–",
                                color = EntropiColors.Text3,
                                fontSize = 6.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 6.sp
                            )
                        }
                    }
                }

                // Depth badge + match % row (compact right-aligned badge)
                val (depthLabel, depthColor) = when (connection.depth) {
                    1 -> "Direct" to EntropiColors.Green
                    2 -> "2nd" to EntropiColors.Blue2
                    else -> "3rd+" to EntropiColors.Text3
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = depthColor.copy(alpha = 0.1f),
                        border = BorderStroke(0.5.dp, depthColor.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = depthLabel,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
                            color = depthColor,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                    Text(
                        text = "${connection.mutualCount}%",
                        color = EntropiColors.Green.copy(alpha = 0.9f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGroteskFontFamily
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Row 2: Complete Name & Role Block (Full Width) ────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = connection.author.name,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = EntropiColors.Text,
                    maxLines = 2, // Allow wrapping to 2 lines so the complete name is fully visible
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )
                
                Spacer(Modifier.height(1.dp))

                val inferredRole = when {
                    connection.author.totalPapers > 50 -> "Professor"
                    connection.author.totalPapers > 15 -> "Postdoc"
                    else -> "Researcher"
                }
                Text(
                    text = "$inferredRole · ${connection.author.institution.ifEmpty { connection.author.country }.take(25)}",
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 9.sp,
                    color = EntropiColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 2: Slim metrics ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(value = connection.papersCollaborated.toString(), label = "Joint", color = EntropiColors.Blue1)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(EntropiColors.Border))
                MetricPill(value = connection.totalPublications.toString(), label = "Papers", color = EntropiColors.Text)
                Box(modifier = Modifier.width(1.dp).height(12.dp).background(EntropiColors.Border))
                MetricPill(value = "h${connection.hIndex}", label = "Index", color = EntropiColors.Purple1)
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 3: Action button(s) ───────────────────────────────────────
            if (connection.isOnSkoLab) {
                if (isConnectedExternal) {
                    Surface(
                        onClick = onChatClick,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = EntropiColors.Card2,
                        border = BorderStroke(1.dp, EntropiColors.Border)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("💬 Message", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EntropiColors.Gold2)
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = onConnect,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.horizontalGradient(listOf(Color(0xFF1565C0), Color(0xFF6A1B9A))),
                                    RoundedCornerShape(8.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+ Connect", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Surface(
                            onClick = onChatClick,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1B5E20).copy(alpha = 0.85f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("🤝 Collab", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Invite via Email button
                    Surface(
                        onClick = { showEmailInviteDialog = true },
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, EntropiColors.Gold1.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(listOf(EntropiColors.Gold1.copy(alpha = 0.10f), EntropiColors.Gold2.copy(alpha = 0.06f))),
                                RoundedCornerShape(8.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✉️ Email Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = EntropiColors.Gold1)
                        }
                    }

                    // Invite via SMS button
                    Surface(
                        onClick = { showSMSInviteDialog = true },
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, EntropiColors.Cyan.copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(listOf(EntropiColors.Cyan.copy(alpha = 0.10f), EntropiColors.Cyan.copy(alpha = 0.06f))),
                                RoundedCornerShape(8.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💬 SMS Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = EntropiColors.Cyan)
                        }
                    }
                }
            }

            if (showEmailInviteDialog) {
                val suggestedEmail = remember { connection.author.name.lowercase().replace(" ", "") + "@university.edu" }
                var emailInput by remember { mutableStateOf(suggestedEmail) }
                
                AlertDialog(
                    onDismissRequest = { showEmailInviteDialog = false },
                    title = {
                        Text(
                            text = "Email Invitation",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = EntropiColors.Gold1
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Send a secure SkoLab invite to ${connection.author.name}:",
                                color = EntropiColors.Text2,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Collaborator's Email", color = EntropiColors.Text3) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = EntropiColors.Text,
                                    unfocusedTextColor = EntropiColors.Text,
                                    focusedBorderColor = EntropiColors.Gold1,
                                    unfocusedBorderColor = EntropiColors.Border,
                                    focusedContainerColor = EntropiColors.Card2,
                                    unfocusedContainerColor = EntropiColors.Card2
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (emailInput.isNotBlank()) {
                                    showEmailInviteDialog = false
                                    val subject = "Invitation to collaborate on SkoLab"
                                    val body = "Hi ${connection.author.name},\n\nI would love to collaborate with you on our research papers using SkoLab. SkoLab offers secure, encrypted voice/video synchronization, real-time LaTeX blackboards, and joint manuscript editing.\n\nJoin me on SkoLab here: https://skolab.open/invite\n\nBest regards,\nResearcher"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("mailto:")
                                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(emailInput.trim()))
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                                    }
                                    try {
                                        context.startActivity(android.content.Intent.createChooser(intent, "Send email via..."))
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No email client found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = EntropiColors.Gold1)
                        ) {
                            Text("Send Email", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEmailInviteDialog = false }) {
                            Text("Cancel", color = EntropiColors.Text3)
                        }
                    },
                    containerColor = EntropiColors.Card,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            if (showSMSInviteDialog) {
                var phoneInput by remember { mutableStateOf("") }
                
                AlertDialog(
                    onDismissRequest = { showSMSInviteDialog = false },
                    title = {
                        Text(
                            text = "SMS Invitation",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = EntropiColors.Cyan
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Send an SMS invite to ${connection.author.name}:",
                                color = EntropiColors.Text2,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("Mobile Number", color = EntropiColors.Text3) },
                                placeholder = { Text("e.g. +1234567890", color = EntropiColors.Text3) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = EntropiColors.Text,
                                    unfocusedTextColor = EntropiColors.Text,
                                    focusedBorderColor = EntropiColors.Cyan,
                                    unfocusedBorderColor = EntropiColors.Border,
                                    focusedContainerColor = EntropiColors.Card2,
                                    unfocusedContainerColor = EntropiColors.Card2
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (phoneInput.isNotBlank()) {
                                    showSMSInviteDialog = false
                                    val smsText = "Hi ${connection.author.name}, join me on SkoLab for secure, encrypted audio/video calling, real-time LaTeX blackboards, and joint manuscript editing: https://skolab.open/invite"
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = android.net.Uri.parse("smsto:${phoneInput.trim()}")
                                        putExtra("sms_body", smsText)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No SMS application found.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = EntropiColors.Cyan)
                        ) {
                            Text("Send SMS", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSMSInviteDialog = false }) {
                            Text("Cancel", color = EntropiColors.Text3)
                        }
                    },
                    containerColor = EntropiColors.Card,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricPill(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = JetBrainsMonoFontFamily)
        Text(text = label, color = EntropiColors.Text3, fontSize = 8.sp, fontFamily = SpaceGroteskFontFamily)
    }
}

// ── COMPONENT: CompactPaperTile ──────────────────────────────────────────────
@Composable
fun CompactPaperTile(
    paper: Paper,
    borderCol: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .width(140.dp)
            .height(180.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Accent Color Top Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(borderCol)
            )
            Column(modifier = Modifier.padding(10.dp)) {
                // Journal tag
                Text(
                    text = paper.journal.take(16).uppercase(),
                    color = borderCol,
                    fontSize = 8.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                // Score orb centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (paper.disruptionScore * 100).toInt().toString(),
                        color = EntropiColors.Gold2,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Title
                Text(
                    text = paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )

                // Stats
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${paper.citationCount} cit",
                        color = EntropiColors.Green,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "2026",
                        color = EntropiColors.Text3,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }
            }
        }
    }
}

// ── INSTITUTION TILE ─────────────────────────────────────────────────────────
@Composable
fun InstitutionTile(institution: Institution, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(EntropiColors.Card)
                .border(BorderStroke(1.dp, EntropiColors.Border), RoundedCornerShape(20.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(institution.color.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = institution.initials,
                    color = institution.color,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
        Text(
            text = institution.name,
            fontFamily = SpaceGroteskFontFamily,
            fontSize = 9.sp,
            color = EntropiColors.Text2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── OPEN ACCESS CARD ─────────────────────────────────────────────────────────
@Composable
fun OpenAccessCard(
    paper: Paper,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .width(280.dp)
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(EntropiColors.Green)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = EntropiColors.Green.copy(alpha = 0.08f),
                        border = BorderStroke(0.5.dp, EntropiColors.Green.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = "OPEN ACCESS",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = EntropiColors.Green,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }

                    // Download button
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(24.dp)
                            .background(EntropiColors.Card2, CircleShape)
                            .border(0.5.dp, EntropiColors.Border, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Read",
                            tint = EntropiColors.Green,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 10.sp,
                    color = EntropiColors.Text3
                )
            }
        }
    }
}

// ── RESUME CARD ──────────────────────────────────────────────────────────────
@Composable
fun ResumeCard(
    progress: ReadingProgress,
    onClick: () -> Unit,
    onResume: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .width(200.dp)
            .height(110.dp)
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // reading progress bar (vertical, 4dp wide, Gold fill, rounded)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(EntropiColors.Border)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(progress.progressPercent / 100f)
                        .background(EntropiColors.Gold1)
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = progress.paper.title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = EntropiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = progress.paper.journal,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 9.sp,
                    color = EntropiColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progress.progressPercent}% read",
                        color = EntropiColors.Gold2,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Resume →",
                        color = EntropiColors.Blue2,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onResume() }
                    )
                }
            }
        }
    }
}

// ── SECTION HEADER ───────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    title: String,
    badgeCount: Int? = null,
    subtitle: String? = null,
    onSeeAll: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = EntropiColors.Text
            )
            if (badgeCount != null) {
                Surface(
                    shape = CircleShape,
                    color = EntropiColors.Card2,
                    border = BorderStroke(0.5.dp, EntropiColors.Border)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = EntropiColors.Gold2,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (subtitle != null) {
            Text(
                text = subtitle,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = EntropiColors.Red,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = "See all →",
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 11.sp,
                color = EntropiColors.Gold2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onSeeAll() }
            )
        }
    }
}

// ── SHIMMER PAPER CARD ────────────────────────────────────────────────────────
@Composable
fun PaperShimmerCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShimmerBar(Modifier.width(120.dp).height(10.dp))
                ShimmerBar(Modifier.width(36.dp).height(10.dp))
            }
            Spacer(Modifier.height(12.dp))
            ShimmerBar(Modifier.fillMaxWidth().height(14.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBar(Modifier.fillMaxWidth(0.85f).height(14.dp))
            Spacer(Modifier.height(10.dp))
            ShimmerBar(Modifier.width(80.dp).height(8.dp))
        }
    }
}

private fun getCountdownText(): String {
    val calendar = Calendar.getInstance()
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val daysLeft = if (dayOfWeek == Calendar.SUNDAY) 0 else 8 - dayOfWeek
    return "Resets in $daysLeft days"
}

private data class HomeAction(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit
)

@Composable
fun ResearchActionRail(
    onNavigateToAgent: () -> Unit,
    onNavigateToCollabs: () -> Unit,
    onNavigateToMetrics: () -> Unit,
    onNavigateToIndustry: () -> Unit,
    onNavigateToPapers: () -> Unit,
    onNavigateToDailyDiscovery: () -> Unit
) {
    val actions = listOf(
        HomeAction("Ask Agent", Icons.Default.AutoAwesome, AccentViolet, onNavigateToAgent),
        HomeAction("Team Pulse", Icons.Default.People, AccentTeal, onNavigateToCollabs),
        HomeAction("Metrics", Icons.Default.AutoGraph, AccentAmber, onNavigateToMetrics),
        HomeAction("Industry", Icons.Default.BusinessCenter, AccentEmerald, onNavigateToIndustry),
        HomeAction("Papers", Icons.AutoMirrored.Filled.Article, AccentRose, onNavigateToPapers),
        HomeAction("Discovery", Icons.Default.Search, AccentIndigo, onNavigateToDailyDiscovery)
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(actions) { action ->
            Surface(
                onClick = action.onClick,
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                border = BorderStroke(1.dp, action.tint.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(action.tint.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = action.tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = action.label,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun DailyChallengeCard(
    conjecture: Conjecture,
    onOpenChallenge: () -> Unit
) {
    Surface(
        onClick = onOpenChallenge,
        shape = RoundedCornerShape(18.dp),
        color = BgCard,
        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(AccentAmber.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = "Daily challenge",
                        tint = AccentAmber,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Today's challenge",
                        color = AccentAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = conjecture.category,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AccentAmber.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Open",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = AccentAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = conjecture.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = conjecture.hypothesis,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                conjecture.options.take(2).forEach { option ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AccentTeal.copy(alpha = 0.08f)
                    ) {
                        Text(
                            text = option,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = AccentTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerMomentumStrip(
    peers: List<Connection>,
    onAuthorClick: (String) -> Unit
) {
    if (peers.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.People, null, tint = AccentTeal, modifier = Modifier.size(16.dp))
                Text(
                    text = "Peers moving fast",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "Tap to inspect",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(peers) { peer ->
                Surface(
                    onClick = { onAuthorClick("${peer.author.name}|${peer.author.id}") },
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.22f)),
                    modifier = Modifier.width(190.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.author.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = peer.author.institution,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentTeal.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "H ${peer.hIndex}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentTeal,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = peer.connectionPath.ifBlank { "Suggested collaborator" },
                            color = AccentTeal,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentAmber.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${peer.totalPublications} papers",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentAmber,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentRose.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${peer.papersCollaborated} joint",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentRose,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
