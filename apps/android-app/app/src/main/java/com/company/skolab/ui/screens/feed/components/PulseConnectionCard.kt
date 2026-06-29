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
            .width(190.dp)
            .height(270.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onAuthorClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Avatar + status badge row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.size(42.dp)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
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
                            fontSize = 14.sp
                        )
                    }
                    // Online/SkoLab badge
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(
                                if (connection.isOnSkoLab) OpenAlexBrightGreen
                                else SkoLabColors.Border
                            )
                            .border(1.5.dp, SkoLabColors.Card, CircleShape)
                    )
                }

                // Depth badge
                val (depthLabel, depthColor) = when (connection.depth) {
                    1 -> "Direct" to SkoLabColors.Green
                    2 -> "2nd°" to SkoLabColors.Blue1
                    else -> "3rd°" to SkoLabColors.Text3
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = depthColor.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, depthColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = depthLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = depthColor,
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
                fontSize = 13.sp,
                color = SkoLabColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )

            // Role + institution
            val inferredRole = when {
                connection.author.totalPapers > 50 -> "Professor"
                connection.author.totalPapers > 15 -> "Postdoc"
                else -> "Researcher"
            }
            Text(
                text = "$inferredRole · ${connection.author.institution.ifEmpty { connection.author.country }.take(22)}",
                fontFamily = SpaceGroteskFontFamily,
                fontSize = 9.5.sp,
                color = SkoLabColors.Text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )

            // Metrics strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SkoLabColors.Card2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(connection.papersCollaborated.toString(), fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = PRIMARY)
                    Text("Joint", fontFamily = SpaceGroteskFontFamily, fontSize = 7.sp, color = SkoLabColors.Text3)
                }
                Box(modifier = Modifier.width(0.5.dp).height(16.dp).background(SkoLabColors.Border).align(Alignment.CenterVertically))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("h${connection.hIndex}", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = PRIMARY)
                    Text("Index", fontFamily = SpaceGroteskFontFamily, fontSize = 7.sp, color = SkoLabColors.Text3)
                }
                Box(modifier = Modifier.width(0.5.dp).height(16.dp).background(SkoLabColors.Border).align(Alignment.CenterVertically))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${connection.mutualCount}%", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = AccentTeal)
                    Text("Match", fontFamily = SpaceGroteskFontFamily, fontSize = 7.sp, color = SkoLabColors.Text3)
                }
            }

            Spacer(Modifier.weight(1f))

            // Action buttons
            if (connection.isOnSkoLab) {
                if (isConnectedExternal) {
                    Surface(
                        onClick = onChatClick,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = SkoLabColors.Card2,
                        border = BorderStroke(1.dp, SkoLabColors.Border)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null, tint = AccentTeal, modifier = Modifier.size(12.dp))
                                Text("Message", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
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
                                    Icon(Icons.Default.PersonAdd, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Text("Connect", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                        Surface(
                            onClick = onChatClick,
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = AccentTeal.copy(alpha = 0.10f),
                            border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Collaborate", fontFamily = SpaceGroteskFontFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    onClick = {
                        android.widget.Toast.makeText(context, "Invite ${connection.author.name} to SkoLab", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = AccentTeal.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.35f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Invite", fontFamily = SpaceGroteskFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                    }
                }
            }
        }
    }
}
