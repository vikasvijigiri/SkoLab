package com.company.skolab.ui.screens.feed.components

import com.company.skolab.ui.screens.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.model.Paper
import com.company.skolab.model.Author
import com.company.skolab.model.UserConnection
import com.company.skolab.model.Conjecture
import com.company.skolab.model.Connection
import com.company.skolab.model.Country
import com.company.skolab.model.Discipline
import com.company.skolab.model.FeedUiState
import com.company.skolab.model.FrontierMetrics
import com.company.skolab.model.Institution
import com.company.skolab.model.ReadingProgress
import com.company.skolab.model.ResearchArea
import com.company.skolab.model.ResearchFilter
import com.company.skolab.model.User
import androidx.compose.material.icons.automirrored.filled.Article
import com.company.skolab.ui.components.ScoreArcMeter
import com.company.skolab.ui.components.MarkdownText
import com.company.skolab.ui.components.StreakCard
import com.company.skolab.ui.components.SwipeVaultCard
import com.company.skolab.ui.components.PaperCard
import com.company.skolab.ui.components.primitives.EmptyState
import com.company.skolab.ui.components.primitives.ErrorState
import com.company.skolab.ui.components.primitives.GlassSearchBar
import com.company.skolab.ui.theme.*
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun PulseConnectionCard(
    connection: Connection,
    isConnectedExternal: Boolean,
    onConnect: () -> Unit,
    onChatClick: () -> Unit = {},
    onCollabClick: () -> Unit = onChatClick, // distinct "Start Collab" callback; falls back to chat by default
    onAuthorClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "cardScale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, if (isPressed) SkoLabColors.Blue1.copy(alpha = 0.5f) else SkoLabColors.Border),
        modifier = Modifier
            .width(165.dp)
            .height(210.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onAuthorClick() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Avatar + status badge + Match Score Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.size(32.dp)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(SkoLabColors.Blue1.copy(alpha = 0.20f), SkoLabColors.Card2)
                                )
                            )
                            .border(1.dp, SkoLabColors.Blue1.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connection.author.name.take(2).uppercase(),
                            color = SkoLabColors.Blue1,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                    // Online/SkoLab badge
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(
                                if (connection.isOnSkoLab) OpenAlexBrightGreen
                                else SkoLabColors.Border
                            )
                            .border(1.dp, SkoLabColors.Card, CircleShape)
                    )
                }

                // Match Score Badge
                val badgeColor = if (connection.mutualCount >= 85) SkoLabColors.Green else SkoLabColors.Blue1
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeColor.copy(alpha = 0.08f),
                    border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "${connection.mutualCount}% Match",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = badgeColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGroteskFontFamily
                    )
                }
            }

            // Name
            Text(
                text = connection.author.name,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = SkoLabColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )

            // Role + institution
            val inferredRole = when {
                connection.author.totalPapers > 50 -> "Prof"
                connection.author.totalPapers > 15 -> "Postdoc"
                else -> "Researcher"
            }
            Text(
                text = "$inferredRole · ${connection.author.institution.ifEmpty { connection.author.country }.take(18)}",
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 9.sp,
                color = SkoLabColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp
            )

            // Shared topics tag row
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val sharedAreas = if (connection.sharedAreas.isNotEmpty()) {
                    connection.sharedAreas
                } else if (connection.tags.isNotEmpty()) {
                    connection.tags
                } else {
                    listOf("Research", "Science")
                }
                sharedAreas.take(2).forEach { area ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SkoLabColors.Blue1.copy(alpha = 0.05f),
                        border = BorderStroke(0.5.dp, SkoLabColors.Blue1.copy(alpha = 0.15f))
                    ) {
                        Text(
                            text = area.uppercase(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            color = SkoLabColors.Blue1,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                }
            }

            // Single line metrics strip
            Text(
                text = "h-${connection.hIndex} · ${connection.papersCollaborated} joint",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.5.sp,
                color = SkoLabColors.Text3,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.weight(1f))

            // Action button (Single compact line)
            if (connection.isOnSkoLab) {
                if (isConnectedExternal) {
                    Surface(
                        onClick = onChatClick,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = SkoLabColors.Card2,
                        border = BorderStroke(1.dp, SkoLabColors.Border)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null, tint = AccentTeal, modifier = Modifier.size(10.dp))
                                Text("Message", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                            }
                        }
                    }
                } else {
                    Surface(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(listOf(AccentTeal, AccentTealDark)),
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.PersonAdd, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                Text("Connect", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    onClick = {
                        val subject = "Invitation to collaborate on SkoLab"
                        val body = "Hi ${connection.author.name},\n\nI'd love to collaborate with you on SkoLab — a research platform with secure calls, real-time LaTeX whiteboards, and joint manuscript editing.\n\nJoin here: https://skolab.open/invite\n\nBest regards"
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                            putExtra(android.content.Intent.EXTRA_TEXT, body)
                        }
                        try {
                            context.startActivity(android.content.Intent.createChooser(intent, "Invite via Email"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "No email client found.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = AccentTeal.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.35f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("✉️ Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                    }
                }
            }
        }
    }
}
