package com.open.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.skolab.auth.AuthManager
import com.open.skolab.network.ChatMessage
import com.open.skolab.ui.components.MarkdownText
import com.open.skolab.ui.components.MessageBubbleWrapper
import com.open.skolab.ui.components.ReactionBadge
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.open.skolab.ui.theme.*
import com.open.skolab.viewmodel.AgentMode
import com.open.skolab.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.*

// Note: quick prompts are now driven by UserMemoryProfile — see AgentViewModel.personalizedQuickPrompts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val userUid = cachedUser?.uid ?: authManager.currentUser?.uid ?: "guest_user"

    val viewModel: AgentViewModel = viewModel(
        key = userUid,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AgentViewModel(context, userUid) as T
            }
        }
    )

    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val isConversationEmpty = uiState.messages.isEmpty() && !uiState.isTyping

    var showHistoryBottomSheet by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // Auto-scroll to latest message
    LaunchedEffect(uiState.messages.size, uiState.isTyping) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = EntropiColors.Background,
        topBar = {
            AgentTopBar(
                currentProject = uiState.currentProject,
                activeMode = uiState.activeMode,
                onModeToggle = {
                    val newMode = if (uiState.activeMode == AgentMode.RESEARCH) AgentMode.CODING else AgentMode.RESEARCH
                    viewModel.setMode(newMode)
                },
                onHistoryClick = { showHistoryBottomSheet = true },
                onNewChatClick = { viewModel.startNewChat() }
            )
        },
        bottomBar = {
            val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri: android.net.Uri? ->
                if (uri != null) {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    var name = "document.pdf"
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) name = it.getString(nameIndex)
                        }
                    }
                    viewModel.uploadFile(uri, name)
                }
            }
            AgentInputBar(
                text = messageText,
                onTextChanged = { messageText = it },
                onSend = {
                    if (messageText.isNotBlank()) {
                        val userMsg = if (replyingToMessage != null) {
                            "> ${replyingToMessage!!.content.replace("\n", "\n> ")}\n\n${messageText.trim()}"
                        } else {
                            messageText.trim()
                        }
                        replyingToMessage = null
                        viewModel.sendMessage(userMsg)
                        messageText = ""
                    }
                },
                attachedFileName = uiState.attachedFileName,
                isAttachingFile = uiState.isAttachingFile,
                onAttachClick = { filePickerLauncher.launch("*/*") },
                onClearAttachment = { viewModel.clearAttachment() },
                showQuickPrompts = isConversationEmpty,
                quickPrompts = uiState.personalizedQuickPrompts.ifEmpty {
                    listOf("Summarise my recent papers", "What should I read next?", "Find collaborators in my field")
                },
                onQuickPrompt = { prompt ->
                    val userMsg = if (replyingToMessage != null) {
                        "> ${replyingToMessage!!.content.replace("\n", "\n> ")}\n\n$prompt"
                    } else {
                        prompt
                    }
                    replyingToMessage = null
                    viewModel.sendMessage(userMsg)
                },
                replyingToMessage = replyingToMessage,
                onClearReply = { replyingToMessage = null }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isConversationEmpty) {
                // ── Empty state hero ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.height(40.dp))

                    // Glowing icon with logo
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        EntropiColors.Gold1.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.open.skolab.R.drawable.logo),
                            contentDescription = "Skolar Logo",
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Personalized greeting
                    val greeting = uiState.personalizedGreeting
                    Text(
                        text = if (greeting.isNotEmpty()) greeting else "Ask Skolar",
                        color = EntropiColors.Text,
                        fontSize = if (greeting.length > 40) 16.sp else 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = SyneFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 26.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = if (uiState.memoryProfile.topTopics.isNotEmpty())
                            "Personalised to your research in ${uiState.memoryProfile.topTopics.take(2).joinToString(" · ")}"
                        else
                            "Your AI research partner. Ask anything.",
                        color = EntropiColors.Text3,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    // ── Proactive reminder chips ────────────────────────────────
                    if (uiState.proactiveReminders.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "REMINDERS",
                            color = EntropiColors.Text3,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.proactiveReminders.forEach { reminder ->
                                Surface(
                                    onClick = {
                                        // Tap reminder to ask Skolar about it
                                        val clean = reminder.replace(Regex("^[^a-zA-Z]+"), "").trim()
                                        viewModel.sendMessage("Tell me more: $clean")
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = EntropiColors.Card,
                                    border = BorderStroke(0.5.dp, EntropiColors.Gold1.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = reminder,
                                            color = EntropiColors.Text2,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = EntropiColors.Gold1.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Memory stats row ────────────────────────────────────────
                    val mem = uiState.memoryProfile
                    if (mem.totalPapersRead > 0 || mem.streakDays > 0) {
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(EntropiColors.Card)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (mem.totalPapersRead > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(mem.totalPapersRead.toString(), color = EntropiColors.Gold1, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    Text("Papers Read", color = EntropiColors.Text3, fontSize = 10.sp)
                                }
                            }
                            if (mem.streakDays > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${mem.streakDays}🔥", color = EntropiColors.Gold1, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    Text("Day Streak", color = EntropiColors.Text3, fontSize = 10.sp)
                                }
                            }
                            if (mem.avgReadMinutes > 0f) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${mem.avgReadMinutes.toInt()}m", color = EntropiColors.Blue2, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    Text("Avg Read", color = EntropiColors.Text3, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(40.dp))
                }
            } else {
                // ── Message list ──────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 12.dp)
                ) {
                    items(uiState.messages) { msg ->
                        val isMe = msg.role == "user"
                        AgentMessageBubble(
                            message = msg,
                            isMe = isMe,
                            onReact = { emoji ->
                                viewModel.reactToMessage(msg, if (msg.reaction == emoji) null else emoji)
                            },
                            onReply = {
                                replyingToMessage = msg
                            },
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(msg.content))
                            },
                            onToggleStar = {
                                viewModel.toggleStarMessage(msg)
                            },
                            onDelete = {
                                viewModel.deleteMessage(msg)
                            }
                        )
                    }
                    if (uiState.isTyping) {
                        item { AgentTypingIndicator() }
                    }
                }
            }
        }
    }

    if (showHistoryBottomSheet) {
        AgentHistoryBottomSheet(
            onDismissRequest = { showHistoryBottomSheet = false },
            sessions = uiState.pastSessions,
            currentSessionId = uiState.currentSessionId,
            onSessionSelected = { viewModel.switchSession(it) },
            onDeleteSession = { viewModel.deleteSession(it) },
            onClearAllClick = {
                showClearConfirmDialog = true
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = EntropiColors.Card,
            title = { Text("Wipe All Conversations?", color = EntropiColors.Text, fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete all Ask Skolar chat sessions. You cannot undo this.", color = EntropiColors.Text2) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCurrentHistory()
                        uiState.pastSessions.forEach { (sId, _) ->
                            viewModel.deleteSession(sId)
                        }
                        showClearConfirmDialog = false
                        showHistoryBottomSheet = false
                    }
                ) {
                    Text("Delete All", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = EntropiColors.Text3)
                }
            }
        )
    }
}

// ── TOP BAR ──────────────────────────────────────────────────────────────────
@Composable
fun AgentTopBar(
    currentProject: String,
    activeMode: AgentMode,
    onModeToggle: () -> Unit,
    onHistoryClick: () -> Unit,
    onNewChatClick: () -> Unit
) {
    Surface(
        color = EntropiColors.Background,
        border = BorderStroke(0.5.dp, EntropiColors.Border.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // AI avatar dot (Logo)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = com.open.skolab.R.drawable.logo),
                    contentDescription = "Skolar Logo",
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ask Skolar",
                    color = EntropiColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = SyneFontFamily
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(Color(0xFF00E676), CircleShape)
                    )
                    Text(
                        text = "Online",
                        color = EntropiColors.Text3,
                        fontSize = 10.sp
                    )
                }
            }

            // Mode toggle pill
            Surface(
                onClick = onModeToggle,
                color = if (activeMode == AgentMode.CODING)
                    EntropiColors.Cyan.copy(alpha = 0.1f)
                else
                    EntropiColors.Gold1.copy(alpha = 0.1f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    if (activeMode == AgentMode.CODING) EntropiColors.Cyan.copy(alpha = 0.4f)
                    else EntropiColors.Gold1.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (activeMode == AgentMode.CODING) Icons.Default.Code else Icons.Default.Science,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = if (activeMode == AgentMode.CODING) EntropiColors.Cyan else EntropiColors.Gold1
                    )
                    Text(
                        text = activeMode.name,
                        color = if (activeMode == AgentMode.CODING) EntropiColors.Cyan else EntropiColors.Gold1,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            IconButton(
                onClick = onNewChatClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Chat",
                    tint = EntropiColors.Gold1,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                    tint = EntropiColors.Text2,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── MESSAGE BUBBLE ────────────────────────────────────────────────────────────
@Composable
fun AgentMessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit
) {
    val timestamp = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // AI avatar on the left (Logo)
        if (!isMe) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = com.open.skolab.R.drawable.logo),
                    contentDescription = "Skolar Logo",
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        MessageBubbleWrapper(
            message = message,
            onReact = onReact,
            onReply = onReply,
            onCopy = onCopy,
            onToggleStar = onToggleStar,
            onDelete = onDelete
        ) {
            Column(
                modifier = Modifier.widthIn(max = 290.dp),
                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
            ) {
                Box {
                    // Bubble
                    Box(
                        modifier = Modifier
                            .clip(
                                if (isMe) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                                else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                            )
                            .then(
                                if (isMe) {
                                    Modifier.background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF2A2010), Color(0xFF1A1500)),
                                            start = Offset(0f, 0f),
                                            end = Offset(300f, 100f)
                                        )
                                    )
                                } else {
                                    Modifier.background(EntropiColors.Card)
                                }
                            )
                            .then(
                                // Left accent bar for AI messages
                                if (!isMe) {
                                    Modifier.drawBehind {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    Color(0xFFFFCA28),
                                                    Color(0xFFFF8F00)
                                                )
                                            ),
                                            size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height)
                                        )
                                    }
                                } else Modifier
                            )
                            .padding(
                                start = if (!isMe) 12.dp else 10.dp,
                                end = 12.dp,
                                top = 9.dp,
                                bottom = 7.dp
                            )
                    ) {
                        Column {
                            MarkdownText(
                                markdown = message.content,
                                color = if (isMe) EntropiColors.Text else EntropiColors.Text2,
                                fontSize = 14.sp,
                                modifier = Modifier.widthIn(max = 280.dp)
                            )
                        }
                    }

                    // Render reaction badge if present
                    if (!message.reaction.isNullOrEmpty()) {
                        val badgeAlignment = if (isMe) Alignment.BottomStart else Alignment.BottomEnd
                        Box(
                            modifier = Modifier
                                .align(badgeAlignment)
                                .offset(y = 10.dp, x = if (isMe) (-4).dp else 4.dp)
                        ) {
                            ReactionBadge(reaction = message.reaction)
                        }
                    }
                }

                // Timestamp row
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (message.isStarred) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Starred",
                            tint = EntropiColors.Gold1,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(timestamp, color = EntropiColors.Text3, fontSize = 9.sp)
                    if (isMe) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = null,
                            tint = EntropiColors.Gold1.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
        }

        if (isMe) Spacer(Modifier.width(4.dp))
    }
}

// ── TYPING INDICATOR ──────────────────────────────────────────────────────────
@Composable
fun AgentTypingIndicator() {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        // AI avatar
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.open.skolab.R.drawable.logo),
                contentDescription = "Skolar Logo",
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(EntropiColors.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "typing")
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0, 150, 300).forEach { delay ->
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(500, delayMillis = delay, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "dot_$delay"
                    )
                    Box(
                        modifier = Modifier
                            .size((5 * scale).dp)
                            .background(EntropiColors.Gold1.copy(alpha = 0.6f + 0.4f * scale), CircleShape)
                    )
                }
            }
        }
    }
}

// ── INPUT BAR ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    attachedFileName: String? = null,
    isAttachingFile: Boolean = false,
    onAttachClick: () -> Unit = {},
    onClearAttachment: () -> Unit = {},
    showQuickPrompts: Boolean = false,
    quickPrompts: List<String> = emptyList(),
    onQuickPrompt: (String) -> Unit = {},
    replyingToMessage: ChatMessage? = null,
    onClearReply: () -> Unit = {}
) {
    Surface(
        color = EntropiColors.Background,
        border = BorderStroke(0.5.dp, EntropiColors.Border.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── Replying to preview ──────────────────────────────────────────
            AnimatedVisibility(visible = replyingToMessage != null) {
                if (replyingToMessage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(EntropiColors.Card)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .background(EntropiColors.Gold1, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (replyingToMessage.role == "user") "You" else "Skolar",
                                color = EntropiColors.Gold1,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyingToMessage.content,
                                color = EntropiColors.Text2,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onClearReply, modifier = Modifier.size(16.dp)) {
                            Icon(Icons.Default.Close, null, tint = EntropiColors.Text3, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }

            // ── Quick prompt chips (only when conversation is empty) ───────────
            AnimatedVisibility(
                visible = showQuickPrompts,
                enter = fadeIn(tween(300)) + expandVertically(),
                exit = fadeOut(tween(200)) + shrinkVertically()
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(quickPrompts) { prompt ->
                        Surface(
                            onClick = { onQuickPrompt(prompt) },
                            shape = RoundedCornerShape(16.dp),
                            color = EntropiColors.Card,
                            border = BorderStroke(0.5.dp, EntropiColors.Border)
                        ) {
                            Text(
                                text = prompt,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = EntropiColors.Text2,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ── Attached file chip ────────────────────────────────────────────
            AnimatedVisibility(visible = isAttachingFile || attachedFileName != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EntropiColors.Card)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, null, tint = EntropiColors.Gold1, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isAttachingFile) "Extracting…" else attachedFileName ?: "",
                        color = EntropiColors.Text2,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isAttachingFile) {
                        CircularProgressIndicator(
                            color = EntropiColors.Gold1,
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.5.dp
                        )
                    } else {
                        IconButton(onClick = onClearAttachment, modifier = Modifier.size(16.dp)) {
                            Icon(Icons.Default.Close, null, tint = EntropiColors.Text3, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }

            // ── Main input row ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 120.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(EntropiColors.Card)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach button
                IconButton(onClick = onAttachClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach",
                        tint = EntropiColors.Text3,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Text field
                BasicTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    textStyle = TextStyle(
                        color = EntropiColors.Text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(EntropiColors.Gold1),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    "Ask Skolar…",
                                    color = EntropiColors.Text3,
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // Send / Mic button — animated swap
                AnimatedContent(
                    targetState = text.isNotBlank(),
                    transitionSpec = {
                        (scaleIn(tween(150)) + fadeIn(tween(150))).togetherWith(
                            scaleOut(tween(100)) + fadeOut(tween(100))
                        )
                    },
                    label = "send_mic"
                ) { hasTex ->
                    if (hasTex) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier
                                .size(36.dp)
                                .background(EntropiColors.Gold1, CircleShape)
                                .padding(end = 2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = EntropiColors.Background,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        val localCtx = LocalContext.current
                        IconButton(
                            onClick = {
                                android.widget.Toast.makeText(localCtx, "Voice coming soon", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice",
                                tint = EntropiColors.Text3,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

// ── HISTORY BOTTOM SHEET ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHistoryBottomSheet(
    onDismissRequest: () -> Unit,
    sessions: List<Pair<String, String>>,
    currentSessionId: String,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearAllClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = EntropiColors.Background,
        dragHandle = { BottomSheetDefaults.DragHandle(color = EntropiColors.Border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversation History",
                    color = EntropiColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SyneFontFamily
                )
                TextButton(onClick = onClearAllClick) {
                    Text("Clear All", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No past conversations found.",
                        color = EntropiColors.Text3,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { (sId, title) ->
                        val isCurrent = sId == currentSessionId
                        Surface(
                            onClick = {
                                onSessionSelected(sId)
                                onDismissRequest()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) EntropiColors.Gold1.copy(alpha = 0.12f) else EntropiColors.Card,
                            border = if (isCurrent) BorderStroke(1.dp, EntropiColors.Gold1.copy(alpha = 0.6f)) else BorderStroke(0.5.dp, EntropiColors.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = if (isCurrent) EntropiColors.Gold1 else EntropiColors.Text3,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = title,
                                    color = if (isCurrent) EntropiColors.Gold1 else EntropiColors.Text,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onDeleteSession(sId) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete chat",
                                        tint = Color(0xFFEF5350).copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

