package com.open.skolab.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.open.skolab.auth.AuthManager
import com.open.skolab.di.AppDependencies
import com.open.skolab.data.UserPreferences
import com.open.skolab.model.SkoLabUser
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = AppDependencies.authManager
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)

    var nameInput by remember { mutableStateOf("") }
    var selectedDiscipline by remember { mutableStateOf<ArXivDiscipline?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Pre-populate name if already present in firebase auth
    LaunchedEffect(cachedUser) {
        if (nameInput.isBlank()) {
            nameInput = cachedUser?.name ?: authManager.currentUser?.displayName ?: ""
        }
        if (selectedDiscipline == null && !cachedUser?.researchFocus.isNullOrBlank()) {
            selectedDiscipline = arxivDisciplines.find { it.name.equals(cachedUser?.researchFocus, ignoreCase = true) }
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
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = BORDER,
                        focusedContainerColor = BgCard,
                        unfocusedContainerColor = BgCard
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
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
                onClick = {
                    val trimmedName = nameInput.trim()
                    val discipline = selectedDiscipline
                    if (trimmedName.isNotEmpty() && discipline != null) {
                        isSaving = true
                        scope.launch {
                            try {
                                val uid = authManager.currentUser?.uid ?: "user_default"
                                val email = authManager.currentUser?.email ?: ""
                                
                                userPrefs.cacheUser(
                                    SkoLabUser(
                                        uid = uid,
                                        name = trimmedName,
                                        email = email,
                                        researchFocus = discipline.name,
                                        complexityScore = 0.5f,
                                        savedPapers = emptyList()
                                    )
                                )

                                authManager.updateUserProfile(trimmedName, discipline.name)
                                onSetupComplete()
                            } catch (e: Exception) {
                                android.util.Log.e("ProfileSetupScreen", "Failed to save profile", e)
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                },
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

