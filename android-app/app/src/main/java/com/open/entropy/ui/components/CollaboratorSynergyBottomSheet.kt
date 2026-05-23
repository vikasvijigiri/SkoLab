package com.open.entropy.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.network.ApiService
import com.open.entropy.network.CollaboratorSynergy
import com.open.entropy.state.ActiveResearcherState
import com.open.entropy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollaboratorSynergyBottomSheet(
    collaboratorId: String,
    collaboratorName: String,
    onDismissRequest: () -> Unit,
    apiService: ApiService = remember { ApiService() }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeAuthor by ActiveResearcherState.activeAuthor.collectAsState()
    
    var synergy by remember { mutableStateOf<CollaboratorSynergy?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(collaboratorId, activeAuthor) {
        isLoading = true
        errorMsg = null
        try {
            val authorId = activeAuthor?.id ?: "A5023888391"
            val result = apiService.getCollaboratorSynergy(authorId, collaboratorId)
            if (result != null) {
                synergy = result
            } else {
                errorMsg = "Failed to load synergy profile"
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to generate synergy details"
        } finally {
            isLoading = false
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
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Handshake,
                        contentDescription = null,
                        tint = AccentViolet,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Collaborator Synergy",
                        style = Typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = AccentViolet, strokeWidth = 3.dp)
                        Text(
                            text = "Analyzing semantic knowledge overlapping...",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (errorMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMsg ?: "Error",
                        color = AccentRose,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }
            } else if (synergy != null) {
                val data = synergy!!
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Synergy score card
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = BgCard,
                            border = BorderStroke(1.dp, BorderLight)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SYNERGY OVERLAP SCORE",
                                        style = Typography.labelSmall,
                                        color = TextMuted,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "High potential partnership direction",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        progress = { data.synergy_score / 100f },
                                        modifier = Modifier.size(56.dp),
                                        color = AccentViolet,
                                        trackColor = BorderLight,
                                        strokeWidth = 4.dp
                                    )
                                    Text(
                                        "${data.synergy_score}%",
                                        color = AccentViolet,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }

                    // Proposed Joint Title
                    item {
                        Column {
                            Text(
                                text = "PROPOSED JOINT INITIATIVE",
                                style = Typography.labelSmall,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = AccentViolet.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lightbulb,
                                        contentDescription = null,
                                        tint = AccentAmber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = data.joint_proposal_title,
                                            style = Typography.titleMedium,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = data.co_authorship_direction,
                                            style = Typography.bodyMedium,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Co-authorship Action Plan
                    item {
                        Column {
                            Text(
                                text = "STRATEGIC ROADMAP",
                                style = Typography.labelSmall,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    items(data.strategic_action_plan) { action ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = AccentTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = action,
                                    style = Typography.bodyMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
