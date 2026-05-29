package com.open.skolab.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.School
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
import com.open.skolab.auth.AuthManager
import com.open.skolab.data.UserPreferences
import com.open.skolab.model.SkoLabUser
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.launch

data class ArXivDiscipline(
    val name: String,
    val categoryCode: String,
    val emoji: String,
    val description: String
)

val arxivDisciplines = listOf(
    ArXivDiscipline("Physics", "Physics", "⚛️", "Astrophysics, Condensed Matter, Quantum, High-Energy Physics"),
    ArXivDiscipline("Mathematics", "Mathematics", "📐", "Algebra, Geometry, Analysis, Probability, Topology"),
    ArXivDiscipline("Computer Science", "Computer Science", "🤖", "AI, Machine Learning, Security, Theory, Software"),
    ArXivDiscipline("Quantitative Biology", "Quantitative Biology", "🧬", "Genomics, Biomolecules, Neurons, Populations"),
    ArXivDiscipline("Quantitative Finance", "Quantitative Finance", "📈", "Portfolio Management, Pricing, Mathematical Finance"),
    ArXivDiscipline("Statistics", "Statistics", "📊", "Applications, Methodology, Computation, Machine Learning"),
    ArXivDiscipline("Electrical Engineering", "Electrical Engineering", "⚡", "Signal Processing, Control, Systems, Audio"),
    ArXivDiscipline("Economics", "Economics", "💸", "Theoretical and Applied Econometrics, Micro, Macro")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { AuthManager(context) }
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = PRIMARY,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Welcome to SkoLab",
                style = Typography.displaySmall,
                color = TEXT_PRIMARY,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Let's personalize your academic workspace and discover dynamic papers.",
                style = Typography.bodyMedium,
                color = TEXT_SECONDARY,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Full Name input card
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BORDER),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Your Full Name",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TEXT_PRIMARY
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text("e.g. Vikas Vijigiri", color = TEXT_MUTED) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TEXT_PRIMARY,
                            unfocusedTextColor = TEXT_PRIMARY,
                            focusedBorderColor = PRIMARY,
                            unfocusedBorderColor = BORDER,
                            focusedContainerColor = BgPrimary,
                            unfocusedContainerColor = BgPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Discipline list card
            Text(
                text = "Choose Your Research Discipline",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TEXT_PRIMARY,
                modifier = Modifier.align(Alignment.Start)
            )
            Text(
                text = "Select your primary arXiv field to build a personalized feed.",
                fontSize = 12.sp,
                color = TEXT_SECONDARY,
                modifier = Modifier.align(Alignment.Start).padding(top = 2.dp, bottom = 12.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                arxivDisciplines.forEach { disc ->
                    val isSelected = selectedDiscipline == disc
                    Surface(
                        onClick = { selectedDiscipline = disc },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) PRIMARY.copy(alpha = 0.05f) else BgCard,
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) PRIMARY else BORDER
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = disc.emoji,
                                fontSize = 28.sp,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = disc.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TEXT_PRIMARY
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = disc.description,
                                    fontSize = 11.sp,
                                    color = TEXT_SECONDARY,
                                    lineHeight = 15.sp
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(PRIMARY, RoundedCornerShape(50))
                                        .align(Alignment.CenterVertically),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Action Button
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
                                
                                // Industry standard local persistence using DataStore
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

                                // Sync changes to remote Firestore DB
                                authManager.updateUserProfile(trimmedName, discipline.name)
                                
                                // Successful profile setup leads to discover
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
                    .height(48.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("Start Exploring SkoLab", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
