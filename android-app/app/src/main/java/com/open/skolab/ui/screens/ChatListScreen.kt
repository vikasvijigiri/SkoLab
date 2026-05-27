package com.open.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.auth.AuthManager
import com.open.skolab.data.ChatStorage
import com.open.skolab.data.UserPreferences
import com.open.skolab.model.UserConnection
import com.open.skolab.network.ChatMessage
import com.open.skolab.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (String, String) -> Unit,
    onCoLabClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val userPrefs = remember { UserPreferences(context) }
    
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val connections by userPrefs.userConnections.collectAsState(initial = emptyList())
    
    var searchQuery by remember { mutableStateOf("") }
    var showSelectContactDialog by remember { mutableStateOf(false) }

    // Initials color palette
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)

    val userUid = cachedUser?.uid ?: ""
    val chatStorage = remember(userUid) { if (userUid.isNotEmpty()) ChatStorage(context, userUid) else null }

    // Load active chat threads based on existing chat logs
    val activeChats = remember(connections, chatStorage, userUid) {
        if (chatStorage == null) return@remember emptyList<Pair<UserConnection, ChatMessage>>()
        val partners = chatStorage.getActiveChatPartners()
        connections.mapNotNull { conn ->
            val cleanConnId = conn.id.substringAfterLast("/")
            if (partners.contains(cleanConnId)) {
                val lastMsg = chatStorage.getLastMessage(conn.id)
                if (lastMsg != null) conn to lastMsg else null
            } else {
                null
            }
        }.sortedByDescending { it.second.timestamp }
    }

    val filteredChats = activeChats.filter {
        it.first.name.contains(searchQuery, ignoreCase = true) ||
        it.first.institution.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
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
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                    Text(
                        text = "Chats",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        fontFamily = DisplayFontFamily,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSelectContactDialog = true },
                containerColor = AccentEmerald,
                contentColor = TextOnAccent,
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "New Chat")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chats...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderLight,
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 1. Project Co-Labs Section
                item {
                    Text(
                        text = "Project Co-Labs",
                        color = AccentTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgCard),
                        border = BorderStroke(0.5.dp, BorderLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { onCoLabClick("Project Nexus") }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AccentEmerald.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Project Nexus",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Sumiran Pujari: Added the transverse...",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = AccentEmerald,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "3 online",
                                    color = TextOnAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Direct Messages",
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // 2. Direct Messages List
                if (filteredChats.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isEmpty()) "No active chats yet" else "No matching chats found",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(filteredChats) { (connection, lastMessage) ->
                        val avatarColor = avatarColors[kotlin.math.abs(connection.id.hashCode()) % avatarColors.size]
                        val timestampFormatted = remember(lastMessage.timestamp) {
                            val msgCal = Calendar.getInstance().apply { timeInMillis = lastMessage.timestamp }
                            val nowCal = Calendar.getInstance()
                            
                            if (msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                                msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
                                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(lastMessage.timestamp))
                            } else if (msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                                nowCal.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1) {
                                "Yesterday"
                            } else {
                                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(lastMessage.timestamp))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChatClick(connection.name, connection.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(avatarColor.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = connection.name.take(1).uppercase(),
                                    color = avatarColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    fontFamily = DisplayFontFamily
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Name + Last Message snippet
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = connection.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (lastMessage.role == "user") {
                                        Icon(
                                            imageVector = Icons.Default.DoneAll,
                                            contentDescription = "Sent",
                                            tint = AccentTeal,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = lastMessage.content,
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Timestamp
                            Text(
                                text = timestampFormatted,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        HorizontalDivider(
                            color = BorderLight,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }

    // Select Contact Dialog / Bottom Sheet
    if (showSelectContactDialog) {
        AlertDialog(
            onDismissRequest = { showSelectContactDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSelectContactDialog = false }) {
                    Text("Close", color = AccentTeal)
                }
            },
            title = {
                Text(
                    text = "Select Contact",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    fontFamily = DisplayFontFamily
                )
            },
            text = {
                if (connections.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "You haven't added any connections yet. Go to the Network tab to connect with peers.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(connections) { contact ->
                            val avatarColor = avatarColors[kotlin.math.abs(contact.id.hashCode()) % avatarColors.size]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSelectContactDialog = false
                                        onChatClick(contact.name, contact.id)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(avatarColor.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.name.take(1).uppercase(),
                                        color = avatarColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = contact.name,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = contact.institution,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
                        }
                    }
                }
            },
            containerColor = BgCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
