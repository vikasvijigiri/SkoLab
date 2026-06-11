package com.company.skolab.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    // Real network collaborators — user's actual co-authors from their papers
    var networkCollaborators by remember { mutableStateOf<List<com.company.skolab.network.NetworkCollaborator>>(emptyList()) }

    // Real orbit metrics from OpenAlex
    var orbitMetrics by remember { mutableStateOf<OrbitMetrics?>(null) }
    var isLoadingOrbitMetrics by remember { mutableStateOf(false) }
    var userOpenAlexId by remember { mutableStateOf("") }

    val apiService = com.company.skolab.di.AppDependencies.apiService

    // Resolve user's OpenAlex ID + orbit metrics
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

    // Fetch real co-authors once we have the user's OpenAlex ID
    LaunchedEffect(userOpenAlexId) {
        if (userOpenAlexId.isNotBlank()) {
            try {
                val collabs = apiService.getNetworkCollaborators(
                    authorId = userOpenAlexId,
                    limit = 6,
                    excludeName = currentUserName
                )
                networkCollaborators = collabs
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to fetch network collaborators", e)
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
                HomeTopWidget(
                    recentPapers = userMemoryProfile?.recently_read_papers.orEmpty().take(5),
                    skolabConnections = membersPresence.values.filter { it.uid != currentUserId }.take(4),
                    networkCollaborators = networkCollaborators.take(5),
                    onNavigateToChat = onNavigateToChat,
                    onInviteClick = onNavigateToExternalInvite,
                    onAuthorClick = onAuthorClick
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


    // Ring expert's phone when a new matching request arrives
    val firstIncomingId = sparkUiState.incomingRequests.firstOrNull()?.id
    LaunchedEffect(firstIncomingId) {
        if (firstIncomingId != null && sparkUiState.isOnline && sparkUiState.activeSession == null) {
            val incoming = sparkUiState.incomingRequests.first()
            com.company.skolab.utils.SkoLabNotificationManager.showSparkCallNotification(
                context, incoming.seekerName, incoming.topic
            )
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

// ── HOME TOP WIDGET — Trending & Network ──
@Composable
fun HomeTopWidget(
    recentPapers: List<String>,
    skolabConnections: List<com.company.skolab.model.SkoLabUser>,
    networkCollaborators: List<com.company.skolab.network.NetworkCollaborator>,
    onNavigateToChat: (String, String) -> Unit,
    onInviteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit = {}
) {
    val hasConnections = skolabConnections.isNotEmpty() || networkCollaborators.isNotEmpty()
    if (recentPapers.isEmpty() && !hasConnections) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgPrimary)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Trending papers ──────────────────────────────────────────────────
        if (recentPapers.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Trending",
                    color = TEXT_MUTED,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp)
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentPapers.forEach { paper ->
                        val label = if (paper.length > 28) paper.take(26) + "…" else paper
                        Row(
                            modifier = Modifier
                                .background(SURFACE, RoundedCornerShape(8.dp))
                                .border(0.5.dp, BORDER, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Book, null, tint = TEXT_MUTED, modifier = Modifier.size(10.dp))
                            Text(label, color = TEXT_SECONDARY, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // ── Network connections row ──────────────────────────────────────────
        if (hasConnections) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Your Network",
                    color = TEXT_SECONDARY,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // SkoLab project collaborators — show "Message" (filled)
                    skolabConnections.forEach { user ->
                        val initials = user.name.split(" ")
                            .filter { it.isNotBlank() }
                            .mapNotNull { token -> token.filter { c -> c.isLetter() }.firstOrNull()?.uppercaseChar() }
                            .take(2).joinToString("")
                        val subtitle = user.researchFocus.ifBlank { "SkoLab Researcher" }
                        NetworkPersonCard(
                            initials = initials,
                            name = user.name,
                            subtitle = subtitle,
                            isOnline = user.isOnline,
                            actionLabel = "Message",
                            actionFilled = true,
                            onClick = { onNavigateToChat(user.uid, user.name) },
                            onCardClick = { onNavigateToChat(user.uid, user.name) }
                        )
                    }
                    // Real co-authors from user's papers — show "+ Invite" to connect outside SkoLab
                    networkCollaborators.forEach { collab ->
                        val initials = collab.name.split(" ")
                            .filter { it.isNotBlank() }
                            .mapNotNull { token -> token.filter { c -> c.isLetter() }.firstOrNull()?.uppercaseChar() }
                            .take(2).joinToString("")
                        val papersLabel = collab.papers_collaborated?.let { n ->
                            if (n > 0) "$n paper${if (n > 1) "s" else ""}" else null
                        }
                        val subtitle = buildString {
                            val inst = collab.institution.trim()
                            if (inst.isNotBlank() && inst != "Independent Researcher") {
                                append(if (inst.length > 18) inst.take(16) + "…" else inst)
                            }
                            if (papersLabel != null) {
                                if (isNotEmpty()) append(" · ")
                                append(papersLabel)
                            }
                        }.ifBlank { collab.field.ifBlank { "Co-author" } }
                        NetworkPersonCard(
                            initials = initials,
                            name = collab.name,
                            subtitle = subtitle,
                            isOnline = false,
                            actionLabel = "+ Invite",
                            actionFilled = false,
                            onClick = { onInviteClick(collab.name) },
                            onCardClick = { onAuthorClick("${collab.name}|${collab.id}") }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkPersonCard(
    initials: String,
    name: String,
    subtitle: String,
    isOnline: Boolean,
    actionLabel: String,
    actionFilled: Boolean,
    onClick: () -> Unit,
    onCardClick: () -> Unit = onClick
) {
    val avatarPalette = listOf(
        Color(0xFF0D9488), Color(0xFF4F46E5), Color(0xFF059669),
        Color(0xFFD97706), Color(0xFF7C3AED), Color(0xFFEA580C),
        Color(0xFF0891B2), Color(0xFFDB2777)
    )
    val accentColor = avatarPalette[kotlin.math.abs(name.hashCode()) % avatarPalette.size]

    Surface(
        onClick = onCardClick,
        modifier = Modifier.width(148.dp),
        shape = RoundedCornerShape(14.dp),
        color = SURFACE,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
        shadowElevation = 3.dp
    ) {
        Column {
            // Accent top strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.4f)))
                    )
            )
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Avatar + name/subtitle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(accentColor.copy(alpha = 0.18f), CircleShape)
                                .border(1.5.dp, accentColor.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                initials.ifEmpty { "?" },
                                color = accentColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isOnline) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFF3DD68C), CircleShape)
                                    .border(1.5.dp, SURFACE, CircleShape)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            name,
                            color = TEXT_PRIMARY,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            lineHeight = 15.sp,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subtitle,
                            color = TEXT_SECONDARY,
                            fontSize = 10.sp,
                            maxLines = 2,
                            lineHeight = 13.sp,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Action button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (actionFilled) accentColor else Color.White)
                        .border(
                            width = 1.dp,
                            color = if (actionFilled) Color.Transparent else accentColor,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(onClick = onClick)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        actionLabel,
                        color = if (actionFilled) Color.White else accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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
    var isListeningForDoubt by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) isListeningForDoubt = true
    }

    fun launchMicOrRequest() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            isListeningForDoubt = true
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val autoTags = remember(userMemoryProfile, userFocus) {
        val list = mutableListOf<String>()
        userMemoryProfile?.let { prof ->
            list.addAll(prof.top_topics)
            list.addAll(prof.frequent_search_terms)
        }
        if (userFocus.isNotBlank() && !list.contains(userFocus)) list.add(userFocus)
        list.map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            .distinct()
            .take(6)
            .ifEmpty { listOf("Research", "Methodology", "Writing") }
    }

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

    // Animate convergence ratio when searching (avatars slide closer)
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
                            launchMicOrRequest()
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
                        onClick = { launchMicOrRequest() },
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
                            Text("Ask an Expert", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }
            }

            Text(
                text = "🪙 ${uiState.walletTokens} Tokens",
                color = TEXT_MUTED,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp)
            )
        }
    }

    if (isListeningForDoubt) {
        SparkMicOverlay(
            onResult = { transcript ->
                isListeningForDoubt = false
                onHailClick(
                    transcript.ifBlank { userFocus.ifBlank { "Need expert help" } },
                    "10 Tokens",
                    autoTags
                )
            },
            onDismiss = { isListeningForDoubt = false }
        )
    }
}

// ── SPARK MIC OVERLAY ──
@Composable
fun SparkMicOverlay(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var transcript by remember { mutableStateOf("") }
    // phases: "listening" | "processing" | "text_fallback"
    var phase by remember { mutableStateOf("listening") }
    var textInput by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")

    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "r1a"
    )
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "r1s"
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = StartOffset(470)
        ),
        label = "r2a"
    )
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = StartOffset(470)
        ),
        label = "r2s"
    )
    val ring3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = StartOffset(940)
        ),
        label = "r3a"
    )
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = StartOffset(940)
        ),
        label = "r3s"
    )
    val micScale by infiniteTransition.animateFloat(
        initialValue = 0.93f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "micS"
    )

    DisposableEffect(Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            phase = "text_fallback"
            return@DisposableEffect onDispose {}
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { phase = "processing" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) transcript = text
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                onResult(text)
            }
            override fun onError(error: Int) {
                // Fall back to text input instead of silently dismissing
                phase = "text_fallback"
            }
        })
        recognizer.startListening(intent)
        onDispose {
            recognizer.stopListening()
            recognizer.destroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0100B09))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer(scaleX = ring3Scale, scaleY = ring3Scale, alpha = ring3Alpha)
                        .background(PRIMARY.copy(alpha = 0.1f), CircleShape)
                        .border(1.dp, PRIMARY.copy(alpha = ring3Alpha * 0.4f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .graphicsLayer(scaleX = ring2Scale, scaleY = ring2Scale, alpha = ring2Alpha)
                        .background(PRIMARY.copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, PRIMARY.copy(alpha = ring2Alpha * 0.5f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer(scaleX = ring1Scale, scaleY = ring1Scale, alpha = ring1Alpha)
                        .background(PRIMARY.copy(alpha = 0.22f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer(scaleX = micScale, scaleY = micScale)
                        .background(PRIMARY, CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            if (phase == "text_fallback") {
                Text(
                    text = "Describe your doubt",
                    color = TEXT_PRIMARY,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("e.g. Need help with my methodology section", color = TEXT_MUTED, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PRIMARY,
                        unfocusedBorderColor = TEXT_MUTED.copy(alpha = 0.4f),
                        focusedTextColor = TEXT_PRIMARY,
                        unfocusedTextColor = TEXT_PRIMARY,
                        cursorColor = PRIMARY
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TEXT_MUTED, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { if (textInput.isNotBlank()) onResult(textInput) },
                        enabled = textInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Ask", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                if (transcript.isNotBlank()) {
                    Text(
                        text = "\"$transcript\"",
                        color = TEXT_PRIMARY,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                } else {
                    Text(
                        text = if (phase == "processing") "Processing your doubt..." else "Speak your doubt...",
                        color = TEXT_SECONDARY,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TEXT_MUTED, fontSize = 13.sp)
                }
            }
        }
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
