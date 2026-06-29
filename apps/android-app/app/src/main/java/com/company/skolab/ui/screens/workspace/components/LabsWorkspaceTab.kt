package com.company.skolab.ui.screens.workspace.components

import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*

import androidx.compose.ui.platform.LocalContext
import com.company.skolab.data.UserPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import com.company.skolab.network.ChatMessage

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.*
import com.company.skolab.ui.components.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.company.skolab.model.*
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LabsWorkspaceTab(isEnabled: Boolean) {
    if (!isEnabled) {
        PremiumPlaceholderCard(
            requiredTier = "SkoLab Labs",
            featureDesc = "SkoLab Labs is the unified workspace for research groups and departments. Create shared paper vaults, invite lab collaborators, leave annotations on papers, and build automatic synergy matrices."
        )
        return
    }

    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)
    val currentUserName = cachedUser?.name ?: "SkoLab User"
    val currentUserEmail = cachedUser?.email ?: "user@university.edu"
    val userFirstName = cachedUser?.firstName ?: "User"

    var collaboratorEmail by remember { mutableStateOf("") }
    val collaborators = remember(currentUserName, currentUserEmail) {
        mutableStateListOf(
            LabMember("Prof. $currentUserName", currentUserEmail, "Director / PI"),
            LabMember("Dr. Ananya Rao", "ananya@iith.ac.in", "Postdoc Fellow"),
            LabMember("Sundeep Sen", "sundeep@iith.ac.in", "PhD Researcher")
        )
    }

    var sharedPaperTitle by remember { mutableStateOf("") }
    val sharedVault = remember(userFirstName) {
        mutableStateListOf(
            SharedPaper("Entropy Collapse in LLM Logs", "Added by Sundeep, 2 hours ago"),
            SharedPaper("Topological Quantum Field Theory in 2D", "Added by Prof. $userFirstName, 1 day ago")
        )
    }

    var showSynergyMatrix by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Lab Members Section
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LAB RESEARCH GROUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentEmerald,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    collaborators.forEach { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = AccentEmerald,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        member.name.split(" ").last().take(1),
                                        color = AccentEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(member.email, fontSize = 11.sp, color = TextSecondary)
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = BgSubtle,
                                border = BorderStroke(0.5.dp, BorderLight)
                            ) {
                                Text(
                                    member.role,
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = collaboratorEmail,
                            onValueChange = { collaboratorEmail = it },
                            placeholder = { Text("Enter collaborator email") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (collaboratorEmail.isNotBlank() && collaboratorEmail.contains("@")) {
                                    val name = collaboratorEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                                    collaborators.add(LabMember(name, collaboratorEmail, "Researcher"))
                                    collaboratorEmail = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Invite", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Shared Paper Vault Section
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SHARED PAPER VAULT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    sharedVault.forEach { paper ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(paper.title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(paper.meta, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = sharedPaperTitle,
                            onValueChange = { sharedPaperTitle = it },
                            placeholder = { Text("Enter paper title to share") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (sharedPaperTitle.isNotBlank()) {
                                    sharedVault.add(SharedPaper(sharedPaperTitle, "Added by You, Just Now"))
                                    sharedPaperTitle = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Share", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Synergy matrix trigger
        item {
            Button(
                onClick = { showSynergyMatrix = !showSynergyMatrix },
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.TableChart, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (showSynergyMatrix) "Hide Comparison Matrix" else "Synthesize Comparison Matrix",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Dynamic Comparison Synergy Matrix
        if (showSynergyMatrix) {
            item {
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AccentAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            "CROSS-PAPER SYNERGY MATRIX",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Render Matrix Table
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Dimension", width = 100.dp, header = true)
                            TableTextCell("Entropy Collapse", width = 140.dp, header = true)
                            TableTextCell("TQFT in 2D", width = 140.dp, header = true)
                        }
                        HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Core Model", width = 100.dp, header = false)
                            TableTextCell("Autoregressive LLM", width = 140.dp, header = false)
                            TableTextCell("Chern-Simons", width = 140.dp, header = false)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Key Metric", width = 100.dp, header = false)
                            TableTextCell("Perplexity Bounds", width = 140.dp, header = false)
                            TableTextCell("Berry Curvature", width = 140.dp, header = false)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Limitation", width = 100.dp, header = false)
                            TableTextCell("Finite Context", width = 140.dp, header = false)
                            TableTextCell("Gauge Ambiguity", width = 140.dp, header = false)
                        }
                    }
                }
            }
        }
    }
}

