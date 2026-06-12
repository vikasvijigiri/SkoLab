package com.company.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.*
import androidx.compose.ui.res.pluralStringResource
import com.company.skolab.R
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.company.skolab.ui.components.MarkdownText
import android.widget.Toast
import kotlinx.coroutines.tasks.await

data class CoLabMessage(
    val sender: String,
    val isMe: Boolean,
    val content: String,
    val time: String,
    val isSystem: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoLabWorkspaceScreen(
    projectName: String = "Project Nexus",
    onBack: () -> Unit,
    onNavigateToInviteMember: (String) -> Unit = {},
    onNavigateToCreateTask: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    
    val currentUserName = cachedUser?.name ?: "Researcher"
    val currentUserId = cachedUser?.uid ?: ""
    val firstName = remember(cachedUser?.name) {
        val fullName = cachedUser?.name ?: "Researcher"
        val first = fullName.trim().split(" ").firstOrNull() ?: "Researcher"
        if (first.equals("SkoLab", ignoreCase = true) || first.equals("User", ignoreCase = true)) "Researcher" else first
    }

    var activeTab by remember { mutableStateOf("Chat") } // Chat, Equation, Manuscript, Meetings
    var showVideoCall by remember { mutableStateOf(false) }
    var userMessage by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var projectDetails by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isLoadingProject by remember { mutableStateOf(true) }
    var tasksList by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

    LaunchedEffect(projectName) {
        val db = FirebaseFirestore.getInstance()
        // 1. Try to fetch as document ID first
        db.collection("collabs_groups").document(projectName).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    projectDetails = doc.data
                    isLoadingProject = false
                } else {
                    // Fallback to name search
                    db.collection("collabs_groups")
                        .whereEqualTo("name", projectName)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            val fallbackDoc = querySnapshot.documents.firstOrNull()
                            if (fallbackDoc != null) {
                                projectDetails = fallbackDoc.data
                            }
                            isLoadingProject = false
                        }
                        .addOnFailureListener {
                            isLoadingProject = false
                        }
                }
            }
            .addOnFailureListener {
                // Fallback to name search on failure
                db.collection("collabs_groups")
                    .whereEqualTo("name", projectName)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        val fallbackDoc = querySnapshot.documents.firstOrNull()
                        if (fallbackDoc != null) {
                            projectDetails = fallbackDoc.data
                        }
                        isLoadingProject = false
                    }
                    .addOnFailureListener {
                        isLoadingProject = false
                    }
            }
    }

    val activeProjectDetails = projectDetails ?: mapOf(
        "id" to projectName,
        "name" to projectName,
        "description" to "Collaborative workspace for $projectName.",
        "members" to listOf(
            mapOf("uid" to currentUserId, "name" to currentUserName, "email" to (cachedUser?.email ?: "you@skolab.com"))
        ),
        "recentEquations" to "",
        "manuscriptProgress" to 0.0f
    )
    val projectId = activeProjectDetails["id"] as? String ?: ""
    val isRealProject = projectId.isNotEmpty()

    // Real-time editable LaTeX Equation loaded from project details
    var latexEquation by remember(activeProjectDetails) {
        mutableStateOf(
            (activeProjectDetails["recentEquations"] as? String) ?: ""
        )
    }
    var isCollaboratorTypingEquation by remember { mutableStateOf(false) }

    // Manuscript draft text loaded from project details
    var manuscriptDraft by remember(activeProjectDetails) {
        mutableStateOf(
            (activeProjectDetails["manuscriptDraft"] as? String) ?: ""
        )
    }

    // Dynamic list of task items loaded in real-time
    DisposableEffect(activeProjectDetails) {
        val projId = activeProjectDetails["id"] as? String ?: ""
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        if (projId.isNotEmpty()) {
            val db = FirebaseFirestore.getInstance()
            listener = db.collection("collabs_groups").document(projId).collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        tasksList = snapshot.documents.mapNotNull { it.data }
                    }
                }
        }
        onDispose {
            listener?.remove()
        }
    }

    // Dynamic Chat Messages from Firestore
    val chatMessages = remember { mutableStateListOf<CoLabMessage>() }

    DisposableEffect(projectId, firstName) {
        chatMessages.clear()
        if (projectId.isEmpty()) {
            onDispose {}
        } else {
            val db = FirebaseFirestore.getInstance()
            val listener = db.collection("collabs_groups").document(projectId)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        android.util.Log.e("CoLabWorkspaceScreen", "Error listening to messages", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        chatMessages.clear()
                        if (snapshot.isEmpty) {
                            chatMessages.add(CoLabMessage("System", false, "Welcome to the workspace! Send a message to start collaborating.", "Just Now", isSystem = true))
                        } else {
                            snapshot.documents.forEach { doc ->
                                val sender = doc.getString("senderName") ?: "Researcher"
                                val text = doc.getString("text") ?: ""
                                val isMe = doc.getString("senderUid") == currentUserId || sender == "You" || sender == currentUserName
                                val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
                                chatMessages.add(CoLabMessage(sender, isMe, text, timeStr))
                            }
                        }
                    }
                }
            onDispose {
                listener.remove()
            }
        }
    }

    val activeCoauthorsCount by remember(activeProjectDetails) {
        derivedStateOf {
            val membersList = activeProjectDetails["members"] as? List<*>
            membersList?.size ?: 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary) // Sleek WhatsApp Dark Mode Bg
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = BgCard,
                    border = BorderStroke(0.5.dp, BorderLight),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                             Text(
                                 text = activeProjectDetails["name"] as? String ?: projectName,
                                 color = TextPrimary,
                                 fontWeight = FontWeight.Bold,
                                 fontSize = 15.sp,
                                 fontFamily = DisplayFontFamily,
                                 maxLines = 1
                             )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(AccentEmerald, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = pluralStringResource(
                                        id = R.plurals.active_co_authors,
                                        count = activeCoauthorsCount,
                                        activeCoauthorsCount
                                    ),
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Invite Member Button
                            IconButton(
                                onClick = {
                                    val id = activeProjectDetails["id"] as? String ?: ""
                                    if (id.isNotEmpty() && id != "project_nexus_mock") {
                                        onNavigateToInviteMember(id)
                                    }
                                },
                                modifier = Modifier.size(32.dp).background(BgSubtle, CircleShape)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = "Invite Member", tint = AccentTeal, modifier = Modifier.size(16.dp))
                            }

                            // Create Task Button
                            IconButton(
                                onClick = {
                                    val id = activeProjectDetails["id"] as? String ?: ""
                                    if (id.isNotEmpty() && id != "project_nexus_mock") {
                                        onNavigateToCreateTask(id)
                                    }
                                },
                                modifier = Modifier.size(32.dp).background(BgSubtle, CircleShape)
                            ) {
                                Icon(Icons.Default.AddTask, contentDescription = "Create Task", tint = AccentTeal, modifier = Modifier.size(16.dp))
                            }

                            Button(
                                onClick = { showVideoCall = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.VideoCall, contentDescription = "Join Call", tint = TextOnAccent, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Join Call", color = TextOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(32.dp).background(BgSubtle, CircleShape)
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(BgCard)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Delete Project", color = AccentRose, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                                        onClick = {
                                            showMenu = false
                                            showDeleteDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = AccentRose, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Workspace Navigation Tab Row — compact text-only, custom zero-padding tabs to prevent wrapping
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(BgCard),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val tabs = listOf(
                        "Chat" to "Chat",
                        "Equation" to "Blackboard",
                        "Manuscript" to "Draft",
                        "Meetings" to "Syncs"
                    )
                    tabs.forEach { (tabId, label) ->
                        val isSelected = activeTab == tabId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { activeTab = tabId },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AccentTeal else TextMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Custom indicator
                                Box(
                                    modifier = Modifier
                                        .height(2.dp)
                                        .width(if (isSelected) 36.dp else 0.dp)
                                        .background(AccentTeal, RoundedCornerShape(1.dp))
                                )
                            }
                        }
                    }
                }

                // Main Tab Content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        "Chat" -> CoLabChatView(
                            messages = chatMessages,
                            userMessage = userMessage,
                            onMessageChange = { userMessage = it },
                            onSendMessage = {
                                if (userMessage.isNotBlank()) {
                                    val text = userMessage.trim()
                                    if (isRealProject) {
                                        val db = FirebaseFirestore.getInstance()
                                        val msgId = db.collection("collabs_groups").document(projectId).collection("messages").document().id
                                        db.collection("collabs_groups").document(projectId).collection("messages").document(msgId).set(
                                            hashMapOf(
                                                "id" to msgId,
                                                "senderUid" to currentUserId,
                                                "senderName" to currentUserName,
                                                "text" to text,
                                                "timestamp" to System.currentTimeMillis()
                                            )
                                        )
                                        // Also add to activity log!
                                        val actId = db.collection("collabs_groups").document(projectId).collection("activity").document().id
                                        db.collection("collabs_groups").document(projectId).collection("activity").document(actId).set(
                                            hashMapOf(
                                                "id" to actId,
                                                "author" to currentUserName,
                                                "action" to "Posted message: \"$text\"",
                                                "time" to "just now",
                                                "timestamp" to System.currentTimeMillis()
                                            )
                                        )
                                    } else {
                                        chatMessages.add(CoLabMessage("You", true, text, "Just Now"))
                                    }
                                    userMessage = ""
                                }
                            }
                        )
                        "Equation" -> SharedEquationsBlackboard(
                            equation = latexEquation,
                            onEquationChange = { 
                                latexEquation = it 
                                val id = activeProjectDetails["id"] as? String ?: ""
                                if (id.isNotEmpty()) {
                                    FirebaseFirestore.getInstance().collection("collabs_groups").document(id).update("recentEquations", it)
                                }
                            },
                            isTyping = isCollaboratorTypingEquation,
                            projectDescription = activeProjectDetails["description"] as? String ?: ""
                        )
                        "Manuscript" -> ManuscriptSandbox(
                            draft = manuscriptDraft,
                            onDraftChange = { 
                                manuscriptDraft = it 
                                val id = activeProjectDetails["id"] as? String ?: ""
                                if (id.isNotEmpty()) {
                                    FirebaseFirestore.getInstance().collection("collabs_groups").document(id).update("manuscriptDraft", it)
                                }
                            }
                        )
                        "Meetings" -> MeetingsSyncGrid(
                            projectId = activeProjectDetails["id"] as? String ?: "",
                            tasksList = tasksList,
                            onNavigateToCreateTask = onNavigateToCreateTask
                        )
                    }
                }
            }
        }

        // Futuristic Glassmorphic Video Call Overlay!
        AnimatedVisibility(
            visible = showVideoCall,
            enter = fadeIn(animationSpec = tween(400)) + expandIn(expandFrom = Alignment.Center),
            exit = fadeOut(animationSpec = tween(400)) + shrinkOut(shrinkTowards = Alignment.Center)
        ) {
            VideoConferenceOverlay(
                projectName = activeProjectDetails["name"] as? String ?: projectName,
                onEndCall = { showVideoCall = false }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
                title = {
                    Text(
                        text = "Delete Project?",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DisplayFontFamily
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to permanently delete this project? This will delete all tasks, meetings, blackboard equations, and chat history. This action cannot be undone.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isDeleting = true
                            scope.launch {
                                try {
                                    val db = FirebaseFirestore.getInstance()
                                    val projectRef = db.collection("collabs_groups").document(projectId)

                                    // Delete tasks subcollection
                                    val tasksSnapshot = projectRef.collection("tasks").get().await()
                                    tasksSnapshot.documents.forEach { it.reference.delete().await() }

                                    // Delete messages subcollection
                                    val messagesSnapshot = projectRef.collection("messages").get().await()
                                    messagesSnapshot.documents.forEach { it.reference.delete().await() }

                                    // Delete meetings subcollection
                                    val meetingsSnapshot = projectRef.collection("meetings").get().await()
                                    meetingsSnapshot.documents.forEach { it.reference.delete().await() }

                                    // Delete activity subcollection
                                    val activitySnapshot = projectRef.collection("activity").get().await()
                                    activitySnapshot.documents.forEach { it.reference.delete().await() }

                                    // Delete main project document
                                    projectRef.delete().await()

                                    Toast.makeText(context, "Project deleted successfully", Toast.LENGTH_SHORT).show()
                                    showDeleteDialog = false
                                    onBack()
                                } catch (e: Exception) {
                                    isDeleting = false
                                    Toast.makeText(context, "Failed to delete project: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
                        enabled = !isDeleting
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDialog = false },
                        enabled = !isDeleting
                    ) {
                        Text("Cancel", color = AccentTeal)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun CoLabChatView(
    messages: List<CoLabMessage>,
    userMessage: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showEmojiPanel by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { "${it.sender}-${it.time}" }) { msg ->
                if (msg.isSystem) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = BgSubtle.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.5.dp, BorderLight.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = msg.content,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    val alignment = if (msg.isMe) Alignment.End else Alignment.Start
                    val bubbleBg = if (msg.isMe) BrandWhatsAppBubbleDarkMe else BgCard
                    val textColor = if (msg.isMe) Color.White else TextPrimary

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = alignment
                    ) {
                        Surface(
                            color = bubbleBg,
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (msg.isMe) 12.dp else 2.dp,
                                bottomEnd = if (msg.isMe) 2.dp else 12.dp
                            ),
                            border = BorderStroke(0.5.dp, BorderLight),
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                if (!msg.isMe) {
                                    val senderColor = remember(msg.sender) {
                                        val colors = listOf(AccentAmber, AccentCyan, AccentTeal, AccentViolet, AccentOrange, AccentRose)
                                        colors[kotlin.math.abs(msg.sender.hashCode()) % colors.size]
                                    }
                                    Text(
                                        text = msg.sender,
                                        color = senderColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                                
                                MarkdownText(
                                    markdown = msg.content,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = msg.time,
                                    color = if (msg.isMe) Color.White.copy(alpha = 0.6f) else TextMuted,
                                    fontSize = 9.sp,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Compact multi-line auto-expanding message input bar
        Surface(
            color = BgCard,
            border = BorderStroke(0.5.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            val isMessageEmpty = userMessage.trim().isEmpty()
            val sendButtonBg by androidx.compose.animation.animateColorAsState(
                targetValue = if (isMessageEmpty) BgSubtle else AccentTeal,
                label = "sendBg"
            )
            val sendButtonTint by androidx.compose.animation.animateColorAsState(
                targetValue = if (isMessageEmpty) TextMuted else Color.White,
                label = "sendTint"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(BgSubtle)
                        .border(BorderStroke(0.5.dp, BorderLight), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showEmojiPanel) Icons.Default.Keyboard else Icons.Default.SentimentSatisfiedAlt,
                        contentDescription = "Emojis",
                        tint = if (showEmojiPanel) AccentTeal else TextMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                if (!showEmojiPanel) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                                showEmojiPanel = !showEmojiPanel
                            }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 0.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = userMessage,
                            onValueChange = onMessageChange,
                            singleLine = false,
                            maxLines = 5,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextPrimary,
                                fontSize = 13.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentTeal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        showEmojiPanel = false
                                    }
                                },
                            decorationBox = { inner ->
                                if (userMessage.isEmpty()) {
                                    Text("Message co-authors...", color = TextMuted, fontSize = 13.sp)
                                }
                                inner()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = "Mention Co-Authors",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                onMessageChange(userMessage + "@")
                            }
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(sendButtonBg)
                        .then(
                            if (!isMessageEmpty) {
                                Modifier.clickable {
                                    onSendMessage()
                                    showEmojiPanel = false
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = sendButtonTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showEmojiPanel,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(BgCard)
                    .border(BorderStroke(0.5.dp, BorderLight))
            ) {
                val emojis = remember {
                    listOf(
                        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
                        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
                        "🥳", "🔬", "🧪", "🧬", "📚", "💻", "🧠", "🎓", "🌌", "🚀", 
                        "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👋", "👏", "🎉"
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                ) {
                    items(emojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { 
                                    onMessageChange(userMessage + emoji) 
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SharedEquationsBlackboard(
    equation: String,
    onEquationChange: (String) -> Unit,
    isTyping: Boolean,
    projectDescription: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Gesture, contentDescription = null, tint = AccentTeal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Shared Equations Canvas",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isTyping) {
                        Text(
                            text = "Collaborator is typing...",
                            color = AccentAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Simulated LaTeX Render Block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkChalkGreenBg) // Dark chalk green
                        .border(1.dp, DarkChalkGreenBorder, RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "RENDERED LATEX FORMULA",
                            color = DarkChalkGreenText.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        MarkdownText(
                            markdown = "$$" + equation + "$$",
                            color = DarkChalkGreenText,
                            fontSize = 18.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (projectDescription.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = projectDescription,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Shared Code Editor Input
        Card(
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth().height(160.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "LaTeX Source Code Editor",
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = equation,
                    onValueChange = onEquationChange,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentTeal,
                        unfocusedBorderColor = BorderLight,
                        focusedContainerColor = BgSubtle,
                        unfocusedContainerColor = BgSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun ManuscriptSandbox(
    draft: String,
    onDraftChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Manuscript Draft Sandbox",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Real-time sync to Overleaf / Git repo",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sync Overleaf", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentTeal,
                unfocusedBorderColor = BorderLight,
                focusedContainerColor = BgCard,
                unfocusedContainerColor = BgCard,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
fun MeetingsSyncGrid(
    projectId: String,
    tasksList: List<Map<String, Any>>,
    onNavigateToCreateTask: (String) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var meetingsList by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    DisposableEffect(projectId) {
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        if (projectId.isNotEmpty()) {
            listener = db.collection("collabs_groups").document(projectId).collection("meetings")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        meetingsList = snapshot.documents.mapNotNull { it.data }
                    }
                }
        }
        onDispose {
            listener?.remove()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Meetings Section ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Co-Author Synchronizations",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            IconButton(
                onClick = { showScheduleDialog = true }
            ) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = "Schedule Sync", tint = AccentTeal)
            }
        }

        if (meetingsList.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(0.5.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No scheduled synchronizations yet. Tap '+' to schedule a sync with your team.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            meetingsList.forEach { sync ->
                val title = sync["title"] as? String ?: "Sync Session"
                val whenStr = sync["when"] as? String ?: "Upcoming"
                Card(
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(0.5.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = whenStr,
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        IconButton(
                            onClick = {},
                            colors = IconButtonDefaults.iconButtonColors(containerColor = BgSubtle),
                            modifier = Modifier.clip(CircleShape)
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = AccentTeal)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. Tasks Section ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Workspace Tasks",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            IconButton(
                onClick = { onNavigateToCreateTask(projectId) }
            ) {
                Icon(Icons.Default.AddTask, contentDescription = "Add Task", tint = AccentTeal)
            }
        }

        if (tasksList.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(0.5.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No tasks created yet. Click '+' to assign tasks to co-authors.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            tasksList.forEach { task ->
                val title = task["title"] as? String ?: "Untitled Task"
                val assignee = task["assignee"] as? String ?: "Unassigned"
                val isCompleted = task["isCompleted"] as? Boolean ?: false
                val taskId = task["id"] as? String ?: ""

                Card(
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(0.5.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = isCompleted,
                            onCheckedChange = { checked ->
                                if (projectId.isNotEmpty() && taskId.isNotEmpty()) {
                                    FirebaseFirestore.getInstance()
                                        .collection("collabs_groups")
                                        .document(projectId)
                                        .collection("tasks")
                                        .document(taskId)
                                        .update("isCompleted", checked)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentTeal,
                                uncheckedColor = TextMuted
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = if (isCompleted) TextMuted else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                style = androidx.compose.ui.text.TextStyle(
                                    textDecoration = if (isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Assigned to: $assignee",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        if (showScheduleDialog) {
            var meetingTitle by remember { mutableStateOf("") }
            var meetingWhen by remember { mutableStateOf("") }
            var isScheduling by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showScheduleDialog = false },
                title = { Text("Schedule Sync Session", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = meetingTitle,
                            onValueChange = { meetingTitle = it },
                            label = { Text("Session Title", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = meetingWhen,
                            onValueChange = { meetingWhen = it },
                            label = { Text("Date & Time (e.g. Thursday, 2:00 PM)", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (meetingTitle.isNotBlank() && meetingWhen.isNotBlank()) {
                                isScheduling = true
                                val newId = db.collection("collabs_groups").document(projectId).collection("meetings").document().id
                                val meetingData = hashMapOf(
                                    "id" to newId,
                                    "title" to meetingTitle.trim(),
                                    "when" to meetingWhen.trim(),
                                    "timestamp" to System.currentTimeMillis()
                                )
                                db.collection("collabs_groups").document(projectId).collection("meetings").document(newId).set(meetingData)
                                    .addOnSuccessListener {
                                        val actId = db.collection("collabs_groups").document(projectId).collection("activity").document().id
                                        db.collection("collabs_groups").document(projectId).collection("activity").document(actId).set(
                                            hashMapOf(
                                                "id" to actId,
                                                "author" to "System",
                                                "action" to "Scheduled sync session: \"$meetingTitle\" at $meetingWhen",
                                                "time" to "just now",
                                                "timestamp" to System.currentTimeMillis()
                                            )
                                        )
                                        showScheduleDialog = false
                                    }
                                    .addOnFailureListener {
                                        isScheduling = false
                                    }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        enabled = !isScheduling && meetingTitle.isNotBlank() && meetingWhen.isNotBlank()
                    ) {
                        Text("Schedule", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showScheduleDialog = false }) {
                        Text("Cancel", color = AccentTeal)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun VideoConferenceOverlay(
    projectName: String,
    onEndCall: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$projectName Co-Lab Sync",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    fontFamily = DisplayFontFamily
                )
                Text(
                    text = "Live Video Conference",
                    color = AccentTeal,
                    fontSize = 12.sp
                )
            }

            // Participants view (Vibrantly styled Avatar streams)
            Row(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Sumiran stream
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgCard)
                        .border(BorderStroke(1.dp, BorderLight), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size((80 * pulseScale1).dp)
                                    .clip(CircleShape)
                                    .background(AccentAmber.copy(alpha = 0.15f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(AccentAmber.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SP", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Sumiran Pujari", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Muted", color = TextMuted, fontSize = 11.sp)
                    }
                }

                // Nisheeta stream
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgCard)
                        .border(BorderStroke(1.dp, BorderLight), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size((80 * pulseScale2).dp)
                                    .clip(CircleShape)
                                    .background(AccentCyan.copy(alpha = 0.15f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(AccentCyan.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ND", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Nisheeta Desai", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Speaking", color = AccentTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(BgCard)
                    .border(BorderStroke(1.dp, BorderLight), RoundedCornerShape(32.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {},
                    colors = IconButtonDefaults.iconButtonColors(containerColor = BgSubtle),
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Mute", tint = Color.White)
                }

                IconButton(
                    onClick = onEndCall,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = AccentRed),
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White)
                }

                IconButton(
                    onClick = {},
                    colors = IconButtonDefaults.iconButtonColors(containerColor = BgSubtle),
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = "Video Toggle", tint = Color.White)
                }
            }
        }
    }
}

