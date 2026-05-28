package com.open.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.open.skolab.ui.components.MessageBubbleWrapper
import com.open.skolab.ui.components.ReactionBadge
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import android.media.ToneGenerator
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.ScreenShare

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
    
    var isPeerSkoLab by remember { mutableStateOf(false) }
    var checkingSkoLabStatus by remember { mutableStateOf(true) }

    LaunchedEffect(peerId) {
        if (peerId.isNotEmpty() &&
            !peerId.startsWith("https://openalex.org/") &&
            peerId != "sumiran_uid" &&
            peerId != "nisheeta_uid" &&
            peerId != "paulson_uid" &&
            peerId != "saptarshi_uid" &&
            peerId != "you_uid" &&
            peerId != "default_owner"
        ) {
            checkingSkoLabStatus = true
            try {
                val db = FirebaseFirestore.getInstance()
                val doc = db.collection("researchers").document(peerId).get().await()
                isPeerSkoLab = doc.exists()
            } catch (e: Exception) {
                isPeerSkoLab = false
            } finally {
                checkingSkoLabStatus = false
            }
        } else {
            isPeerSkoLab = false
            checkingSkoLabStatus = false
        }
    }

    // Video Call simulation
    var showCallingOverlay by remember { mutableStateOf(false) }
    var callingOverlayMode by remember { mutableStateOf("voice") } // "voice" or "video"
    var callConnectedTime by remember { mutableStateOf(0) }
    var callState by remember { mutableStateOf("calling") } // "calling", "ringing", "connected", "disconnected"
    var micMuted by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(true) }
    var speakerOn by remember { mutableStateOf(false) }

    // Ringing sound simulation using ToneGenerator
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay && (callState == "calling" || callState == "ringing")) {
            val toneGenerator = try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            } catch (e: Exception) {
                null
            }
            if (toneGenerator != null) {
                scope.launch {
                    try {
                        while (showCallingOverlay && (callState == "calling" || callState == "ringing")) {
                            toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                            delay(2000)
                            toneGenerator.stopTone()
                            delay(3000)
                        }
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        toneGenerator.release()
                    }
                }
            }
        }
    }

    // Auto-connect call simulation after a few seconds
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay) {
            if (callState == "calling") {
                delay(2000)
                callState = "ringing"
            }
            if (callState == "ringing") {
                delay(3000)
                callState = "connected"
                callConnectedTime = 0
            }
        }
    }

    // Call active timer
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay && callState == "connected") {
            while (showCallingOverlay && callState == "connected") {
                delay(1000)
                callConnectedTime++
            }
        }
    }

    var showNonSkoLabDialog by remember { mutableStateOf(false) }
    var selectedCallTypeForDialog by remember { mutableStateOf("voice") }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    IconButton(
                        onClick = {
                            if (isPeerSkoLab) {
                                callingOverlayMode = "video"
                                callState = "calling"
                                showCallingOverlay = true
                            } else {
                                selectedCallTypeForDialog = "video"
                                showNonSkoLabDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video Call",
                            tint = if (isPeerSkoLab) AccentTeal else TextSecondary.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isPeerSkoLab) {
                                callingOverlayMode = "voice"
                                callState = "calling"
                                showCallingOverlay = true
                            } else {
                                selectedCallTypeForDialog = "voice"
                                showNonSkoLabDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Voice Call",
                            tint = if (isPeerSkoLab) AccentTeal else TextSecondary.copy(alpha = 0.38f)
                        )
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
                    ChatBubble(
                        message = msg,
                        isMe = isMe,
                        onReact = { emoji ->
                            val updated = chatHistory.map {
                                if (it == msg) it.copy(reaction = if (it.reaction == emoji) null else emoji) else it
                            }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        },
                        onReply = {
                            replyingToMessage = msg
                        },
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.content))
                        },
                        onToggleStar = {
                            val updated = chatHistory.map {
                                if (it == msg) it.copy(isStarred = !it.isStarred) else it
                            }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        },
                        onDelete = {
                            val updated = chatHistory.filter { it != msg }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        }
                    )
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (replyingToMessage != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgElevated)
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left accent bar
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(AccentTeal, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (replyingToMessage!!.role == "user") "You" else peerName,
                                    color = AccentTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replyingToMessage!!.content,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

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
                                    val userMsg = if (replyingToMessage != null) {
                                        "> ${replyingToMessage!!.content.replace("\n", "\n> ")}\n\n${messageText.trim()}"
                                    } else {
                                        messageText.trim()
                                    }
                                    replyingToMessage = null
                                    
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

    if (showNonSkoLabDialog) {
        AlertDialog(
            onDismissRequest = { showNonSkoLabDialog = false },
            title = {
                Text(
                    text = "Profile Unclaimed",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AccentTeal
                )
            },
            text = {
                Text(
                    text = "$peerName hasn't claimed their SkoLab profile yet. Invite them to join and activate secure encrypted audio/video calling!\n\nFor evaluation, you can launch a simulated demo call.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNonSkoLabDialog = false
                        callingOverlayMode = selectedCallTypeForDialog
                        callState = "calling"
                        showCallingOverlay = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentTeal)
                ) {
                    Text("Start Demo Call", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNonSkoLabDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    AnimatedVisibility(
        visible = showCallingOverlay,
        enter = fadeIn(animationSpec = tween(500)) + expandIn(expandFrom = Alignment.Center),
        exit = fadeOut(animationSpec = tween(500)) + shrinkOut(shrinkTowards = Alignment.Center)
    ) {
        SkoLabCallingOverlay(
            peerName = peerName,
            mode = callingOverlayMode,
            callState = callState,
            callConnectedTime = callConnectedTime,
            micMuted = micMuted,
            cameraOn = cameraOn,
            speakerOn = speakerOn,
            color = color,
            onMuteToggle = { micMuted = !micMuted },
            onCameraToggle = { cameraOn = !cameraOn },
            onSpeakerToggle = { speakerOn = !speakerOn },
            onEndCall = {
                callState = "disconnected"
                scope.launch {
                    delay(500)
                    showCallingOverlay = false
                }
            },
            onJoinRealJitsi = {
                val roomName = "SkoLabSecure_" + peerId.hashCode().toString()
                val jitsiUrl = "https://meet.jit.si/$roomName"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(jitsiUrl))
                context.startActivity(intent)
            }
        )
    }
}
}
}



@Composable
fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit
) {
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
        MessageBubbleWrapper(
            message = message,
            onReact = onReact,
            onReply = onReply,
            onCopy = onCopy,
            onToggleStar = onToggleStar,
            onDelete = onDelete
        ) {
            Box {
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
                            if (message.isStarred) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Starred",
                                    tint = AccentAmber,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
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

@Composable
fun SkoLabCallingOverlay(
    peerName: String,
    mode: String,
    callState: String,
    callConnectedTime: Int,
    micMuted: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    color: Color,
    onMuteToggle: () -> Unit,
    onCameraToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit,
    onJoinRealJitsi: () -> Unit
) {
    val initials = peerName.split(" ").map { it.take(1) }.joinToString("").uppercase()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        color = Color(0xFF0C1013).copy(alpha = 0.96f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Secure Indicator
            Surface(
                color = Color(0xFF1B3B2B),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure",
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End-to-End Encrypted SkoLab Call",
                        color = Color(0xFF25D366),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Peer info & Ringing / Calling state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsating Avatar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(180.dp)
                ) {
                    if (callState == "calling" || callState == "ringing") {
                        Box(
                            modifier = Modifier
                                .size((120 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.15f))
                        )
                        Box(
                            modifier = Modifier
                                .size((140 * pulseScale).dp)
                                .clip(CircleShape)
                                .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.25f))
                            .border(2.dp, color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = when (callState) {
                        "calling" -> "SkoLab secure calling..."
                        "ringing" -> "Ringing..."
                        "connected" -> "Connected"
                        else -> "Disconnected"
                    },
                    color = if (callState == "connected") Color(0xFF25D366) else Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (callState == "connected") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Real Voice/Video Jitsi transition trigger
            if (callState == "connected") {
                Button(
                    onClick = onJoinRealJitsi,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Real Voice/Video Room",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Controls Panel
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Mic Button
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (micMuted) Color(0xFFEA0038) else Color(0xFF2E3B43))
                ) {
                    Icon(
                        imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                // Video/Camera Button
                if (mode == "video") {
                    IconButton(
                        onClick = onCameraToggle,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (!cameraOn) Color(0xFFEA0038) else Color(0xFF2E3B43))
                    ) {
                        Icon(
                            imageVector = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Camera",
                            tint = Color.White
                        )
                    }
                }

                // Speaker Button
                IconButton(
                    onClick = onSpeakerToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (speakerOn) AccentTeal else Color(0xFF2E3B43))
                ) {
                    Icon(
                        imageVector = if (speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        contentDescription = "Speaker",
                        tint = Color.White
                    )
                }

                // Red Hang-up button
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEA0038))
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Hang Up",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
