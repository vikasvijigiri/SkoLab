package com.open.skolab.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import com.google.firebase.firestore.FirebaseFirestore
import com.open.skolab.auth.AuthManager
import com.open.skolab.di.AppDependencies
import com.open.skolab.ui.components.MarkdownText
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            name.lowercase() != "you"
        }
}

data class CollabTask(
    val id: String = "",
    val title: String = "",
    val assignee: String = "",
    @field:JvmField val isCompleted: Boolean = false
)

data class CollabEvent(
    val author: String,
    val action: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperCollabsScreen(
    savedStateHandle: SavedStateHandle? = null,
    startTab: String = "orbit_network", // "orbit_network" or "workspaces"
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    onNavigateToCreateProject: () -> Unit = {},
    onNavigateToInviteMember: (String) -> Unit = {},
    onNavigateToCreateTask: (String) -> Unit = {},
    onNavigateToExternalInvite: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUser = authManager.currentUser
    val currentUserId = currentUser?.uid ?: ""
    val currentUserName = currentUser?.displayName ?: (cachedUser?.name ?: "SkoLab User")
    val currentUserEmail = currentUser?.email ?: (cachedUser?.email ?: "user@university.edu")

    val db = remember { FirebaseFirestore.getInstance() }
    var dbProjects by remember { mutableStateOf<List<ProjectCollab>>(emptyList()) }

    // Tab is locked to startTab — each bottom-nav tab shows only its own mode
    var currentTab by remember { mutableStateOf(startTab) } // "orbit_network" or "workspaces"

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

    val defaultProjects = emptyList<ProjectCollab>()

    val projects = remember(dbProjects) {
        dbProjects.ifEmpty { defaultProjects }
    }

    var selectedProjectIndex by remember { mutableStateOf(0) }
    val currentProject = projects.getOrNull(selectedProjectIndex)
    var showProjectDropdown by remember { mutableStateOf(false) }

    var suggestedCollaborators by remember { mutableStateOf<List<com.open.skolab.network.AuthorSuggestion>>(emptyList()) }
    var similarResearchers by remember { mutableStateOf<List<com.open.skolab.network.AuthorSuggestion>>(emptyList()) }
    var isLoadingSuggestions by remember { mutableStateOf(false) }

    val apiService = com.open.skolab.di.AppDependencies.apiService
    val userFocus = cachedUser?.researchFocus ?: ""

    LaunchedEffect(userFocus) {
        if (userFocus.isNotEmpty() && userFocus != "Researcher" && userFocus != "General Research") {
            isLoadingSuggestions = true
            try {
                val list = apiService.getSimilarAuthors(userFocus, limit = 6)
                if (list.isNotEmpty()) {
                    suggestedCollaborators = list.take(3)
                    similarResearchers = list.drop(3).take(3)
                } else {
                    suggestedCollaborators = emptyList()
                    similarResearchers = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("PaperCollabsScreen", "Failed to fetch similar authors", e)
            } finally {
                isLoadingSuggestions = false
            }
        }
    }

    var membersPresence by remember { mutableStateOf<Map<String, com.open.skolab.model.SkoLabUser>>(emptyMap()) }

    DisposableEffect(currentProject?.id) {
        val proj = currentProject
        if (proj == null || proj.id.isEmpty()) {
            membersPresence = emptyMap()
            onDispose {}
        } else {
            val uids = proj.memberUids.filter { it != currentUserId && !it.startsWith("default_") && it != "you_uid" }
            if (uids.isEmpty()) {
                membersPresence = emptyMap()
                onDispose {}
            } else {
                val listener = db.collection("researchers")
                    .whereIn("uid", uids)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.e("PaperCollabsScreen", "Error listening to members presence", error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            val presenceMap = snapshot.toObjects(com.open.skolab.model.SkoLabUser::class.java)
                                .associateBy { it.uid }
                            membersPresence = presenceMap
                        }
                    }
                onDispose {
                    listener.remove()
                }
            }
        }
    }

    var tasks by remember { mutableStateOf<List<CollabTask>>(emptyList()) }
    var timelineLogs by remember { mutableStateOf<List<CollabEvent>>(emptyList()) }
    val chatMessages = remember { mutableStateListOf<String>() }

    DisposableEffect(currentProject?.id) {
        var tasksListener: com.google.firebase.firestore.ListenerRegistration? = null
        var activityListener: com.google.firebase.firestore.ListenerRegistration? = null
        var messagesListener: com.google.firebase.firestore.ListenerRegistration? = null

        val proj = currentProject
        if (proj == null || proj.id.isEmpty() || proj.id.startsWith("default_")) {
            tasks = emptyList()
            timelineLogs = emptyList()
            chatMessages.clear()
            onDispose {}
        } else {
            tasks = emptyList()
            timelineLogs = emptyList()
            chatMessages.clear()

            tasksListener = db.collection("collabs_groups").document(proj.id)
                .collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        tasks = snapshot.toObjects(CollabTask::class.java)
                    }
                }
            
            activityListener = db.collection("collabs_groups").document(proj.id)
                .collection("activity")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        timelineLogs = snapshot.documents.map { doc ->
                            CollabEvent(
                                author = doc.getString("author") ?: "",
                                action = doc.getString("action") ?: "",
                                time = doc.getString("time") ?: "just now"
                            )
                        }
                    }
                }

            messagesListener = db.collection("collabs_groups").document(proj.id)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        chatMessages.clear()
                        snapshot.documents.forEach { doc ->
                            val sender = doc.getString("senderName") ?: ""
                            val text = doc.getString("text") ?: ""
                            chatMessages.add("$sender: $text")
                        }
                    }
                }
        }

        onDispose {
            tasksListener?.remove()
            activityListener?.remove()
            messagesListener?.remove()
        }
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
            
            // Header Screen Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = if (startTab == "workspaces") "COLLABS" else "ORBIT",
                            color = PRIMARY,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (startTab == "workspaces") "Active Workspaces" else "Relationship Intelligence",
                            color = TEXT_PRIMARY,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            if (currentTab == "orbit_network") {
                // ORBIT NETWORK TAB: Breathtaking Relationship intelligence dashboard!
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // HERO Section
                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "YOUR RESEARCH NETWORK",
                                    color = TEXT_MUTED,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    OrbitMetricCell(count = "18", label = "Collaborators", tint = PRIMARY)
                                    OrbitMetricCell(count = "8", label = "Institutions", tint = Color(0xFF00A884))
                                    OrbitMetricCell(count = "3", label = "Active Grants", tint = Color(0xFFE28743))
                                    OrbitMetricCell(count = "5", label = "Communities", tint = Color(0xFF8B5CF6))
                                }
                            }
                        }
                    }

                    // NETWORK GRAPH Section
                    item {
                        Text(
                            text = "Interactive Relationship Orbit Map",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Concentric orbit rings represent connection similarity and co-citation overlap strength.",
                            color = TEXT_SECONDARY,
                            fontSize = 12.sp
                        )
                    }

                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                RelationshipOrbitCanvas()
                            }
                        }
                    }

                    // Potential Collaborators Section
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Potential Collaborators Entering Your Orbit",
                                color = TEXT_PRIMARY,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                            }
                        } else if (suggestedCollaborators.isEmpty()) {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (userFocus.isBlank()) "Please set your research focus in your profile to discover dynamic collaborators!" else "No dynamic collaborators found for '$userFocus'. Try updating your research focus in your profile to discover more researchers!",
                                    color = TEXT_SECONDARY,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                suggestedCollaborators.forEachIndexed { index, sugg ->
                                    OrbitCollaboratorRecommendationCard(
                                        name = sugg.display_name,
                                        institution = sugg.institution,
                                        match = 96 - index * 4,
                                        reason = "Substantial overlap in citation networks, publications, and methodologies in '$userFocus'.",
                                        tags = listOf(userFocus, "Highly Aligned")
                                    )
                                }
                            }
                        }
                    }

                    // Researchers Similar To You
                    item {
                        Text(
                            text = "Researchers Similar To You",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                            }
                        } else if (similarResearchers.isEmpty()) {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (userFocus.isBlank()) "Please set your research focus in your profile to view similar researchers." else "No similar researchers found in '$userFocus'.",
                                    color = TEXT_SECONDARY,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                similarResearchers.forEach { sugg ->
                                    OrbitSimilarResearcherCard(
                                        name = sugg.display_name,
                                        institution = sugg.institution,
                                        overlapReason = "High topic correlation and co-citation index similarity in the field of '$userFocus'.",
                                        papersCount = 6
                                    )
                                }
                            }
                        }
                    }

                    // Rising Researchers
                    item {
                        Text(
                            text = "Rising Researchers in Your Domain",
                            color = TEXT_PRIMARY,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PRIMARY, strokeWidth = 2.dp)
                            }
                        } else if (similarResearchers.isEmpty()) {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (userFocus.isBlank()) "Please set your research focus in your profile to view rising researchers." else "No rising researchers discovered in '$userFocus'.",
                                    color = TEXT_SECONDARY,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                similarResearchers.take(2).forEach { sugg ->
                                    OrbitRisingResearcherCard(
                                        name = sugg.display_name,
                                        momentum = "+35% Growth",
                                        reason = "High publication velocity, citation count surges, and active preprint drafts in '$userFocus'."
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // WORKSPACES TAB: The original Firestore collaboration screen!
                Surface(
                    color = SURFACE_SUBTLE,
                    border = BorderStroke(0.5.dp, BORDER),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = currentProject != null) {
                                    showProjectDropdown = !showProjectDropdown
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Workspaces,
                                contentDescription = null,
                                tint = PRIMARY,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentProject?.name ?: "No Active Workspace",
                                color = TEXT_PRIMARY,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                            if (currentProject != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showProjectDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = PRIMARY,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { onNavigateToCreateProject() },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SURFACE)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    val proj = currentProject
                                    if (proj == null) {
                                        Toast.makeText(context, "Please create a project first.", Toast.LENGTH_SHORT).show()
                                    } else if (proj.id.startsWith("default_")) {
                                        Toast.makeText(context, "Call sync requires registered SkoLab co-authors.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showVideoSync = true
                                        callConnectedTime = 0
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (currentProject != null) PRIMARY else BORDER)
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null, tint = if (currentProject != null) TEXT_ON_PRIMARY else TEXT_MUTED, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Project selection dropdown
                    if (currentProject != null) {
                        DropdownMenu(
                            expanded = showProjectDropdown,
                            onDismissRequest = { showProjectDropdown = false },
                            modifier = Modifier
                                .background(SURFACE)
                                .border(BorderStroke(0.5.dp, BORDER), RoundedCornerShape(12.dp))
                        ) {
                            projects.forEachIndexed { index, proj ->
                                DropdownMenuItem(
                                    text = {
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(text = proj.name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(text = proj.description, color = TEXT_SECONDARY, fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedProjectIndex = index
                                        showProjectDropdown = false
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
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val proj = currentProject
                    if (proj == null) {
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Workspaces,
                                        contentDescription = null,
                                        tint = PRIMARY.copy(alpha = 0.5f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "No Active Workspace",
                                        color = TEXT_PRIMARY,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "Create shared paper drafts, interactive roadmaps, equations blackboard, and dynamic group discussions with your co-authors.",
                                        color = TEXT_SECONDARY,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 19.sp
                                    )
                                    Button(
                                        onClick = { onNavigateToCreateProject() },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Create Workspace Project", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // Collaborators profiles horizontal ring
                        item {
                            Text(
                                text = "ACTIVE COLLABORATORS",
                                color = TEXT_MUTED,
                                fontSize = 10.sp,
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
                                // You Avatar
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val myInitials = remember(currentUserName) {
                                        currentUserName.split(" ")
                                            .filter { it.isNotEmpty() }
                                            .take(2)
                                            .map { it.first() }
                                            .joinToString("")
                                            .uppercase()
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(PRIMARY.copy(alpha = 0.1f))
                                            .border(1.dp, PRIMARY, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(myInitials.ifEmpty { "ME" }, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("You", color = TEXT_PRIMARY, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                // Co-authors
                                val otherMembers = proj.members.filter {
                                    it.uid != currentUserId && it.name.lowercase() != "you" && !it.name.equals(currentUserName, ignoreCase = true)
                                }

                                otherMembers.forEach { member ->
                                    val initials = member.name.split(" ").map { it.take(1) }.joinToString("").uppercase()
                                    val presence = membersPresence[member.uid]
                                    val isOnline = presence?.isOnline == true

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { onNavigateToChat(member.name, member.uid) }
                                    ) {
                                        Box(contentAlignment = Alignment.BottomEnd) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(SURFACE_SUBTLE)
                                                    .border(1.dp, BORDER, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(initials.ifEmpty { "U" }, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isOnline) Color(0xFF00A884) else Color.Gray)
                                                    .border(1.dp, SURFACE, CircleShape)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(member.name.split(" ").firstOrNull() ?: member.name, color = TEXT_SECONDARY, fontSize = 10.sp)
                                    }
                                }

                                // Add collaborator button
                                IconButton(
                                    onClick = { onNavigateToInviteMember(proj.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(SURFACE)
                                        .border(1.dp, BORDER, CircleShape)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Blackboard math equation
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToWorkspace(proj.name) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.EditNote, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Blackboard Latex Draft", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0C1424))
                                            .padding(12.dp)
                                    ) {
                                        MarkdownText(
                                            markdown = "$$" + proj.recentEquations + "$$",
                                            color = Color(0xFFEEF3FE),
                                            fontSize = 13.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Manuscript Draft Progress", color = TEXT_SECONDARY, fontSize = 12.sp)
                                        Text("${(proj.manuscriptProgress * 100).toInt()}%", color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { proj.manuscriptProgress },
                                        color = PRIMARY,
                                        trackColor = SURFACE_SUBTLE,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                    )
                                }
                            }
                        }

                        // Roadmap Tasks Checklist
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("ROADMAP & TASKS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Icon(
                                            imageVector = Icons.Default.AddCircleOutline,
                                            contentDescription = null,
                                            tint = PRIMARY,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable { onNavigateToCreateTask(proj.id) }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    tasks.forEachIndexed { idx, task ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (proj.id.startsWith("default_")) {
                                                        tasks = tasks.map { if (it.id == task.id) it.copy(isCompleted = !it.isCompleted) else it }
                                                    } else {
                                                        db.collection("collabs_groups").document(proj.id)
                                                            .collection("tasks").document(task.id)
                                                            .update("isCompleted", !task.isCompleted)
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (task.isCompleted) Color(0xFF00A884) else TEXT_MUTED,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = task.title,
                                                    color = if (task.isCompleted) TEXT_MUTED else TEXT_PRIMARY,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(text = "Assignee: ${task.assignee}", color = TEXT_SECONDARY, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Activity logs
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("COLLABORATION ACTIVITY LOGS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    timelineLogs.forEach { log ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 4.dp)
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(PRIMARY)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("${log.author} - ${log.time}", color = TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                Text(log.action, color = TEXT_PRIMARY, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // In-Workspace Group Discussion Chat board
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("DISCUSSION BOARD", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    chatMessages.forEach { msg ->
                                        val splitMsg = msg.split(": ")
                                        val sender = splitMsg.getOrNull(0) ?: ""
                                        val body = splitMsg.getOrNull(1) ?: msg
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(sender, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text(body, color = TEXT_PRIMARY, fontSize = 12.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = groupMessageInput,
                                            onValueChange = { groupMessageInput = it },
                                            placeholder = { Text("Post to blackboard chat...", fontSize = 12.sp) },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = PRIMARY,
                                                unfocusedBorderColor = BORDER
                                            )
                                        )
                                        IconButton(
                                            onClick = {
                                                if (groupMessageInput.isNotBlank()) {
                                                    val text = groupMessageInput.trim()
                                                    groupMessageInput = ""
                                                    if (proj.id.startsWith("default_")) {
                                                        chatMessages.add("You: $text")
                                                    } else {
                                                        val msgId = db.collection("collabs_groups").document(proj.id).collection("messages").document().id
                                                        db.collection("collabs_groups").document(proj.id).collection("messages").document(msgId).set(
                                                            hashMapOf("id" to msgId, "senderName" to currentUserName, "text" to text, "timestamp" to System.currentTimeMillis())
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(PRIMARY)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TEXT_ON_PRIMARY, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Jitsi Sync room simulated overlay
        AnimatedVisibility(
            visible = showVideoSync,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Surface(
                color = Color(0xFF0F1A24),
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentProject?.name ?: "",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                            color = Color(0xFF00A884),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(
                                modifier = Modifier.size(120.dp, 150.dp),
                                color = Color(0xFF1F2C3C),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.5.dp, PRIMARY)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("You", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Speaking...", color = PRIMARY, fontSize = 10.sp)
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.size(120.dp, 150.dp),
                                color = Color(0xFF1F2C3C),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.5.dp, BORDER)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Sumiran P.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val roomName = "SkoLabSecure_" + (currentProject?.id?.hashCode()?.toString() ?: "Default")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.jit.si/$roomName"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(PRIMARY)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("Launch Live Call Sync", color = TEXT_ON_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { showVideoSync = false },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEA0038))
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrbitMetricCell(count: String, label: String, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = count,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = tint
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = TEXT_SECONDARY,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RelationshipOrbitCanvas() {
    val infiniteTransition = rememberInfiniteTransition()
    
    // Slow organic orbit rotation angles
    val rotationAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val rotationAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // Soft node pulse scale
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)

        // Concentric Orbit Track 1 (Inner)
        drawCircle(
            color = BORDER.copy(alpha = 0.5f),
            radius = 65.dp.toPx(),
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        )

        // Concentric Orbit Track 2 (Outer)
        drawCircle(
            color = BORDER.copy(alpha = 0.3f),
            radius = 110.dp.toPx(),
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f)
            )
        )

        // Inner Orbit Connections & Nodes
        val rad1 = 65.dp.toPx()
        val numInnerNodes = 3
        val innerNodeLabels = listOf("Sumiran P.", "Nisheeta D.", "Stellar AI")
        val innerColors = listOf(PRIMARY, Color(0xFF00A884), PRIMARY)

        for (i in 0 until numInnerNodes) {
            val angleRad = Math.toRadians((rotationAngle1 + (i * 360f / numInnerNodes)).toDouble())
            val nodeCenter = Offset(
                (center.x + rad1 * Math.cos(angleRad)).toFloat(),
                (center.y + rad1 * Math.sin(angleRad)).toFloat()
            )

            // Connection Line to Center
            drawLine(
                color = BORDER.copy(alpha = 0.6f),
                start = center,
                end = nodeCenter,
                strokeWidth = 1.dp.toPx()
            )

            // Outer ring soft halo on the node
            drawCircle(
                color = innerColors[i].copy(alpha = 0.1f * pulseScale),
                radius = 18.dp.toPx(),
                center = nodeCenter
            )

            // Node Circle
            drawCircle(
                color = innerColors[i],
                radius = 7.dp.toPx(),
                center = nodeCenter
            )
        }

        // Outer Orbit Connections & Nodes
        val rad2 = 110.dp.toPx()
        val numOuterNodes = 4
        val outerColors = listOf(Color(0xFFE28743), Color(0xFF8B5CF6), Color(0xFF00D4FF), Color(0xFF2D6BE4))

        for (i in 0 until numOuterNodes) {
            val angleRad = Math.toRadians((rotationAngle2 + (i * 360f / numOuterNodes)).toDouble())
            val nodeCenter = Offset(
                (center.x + rad2 * Math.cos(angleRad)).toFloat(),
                (center.y + rad2 * Math.sin(angleRad)).toFloat()
            )

            // Connection Line to nearby nodes
            drawLine(
                color = BORDER.copy(alpha = 0.3f),
                start = center,
                end = nodeCenter,
                strokeWidth = 0.5.dp.toPx()
            )

            // Outer node glow
            drawCircle(
                color = outerColors[i].copy(alpha = 0.08f),
                radius = 24.dp.toPx(),
                center = nodeCenter
            )

            drawCircle(
                color = outerColors[i],
                radius = 5.dp.toPx(),
                center = nodeCenter
            )
        }

        // Center Node: Active User (You)
        drawCircle(
            color = PRIMARY.copy(alpha = 0.15f * pulseScale),
            radius = 32.dp.toPx(),
            center = center
        )

        drawCircle(
            color = PRIMARY,
            radius = 10.dp.toPx(),
            center = center
        )
    }
}

@Composable
fun OrbitCollaboratorRecommendationCard(
    name: String,
    institution: String,
    match: Int,
    reason: String,
    tags: List<String>
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PRIMARY.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2),
                            color = PRIMARY,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text = institution, color = TEXT_SECONDARY, fontSize = 11.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MATCH_SCORE_BG)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$match% Match",
                        color = MATCH_SCORE_TEXT,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = reason, color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SURFACE_SUBTLE)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = tag, color = TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {},
                    border = BorderStroke(1.dp, BORDER),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PRIMARY),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Orbit Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Request Collab", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun OrbitSimilarResearcherCard(
    name: String,
    institution: String,
    overlapReason: String,
    papersCount: Int
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00A884).copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2),
                    color = Color(0xFF00A884),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(text = institution, color = TEXT_SECONDARY, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = overlapReason, color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = "$papersCount", color = PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(text = "Papers", color = TEXT_MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OrbitRisingResearcherCard(
    name: String,
    momentum: String,
    reason: String
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE28743).copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = momentum,
                        color = Color(0xFFE28743),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = reason, color = TEXT_SECONDARY, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

