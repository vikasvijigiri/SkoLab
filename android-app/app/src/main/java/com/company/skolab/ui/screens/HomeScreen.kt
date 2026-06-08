package com.company.skolab.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.SparkViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.firebase.firestore.FirebaseFirestore
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.OrbitMetrics
import com.company.skolab.ui.components.MarkdownText


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPaperClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToDailyDiscovery: () -> Unit,
    onNavigateToCollabs: () -> Unit,
    onNavigateToCreateProject: () -> Unit,
    onNavigateToSparkSession: (String) -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    onNavigateToInviteMember: (String) -> Unit,
    onNavigateToCreateTask: (String) -> Unit,
    onNavigateToExternalInvite: (String) -> Unit,
    sparkViewModel: SparkViewModel = viewModel()
) {
    val context = LocalContext.current
    val authManager = com.company.skolab.di.AppDependencies.authManager
    val userPrefs = remember { com.company.skolab.data.UserPreferences(context) }
    
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val userName = cachedUser?.name?.split(" ")?.firstOrNull() ?: "Researcher"
    val userFocus = cachedUser?.researchFocus ?: ""
    val currentUserId = cachedUser?.uid ?: ""
    val currentUserName = cachedUser?.name ?: "SkoLab User"
    val currentUserEmail = cachedUser?.email ?: "user@university.edu"

    val sparkUiState by sparkViewModel.uiState.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf("spark") } // "spark", "workspaces", "orbit"

    // Firestore & Workspaces state
    val db = remember { FirebaseFirestore.getInstance() }
    var dbProjects by remember { mutableStateOf<List<ProjectCollab>>(emptyList()) }

    DisposableEffect(currentUserId) {
        if (currentUserId.isEmpty()) {
            onDispose {}
        } else {
            val listener = db.collection("collabs_groups")
                .whereArrayContains("memberUids", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        android.util.Log.e("HomeScreen", "Error listening to collab groups", error)
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

    val projects = dbProjects
    var selectedProjectIndex by remember { mutableStateOf(0) }
    val currentProject = projects.getOrNull(selectedProjectIndex)
    var showProjectDropdown by remember { mutableStateOf(false) }

    var suggestedCollaborators by remember { mutableStateOf<List<com.company.skolab.network.AuthorSuggestion>>(emptyList()) }
    var similarResearchers by remember { mutableStateOf<List<com.company.skolab.network.AuthorSuggestion>>(emptyList()) }
    var isLoadingSuggestions by remember { mutableStateOf(false) }

    // Real orbit metrics from OpenAlex
    var orbitMetrics by remember { mutableStateOf<OrbitMetrics?>(null) }
    var isLoadingOrbitMetrics by remember { mutableStateOf(false) }
    var userOpenAlexId by remember { mutableStateOf("") }

    val apiService = com.company.skolab.di.AppDependencies.apiService

    // Fetch real orbit metrics
    LaunchedEffect(currentUserName, userFocus) {
        if (currentUserName.isNotBlank() && currentUserName != "SkoLab User") {
            isLoadingOrbitMetrics = true
            try {
                val profile = apiService.searchAuthor(currentUserName, focus = userFocus.ifBlank { null })
                val authorId = profile?.id ?: ""
                userOpenAlexId = authorId
                if (authorId.isNotBlank()) {
                    orbitMetrics = apiService.getOrbitMetrics(authorId)
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to fetch orbit metrics", e)
            } finally {
                isLoadingOrbitMetrics = false
            }
        }
    }

    var userMemoryProfile by remember { mutableStateOf<com.company.skolab.network.UserMemoryProfileResponse?>(null) }
    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            try {
                val profile = apiService.getUserMemory(currentUserId)
                if (profile != null) {
                    userMemoryProfile = profile
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to fetch user memory profile", e)
            }
        }
    }

    LaunchedEffect(userFocus) {
        if (userFocus.isNotEmpty() && userFocus != "Researcher" && userFocus != "General Research") {
            isLoadingSuggestions = true
            try {
                val list = apiService.getSimilarAuthors(userFocus, limit = 8)
                if (list.isNotEmpty()) {
                    suggestedCollaborators = list.take(4)
                    similarResearchers = list.drop(4).take(4)
                } else {
                    suggestedCollaborators = emptyList()
                    similarResearchers = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to fetch similar authors", e)
            } finally {
                isLoadingSuggestions = false
            }
        }
    }

    var membersPresence by remember { mutableStateOf<Map<String, com.company.skolab.model.SkoLabUser>>(emptyMap()) }

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
                            android.util.Log.e("HomeScreen", "Error listening to members presence", error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            val presenceMap = snapshot.toObjects(com.company.skolab.model.SkoLabUser::class.java)
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

    // Auto-navigation effect when Spark Session is ACTIVE
    LaunchedEffect(sparkUiState.activeSession) {
        val active = sparkUiState.activeSession
        if (active != null && active.status == "ACTIVE") {
            onNavigateToSparkSession(active.id)
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = { 
            Column(modifier = Modifier.background(BgPrimary)) {
                HomeScreenTopBar(
                    userName = userName,
                    onProfileClick = { /* Profile page action */ },
                    isOnline = sparkUiState.isOnline,
                    onOnlineToggle = { online ->
                        sparkViewModel.toggleOnline(currentUserId, userName, userFocus, online)
                    }
                )
                // Tab Selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabOptions = listOf(
                        "spark" to "Spark Live ⚡",
                        "workspaces" to "Workspaces 📂",
                        "orbit" to "Orbit Intel 🌐"
                    )
                    tabOptions.forEach { (route, label) ->
                        val isSelected = activeTab == route
                        val backgroundGlow = if (isSelected) {
                            Brush.linearGradient(listOf(PRIMARY, AccentTeal))
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(backgroundGlow)
                                .clickable { activeTab = route }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TEXT_SECONDARY,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when (activeTab) {
                "spark" -> {
                    // Spark Console occupies the full available height and width as the sole entity
                    SparkConsole(
                        uiState = sparkUiState,
                        userFocus = userFocus,
                        userMemoryProfile = userMemoryProfile,
                        projects = projects,
                        tasks = tasks,
                        onHailClick = { topic, bounty, tags ->
                            sparkViewModel.hailHelper(currentUserId, userName, topic, bounty, tags)
                        },
                        onCancelBroadcast = {
                            sparkViewModel.cancelSession()
                        }
                    )
                }
                
                "workspaces" -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            color = SURFACE_SUBTLE,
                            border = BorderStroke(0.5.dp, BORDER),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = currentProject?.name ?: "No Active Workspace",
                                        color = TEXT_PRIMARY,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 180.dp)
                                    )
                                    if (currentProject != null) {
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            imageVector = if (showProjectDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = PRIMARY,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(
                                        onClick = { onNavigateToCreateProject() },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SURFACE)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(14.dp))
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
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (currentProject != null) PRIMARY else BORDER)
                                    ) {
                                        Icon(Icons.Default.Videocam, contentDescription = null, tint = if (currentProject != null) TEXT_ON_PRIMARY else TEXT_MUTED, modifier = Modifier.size(14.dp))
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
                                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                    Text(text = proj.name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    Text(text = proj.description, color = TEXT_SECONDARY, fontSize = 10.sp)
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
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val proj = currentProject
                            if (proj == null) {
                                item {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Workspaces,
                                                contentDescription = null,
                                                tint = PRIMARY.copy(alpha = 0.5f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Text(
                                                text = "No Active Workspace",
                                                color = TEXT_PRIMARY,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = "Create shared paper drafts, interactive roadmaps, equations blackboard, and dynamic group discussions with your co-authors.",
                                                color = TEXT_SECONDARY,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 17.sp
                                            )
                                            Button(
                                                onClick = { onNavigateToCreateProject() },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Create Workspace Project", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
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
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(PRIMARY.copy(alpha = 0.1f))
                                                    .border(1.dp, PRIMARY, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(myInitials.ifEmpty { "ME" }, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("You", color = TEXT_PRIMARY, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                                                            .size(40.dp)
                                                            .clip(CircleShape)
                                                            .background(SURFACE_SUBTLE)
                                                            .border(1.dp, BORDER, CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(initials.ifEmpty { "U" }, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(if (isOnline) WhatsAppTealGreen else Color.Gray)
                                                            .border(1.dp, SURFACE, CircleShape)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(member.name.split(" ").firstOrNull() ?: member.name, color = TEXT_SECONDARY, fontSize = 9.sp)
                                            }
                                        }

                                        // Add collaborator button
                                        IconButton(
                                            onClick = { onNavigateToInviteMember(proj.id) },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(SURFACE)
                                                .border(1.dp, BORDER, CircleShape)
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                // Blackboard math equation
                                item {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateToWorkspace(proj.name) }
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.EditNote, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Blackboard Latex Draft", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(PremiumDarkSpace)
                                                    .padding(10.dp)
                                            ) {
                                                MarkdownText(
                                                    markdown = "$$" + proj.recentEquations + "$$",
                                                    color = PremiumLightText,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Manuscript Draft Progress", color = TEXT_SECONDARY, fontSize = 11.sp)
                                                Text("${(proj.manuscriptProgress * 100).toInt()}%", color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { proj.manuscriptProgress },
                                                color = PRIMARY,
                                                trackColor = SURFACE_SUBTLE,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                    }
                                }

                                // Roadmap Tasks Checklist
                                item {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("ROADMAP & TASKS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Icon(
                                                    imageVector = Icons.Default.AddCircleOutline,
                                                    contentDescription = null,
                                                    tint = PRIMARY,
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clickable { onNavigateToCreateTask(proj.id) }
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            tasks.forEach { task ->
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
                                                        .padding(vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                        contentDescription = null,
                                                        tint = if (task.isCompleted) WhatsAppTealGreen else TEXT_MUTED,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            text = task.title,
                                                            color = if (task.isCompleted) TEXT_MUTED else TEXT_PRIMARY,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(text = "Assignee: ${task.assignee}", color = TEXT_SECONDARY, fontSize = 9.sp)
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
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text("DISCUSSION BOARD", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            chatMessages.forEach { msg ->
                                                val splitMsg = msg.split(": ")
                                                val sender = splitMsg.getOrNull(0) ?: ""
                                                val body = splitMsg.getOrNull(1) ?: msg
                                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                    Text(sender, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                    Text(body, color = TEXT_PRIMARY, fontSize = 11.sp)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = groupMessageInput,
                                                    onValueChange = { groupMessageInput = it },
                                                    placeholder = { Text("Post to chat...", fontSize = 11.sp) },
                                                    shape = RoundedCornerShape(8.dp),
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
                                                        .size(36.dp)
                                                        .clip(CircleShape)
                                                        .background(PRIMARY)
                                                ) {
                                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TEXT_ON_PRIMARY, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                // Activity logs
                                item {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text("COLLABORATION ACTIVITY LOGS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            timelineLogs.forEach { log ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .padding(top = 4.dp)
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(PRIMARY)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text("${log.author} - ${log.time}", color = TEXT_SECONDARY, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        Text(log.action, color = TEXT_PRIMARY, fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                "orbit" -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // HERO METRICS CARD - Real data from OpenAlex
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.linearGradient(listOf(PRIMARY, AccentTeal)))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "YOUR RESEARCH ORBIT",
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        )
                                        if (isLoadingOrbitMetrics) {
                                            CircularProgressIndicator(
                                                color = Color.White.copy(alpha = 0.7f),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        val metrics = orbitMetrics
                                        OrbitMetricCell(
                                            count = if (metrics != null) "${metrics.collaborator_count}" else "—",
                                            label = "Co-Authors",
                                            tint = Color.White
                                        )
                                        OrbitMetricCell(
                                            count = if (metrics != null) "${metrics.institution_count}" else "—",
                                            label = "Institutions",
                                            tint = Color.White
                                        )
                                        OrbitMetricCell(
                                            count = if (metrics != null) "${metrics.works_count}" else "—",
                                            label = "Publications",
                                            tint = Color.White
                                        )
                                        OrbitMetricCell(
                                            count = if (metrics != null) "h${metrics.h_index}" else "—",
                                            label = "h-Index",
                                            tint = StarGold
                                        )
                                    }
                                }
                            }
                        }

                        // ORBIT CANVAS - Real co-author node names
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val realInnerNodes = orbitMetrics?.top_coauthors?.take(3)?.map {
                                        val parts = it.split(" ")
                                        if (parts.size >= 2) "${parts[0].take(1)}. ${parts.last()}" else it.take(10)
                                    } ?: listOf("Loading...", "", "")
                                    val realOuterNodes = orbitMetrics?.top_coauthors?.drop(3)?.take(4)?.map {
                                        val parts = it.split(" ")
                                        if (parts.size >= 2) "${parts[0].take(1)}. ${parts.last()}" else it.take(10)
                                    } ?: listOf("", "", "", "")
                                    RelationshipOrbitCanvas(
                                        innerNodeLabels = realInnerNodes,
                                        outerNodeLabels = realOuterNodes,
                                        centerLabel = currentUserName.split(" ").firstOrNull() ?: "You"
                                    )
                                }
                            }
                        }

                        // POTENTIAL COLLABORATORS
                        item {
                            Text(
                                text = "Potential Collaborators Matching Profile",
                                color = TEXT_PRIMARY,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isLoadingSuggestions) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = PRIMARY)
                                }
                            }
                        } else if (suggestedCollaborators.isEmpty()) {
                            item {
                                Surface(
                                    color = SURFACE,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, BORDER),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Update your research focus in your profile to discover matching collaborators.",
                                        color = TEXT_SECONDARY,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(16.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(suggestedCollaborators) { sugg ->
                                val matchScore = 85
                                val reason = "Specialises in ${sugg.field_of_study ?: userFocus}, matching your focus."
                                OrbitCollaboratorRecommendationCard(
                                    name = sugg.display_name,
                                    institution = sugg.institution,
                                    match = matchScore,
                                    hIndex = sugg.h_index,
                                    reason = reason,
                                    tags = listOf(sugg.field_of_study ?: userFocus, "Strong Match"),
                                    onConnect = {
                                        val cleanName = sugg.display_name.lowercase().replace(" ", ".")
                                        val subject = "Collaboration Inquiry — $userFocus"
                                        val body = "Dear ${sugg.display_name.split(" ").firstOrNull() ?: "Professor"},\n\nI came across your work in ${sugg.field_of_study ?: userFocus} and believe there may be a great opportunity for collaboration.\n\nBest regards,\n$currentUserName"
                                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("mailto:")
                                            putExtra(Intent.EXTRA_EMAIL, arrayOf("$cleanName@university.edu"))
                                            putExtra(Intent.EXTRA_SUBJECT, subject)
                                            putExtra(Intent.EXTRA_TEXT, body)
                                        }
                                        try { context.startActivity(Intent.createChooser(emailIntent, "Send Collaboration Request")) }
                                        catch (e: Exception) { Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show() }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Error banner — floats over the console at the bottom edge
            androidx.compose.animation.AnimatedVisibility(
                visible = sparkUiState.error != null,
                enter = androidx.compose.animation.slideInVertically { it } +
                        androidx.compose.animation.fadeIn(),
                exit  = androidx.compose.animation.slideOutVertically { it } +
                        androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = AccentRose.copy(alpha = 0.92f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sparkUiState.error ?: "",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(
                            onClick = { sparkViewModel.clearError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss",
                                tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    // Video Call Simulation overlay
    AnimatedVisibility(
        visible = showVideoSync,
        enter = fadeIn() + expandIn(),
        exit = fadeOut() + shrinkOut()
    ) {
        Surface(
            color = PremiumChatRoomBg,
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
                        color = WhatsAppTealGreen,
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
                            color = PremiumChatCardBg,
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
                            color = PremiumChatCardBg,
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
                    Button(
                        onClick = {
                            val roomName = "SkoLabSecure_" + (currentProject?.id?.hashCode()?.toString() ?: "Default")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.jit.si/$roomName"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("Launch Live Call Sync", color = TEXT_ON_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = { showVideoSync = false },
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(CallEndRed)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }


    // Incoming Spark Call Ring Overlay
    if (sparkUiState.isOnline && sparkUiState.incomingRequests.isNotEmpty() && sparkUiState.activeSession == null) {
        val incoming = sparkUiState.incomingRequests.first()
        IncomingSparkRingOverlay(
            session = incoming,
            onAccept = {
                sparkViewModel.acceptSpark(incoming.id, currentUserId, userName)
            },
            onDecline = {
                // Decline action
            }
        )
    }
}

// ── HEADER TOP BAR ──
@Composable
fun HomeScreenTopBar(
    userName: String,
    onProfileClick: () -> Unit,
    isOnline: Boolean,
    onOnlineToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgPrimary)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Welcome back",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted
            )
            Text(
                text = "Hello, $userName",
                style = Typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontFamily = SpaceGroteskFontFamily
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .clickable { onOnlineToggle(!isOnline) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isOnline) AccentEmerald else TextMuted, CircleShape)
                )
                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    color = if (isOnline) AccentEmerald else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    .clickable { onProfileClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── MAXIMIZED SPARK CONSOLE DASHBOARD CENTERPIECE ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkConsole(
    uiState: com.company.skolab.viewmodel.SparkUiState,
    userFocus: String,
    userMemoryProfile: com.company.skolab.network.UserMemoryProfileResponse?,
    projects: List<ProjectCollab>,
    tasks: List<CollabTask>,
    onHailClick: (String, String, List<String>) -> Unit,
    onCancelBroadcast: () -> Unit
) {
    var showHailDialog by remember { mutableStateOf(false) }

    // Animating transition loop
    val infiniteTransition = rememberInfiniteTransition(label = "radarTransition")

    // Concentric pulsing radar rings
    val ring1Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (uiState.isBroadcasting) 1600 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1Progress"
    )
    val ring2Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (uiState.isBroadcasting) 1600 else 3000, easing = LinearEasing),
            initialStartOffset = StartOffset(if (uiState.isBroadcasting) 800 else 1500),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2Progress"
    )

    // Pulse glows for launcher button
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (uiState.isBroadcasting) 700 else 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Dynamic, non-synchronous floating expert avatar drifts
    val floatX1 by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX1"
    )
    val floatY1 by infiniteTransition.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY1"
    )

    val floatX2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX2"
    )
    val floatY2 by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(4800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY2"
    )

    val floatX3 by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX3"
    )
    val floatY3 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY3"
    )

    val floatX4 by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = -16f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX4"
    )
    val floatY4 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(4600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY4"
    )

    val floatX5 by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX5"
    )
    val floatY5 by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(4400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY5"
    )

    // Animate convergence ratio when searching (avatars slide closer)
    val convergenceRatio by animateFloatAsState(
        targetValue = if (uiState.isBroadcasting) 0.35f else 1.0f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "convergence"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF130D0A)) // Warm coffee-shop dark espresso
            .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(28.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Radar Scan container box (Pulsing rings + Floating Avatars + Bolt button) ──
        Box(
            modifier = Modifier
                .weight(1.4f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            // Concentric radar scan rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = this.center
                val maxRadius = size.minDimension * 0.45f
                
                // Ring 1
                drawCircle(
                    color = PRIMARY.copy(alpha = (1f - ring1Progress) * 0.16f),
                    radius = maxRadius * ring1Progress,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
                // Ring 2
                drawCircle(
                    color = PRIMARY.copy(alpha = (1f - ring2Progress) * 0.16f),
                    radius = maxRadius * ring2Progress,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }

            // Floating Matchable Avatars (positioned relative to container center)
            // Dr. Alice (Top-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-80.dp * convergenceRatio) + floatX1.dp,
                        y = (-85.dp * convergenceRatio) + floatY1.dp
                    )
            ) {
                ExpertAvatar(initials = "AJ", name = "Dr. Alice", isOnline = true)
            }

            // Prof. Lin (Top-Right)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (85.dp * convergenceRatio) + floatX2.dp,
                        y = (-60.dp * convergenceRatio) + floatY2.dp
                    )
            ) {
                ExpertAvatar(initials = "RL", name = "Prof. Lin", isOnline = true)
            }

            // Sarah K. (Bottom-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-80.dp * convergenceRatio) + floatX3.dp,
                        y = (65.dp * convergenceRatio) + floatY3.dp
                    )
            ) {
                ExpertAvatar(initials = "SK", name = "Sarah K.", isOnline = true)
            }

            // Robert M. (Bottom-Right)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (80.dp * convergenceRatio) + floatX4.dp,
                        y = (75.dp * convergenceRatio) + floatY4.dp
                    )
            ) {
                ExpertAvatar(initials = "RM", name = "Robert M.", isOnline = true)
            }

            // Dr. Sarah (Mid-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-105.dp * convergenceRatio) + floatX5.dp,
                        y = (-10.dp * convergenceRatio) + floatY5.dp
                    )
            ) {
                ExpertAvatar(initials = "SB", name = "Dr. Sarah", isOnline = true)
            }

            // Central Bolt Launcher Button (Perfect Symmetrical center of Radar rings)
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        .background(PRIMARY.copy(alpha = 0.08f), CircleShape)
                        .border(1.dp, PRIMARY.copy(alpha = 0.18f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(PRIMARY.copy(alpha = 0.12f), CircleShape)
                        .border(1.5.dp, PRIMARY.copy(alpha = 0.28f), CircleShape)
                )

                IconButton(
                    onClick = { 
                        if (!uiState.isBroadcasting) {
                            showHailDialog = true 
                        } else {
                            onCancelBroadcast()
                        }
                    },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(PRIMARY)
                        .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.2f)), CircleShape)
                ) {
                    Icon(
                        imageVector = if (uiState.isBroadcasting) Icons.Default.Hearing else Icons.Default.Bolt,
                        contentDescription = "Launcher",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // ── Controls & Information Area (Texts, CTA button, Symmetrical footer row) ──
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (uiState.isBroadcasting) "Broadcasting Spark..." else "SkoLab Spark Console",
                    style = Typography.headlineSmall,
                    color = TEXT_PRIMARY,
                    fontWeight = FontWeight.Black,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 20.sp
                )
                Text(
                    text = if (uiState.isBroadcasting) "Searching for matching experts online..." else "Connect with verified researchers for instant 10-minute help pings.",
                    color = TEXT_SECONDARY,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                if (uiState.isBroadcasting) {
                    Button(
                        onClick = onCancelBroadcast,
                        colors = ButtonDefaults.buttonColors(containerColor = SURFACE_SUBTLE, contentColor = TEXT_PRIMARY),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Cancel Call ❌", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showHailDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.WifiTethering, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Hail Live Helper Now", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Symmetrical, non-wrapping footer stats row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "● 1,420 Experts Online",
                    color = AccentEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "⭐ 4.9 Seeker Rating",
                    color = TEXT_MUTED,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "🪙 ${uiState.walletTokens} Tokens",
                    color = TEXT_MUTED,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showHailDialog) {
        var topic by remember { mutableStateOf("") }
        var selectedBounty by remember { mutableStateOf("10 Tokens") }
        var selectedProject by remember { mutableStateOf<ProjectCollab?>(null) }
        var selectedTask by remember { mutableStateOf<CollabTask?>(null) }
        var selectedPaper by remember { mutableStateOf<String?>(null) }

        // Filter tasks that belong to the selected project
        val projectTasks = remember(selectedProject, tasks) {
            if (selectedProject == null) emptyList() else tasks
        }

        // Available tags from user profile
        val availableTags = remember(userMemoryProfile, userFocus) {
            val list = mutableListOf<String>()
            userMemoryProfile?.let { prof ->
                list.addAll(prof.top_topics)
                list.addAll(prof.frequent_search_terms)
            }
            if (userFocus.isNotBlank() && !list.contains(userFocus)) {
                list.add(userFocus)
            }
            val cleaned = list.map { it.trim() }
                .filter { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
                .distinct()
                .take(6)
            if (cleaned.isNotEmpty()) cleaned else listOf("Research", "LaTeX", "Methodology", "Writing", "Data Analysis")
        }

        val selectedTags = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = { showHailDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(1.dp, BORDER, RoundedCornerShape(24.dp)),
            confirmButton = {
                Button(
                    onClick = {
                        if (topic.isNotBlank()) {
                            onHailClick(topic, selectedBounty, selectedTags.toList())
                            showHailDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                    shape = RoundedCornerShape(12.dp),
                    enabled = topic.isNotBlank()
                ) {
                    Text("Broadcast Call ⚡", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHailDialog = false }) {
                    Text("Cancel", color = TEXT_MUTED)
                }
            },
            title = {
                Column {
                    Text(
                        text = "Hail Live Helper",
                        color = TEXT_PRIMARY,
                        fontWeight = FontWeight.Black,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Specify your blocker context using your active project files, tasks, and papers.",
                        color = TEXT_SECONDARY,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // Blocker input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Blocker Description:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = topic,
                            onValueChange = { topic = it },
                            placeholder = { Text("Describe what you are blocked on...", fontSize = 12.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = TEXT_PRIMARY, fontSize = 13.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY,
                                focusedBorderColor = PRIMARY,
                                unfocusedBorderColor = BORDER,
                                focusedContainerColor = SURFACE,
                                unfocusedContainerColor = SURFACE
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Select Workspace Project (Dynamic User Content)
                    if (projects.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Project Context:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                projects.forEach { proj ->
                                    val isSelected = selectedProject?.id == proj.id
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE)
                                            .border(0.5.dp, if (isSelected) PRIMARY else BORDER, RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (isSelected) {
                                                    selectedProject = null
                                                    selectedTask = null
                                                } else {
                                                    selectedProject = proj
                                                    selectedTask = null
                                                    // Append or set project context in text
                                                    if (!topic.contains(proj.name)) {
                                                        topic = "Working on project [${proj.name}]: " + topic.replace(Regex("Working on project \\[.*?\\]: "), "")
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = proj.name, color = if (isSelected) PRIMARY else TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    // Select Project Task (Dynamic User Content)
                    if (selectedProject != null && projectTasks.isNotEmpty()) {
                        val unfinishedTasks = projectTasks.filter { !it.isCompleted }
                        if (unfinishedTasks.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Blocked Task:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    unfinishedTasks.forEach { task ->
                                        val isSelected = selectedTask?.id == task.id
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE)
                                                .border(0.5.dp, if (isSelected) PRIMARY else BORDER, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    if (isSelected) {
                                                        selectedTask = null
                                                    } else {
                                                        selectedTask = task
                                                        // Pre-fill blocker topic with blocked task context
                                                        topic = "Blocked on task '${task.title}' in project '${selectedProject?.name}': "
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(text = task.title, color = if (isSelected) PRIMARY else TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Attach Active Reading Context (Dynamic User Content)
                    val recentPapers = userMemoryProfile?.recently_read_papers.orEmpty()
                    val unfinishedPapers = userMemoryProfile?.unfinished_papers.orEmpty()
                    val papersList = (recentPapers + unfinishedPapers).distinct().take(4)
                    if (papersList.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Book, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Attach Paper Context:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                papersList.forEach { paper ->
                                    val isSelected = selectedPaper == paper
                                    val truncatedPaper = if (paper.length > 25) paper.take(22) + "..." else paper
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE)
                                            .border(0.5.dp, if (isSelected) PRIMARY else BORDER, RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (isSelected) {
                                                    selectedPaper = null
                                                } else {
                                                    selectedPaper = paper
                                                    // Pre-fill blocker topic with paper context
                                                    topic = "Need to discuss methodology in paper \"$paper\": "
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = truncatedPaper, color = if (isSelected) PRIMARY else TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }

                    // Preset Skills tags selection
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Required Expertise tags:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        
                        // Wrap tags in rows
                        val chunkedTags = availableTags.chunked(3)
                        chunkedTags.forEach { rowTags ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rowTags.forEach { tag ->
                                    val isSelected = selectedTags.contains(tag)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE)
                                            .border(0.5.dp, if (isSelected) PRIMARY else BORDER, RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (isSelected) selectedTags.remove(tag) else selectedTags.add(tag)
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tag,
                                            color = if (isSelected) PRIMARY else TEXT_SECONDARY,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Collaboration Mode (Replaced irrelevant Bounty tokens with professional academic modes)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.People, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Collaboration Mode:", color = TEXT_PRIMARY, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val collabOptions = listOf(
                                "Quick Q&A" to "5 Tokens",
                                "Deep Review" to "10 Tokens",
                                "Co-Authorship" to "Co-Authorship"
                            )
                            collabOptions.forEach { (label, bountyValue) ->
                                val isSelected = selectedBounty == bountyValue
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE)
                                        .border(0.5.dp, if (isSelected) PRIMARY else BORDER, RoundedCornerShape(12.dp))
                                        .clickable { selectedBounty = bountyValue }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) PRIMARY else TEXT_PRIMARY,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun ExpertAvatar(
    initials: String,
    name: String,
    isOnline: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF221512)) // Deep dark coffee bg
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            listOf(PRIMARY, AccentEmerald)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = PRIMARY,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(AccentEmerald, CircleShape)
                        .border(2.dp, Color(0xFF130D0A), CircleShape)
                        .align(Alignment.TopEnd)
                )
            }
        }
        Text(
            text = name,
            color = TEXT_PRIMARY,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── COMPACT INCOMING RING OVERLAY ──
@Composable
fun IncomingSparkRingOverlay(
    session: com.company.skolab.model.SparkSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    val infiniteTransition = rememberInfiniteTransition(label = "ringPulse")
    val borderPulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringBorder"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    AlertDialog(
        onDismissRequest = { 
            dismissed = true
            onDecline()
        },
        containerColor = BgCard,
        confirmButton = {
            Button(
                onClick = {
                    onAccept()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Accept & Help 🤝", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissed = true
                onDecline()
            }) {
                Text("Decline", color = TextMuted)
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = AccentEmerald,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "INCOMING SPARK CALL",
                    color = AccentEmerald,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = SpaceGroteskFontFamily
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer(
                            scaleX = borderPulse,
                            scaleY = borderPulse,
                            alpha = ringAlpha
                        )
                        .border(3.dp, AccentEmerald, CircleShape)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = session.topic,
                        color = TEXT_PRIMARY,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        session.tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                Text(
                                    text = tag,
                                    color = AccentTeal,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Offer: ${session.bounty}",
                        color = AccentEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Hailed by ${session.seekerName.split(" ").firstOrNull() ?: "Researcher"}",
                        color = TEXT_MUTED,
                        fontSize = 10.sp
                    )
                }
            }
        }
    )
}
