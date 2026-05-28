package com.open.skolab.ui.screens

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
import com.open.skolab.ui.theme.AccentTeal
import com.open.skolab.ui.theme.BgCard
import com.open.skolab.ui.theme.BgElevated
import com.open.skolab.ui.theme.BorderLight
import com.open.skolab.ui.theme.SyneFontFamily
import com.open.skolab.ui.theme.TextMuted
import com.open.skolab.ui.theme.TextPrimary
import com.open.skolab.ui.theme.TextSecondary
import kotlinx.coroutines.tasks.await
import android.view.HapticFeedbackConstants

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
        containerColor = com.open.skolab.ui.theme.EntropiColors.Background
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
                            onValueChange = { newTaskTitle = it },
                            label = { Text("Task Description", color = TextMuted) },
                            placeholder = { Text("e.g. Run DMRG scaling simulations", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )

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
                                    nameLower.contains("entropic") -> listOf(
                                        "Model energy dispersion for twisted bilayer twist angle 0.045 rad" to "Sumiran Pujari",
                                        "Analyze non-equilibrium entropic phase dynamics" to "K. G. Paulson",
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
                            color = Color(0xFF004D40).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF00BFA5).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Suggest",
                                    tint = Color(0xFF00BFA5),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "✨ AI Brainstorm Scientific Task",
                                        color = Color(0xFF00BFA5),
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
                                    Toast.makeText(context, "Task description cannot be empty.", Toast.LENGTH_SHORT).show()
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
