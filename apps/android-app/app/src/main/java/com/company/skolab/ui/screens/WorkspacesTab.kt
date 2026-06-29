package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.Send
import com.company.skolab.model.SkoLabUser
import com.company.skolab.ui.components.MarkdownText
import com.company.skolab.ui.theme.*

@Composable
fun WorkspacesTab(
    currentUserId: String,
    currentUserName: String,
    projects: List<ProjectCollab>,
    currentProject: ProjectCollab?,
    tasks: List<CollabTask>,
    chatMessages: List<String>,
    membersPresence: Map<String, SkoLabUser>,
    selectedProjectIndex: Int,
    onProjectSelected: (Int) -> Unit,
    onNavigateToCreateProject: () -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToInviteMember: (String) -> Unit,
    onNavigateToCreateTask: (String) -> Unit,
    onTaskToggle: (CollabTask) -> Unit,
    onSendMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var showProjectDropdown by remember { mutableStateOf(false) }
    var showVideoSync by remember { mutableStateOf(false) }
    var groupMessageInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = SURFACE_SUBTLE,
            border = BorderStroke(0.5.dp, BORDER),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = currentProject != null) {
                            showProjectDropdown = !showProjectDropdown
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Workspaces,
                        contentDescription = null,
                        tint = PRIMARY,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentProject?.name ?: "No Active Workspace",
                        color = TEXT_PRIMARY,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                    if (currentProject != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = if (showProjectDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = PRIMARY,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { onNavigateToCreateProject() },
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SURFACE)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(14.dp))
                    }

                    IconButton(
                        onClick = {
                            val proj = currentProject
                            if (proj == null) {
                                Toast.makeText(context, "Please create a project first.", Toast.LENGTH_SHORT).show()
                            } else if (proj.id.startsWith("default_")) {
                                Toast.makeText(context, "Call sync requires registered SkoLab co-authors.", Toast.LENGTH_SHORT).show()
                            } else {
                                showVideoSync = true
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (currentProject != null) PRIMARY else BORDER)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = if (currentProject != null) TEXT_ON_PRIMARY else TEXT_MUTED, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Project selection dropdown
            if (currentProject != null) {
                DropdownMenu(
                    expanded = showProjectDropdown,
                    onDismissRequest = { showProjectDropdown = false },
                    modifier = Modifier
                        .background(SURFACE)
                        .border(BorderStroke(0.5.dp, BORDER), RoundedCornerShape(12.dp))
                ) {
                    projects.forEachIndexed { index, proj ->
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(text = proj.name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(text = proj.description, color = TEXT_SECONDARY, fontSize = 10.sp)
                                }
                            },
                            onClick = {
                                onProjectSelected(index)
                                showProjectDropdown = false
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val proj = currentProject
            if (proj == null) {
                item {
                    Surface(
                        color = SURFACE,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Workspaces,
                                contentDescription = null,
                                tint = PRIMARY.copy(alpha = 0.5f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "No Active Workspace",
                                color = TEXT_PRIMARY,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Create shared paper drafts, interactive roadmaps, equations blackboard, and dynamic group discussions with your co-authors.",
                                color = TEXT_SECONDARY,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 17.sp
                            )
                            Button(
                                onClick = { onNavigateToCreateProject() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Create Workspace Project", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                // Collaborators profiles horizontal ring
                item {
                    Text(
                        text = "ACTIVE COLLABORATORS",
                        color = TEXT_MUTED,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        // You Avatar
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val myInitials = remember(currentUserName) {
                                currentUserName.split(" ")
                                    .filter { it.isNotEmpty() }
                                    .take(2)
                                    .map { it.first() }
                                    .joinToString("")
                                    .uppercase()
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PRIMARY.copy(alpha = 0.1f))
                                    .border(1.dp, PRIMARY, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(myInitials.ifEmpty { "ME" }, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("You", color = TEXT_PRIMARY, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }

                        // Co-authors
                        val otherMembers = proj.members.filter {
                            it.uid != currentUserId && it.name.lowercase() != "you" && !it.name.equals(currentUserName, ignoreCase = true)
                        }

                        otherMembers.forEach { member ->
                            val initials = member.name.split(" ").map { it.take(1) }.joinToString("").uppercase()
                            val presence = membersPresence[member.uid]
                            val isOnline = presence?.isOnline == true

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { onNavigateToChat(member.name, member.uid) }
                            ) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(SURFACE_SUBTLE)
                                            .border(1.dp, BORDER, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(initials.ifEmpty { "U" }, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isOnline) WhatsAppTealGreen else Color.Gray)
                                            .border(1.dp, SURFACE, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(member.name.split(" ").firstOrNull() ?: member.name, color = TEXT_SECONDARY, fontSize = 9.sp)
                            }
                        }

                        // Add collaborator button
                        IconButton(
                            onClick = { onNavigateToInviteMember(proj.id) },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SURFACE)
                                .border(1.dp, BORDER, CircleShape)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Blackboard math equation
                item {
                    Surface(
                        color = SURFACE,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToWorkspace(proj.name) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.EditNote, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Blackboard Latex Draft", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PremiumDarkSpace)
                                    .padding(10.dp)
                            ) {
                                MarkdownText(
                                    markdown = "$$" + proj.recentEquations + "$$",
                                    color = PremiumLightText,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Manuscript Draft Progress", color = TEXT_SECONDARY, fontSize = 11.sp)
                                Text("${(proj.manuscriptProgress * 100).toInt()}%", color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { proj.manuscriptProgress },
                                color = PRIMARY,
                                trackColor = SURFACE_SUBTLE,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }

                // Roadmap Tasks Checklist
                item {
                    Surface(
                        color = SURFACE,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("ROADMAP & TASKS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = PRIMARY,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onNavigateToCreateTask(proj.id) }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            tasks.forEach { task ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onTaskToggle(task)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (task.isCompleted) WhatsAppTealGreen else TEXT_MUTED,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = task.title,
                                            color = if (task.isCompleted) TEXT_MUTED else TEXT_PRIMARY,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(text = "Assignee: ${task.assignee}", color = TEXT_SECONDARY, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // In-Workspace Group Discussion Chat board
                item {
                    Surface(
                        color = SURFACE,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BORDER),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("DISCUSSION BOARD", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            chatMessages.forEach { msg ->
                                val splitMsg = msg.split(": ")
                                val sender = splitMsg.getOrNull(0) ?: ""
                                val body = splitMsg.getOrNull(1) ?: msg
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(sender, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    Text(body, color = TEXT_PRIMARY, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = groupMessageInput,
                                    onValueChange = { groupMessageInput = it },
                                    placeholder = { Text("Post to chat...", fontSize = 11.sp) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PRIMARY,
                                        unfocusedBorderColor = BORDER
                                    ),
                                    maxLines = 2
                                )
                                IconButton(
                                    onClick = {
                                        if (groupMessageInput.isNotBlank()) {
                                            onSendMessage(groupMessageInput)
                                            groupMessageInput = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(PRIMARY)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TEXT_ON_PRIMARY, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
