package com.company.skolab.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.SparkViewModel
import kotlinx.coroutines.launch

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
    sparkViewModel: SparkViewModel = viewModel()
) {
    val context = LocalContext.current
    val authManager = com.company.skolab.di.AppDependencies.authManager
    val userPrefs = remember { com.company.skolab.data.UserPreferences(context) }
    
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val userName = cachedUser?.name?.split(" ")?.firstOrNull() ?: "Researcher"
    val userFocus = cachedUser?.researchFocus ?: ""
    val currentUserId = cachedUser?.uid ?: ""

    val sparkUiState by sparkViewModel.uiState.collectAsStateWithLifecycle()

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
            HomeScreenTopBar(
                userName = userName,
                onProfileClick = { /* Handled via profile action */ },
                isOnline = sparkUiState.isOnline,
                onOnlineToggle = { online ->
                    sparkViewModel.toggleOnline(currentUserId, userName, userFocus, online)
                }
            ) 
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            // Spark Console occupies the full available height and width as the sole entity
            SparkConsole(
                uiState = sparkUiState,
                onHailClick = { topic, bounty, tags ->
                    sparkViewModel.hailHelper(currentUserId, userName, topic, bounty, tags)
                },
                onCancelBroadcast = {
                    sparkViewModel.cancelSession()
                }
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

// ── HEADER TOP BAR ──
@Composable
fun HomeScreenTopBar(
    userName: String,
    onProfileClick: () -> Unit,
    isOnline: Boolean,
    onOnlineToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgPrimary)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Welcome back",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted
            )
            Text(
                text = "Hello, $userName",
                style = Typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontFamily = SpaceGroteskFontFamily
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .clickable { onOnlineToggle(!isOnline) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isOnline) AccentEmerald else TextMuted, CircleShape)
                )
                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    color = if (isOnline) AccentEmerald else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    .clickable { onProfileClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── MAXIMIZED SPARK CONSOLE DASHBOARD CENTERPIECE ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkConsole(
    uiState: com.company.skolab.viewmodel.SparkUiState,
    onHailClick: (String, String, List<String>) -> Unit,
    onCancelBroadcast: () -> Unit
) {
    var showHailDialog by remember { mutableStateOf(false) }

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

    // Dynamic, non-synchronous floating expert avatar drifts
    val floatX1 by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX1"
    )
    val floatY1 by infiniteTransition.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY1"
    )

    val floatX2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX2"
    )
    val floatY2 by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(4800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY2"
    )

    val floatX3 by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX3"
    )
    val floatY3 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY3"
    )

    val floatX4 by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = -16f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX4"
    )
    val floatY4 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(4600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY4"
    )

    val floatX5 by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatX5"
    )
    val floatY5 by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(4400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY5"
    )

    // Animate convergence ratio when searching (avatars slide closer)
    val convergenceRatio by animateFloatAsState(
        targetValue = if (uiState.isBroadcasting) 0.35f else 1.0f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "convergence"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF130D0A)) // Warm coffee-shop dark espresso
            .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(28.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // ── Radar Scan concentric rings on Canvas ──
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

        // ── Floating Matchable Avatars ──
        Box(modifier = Modifier.fillMaxSize()) {
            // Dr. Alice (Top-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-95.dp * convergenceRatio) + floatX1.dp,
                        y = (-110.dp * convergenceRatio) + floatY1.dp
                    )
            ) {
                ExpertAvatar(initials = "AJ", name = "Dr. Alice", isOnline = true)
            }

            // Prof. Lin (Top-Right)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (105.dp * convergenceRatio) + floatX2.dp,
                        y = (-85.dp * convergenceRatio) + floatY2.dp
                    )
            ) {
                ExpertAvatar(initials = "RL", name = "Prof. Lin", isOnline = true)
            }

            // Sarah K. (Bottom-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-95.dp * convergenceRatio) + floatX3.dp,
                        y = (85.dp * convergenceRatio) + floatY3.dp
                    )
            ) {
                ExpertAvatar(initials = "SK", name = "Sarah K.", isOnline = true)
            }

            // Robert M. (Bottom-Right)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (95.dp * convergenceRatio) + floatX4.dp,
                        y = (105.dp * convergenceRatio) + floatY4.dp
                    )
            ) {
                ExpertAvatar(initials = "RM", name = "Robert M.", isOnline = true)
            }

            // Dr. Sarah (Mid-Left)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (-135.dp * convergenceRatio) + floatX5.dp,
                        y = (-10.dp * convergenceRatio) + floatY5.dp
                    )
            ) {
                ExpertAvatar(initials = "SB", name = "Dr. Sarah", isOnline = true)
            }
        }

        // ── Central Control Dashboard ──
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Main Launcher Spark Key
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        .background(PRIMARY.copy(alpha = 0.08f), CircleShape)
                        .border(1.dp, PRIMARY.copy(alpha = 0.18f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(PRIMARY.copy(alpha = 0.12f), CircleShape)
                        .border(1.5.dp, PRIMARY.copy(alpha = 0.28f), CircleShape)
                )

                IconButton(
                    onClick = { 
                        if (!uiState.isBroadcasting) {
                            showHailDialog = true 
                        } else {
                            onCancelBroadcast()
                        }
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(PRIMARY)
                        .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.2f)), CircleShape)
                ) {
                    Icon(
                        imageVector = if (uiState.isBroadcasting) Icons.Default.Hearing else Icons.Default.Bolt,
                        contentDescription = "Launcher",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // CTAs and text info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (uiState.isBroadcasting) "Broadcasting Spark..." else "SkoLab Spark Console",
                        style = Typography.headlineSmall,
                        color = TEXT_PRIMARY,
                        fontWeight = FontWeight.Black,
                        fontFamily = SpaceGroteskFontFamily
                    )
                    Text(
                        text = if (uiState.isBroadcasting) "Searching for matching experts online..." else "Connect with verified researchers for instant 10-minute help pings.",
                        color = TEXT_SECONDARY,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                if (uiState.isBroadcasting) {
                    Button(
                        onClick = onCancelBroadcast,
                        colors = ButtonDefaults.buttonColors(containerColor = SURFACE_SUBTLE, contentColor = TEXT_PRIMARY),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("Cancel Call ❌", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showHailDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.WifiTethering, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("Hail Live Helper Now", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1,420 Experts Online",
                        color = AccentEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "⭐ 4.9 Seeker Rating · 🪙 ${uiState.walletTokens} Tokens",
                        color = TEXT_MUTED,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    if (showHailDialog) {
        var topic by remember { mutableStateOf("") }
        var selectedBounty by remember { mutableStateOf("10 Tokens") }
        var tagsText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showHailDialog = false },
            containerColor = BgCard,
            confirmButton = {
                Button(
                    onClick = {
                        if (topic.isNotBlank()) {
                            val tagsList = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            onHailClick(topic, selectedBounty, tagsList)
                            showHailDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Broadcast Call ⚡", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHailDialog = false }) {
                    Text("Cancel", color = TEXT_MUTED)
                }
            },
            title = {
                Text("Hail Live Helper", color = TEXT_PRIMARY, fontWeight = FontWeight.Black, fontFamily = SpaceGroteskFontFamily)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Briefly describe what you are stuck on. Matching experts online will receive your ping.", color = TEXT_SECONDARY, fontSize = 12.sp)
                    
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { topic = it },
                        label = { Text("What is your blocker?") },
                        placeholder = { Text("e.g. PyTorch CUDA out of memory error") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TEXT_PRIMARY,
                            unfocusedTextColor = TEXT_PRIMARY,
                            focusedBorderColor = PRIMARY,
                            unfocusedBorderColor = BORDER
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("Skill Tags (comma separated)") },
                        placeholder = { Text("e.g. PyTorch, NLP, Deep Learning") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TEXT_PRIMARY,
                            unfocusedTextColor = TEXT_PRIMARY,
                            focusedBorderColor = PRIMARY,
                            unfocusedBorderColor = BORDER
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Offer Incentive Bounty:", color = TEXT_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("5 Tokens", "10 Tokens", "Co-Authorship").forEach { bountyOption ->
                            val isSelected = selectedBounty == bountyOption
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) PRIMARY.copy(alpha = 0.15f) else SURFACE_SUBTLE,
                                border = BorderStroke(0.5.dp, if (isSelected) PRIMARY else BORDER),
                                modifier = Modifier
                                    .clickable { selectedBounty = bountyOption }
                                    .weight(1f)
                            ) {
                                Text(
                                    text = bountyOption,
                                    color = if (isSelected) PRIMARY else TEXT_PRIMARY,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun ExpertAvatar(
    initials: String,
    name: String,
    isOnline: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF221512)) // Deep dark coffee bg
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            listOf(PRIMARY, AccentEmerald)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = PRIMARY,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(AccentEmerald, CircleShape)
                        .border(2.dp, Color(0xFF130D0A), CircleShape)
                        .align(Alignment.TopEnd)
                )
            }
        }
        Text(
            text = name,
            color = TEXT_PRIMARY,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
