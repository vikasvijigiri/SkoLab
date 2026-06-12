package com.company.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import com.company.skolab.data.UserPreferences
import com.company.skolab.model.SkoLabUser
import com.company.skolab.network.AuthorSuggestion
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import com.company.skolab.ui.theme.SkoLabWarning

data class ArXivDiscipline(
    val name: String,
    val emoji: String
)

val arxivDisciplines = listOf(
    ArXivDiscipline("Physics", "⚛️"),
    ArXivDiscipline("Mathematics", "📐"),
    ArXivDiscipline("Computer Science", "🤖"),
    ArXivDiscipline("Quantitative Biology", "🧬"),
    ArXivDiscipline("Quantitative Finance", "📈"),
    ArXivDiscipline("Statistics", "📊"),
    ArXivDiscipline("Electrical Engineering", "⚡"),
    ArXivDiscipline("Economics", "💸")
)

// ── OpenAlex match states ─────────────────────────────────────────────────────
private sealed class OpenAlexMatchState {
    object Idle : OpenAlexMatchState()
    object Searching : OpenAlexMatchState()
    data class Found(val suggestion: AuthorSuggestion) : OpenAlexMatchState()
    object NotFound : OpenAlexMatchState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = AppDependencies.authManager
    val apiService = AppDependencies.apiService
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)

    var nameInput by remember { mutableStateOf("") }
    var selectedDiscipline by remember { mutableStateOf<ArXivDiscipline?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // OpenAlex match state
    var matchState by remember { mutableStateOf<OpenAlexMatchState>(OpenAlexMatchState.Idle) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    val onBuildIndex = {
        val trimmedName = nameInput.trim()
        val discipline = selectedDiscipline
        if (trimmedName.isNotEmpty() && discipline != null && !isSaving) {
            isSaving = true
            scope.launch {
                try {
                    val uid = authManager.currentUser?.uid ?: "user_default"
                    val email = authManager.currentUser?.email ?: ""

                    // If OpenAlex confirmed a match, use the canonical display_name as the stored name
                    val resolvedName = when (val s = matchState) {
                        is OpenAlexMatchState.Found -> s.suggestion.display_name
                        else -> trimmedName
                    }
                    
                    userPrefs.cacheUser(
                        SkoLabUser(
                            uid = uid,
                            name = resolvedName,
                            email = email,
                            researchFocus = discipline.name,
                            complexityScore = 0.5f,
                            savedPapers = emptyList()
                        )
                    )

                    authManager.updateUserProfile(resolvedName, discipline.name)
                    SkoLabAnalytics.logOnboardingCompleted(discipline.name)
                    onSetupComplete()
                } catch (e: Exception) {
                    android.util.Log.e("ProfileSetupScreen", "Failed to save profile", e)
                } finally {
                    isSaving = false
                }
            }
        }
    }

    // Pre-populate name if already present in firebase auth
    LaunchedEffect(cachedUser) {
        if (nameInput.isBlank()) {
            nameInput = cachedUser?.name ?: authManager.currentUser?.displayName ?: ""
        }
        if (selectedDiscipline == null && !cachedUser?.researchFocus.isNullOrBlank()) {
            selectedDiscipline = arxivDisciplines.find { it.name.equals(cachedUser?.researchFocus, ignoreCase = true) }
        }
    }

    // Debounced OpenAlex lookup — fires 700ms after the user stops typing
    LaunchedEffect(nameInput) {
        debounceJob?.cancel()
        val query = nameInput.trim()
        if (query.length < 4) {
            matchState = OpenAlexMatchState.Idle
            return@LaunchedEffect
        }
        matchState = OpenAlexMatchState.Idle
        debounceJob = scope.launch {
            delay(700)
            matchState = OpenAlexMatchState.Searching
            try {
                val suggestions = apiService.getAuthorSuggestions(query)
                val queryTokens = query.lowercase().split(" ").filter { it.length > 2 }
                val match = suggestions.firstOrNull { s ->
                    val sName = s.display_name.lowercase()
                    queryTokens.all { token -> sName.contains(token) }
                }
                matchState = if (match != null) {
                    OpenAlexMatchState.Found(match)
                } else {
                    OpenAlexMatchState.NotFound
                }
            } catch (e: Exception) {
                // Backend not reachable — silently stay Idle; not a blocker
                matchState = OpenAlexMatchState.Idle
            }
        }
    }

    Scaffold(
        containerColor = BgPrimary
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BgPrimary)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Premium Heading using Space Grotesk
            Text(
                text = "Academic Profile",
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = TEXT_PRIMARY,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Set up your details to build a personalized search index.",
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TEXT_SECONDARY,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 1. Name Input Column
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Name / OpenAlex Identifier",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TEXT_PRIMARY
                )
                
                Text(
                    text = "This username will be used to automatically query and load your publications.",
                    fontFamily = BodyFontFamily,
                    fontSize = 12.sp,
                    color = TEXT_SECONDARY,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text("e.g. Albert Einstein", color = TEXT_MUTED) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = BodyFontFamily,
                        fontSize = 15.sp,
                        color = TEXT_PRIMARY
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT_PRIMARY,
                        unfocusedTextColor = TEXT_PRIMARY,
                        focusedBorderColor = when (matchState) {
                            is OpenAlexMatchState.Found -> OpenAlexGreen
                            is OpenAlexMatchState.NotFound -> OpenAlexOrange
                            else -> PRIMARY
                        },
                        unfocusedBorderColor = when (matchState) {
                            is OpenAlexMatchState.Found -> OpenAlexGreen.copy(alpha = 0.6f)
                            is OpenAlexMatchState.NotFound -> OpenAlexOrange.copy(alpha = 0.5f)
                            else -> BORDER
                        },
                        focusedContainerColor = BgCard,
                        unfocusedContainerColor = BgCard
                    ),
                    trailingIcon = {
                        when (matchState) {
                            is OpenAlexMatchState.Searching -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = PRIMARY,
                                    strokeWidth = 2.dp
                                )
                            }
                            is OpenAlexMatchState.Found -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Profile found on OpenAlex",
                                    tint = OpenAlexGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            is OpenAlexMatchState.NotFound -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Profile not found on OpenAlex",
                                    tint = OpenAlexOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search OpenAlex",
                                    tint = TEXT_MUTED,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onBuildIndex() }
                    )
                )

                // ── OpenAlex match feedback banner ─────────────────────────────
                AnimatedVisibility(
                    visible = matchState is OpenAlexMatchState.Found || matchState is OpenAlexMatchState.NotFound,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    when (val state = matchState) {
                        is OpenAlexMatchState.Found -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                color = OpenAlexGreen.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, OpenAlexGreen.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        text = "✓ Matched on OpenAlex",
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = OpenAlexGreen
                                    )
                                    Text(
                                        text = "${state.suggestion.display_name} · ${state.suggestion.institution}",
                                        fontFamily = BodyFontFamily,
                                        fontSize = 12.sp,
                                        color = TEXT_SECONDARY,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                        is OpenAlexMatchState.NotFound -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                color = OpenAlexOrange.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, OpenAlexOrange.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "⚠ No profile found on OpenAlex — your publications may not load automatically. You can still continue.",
                                    fontFamily = BodyFontFamily,
                                    fontSize = 12.sp,
                                    color = OpenAlexGold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // 2. Discipline Grid Column
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Primary Research Field",
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TEXT_PRIMARY
                )
                
                Text(
                    text = "Select your main field of study to personalize your feed recommendations.",
                    fontFamily = BodyFontFamily,
                    fontSize = 12.sp,
                    color = TEXT_SECONDARY,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val rows = arxivDisciplines.chunked(2)
                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (disc in row) {
                                val isSelected = selectedDiscipline == disc
                                Surface(
                                    onClick = { selectedDiscipline = disc },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) PRIMARY.copy(alpha = 0.08f) else BgCard,
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) PRIMARY else BORDER
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = disc.emoji,
                                            fontSize = 20.sp,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(
                                            text = disc.name,
                                            fontFamily = BodyFontFamily,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            color = if (isSelected) PRIMARY else TEXT_PRIMARY,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Action button
            Button(
                onClick = onBuildIndex,
                enabled = nameInput.trim().isNotEmpty() && selectedDiscipline != null && !isSaving,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        text = "Build My Index",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
