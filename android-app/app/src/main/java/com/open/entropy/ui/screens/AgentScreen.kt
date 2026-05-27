package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.open.entropy.auth.AuthManager
import com.open.entropy.network.ChatMessage
import com.open.entropy.ui.components.MarkdownText
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.AgentMode
import com.open.entropy.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.*

// ── Quick prompts shown above input when conversation is empty ──────────────
private val QUICK_PROMPTS = listOf(
    "Summarize my latest papers",
    "Find grant opportunities",
    "Who should I collaborate with?",
    "Analyze citation trends",
    "Write an abstract",
    "Compare methodologies"
)

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
    val isConversationEmpty = uiState.messages.isEmpty() && !uiState.isTyping

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
                }
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
                        viewModel.sendMessage(messageText)
                        messageText = ""
                    }
                },
                attachedFileName = uiState.attachedFileName,
                isAttachingFile = uiState.isAttachingFile,
                onAttachClick = { filePickerLauncher.launch("*/*") },
                onClearAttachment = { viewModel.clearAttachment() },
                showQuickPrompts = isConversationEmpty,
                onQuickPrompt = { prompt ->
                    viewModel.sendMessage(prompt)
                }
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
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Glowing icon
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
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = EntropiColors.Gold1,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "SkoLab Copilot",
                        color = EntropiColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = SyneFontFamily
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Your AI research partner.\nAsk anything about your field.",
                        color = EntropiColors.Text3,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
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
                        AgentMessageBubble(message = msg, isMe = isMe)
                    }
                    if (uiState.isTyping) {
                        item { AgentTypingIndicator() }
                    }
                }
            }
        }
    }
}

// ── TOP BAR ──────────────────────────────────────────────────────────────────
@Composable
fun AgentTopBar(currentProject: String, activeMode: AgentMode, onModeToggle: () -> Unit) {
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
            // AI avatar dot
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                EntropiColors.Gold1.copy(alpha = 0.3f),
                                EntropiColors.Card
                            )
                        ),
                        CircleShape
                    )
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = EntropiColors.Gold1, modifier = Modifier.size(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Copilot",
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
        }
    }
}

// ── MESSAGE BUBBLE ────────────────────────────────────────────────────────────
@Composable
fun AgentMessageBubble(message: ChatMessage, isMe: Boolean) {
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
        // AI avatar on the left
        if (!isMe) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(EntropiColors.Card, CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = EntropiColors.Gold1, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 290.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
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

            // Timestamp row
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
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
                .background(EntropiColors.Card, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = EntropiColors.Gold1, modifier = Modifier.size(13.dp))
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
    onQuickPrompt: (String) -> Unit = {}
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
                    items(QUICK_PROMPTS) { prompt ->
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
                                    "Ask Copilot…",
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
