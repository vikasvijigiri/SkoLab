package com.open.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.open.skolab.ui.components.MarkdownText
import com.open.skolab.auth.AuthManager
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.Toast

data class CollabMember(
    val uid: String = "",
    val name: String = "",
    val email: String = ""
)

data class ProjectCollab(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val members: List<CollabMember> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val recentEquations: String = "",
    val manuscriptProgress: Float = 0f,
    val createdAt: Long = 0L
) {
    val activeCoAuthors: List<String>
        @com.google.firebase.firestore.Exclude
        get() = members.map { it.name }.filter { name ->
            name.lowercase() != "you" && !name.equals("vikas vijigiri", ignoreCase = true)
        }
}

data class CollabTask(
    val id: Int,
    val title: String,
    val assignee: String,
    val isCompleted: Boolean
)

data class CollabEvent(
    val author: String,
    val action: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperCollabsScreen(
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val currentUser = authManager.currentUser
    val currentUserId = currentUser?.uid ?: ""
    val currentUserName = currentUser?.displayName ?: "Vikas Vijigiri"
    val currentUserEmail = currentUser?.email ?: "vikas@example.com"

    val db = remember { FirebaseFirestore.getInstance() }
    var dbProjects by remember { mutableStateOf<List<ProjectCollab>>(emptyList()) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showInviteMemberDialog by remember { mutableStateOf(false) }
    
    // Create Dialog fields
    var newProjName by remember { mutableStateOf("") }
    var newProjDesc by remember { mutableStateOf("") }
    var newProjEq by remember { mutableStateOf("") }
    var memberEmailInput by remember { mutableStateOf("") }
    var membersList by remember { mutableStateOf<List<CollabMember>>(emptyList()) }
    var isSearchingMember by remember { mutableStateOf(false) }

    // Invite Member Dialog fields
    var inviteEmailInput by remember { mutableStateOf("") }
    var isInvitingMember by remember { mutableStateOf(false) }

    DisposableEffect(currentUserId) {
        if (currentUserId.isEmpty()) {
            onDispose {}
        } else {
            val listener = db.collection("collabs_groups")
                .whereArrayContains("memberUids", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        android.util.Log.e("PaperCollabsScreen", "Error listening to collab groups", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.toObjects(ProjectCollab::class.java)
                        dbProjects = list.sortedByDescending { it.createdAt }
                    }
                }
            onDispose {
                listener.remove()
            }
        }
    }

    val defaultProjects = remember {
        listOf(
            ProjectCollab(
                id = "default_1",
                name = "Project Spin-1 Antiferromagnet",
                description = "Deconfined pseudocriticality on 12x12 square lattice",
                ownerUid = "default_owner",
                ownerName = "Vikas Vijigiri",
                members = listOf(
                    CollabMember("you_uid", "You", "vikas@example.com"),
                    CollabMember("sumiran_uid", "Sumiran Pujari", "sumiran@example.com"),
                    CollabMember("nisheeta_uid", "Nisheeta Desai", "nisheeta@example.com")
                ),
                memberUids = listOf("you_uid", "sumiran_uid", "nisheeta_uid"),
                recentEquations = "\\mathcal{H} = J \\sum \\mathbf{S}_i \\cdot \\mathbf{S}_j - D \\sum (S_i^z)^2",
                manuscriptProgress = 0.72f,
                createdAt = System.currentTimeMillis()
            ),
            ProjectCollab(
                id = "default_2",
                name = "Project Transverse Field Scaling",
                description = "Entanglement entropy near phase boundary",
                ownerUid = "default_owner",
                ownerName = "Vikas Vijigiri",
                members = listOf(
                    CollabMember("you_uid", "You", "vikas@example.com"),
                    CollabMember("saptarshi_uid", "Saptarshi Mandal", "saptarshi@example.com"),
                    CollabMember("nisheeta_uid", "Nisheeta Desai", "nisheeta@example.com")
                ),
                memberUids = listOf("you_uid", "saptarshi_uid", "nisheeta_uid"),
                recentEquations = "S_E = -\\text{Tr}(\\rho_A \\ln \\rho_A)",
                manuscriptProgress = 0.45f,
                createdAt = System.currentTimeMillis()
            ),
            ProjectCollab(
                id = "default_3",
                name = "Project Entropic Dynamics",
                description = "Non-equilibrium states in twisted bilayers",
                ownerUid = "default_owner",
                ownerName = "Vikas Vijigiri",
                members = listOf(
                    CollabMember("you_uid", "You", "vikas@example.com"),
                    CollabMember("sumiran_uid", "Sumiran Pujari", "sumiran@example.com"),
                    CollabMember("paulson_uid", "K. G. Paulson", "paulson@example.com")
                ),
                memberUids = listOf("you_uid", "sumiran_uid", "paulson_uid"),
                recentEquations = "\\theta \\approx 0.045 \\text{ rad}",
                manuscriptProgress = 0.20f,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    val projects = remember(dbProjects) {
        defaultProjects + dbProjects
    }

    var selectedProjectIndex by remember { mutableStateOf(0) }
    val currentProject = projects.getOrNull(selectedProjectIndex) ?: projects.firstOrNull() ?: defaultProjects.first()
    var showProjectDropdown by remember { mutableStateOf(false) }

    // Dynamic state for task board
    var tasks by remember(selectedProjectIndex) {
        mutableStateOf(
            listOf(
                CollabTask(1, "Run square lattice DMRG simulation", "Nisheeta Desai", true),
                CollabTask(2, "Calculate single-ion anisotropy term D", "Sumiran Pujari", true),
                CollabTask(3, "Draft manuscript methodology section", "You", false),
                CollabTask(4, "Compare perturbation theory results", "Sumiran Pujari", false),
                CollabTask(5, "Format final figures for PRL submission", "You", false)
            )
        )
    }

    // Dynamic timeline logs
    var timelineLogs by remember(selectedProjectIndex) {
        mutableStateOf(
            listOf(
                CollabEvent("Sumiran Pujari", "updated the spin Hamiltonian equation", "2 hours ago"),
                CollabEvent("Nisheeta Desai", "uploaded new square-lattice DMRG dataset", "5 hours ago"),
                CollabEvent("You", "updated draft introduction manuscript", "1 day ago")
            )
        )
    }

    // Simulation for chat messages
    val chatMessages = remember(selectedProjectIndex) {
        mutableStateListOf(
            "Sumiran: Vikas, did you double check the spin-1 parameters?",
            "Nisheeta: I finished the scaling checks, exponents look correct.",
            "Sumiran: Excellent. Let's update the Blackboard layout."
        )
    }
    
    var groupMessageInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Video Call simulation
    var showVideoSync by remember { mutableStateOf(false) }
    var callConnectedTime by remember { mutableStateOf(0) }

    if (showVideoSync) {
        LaunchedEffect(Unit) {
            while (showVideoSync) {
                delay(1000)
                callConnectedTime++
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with Dropdown Project Selector
            Surface(
                color = BgCard.copy(alpha = 0.95f),
                border = BorderStroke(0.5.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = "PaperCollabs",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AccentTeal
                            )
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        showProjectDropdown = !showProjectDropdown
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentProject.name,
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 240.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showProjectDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Project",
                                    tint = AccentTeal
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Create Project Button
                            IconButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    showCreateDialog = true
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgElevated)
                                    .border(BorderStroke(1.dp, BorderLight), RoundedCornerShape(10.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Create Project",
                                    tint = AccentTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Sync button (Virtual meeting room trigger)
                            val isDefaultProject = currentProject.id.startsWith("default_")
                            Button(
                                onClick = {
                                    if (isDefaultProject) {
                                        Toast.makeText(context, "Start Sync is only available for registered SkoLab co-authors. Invite them to join to start call sync.", Toast.LENGTH_LONG).show()
                                    } else {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        showVideoSync = true
                                        callConnectedTime = 0
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDefaultProject) Color(0xFF2E3B43).copy(alpha = 0.5f) else Color(0xFF00A884)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = "Sync Room",
                                    tint = if (isDefaultProject) Color.White.copy(alpha = 0.38f) else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Start Sync",
                                    color = if (isDefaultProject) Color.White.copy(alpha = 0.38f) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Project selection dropdown
                    DropdownMenu(
                        expanded = showProjectDropdown,
                        onDismissRequest = { showProjectDropdown = false },
                        modifier = Modifier
                            .background(BgCard)
                            .border(BorderStroke(0.5.dp, BorderLight), RoundedCornerShape(12.dp))
                    ) {
                        projects.forEachIndexed { index, proj ->
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(text = proj.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(text = proj.description, color = TextSecondary, fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    selectedProjectIndex = index
                                    showProjectDropdown = false
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Active Collaborators Profile Ring
                item {
                    Text(
                        text = "ACTIVE COLLABORATORS",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        // User Avatar
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(AccentTeal.copy(alpha = 0.2f))
                                    .border(1.5.dp, AccentTeal, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "VV",
                                    color = AccentTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "You",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Co-author Avatars
                        currentProject.activeCoAuthors.forEach { author ->
                            val initials = author.split(" ").map { it.take(1) }.joinToString("").uppercase()
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        // Open chat with co-author (id resolved mock-wise based on name)
                                        val mockId = "https://openalex.org/" + author.hashCode().toString()
                                        onNavigateToChat(author, mockId)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.sweepGradient(
                                                    colors = listOf(AccentAmber, AccentCyan, AccentAmber)
                                                )
                                            )
                                            .padding(1.5.dp)
                                            .background(BgCard, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = initials,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                    // Online Indicator Dot
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF25D366))
                                            .border(1.5.dp, BgPrimary, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = author.split(" ").firstOrNull() ?: author,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Add new collaborator shortcut button
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(BgElevated)
                                    .border(1.dp, BorderLight.copy(alpha = 0.5f), CircleShape)
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        if (currentProject.id.startsWith("default_")) {
                                            Toast.makeText(context, "Cannot add members to default mock projects.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showInviteMemberDialog = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = "Add",
                                    tint = AccentTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Invite",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Section 2: Shared Blackboard and Manuscript status card
                item {
                    Text(
                        text = "BLACKBOARD & EQUATION HUB",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = BgCard.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onNavigateToWorkspace(currentProject.name)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.EditNote,
                                        contentDescription = "Blackboard",
                                        tint = AccentTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Blackboard Draft",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Surface(
                                    color = Color(0xFF00A884).copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "LATEX ACTIVE",
                                        color = Color(0xFF00A884),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Render equations beautifully inside chalkboard styling
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0E1A14)) // Dark chalk green
                                    .border(1.dp, Color(0xFF1E3A2F), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                MarkdownText(
                                    markdown = "$$" + currentProject.recentEquations + "$$",
                                    color = Color(0xFFD1F2E5),
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Manuscript draft progress",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "${(currentProject.manuscriptProgress * 100).toInt()}%",
                                    color = AccentTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { currentProject.manuscriptProgress },
                                color = AccentTeal,
                                trackColor = BorderLight.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }

                // Section 3: Shared Tasks / Checklist Board
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PROJECT ROADMAP & TASKS",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                // Add task simulation
                                val newId = tasks.size + 1
                                tasks = tasks + CollabTask(newId, "Check boundary conditions", "You", false)
                            },
                            color = Color.Transparent
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = "Add Task", tint = AccentTeal, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(text = "Add", color = AccentTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = BgCard.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            tasks.forEachIndexed { idx, task ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            // Toggle completion state
                                            tasks = tasks.map {
                                                if (it.id == task.id) it.copy(isCompleted = !it.isCompleted) else it
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = "Toggle Complete",
                                        tint = if (task.isCompleted) AccentTeal else TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = task.title,
                                            color = if (task.isCompleted) TextMuted else TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Assignee: ${task.assignee}",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                if (idx < tasks.lastIndex) {
                                    HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }

                // Section 4: Project updates timeline logs
                item {
                    Text(
                        text = "RECENT COLLABORATION ACTIVITY",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = BgCard.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            timelineLogs.forEachIndexed { index, log ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(AccentTeal)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = log.author,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = log.time,
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Text(
                                            text = log.action,
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                                if (index < timelineLogs.lastIndex) {
                                    HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }

                // Section 5: Inline Project Workspace Chat
                item {
                    Text(
                        text = "CO-AUTHOR DISCUSSION BOARD",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = BgCard.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            chatMessages.forEach { msg ->
                                val splitMsg = msg.split(": ")
                                val sender = splitMsg.getOrNull(0) ?: ""
                                val body = splitMsg.getOrNull(1) ?: msg
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = sender,
                                        color = if (sender == "Sumiran") AccentAmber else AccentCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = body,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }
                                HorizontalDivider(color = BorderLight.copy(alpha = 0.2f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            
                            // Send input box inline
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = groupMessageInput,
                                    onValueChange = { groupMessageInput = it },
                                    placeholder = { Text("Message project group...", color = TextMuted) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = BgElevated.copy(alpha = 0.5f),
                                        unfocusedContainerColor = BgElevated.copy(alpha = 0.5f),
                                        focusedBorderColor = AccentTeal,
                                        unfocusedBorderColor = BorderLight
                                    ),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                )
                                IconButton(
                                    onClick = {
                                        if (groupMessageInput.isNotBlank()) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            chatMessages.add("You: $groupMessageInput")
                                            timelineLogs = listOf(
                                                CollabEvent("You", "posted update to project discussion", "just now")
                                            ) + timelineLogs
                                            groupMessageInput = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(AccentTeal)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Animated Video Call Full-screen simulation overlay
        AnimatedVisibility(
            visible = showVideoSync,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Surface(
                color = Color(0xFF0F1A15), // Deep dark green WhatsApp theme background
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            color = Color(0xFF1B3B2B),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Secure", tint = Color(0xFF25D366), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("End-to-End Encrypted Sync", color = Color(0xFF25D366), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = currentProject.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                            color = Color(0xFF25D366),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Call Participant Grid
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            // You Card
                            Surface(
                                modifier = Modifier
                                    .size(130.dp, 160.dp),
                                color = Color(0xFF1F2C34),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.5.dp, Color(0xFF25D366)) // Highlighted border for speaking active participant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(CircleShape)
                                                .background(AccentTeal.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("You", color = AccentTeal, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Vikas Vijigiri", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("Speaking...", color = Color(0xFF25D366), fontSize = 10.sp)
                                    }
                                }
                            }

                            // Sumiran Card
                            Surface(
                                modifier = Modifier
                                    .size(130.dp, 160.dp),
                                color = Color(0xFF1F2C34),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(0.5.dp, Color(0xFF2E3B43))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(CircleShape)
                                                .background(AccentAmber.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("SP", color = AccentAmber, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Sumiran Pujari", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("Muted", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        // Nisheeta Card
                        Surface(
                            modifier = Modifier
                                .size(130.dp, 160.dp),
                            color = Color(0xFF1F2C34),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF2E3B43))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(AccentCyan.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("ND", color = AccentCyan, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Nisheeta Desai", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Connected", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Real voice/video Jitsi room transition
                    Button(
                        onClick = {
                            val roomName = "SkoLabSecure_Group_" + currentProject.id.hashCode().toString()
                            val jitsiUrl = "https://meet.jit.si/$roomName"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(jitsiUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Launch Live Group Meeting",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Call control panel buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        // Mic Button
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E3B43))
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Mute", tint = Color.White)
                        }

                        // Video off button
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E3B43))
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video off", tint = Color.White)
                        }

                        // Screen share
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E3B43))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = "Share Screen", tint = Color.White)
                        }

                        // Red Hang-up button
                        IconButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                showVideoSync = false
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEA0038)) // Intense red hangup
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Hang Up", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }

        // --- Dialogs ---

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = {
                    Text(
                        text = "Create Project Group",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AccentTeal
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = newProjName,
                            onValueChange = { newProjName = it },
                            label = { Text("Project Name", color = TextMuted) },
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
                        OutlinedTextField(
                            value = newProjDesc,
                            onValueChange = { newProjDesc = it },
                            label = { Text("Description", color = TextMuted) },
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
                        OutlinedTextField(
                            value = newProjEq,
                            onValueChange = { newProjEq = it },
                            label = { Text("Recent Equations (LaTeX format)", color = TextMuted) },
                            placeholder = { Text("e.g. \\mathcal{H} = J \\sum ...", color = TextMuted) },
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

                        HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)

                        Text(
                            text = "Invite Members (App Users only)",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = memberEmailInput,
                                onValueChange = { memberEmailInput = it },
                                placeholder = { Text("user@example.com", color = TextMuted) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentTeal,
                                    unfocusedBorderColor = BorderLight,
                                    focusedContainerColor = BgElevated,
                                    unfocusedContainerColor = BgElevated
                                ),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    if (memberEmailInput.isBlank()) return@Button
                                    isSearchingMember = true
                                    db.collection("researchers")
                                        .whereEqualTo("email", memberEmailInput.trim())
                                        .get()
                                        .addOnSuccessListener { querySnapshot ->
                                            isSearchingMember = false
                                            val doc = querySnapshot.documents.firstOrNull()
                                            if (doc != null) {
                                                val researcher = doc.toObject(com.open.skolab.model.SkoLabUser::class.java)
                                                if (researcher != null) {
                                                    if (researcher.uid == currentUserId) {
                                                        Toast.makeText(context, "You are automatically added as the owner.", Toast.LENGTH_SHORT).show()
                                                    } else if (membersList.none { it.uid == researcher.uid }) {
                                                        membersList = membersList + CollabMember(
                                                            uid = researcher.uid,
                                                            name = researcher.name,
                                                            email = researcher.email
                                                        )
                                                        memberEmailInput = ""
                                                    } else {
                                                        Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else {
                                                Toast.makeText(context, "This user is not registered in the app.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .addOnFailureListener {
                                            isSearchingMember = false
                                            Toast.makeText(context, "Error looking up user: ${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isSearchingMember,
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                if (isSearchingMember) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Add", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (membersList.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            membersList.forEach { member ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgElevated, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = member.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(text = member.email, color = TextSecondary, fontSize = 10.sp)
                                    }
                                    IconButton(
                                        onClick = { membersList = membersList.filter { it.uid != member.uid } },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = Color(0xFFEA0038),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newProjName.isBlank()) {
                                Toast.makeText(context, "Project name cannot be empty.", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val newId = db.collection("collabs_groups").document().id
                            val newProject = ProjectCollab(
                                id = newId,
                                name = newProjName.trim(),
                                description = newProjDesc.trim(),
                                ownerUid = currentUserId,
                                ownerName = currentUserName,
                                members = listOf(CollabMember(uid = currentUserId, name = currentUserName, email = currentUserEmail)) + membersList,
                                memberUids = listOf(currentUserId) + membersList.map { it.uid },
                                recentEquations = newProjEq.trim().ifBlank { "\\mathcal{H} = J \\sum \\mathbf{S}_i \\cdot \\mathbf{S}_j" },
                                manuscriptProgress = 0.0f,
                                createdAt = System.currentTimeMillis()
                            )
                            db.collection("collabs_groups").document(newId).set(newProject)
                                .addOnSuccessListener {
                                    Toast.makeText(context, "Project group created!", Toast.LENGTH_SHORT).show()
                                    showCreateDialog = false
                                    newProjName = ""
                                    newProjDesc = ""
                                    newProjEq = ""
                                    membersList = emptyList()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(context, "Failed to create: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentTeal)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (showInviteMemberDialog) {
            AlertDialog(
                onDismissRequest = { showInviteMemberDialog = false },
                title = {
                    Text(
                        text = "Invite to Project",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AccentTeal
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Add collaborators to '${currentProject.name}' by their email. They must be registered in the app.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        OutlinedTextField(
                            value = inviteEmailInput,
                            onValueChange = { inviteEmailInput = it },
                            placeholder = { Text("collaborator@example.com", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (inviteEmailInput.isBlank()) return@Button
                            val emailToSearch = inviteEmailInput.trim()
                            
                            // Check if already a member
                            if (currentProject.members.any { it.email.equals(emailToSearch, ignoreCase = true) }) {
                                Toast.makeText(context, "User is already a member of this project.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isInvitingMember = true
                            db.collection("researchers")
                                .whereEqualTo("email", emailToSearch)
                                .get()
                                .addOnSuccessListener { querySnapshot ->
                                    val doc = querySnapshot.documents.firstOrNull()
                                    if (doc != null) {
                                        val researcher = doc.toObject(com.open.skolab.model.SkoLabUser::class.java)
                                        if (researcher != null) {
                                            val newMember = CollabMember(
                                                uid = researcher.uid,
                                                name = researcher.name,
                                                email = researcher.email
                                            )
                                            val updatedMembers = currentProject.members + newMember
                                            val updatedUids = currentProject.memberUids + researcher.uid

                                            db.collection("collabs_groups").document(currentProject.id)
                                                .update(
                                                    "members", updatedMembers,
                                                    "memberUids", updatedUids
                                                )
                                                .addOnSuccessListener {
                                                    isInvitingMember = false
                                                    Toast.makeText(context, "${researcher.name} added to project!", Toast.LENGTH_SHORT).show()
                                                    showInviteMemberDialog = false
                                                    inviteEmailInput = ""
                                                }
                                                .addOnFailureListener { e ->
                                                    isInvitingMember = false
                                                    Toast.makeText(context, "Failed to update project: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                        } else {
                                            isInvitingMember = false
                                            Toast.makeText(context, "Error parsing user data.", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        isInvitingMember = false
                                        Toast.makeText(context, "This user is not registered in the app.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    isInvitingMember = false
                                    Toast.makeText(context, "Error looking up user: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isInvitingMember && inviteEmailInput.isNotBlank()
                    ) {
                        if (isInvitingMember) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Add Member", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInviteMemberDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
