package com.open.skolab.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.network.ApiService
import com.open.skolab.network.ChatMessage
import com.open.skolab.data.ChatStorage
import com.open.skolab.auth.AuthManager
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.open.skolab.ui.components.MarkdownText
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    peerName: String,
    peerId: String,
    onBack: () -> Unit
) {
    val apiService = remember { ApiService() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    var messageText by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isPeerTyping by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val userUid = cachedUser?.uid ?: ""
    val chatStorage = remember(userUid) { if (userUid.isNotEmpty()) ChatStorage(context, userUid) else null }

    // Initials color
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(peerId.hashCode()) % avatarColors.size]

    // Load initial greeting message or history
    LaunchedEffect(peerId, chatStorage) {
        if (chatStorage != null) {
            val history = chatStorage.getChatHistory(peerId)
            if (history.isEmpty()) {
                val initial = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "Hi there! I am modeled after $peerName's research profile. Ask me anything about my publication topics or methodologies!"
                    )
                )
                chatStorage.saveChatHistory(peerId, initial)
                chatHistory = initial
            } else {
                chatHistory = history
            }
        }
    }

    // Scroll to bottom when history updates
    LaunchedEffect(chatHistory.size, isPeerTyping) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = BgPrimary,
        topBar = {
            Surface(
                color = BgCard,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.12f))
                            .background(color.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peerName.take(1).uppercase(),
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = DisplayFontFamily
                        )
                    }
                    
                    Spacer(Modifier.width(10.dp))
                    
                    // Title/Subtitle
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peerName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = DisplayFontFamily
                        )
                        Text(
                            text = if (isPeerTyping) "typing..." else "Online",
                            color = if (isPeerTyping) AccentTeal else AccentEmerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    // Action icons
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Videocam, null, tint = TextSecondary)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Call, null, tint = TextSecondary)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(chatHistory) { msg ->
                    val isMe = msg.role == "user"
                    ChatBubble(message = msg, isMe = isMe)
                }
                
                if (isPeerTyping) {
                    item {
                        TypingIndicator(peerName = peerName)
                    }
                }
            }

            // Input Bar
            Surface(
                color = BgCard,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Rounded Text Field
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 100.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(BgElevated)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.SentimentSatisfiedAlt, null, tint = TextMuted)
                        }
                        
                        Spacer(Modifier.width(4.dp))
                        
                        BasicTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            textStyle = TextStyle(
                                color = TextPrimary, 
                                fontSize = 14.sp,
                                fontFamily = DisplayFontFamily
                            ),
                            cursorBrush = SolidColor(TextPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (messageText.isEmpty()) {
                                        Text("Message", color = TextMuted, fontSize = 14.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        
                        Spacer(Modifier.width(4.dp))
                        
                        IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.AttachFile, null, tint = TextMuted)
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.CameraAlt, null, tint = TextMuted)
                        }
                    }
                    
                    // Circular Green Send FAB
                    FloatingActionButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                val userMsg = messageText.trim()
                                val updatedHistory = chatHistory + ChatMessage(role = "user", content = userMsg)
                                chatHistory = updatedHistory
                                chatStorage?.saveChatHistory(peerId, updatedHistory)
                                messageText = ""
                                
                                // Trigger peer reply
                                scope.launch {
                                    delay(600)
                                    isPeerTyping = true
                                    val response = apiService.chatWithAuthor(
                                        authorId = peerId,
                                        paperTitle = "General Discussion",
                                        userMessage = userMsg,
                                        history = updatedHistory.takeLast(10)
                                    )
                                    delay(800)
                                    isPeerTyping = false
                                    if (response != null && !response.reply.isNullOrBlank()) {
                                        val finalHistory = chatHistory + ChatMessage(role = "assistant", content = response.reply)
                                        chatHistory = finalHistory
                                        chatStorage?.saveChatHistory(peerId, finalHistory)
                                    } else {
                                        val fallback = chatHistory + ChatMessage(
                                            role = "assistant",
                                            content = "I've considered your point. Based on my research models, that's an area with significant convergence potential."
                                        )
                                        chatHistory = fallback
                                        chatStorage?.saveChatHistory(peerId, fallback)
                                    }
                                }
                            }
                        },
                        containerColor = AccentEmerald,
                        contentColor = TextOnAccent,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, isMe: Boolean) {
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
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
            color = if (isMe) AccentTeal.copy(alpha = 0.12f) else BgCard,
            border = if (isMe) BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f)) else BorderStroke(1.dp, BorderLight),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                MarkdownText(
                    markdown = message.content,
                    color = if (isMe) TextPrimary else TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timestamp,
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                    if (isMe) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Read",
                            tint = AccentTeal,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator(peerName: String) {
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.widthIn(max = 200.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$peerName is typing",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f, label = "dotAlpha",
                animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse)
            )
            Text(
                text = "...",
                color = AccentTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.scale(alpha)
            )
        }
    }
}

private fun Modifier.scale(scale: Float) = this.scale(scale, scale)
private fun Modifier.scale(scaleX: Float, scaleY: Float) = this.graphicsLayer(scaleX = scaleX, scaleY = scaleY)
