package com.open.entropy.ui.screens

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
import com.open.entropy.ui.theme.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.open.entropy.ui.components.MarkdownText

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
    onBack: () -> Unit
) {
    var activeTab by remember { mutableStateOf("Chat") } // Chat, Equation, Manuscript, Meetings
    var showVideoCall by remember { mutableStateOf(false) }
    var userMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Real-time editable LaTeX Equation
    var latexEquation by remember { mutableStateOf("\\mathcal{H} = J \\sum_{\\langle i,j \\rangle} \\mathbf{S}_i \\cdot \\mathbf{S}_j - D \\sum_i (S_i^z)^2") }
    var isCollaboratorTypingEquation by remember { mutableStateOf(false) }

    // Manuscript draft text
    var manuscriptDraft by remember { mutableStateOf("""
\section{Introduction}
We investigate the deconfined pseudocriticality in a model spin-1 quantum antiferromagnet on a square lattice.
By utilizing continuous unitary transformations, we compute the ground state many-body entanglement...
\begin{equation}
S_E = -\text{Tr}(\rho_A \ln \rho_A)
\end{equation}
The pseudocritical behavior is verified near twist angle $\theta \approx 0.045$ rad.
    """.trimIndent()) }

    // Simulated Chat Messages involving actual co-authors from Vikas's publications
    val chatMessages = remember {
        mutableStateListOf(
            CoLabMessage("System", false, "Sumiran Pujari and Nisheeta Desai joined the Workspace.", "10:14 AM", isSystem = true),
            CoLabMessage("Sumiran Pujari", false, "Vikas, I looked over the spin-1 antiferromagnet Hamiltonian again. Did you add the single-ion anisotropy term \$D(S_i^z)^2?", "10:15 AM"),
            CoLabMessage("Nisheeta Desai", false, "I ran the DMRG check on the \$12 \\times 12$ square lattice. The entanglement entropy is showing an anomalous scaling near the phase boundary.", "10:17 AM"),
            CoLabMessage("You", true, "Yes, Sumiran. I updated the Blackboard equation. Let me know if the sign for anisotropy matches the perturbation theory.", "10:20 AM"),
            CoLabMessage("Sumiran Pujari", false, "Excellent. I am modifying the Blackboard LaTeX formula now to include the transverse magnetic field parameter.", "10:22 AM")
        )
    }

    // Typing simulation for collaboration feel
    LaunchedEffect(Unit) {
        delay(4000)
        isCollaboratorTypingEquation = true
        delay(3000)
        latexEquation += " - h_x \\sum_i S_i^x"
        isCollaboratorTypingEquation = false
        chatMessages.add(CoLabMessage("Sumiran Pujari", false, "Added the transverse field \$h_x \\sum S_i^x to the Blackboard equation. Take a look!", "10:25 AM"))

        delay(12000)
        chatMessages.add(CoLabMessage("Nisheeta Desai", false, "Perfect. The scaling exponent $\\eta \\approx 0.35$ fits the deconfined criticality model within error bars.", "10:30 AM"))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary) // Sleek WhatsApp Dark Mode Bg
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(
                    color = BgCard,
                    border = BorderStroke(0.5.dp, BorderLight),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                text = projectName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                fontFamily = DisplayFontFamily
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(AccentEmerald, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "3 active co-authors",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Call Action Button
                        Button(
                            onClick = { showVideoCall = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.VideoCall, contentDescription = "Join Call", tint = TextOnAccent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Join Call", color = TextOnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                // Workspace Navigation Tab Rows (WhatsApp-inspired flat styling)
                SecondaryTabRow(
                    selectedTabIndex = when(activeTab) {
                        "Chat" -> 0
                        "Equation" -> 1
                        "Manuscript" -> 2
                        "Meetings" -> 3
                        else -> 0
                    },
                    containerColor = BgPrimary,
                    contentColor = AccentTeal
                ) {
                    val tabs = listOf(
                        Triple("Chat", Icons.Default.Forum, "Chat"),
                        Triple("Equation", Icons.Default.Gesture, "Blackboard"),
                        Triple("Manuscript", Icons.Default.EditNote, "Draft"),
                        Triple("Meetings", Icons.Default.CalendarMonth, "Syncs")
                    )
                    tabs.forEach { (tabId, icon, label) ->
                        Tab(
                            selected = activeTab == tabId,
                            onClick = { activeTab = tabId },
                            text = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) },
                            selectedContentColor = AccentTeal,
                            unselectedContentColor = TextMuted
                        )
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
                                    chatMessages.add(CoLabMessage("You", true, userMessage, "Just Now"))
                                    userMessage = ""
                                }
                            }
                        )
                        "Equation" -> SharedEquationsBlackboard(
                            equation = latexEquation,
                            onEquationChange = { latexEquation = it },
                            isTyping = isCollaboratorTypingEquation
                        )
                        "Manuscript" -> ManuscriptSandbox(
                            draft = manuscriptDraft,
                            onDraftChange = { manuscriptDraft = it }
                        )
                        "Meetings" -> MeetingsSyncGrid()
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
                onEndCall = { showVideoCall = false }
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
            items(messages) { msg ->
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
                    val bubbleBg = if (msg.isMe) Color(0xFF005C4B) else BgCard
                    val textColor = TextPrimary

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
                                    Text(
                                        text = msg.sender,
                                        color = if (msg.sender.contains("Pujari")) AccentAmber else AccentCyan,
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
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }
                }
            }
        }

        // WhatsApp-like continuous chat input field
        Surface(
            color = BgCard,
            border = BorderStroke(0.5.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = userMessage,
                    onValueChange = onMessageChange,
                    placeholder = { Text("Message co-authors...", color = TextMuted, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = BgSubtle,
                        unfocusedContainerColor = BgSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = onSendMessage,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentTeal)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SharedEquationsBlackboard(
    equation: String,
    onEquationChange: (String) -> Unit,
    isTyping: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            text = "Sumiran is typing...",
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
                        .background(Color(0xFF0E1A14)) // Dark chalk green
                        .border(1.dp, Color(0xFF1E3A2F), RoundedCornerShape(10.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "RENDERED LATEX FORMULA",
                            color = Color(0xFFD1F2E5).copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        MarkdownText(
                            markdown = "$$" + equation + "$$",
                            color = Color(0xFFD1F2E5),
                            fontSize = 18.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Spin-1 Heisenberg Hamiltonian with transverse field",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
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
fun MeetingsSyncGrid() {
    val syncs = listOf(
        Pair("Weekly Progress Review", "Thursday, 2:00 PM (In 3 days)"),
        Pair("Entanglement Scaling Discussion", "Saturday, 11:00 AM (In 5 days)")
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Co-Author Synchronizations",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {}) {
                Icon(Icons.Default.Add, contentDescription = "Add sync", tint = AccentTeal)
            }
        }

        syncs.forEach { (title, whenStr) ->
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
}

// premium glassmorphic Video Call screen
@Composable
fun VideoConferenceOverlay(
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
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Project Nexus Co-Lab Sync",
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
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFE53935)),
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
