package com.open.entropy.ui.components

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.network.ApiService
import com.open.entropy.network.ChatMessage
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MessageStatus {
    SENT, DELIVERED, READ
}

data class UiChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: String,
    var status: MessageStatus = MessageStatus.SENT
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorChatBottomSheet(
    authorId: String,
    authorName: String,
    paperTitle: String,
    onDismissRequest: () -> Unit,
    apiService: ApiService = remember { ApiService() }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages = remember {
        mutableStateListOf(
            UiChatMessage(
                role = "assistant",
                content = "Hi there! I'm $authorName. Thanks for your interest in my research paper: \"$paperTitle\". How can I help you today?",
                timestamp = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                status = MessageStatus.READ
            )
        )
    }

    var textInput by remember { mutableStateOf("") }
    var isAuthorTyping by remember { mutableStateOf(false) }

    // Scroll to bottom when messages list size changes or typing status changes
    LaunchedEffect(messages.size, isAuthorTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = BgPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderMedium) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 1. WhatsApp styled Top Bar / Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Author Avatar Circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AccentTeal.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    val initials = authorName.split(" ")
                        .filter { it.isNotEmpty() }
                        .take(2)
                        .map { it.first().uppercase() }
                        .joinToString("")
                    Text(
                        text = initials.ifEmpty { "A" },
                        color = AccentTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Author Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authorName,
                        style = Typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Green pulsing online indicator
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF25D366))
                        )
                        Text(
                            text = "Online",
                            style = Typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Header Icons (close sheet)
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            // Separator line
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderLight)
            )

            // 2. Sticky Referenced Paper Context Banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = BgCard,
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "Paper Context",
                        tint = AccentTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DISCUSSING PUBLICATION",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = paperTitle,
                            style = Typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Scrollable Message Feed
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
                ) {
                    itemsIndexed(messages) { index, msg ->
                        ChatBubble(message = msg)
                    }

                    if (isAuthorTyping) {
                        item {
                            TypingIndicatorBubble(authorName = authorName)
                        }
                    }
                }
            }

            // 4. Bottom WhatsApp-Style Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Input TextField Card
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = BgPrimary,
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Chat",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = {
                                Text(
                                    text = "Ask about this paper...",
                                    color = TextMuted,
                                    fontSize = 14.sp
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            textStyle = Typography.bodyMedium.copy(color = TextPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = 120.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (textInput.isNotBlank() && !isAuthorTyping) {
                                    sendMessage(
                                        text = textInput,
                                        authorId = authorId,
                                        paperTitle = paperTitle,
                                        apiService = apiService,
                                        onSent = { textInput = "" },
                                        onAddMessage = { messages.add(it) },
                                        onUpdateTyping = { isAuthorTyping = it },
                                        currentHistory = messages.toList()
                                    )
                                }
                            })
                        )
                    }
                }

                // Send Circle Button
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (textInput.isBlank() || isAuthorTyping) BorderMedium else Color(0xFF128C7E))
                        .clickable(enabled = textInput.isNotBlank() && !isAuthorTyping) {
                            sendMessage(
                                text = textInput,
                                authorId = authorId,
                                paperTitle = paperTitle,
                                apiService = apiService,
                                onSent = { textInput = "" },
                                onAddMessage = { messages.add(it) },
                                onUpdateTyping = { isAuthorTyping = it },
                                currentHistory = messages.toList()
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun sendMessage(
    text: String,
    authorId: String,
    paperTitle: String,
    apiService: ApiService,
    onSent: () -> Unit,
    onAddMessage: (UiChatMessage) -> Unit,
    onUpdateTyping: (Boolean) -> Unit,
    currentHistory: List<UiChatMessage>
) {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty()) return

    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    val userMsg = UiChatMessage(
        role = "user",
        content = trimmedText,
        timestamp = formatter.format(Date()),
        status = MessageStatus.SENT
    )

    onAddMessage(userMsg)
    onSent()

    // Run async API call and ticks simulation in global scope but safe handlers
    // Normally would use a ViewModel, but in Compose BottomSheet we can utilize a LaunchedEffect-like flow
    // by spawning on the API caller. Since this is an action, we do it in a background coroutine:
    CoroutineScopeHelper.launch {
        // Ticks animation
        delay(600)
        userMsg.status = MessageStatus.DELIVERED
        // trigger list update by forcing state notification
        onAddMessage(UiChatMessage("", "", "", MessageStatus.SENT)) // Dummy trigger
        // remove dummy
        // A cleaner way is using state fields, but mutableStateListOf detects item reference replacement.
        // Let's just simulate the ticks and trigger recompositions:
        
        delay(600)
        userMsg.status = MessageStatus.READ
        onUpdateTyping(true)

        // Fetch response
        try {
            // Map ui messages history to network format
            val apiHistory = currentHistory
                .filter { it.content.isNotEmpty() }
                .takeLast(6)
                .map { ChatMessage(role = it.role, content = it.content) }

            val response = apiService.chatWithAuthor(
                authorId = authorId,
                paperTitle = paperTitle,
                userMessage = trimmedText,
                history = apiHistory
            )

            // Let typing indicator run for at least 1.2s to look natural
            delay(1200)

            val reply = response?.reply ?: "Sorry, I am having trouble connecting to the research portal. Let's discuss this later."
            val authorMsg = UiChatMessage(
                role = "assistant",
                content = reply,
                timestamp = formatter.format(Date()),
                status = MessageStatus.READ
            )
            onAddMessage(authorMsg)
        } catch (e: Exception) {
            Log.e("AuthorChat", "API call failed", e)
            val errorMsg = UiChatMessage(
                role = "assistant",
                content = "Could you rephrase that? I didn't get your connection.",
                timestamp = formatter.format(Date()),
                status = MessageStatus.READ
            )
            onAddMessage(errorMsg)
        } finally {
            onUpdateTyping(false)
        }
    }
}

// Global scope helper for actions triggered from pure button clicks
object CoroutineScopeHelper {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}

@Composable
fun ChatBubble(message: UiChatMessage) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) Color(0xFFDCF8C6) else Color.White
    val alignment = if (isUser) Alignment.End else Alignment.Start

    if (message.content.isEmpty()) return // Skip empty trigger messages

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
            } else {
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
            },
            color = bubbleColor,
            border = BorderStroke(1.dp, if (isUser) Color(0xFFC7E5AE) else BorderLight),
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    style = Typography.bodyMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                
                // Timestamp & Checkmarks Row
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = message.timestamp,
                        fontSize = 9.sp,
                        color = TextMuted
                    )
                    if (isUser) {
                        StatusCheckmarks(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCheckmarks(status: MessageStatus) {
    val icon = when (status) {
        MessageStatus.SENT -> Icons.Default.Check
        MessageStatus.DELIVERED -> Icons.Default.DoneAll
        MessageStatus.READ -> Icons.Default.DoneAll
    }
    val tint = if (status == MessageStatus.READ) Color(0xFF34B7F1) else TextMuted
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = Modifier.size(14.dp)
    )
}

@Composable
fun TypingIndicatorBubble(authorName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp),
            color = Color.White,
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.widthIn(max = 200.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Pulse Animation / Typing text
                Text(
                    text = "$authorName is typing",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                
                // Simple dot animations in pure Compose
                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                val alpha1 by infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot1"
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(TextMuted.copy(alpha = alpha1)))
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(TextMuted))
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(TextMuted.copy(alpha = 1f - alpha1)))
                }
            }
        }
    }
}
