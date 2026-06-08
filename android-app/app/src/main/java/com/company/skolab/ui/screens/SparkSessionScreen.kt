package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.model.SparkMessage
import com.company.skolab.model.SparkSession
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.SparkViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkSessionScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    viewModel: SparkViewModel = viewModel()
) {
    val context = LocalContext.current
    val authManager = com.company.skolab.di.AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUserId = cachedUser?.uid ?: ""
    val currentUserName = cachedUser?.name ?: "Researcher"

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session = uiState.activeSession
    val chatMessages = uiState.chatMessages

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Trigger listeners
    LaunchedEffect(sessionId) {
        viewModel.listenToActiveSession(sessionId)
    }

    // Scroll chat to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // Local scratchpad state with debounced writing to Firestore
    var localScratchpad by remember { mutableStateOf("") }
    LaunchedEffect(session?.scratchpadContent) {
        if (session != null && session.scratchpadContent != localScratchpad) {
            localScratchpad = session.scratchpadContent
        }
    }

    // Debouncer for scratchpad writes
    LaunchedEffect(localScratchpad) {
        if (session != null && localScratchpad != session.scratchpadContent) {
            delay(1500) // wait for 1.5s pause in typing
            viewModel.updateScratchpad(localScratchpad)
        }
    }

    if (session == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentTeal)
        }
        return
    }

    val isSeeker = session.seekerId == currentUserId

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Spark Help Session",
                            style = Typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = session.topic,
                            fontSize = 11.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            viewModel.cancelSession()
                            viewModel.clearSessionState()
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Ephemeral indicator badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SlateCardBg,
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (session.status == "ACTIVE") AccentEmerald else AccentOrange,
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (session.status == "ACTIVE") "LIVE" else session.status,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (session.status == "ACTIVE") AccentEmerald else AccentOrange
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ── HEADER: PARTICIPANTS & TIMER PANEL ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgCard)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Person, null, tint = AccentTeal, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Seeker: ${session.seekerName.split(" ").firstOrNull() ?: "User"}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.SupportAgent, null, tint = AccentViolet, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Helper: ${session.helperName?.split(" ")?.firstOrNull() ?: "Waiting..."}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Circular countdown timer widget
                    SparkCountdownTimer(remainingSeconds = session.timerRemainingSeconds)
                }

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

                // ── TOP SPLIT: SHARED SCRATCHPAD ──
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, tint = AccentTeal, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Shared Scratchpad",
                                style = Typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Auto-syncs",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SlateCardBg.copy(alpha = 0.5f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        TextField(
                            value = localScratchpad,
                            onValueChange = { localScratchpad = it },
                            placeholder = { Text("Paste code snippets, equations, or collaborative notes here...", color = TextMuted, fontSize = 12.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = AccentTeal
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

                // ── BOTTOM SPLIT: EPHEMERAL CHAT STREAM ──
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxWidth()
                        .background(BgPrimary.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "EPHEMERAL Spark Chat",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentIndigo,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chatMessages, key = { it.id }) { message ->
                            val isMe = message.senderId == currentUserId
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isMe) 16.dp else 4.dp,
                                        bottomEnd = if (isMe) 4.dp else 16.dp
                                    ),
                                    color = if (isMe) AccentTeal.copy(alpha = 0.15f) else SlateCardBg,
                                    border = BorderStroke(
                                        width = 0.5.dp,
                                        color = if (isMe) AccentTeal.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier.widthIn(max = 280.dp)
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        if (!isMe) {
                                            Text(
                                                text = message.senderName.split(" ").firstOrNull() ?: "",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentTeal
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                        }
                                        Text(
                                            text = message.messageText,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Chat Input Row
                    var messageInput by remember { mutableStateOf("") }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgCard)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = messageInput,
                            onValueChange = { messageInput = it },
                            placeholder = { Text("Ask a question or offer code help...", color = TextMuted, fontSize = 12.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SlateCardBg,
                                unfocusedContainerColor = SlateCardBg,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = AccentTeal
                            ),
                            shape = RoundedCornerShape(24.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = 80.dp)
                        )

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(AccentTeal, CircleShape)
                                .clickable {
                                    if (messageInput.isNotBlank()) {
                                        viewModel.sendChatMessage(messageInput, currentUserId, currentUserName)
                                        messageInput = ""
                                        keyboardController?.hide()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ── OVERLAYS: COMPLETION STATE DRAWER ──
            if (session.status == "RESOLVED" || session.status == "CANCELLED") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    SparkSessionCompletionCard(
                        session = session,
                        isSeeker = isSeeker,
                        onClose = {
                            viewModel.clearSessionState()
                            onNavigateBack()
                        }
                    )
                }
            }
        }
    }
}

// ── SUB-COMPONENT: GLOWING COUNTDOWN TIMER ──
@Composable
fun SparkCountdownTimer(remainingSeconds: Int) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)
    
    val progress = (remainingSeconds / 600f).coerceIn(0f, 1f)
    val color = when {
        remainingSeconds < 60 -> AccentOrange
        remainingSeconds < 180 -> AccentAmber
        else -> AccentTeal
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp)
    ) {
        CircularProgressIndicator(
            progress = progress,
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f),
            strokeWidth = 3.5.dp,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = timeText,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = SpaceGroteskFontFamily
        )
    }
}

// ── SUB-COMPONENT: COMPLETION STATUS PANEL ──
@Composable
fun SparkSessionCompletionCard(
    session: SparkSession,
    isSeeker: Boolean,
    onClose: () -> Unit
) {
    var rating by remember { mutableStateOf(5) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = BgCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (session.status == "RESOLVED") AccentEmerald.copy(alpha = 0.1f) else AccentOrange.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (session.status == "RESOLVED") Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (session.status == "RESOLVED") AccentEmerald else AccentOrange,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = if (session.status == "RESOLVED") "Spark Resolved!" else "Spark Cancelled",
                style = Typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontFamily = SpaceGroteskFontFamily
            )

            Text(
                text = if (session.status == "RESOLVED") {
                    "Dynamic ride complete. ${session.bounty} has been locked and credited."
                } else {
                    "The ride help request was closed or cancelled by the user."
                },
                color = TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            if (session.status == "RESOLVED" && !isSeeker) {
                Text(
                    text = "Congratulations! You earned ${session.bounty}!",
                    color = AccentEmerald,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Rating Stars
            if (session.status == "RESOLVED") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Rate your Spark Collaborator", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (i in 1..5) {
                            Icon(
                                imageVector = if (i <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (i <= rating) StarGold else TextMuted,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { rating = i }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (session.status == "RESOLVED") AccentTeal else SlateBorder,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Return to Dashboard",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (session.status == "RESOLVED") Color.Black else Color.White
                )
            }
        }
    }
}
