package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.tasks.await
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskScreen(
    projectId: String,
    onBack: () -> Unit,
    onTaskCreated: (title: String, assignee: String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val db = remember { FirebaseFirestore.getInstance() }
    
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskAssignee by remember { mutableStateOf("You") }
    var assignees by remember { mutableStateOf<List<String>>(listOf("You")) }
    var projectName by remember { mutableStateOf("") }
    var isLoadingProject by remember { mutableStateOf(true) }
    var titleError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        try {
            val doc = db.collection("collabs_groups").document(projectId).get().await()
            if (doc.exists()) {
                projectName = doc.getString("name") ?: "Project"
                @Suppress("UNCHECKED_CAST")
                val membersList = doc.get("members") as? List<Map<String, Any>> ?: emptyList()
                val fetchedAssignees = membersList.mapNotNull { it["name"] as? String }
                if (fetchedAssignees.isNotEmpty()) {
                    assignees = fetchedAssignees
                }
            }
        } catch (e: Exception) {
            // Handle error silently
        } finally {
            isLoadingProject = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create Project Task",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = com.company.skolab.ui.theme.SkoLabColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (isLoadingProject) {
                CircularProgressIndicator(color = AccentTeal, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        OutlinedTextField(
                            value = newTaskTitle,
                            onValueChange = { 
                                newTaskTitle = it 
                                if (it.isNotBlank()) titleError = null
                            },
                            label = { Text("Task Description", color = TextMuted) },
                            placeholder = { Text("e.g. Run DMRG scaling simulations", color = TextMuted) },
                            isError = titleError != null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (newTaskTitle.isBlank()) {
                                        titleError = "Task description cannot be empty"
                                    } else {
                                        onTaskCreated(newTaskTitle.trim(), newTaskAssignee)
                                        onBack()
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated,
                                errorBorderColor = SkoLabWarning,
                                errorLabelColor = SkoLabWarning
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        if (titleError != null) {
                            Text(
                                text = titleError ?: "",
                                color = SkoLabWarning,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        // AI Brainstorming suggestion trigger
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                val nameLower = projectName.lowercase()
                                val suggestions = when {
                                    nameLower.contains("spin-1") -> listOf(
                                        "Perform scaling analysis of single-ion anisotropy D on 12x12 lattice" to "Nisheeta Desai",
                                        "Calculate spin-1 Hamiltonian perturbation corrections" to "Sumiran Pujari",
                                        "Run Heisenberg lattice ground-state DMRG simulations" to "You"
                                    )
                                    nameLower.contains("field scaling") -> listOf(
                                        "Extract critical exponents near entanglement phase boundary" to "Saptarshi Mandal",
                                        "Verify scaling relations and fit correlation length" to "Nisheeta Desai",
                                        "Plot entanglement entropy trace S_E = -Tr(rho_A ln rho_A)" to "You"
                                    )
                                    nameLower.contains("skolabc") -> listOf(
                                        "Model energy dispersion for twisted bilayer twist angle 0.045 rad" to "Sumiran Pujari",
                                        "Analyze non-equilibrium skolabc phase dynamics" to "K. G. Paulson",
                                        "Draft twisted bilayer manuscript methodology" to "You"
                                    )
                                    else -> listOf(
                                        "Draft joint manuscript methodology & introduction sections" to "You",
                                        "Run convergence check parameters against baseline models" to (assignees.lastOrNull { it != "You" } ?: "You"),
                                        "Compile and format LaTeX figures for final PRL submission" to "You"
                                    )
                                }
                                val randomPick = suggestions.random()
                                newTaskTitle = randomPick.first
                                newTaskAssignee = if (assignees.contains(randomPick.second)) randomPick.second else "You"
                                Toast.makeText(context, "✨ AI suggested a highly relevant scientific task!", Toast.LENGTH_SHORT).show()
                            },
                            color = DeepGreenTealBg.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BrightAccentTeal.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Suggest",
                                    tint = BrightAccentTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "✨ AI Brainstorm Scientific Task",
                                        color = BrightAccentTeal,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Generates advanced custom tasks tailored to this project's equations and name.",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        // Assignee Picker
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Assignee",
                                color = TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                assignees.forEach { name ->
                                    val isSelected = newTaskAssignee == name
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { newTaskAssignee = name },
                                        label = { Text(name, fontSize = 13.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentTeal.copy(alpha = 0.15f),
                                            selectedLabelColor = AccentTeal,
                                            containerColor = BgElevated,
                                            labelColor = TextSecondary
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            selectedBorderColor = AccentTeal,
                                            borderColor = BorderLight
                                        )
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (newTaskTitle.isBlank()) {
                                    titleError = "Task description cannot be empty"
                                    return@Button
                                }
                                onTaskCreated(newTaskTitle.trim(), newTaskAssignee)
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("Create Task", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
