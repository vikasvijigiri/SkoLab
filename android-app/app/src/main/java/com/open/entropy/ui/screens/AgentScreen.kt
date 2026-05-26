package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val userUid = cachedUser?.uid ?: ""

    if (userUid.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(EntropiColors.Background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EntropiColors.Gold1)
        }
        return
    }

    val viewModel: AgentViewModel = viewModel(
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

    LaunchedEffect(uiState.messages.size, uiState.isTyping) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = EntropiColors.Background, // Deep Navy Background
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
                onClearAttachment = { viewModel.clearAttachment() }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    // Subtle glowing topology divider effect at the top
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(EntropiColors.Gold1.copy(alpha = 0.05f), Color.Transparent),
                            startY = 0f,
                            endY = 200f
                        )
                    )
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                items(uiState.messages) { msg ->
                    val isMe = msg.role == "user"
                    AgentMessageBubble(message = msg, isMe = isMe)
                }

                if (uiState.isTyping) {
                    item {
                        AgentTypingIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun AgentTopBar(currentProject: String, activeMode: AgentMode, onModeToggle: () -> Unit) {
    Surface(
        color = EntropiColors.Background.copy(alpha = 0.95f),
        border = BorderStroke(0.5.dp, EntropiColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = EntropiColors.Gold1, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Copilot",
                color = EntropiColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onModeToggle,
                color = if (activeMode == AgentMode.CODING) EntropiColors.Cyan.copy(alpha = 0.1f) else EntropiColors.Gold1.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (activeMode == AgentMode.CODING) EntropiColors.Cyan.copy(alpha = 0.3f) else EntropiColors.Gold1.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (activeMode == AgentMode.CODING) Icons.Default.Code else Icons.Default.Science, null, modifier = Modifier.size(12.dp), tint = if (activeMode == AgentMode.CODING) EntropiColors.Cyan else EntropiColors.Gold1)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = activeMode.name,
                        color = if (activeMode == AgentMode.CODING) EntropiColors.Cyan else EntropiColors.Gold1,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AgentMessageBubble(message: ChatMessage, isMe: Boolean) {
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val timestamp = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isMe) EntropiColors.Gold1.copy(alpha = 0.15f) else EntropiColors.Card,
            border = BorderStroke(1.dp, if (isMe) EntropiColors.Gold1.copy(alpha = 0.3f) else EntropiColors.Border),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                MarkdownText(
                    markdown = message.content,
                    color = if (isMe) EntropiColors.Text else EntropiColors.Text2,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timestamp,
                        color = EntropiColors.Text3,
                        fontSize = 10.sp
                    )
                    if (isMe) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Sent",
                            tint = EntropiColors.Gold1.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentTypingIndicator() {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier.widthIn(max = 200.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = EntropiColors.Gold1, modifier = Modifier.size(14.dp))
            Text(
                text = "Synthesizing research",
                color = EntropiColors.Text2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            val alpha1 by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f, label = "dot1",
                animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse)
            )
            val alpha2 by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f, label = "dot2",
                animationSpec = infiniteRepeatable(tween(400, delayMillis = 200, easing = LinearEasing), RepeatMode.Reverse)
            )
            val alpha3 by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f, label = "dot3",
                animationSpec = infiniteRepeatable(tween(400, delayMillis = 400, easing = LinearEasing), RepeatMode.Reverse)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(EntropiColors.Gold1.copy(alpha = alpha1)))
                Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(EntropiColors.Gold1.copy(alpha = alpha2)))
                Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(EntropiColors.Gold1.copy(alpha = alpha3)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    attachedFileName: String? = null,
    isAttachingFile: Boolean = false,
    onAttachClick: () -> Unit = {},
    onClearAttachment: () -> Unit = {}
) {
    Surface(color = EntropiColors.Background, border = BorderStroke(1.dp, EntropiColors.Border)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (isAttachingFile || attachedFileName != null) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp, end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EntropiColors.Card)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AttachFile, null, tint = EntropiColors.Gold1, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isAttachingFile) "Extracting text..." else attachedFileName ?: "",
                        color = EntropiColors.Text2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isAttachingFile) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(color = EntropiColors.Gold1, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onClearAttachment, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, null, tint = EntropiColors.Text3, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp, max = 120.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(EntropiColors.Card)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttachClick, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, null, tint = EntropiColors.Text3)
                }
                TextField(
                    value = text,
                    onValueChange = onTextChanged,
                    placeholder = { Text("Message Copilot...", color = EntropiColors.Text3, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = EntropiColors.Gold1,
                        focusedTextColor = EntropiColors.Text,
                        unfocusedTextColor = EntropiColors.Text
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (text.isNotBlank()) {
                    IconButton(onClick = onSend, modifier = Modifier.size(40.dp).padding(end=4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = EntropiColors.Gold1, modifier = Modifier.size(20.dp))
                    }
                } else {
                    val localCtx = LocalContext.current
                    IconButton(onClick = {
                        android.widget.Toast.makeText(localCtx, "Voice coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(40.dp).padding(end=4.dp)) {
                        Icon(Icons.Default.Mic, null, tint = EntropiColors.Text3, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
