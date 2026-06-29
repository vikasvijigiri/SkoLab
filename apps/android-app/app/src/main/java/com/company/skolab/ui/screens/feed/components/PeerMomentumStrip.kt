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
fun PeerMomentumStrip(
    peers: List<Connection>,
    onAuthorClick: (String) -> Unit
) {
    if (peers.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.People, null, tint = AccentTeal, modifier = Modifier.size(16.dp))
                Text(
                    text = "Peers moving fast",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "Tap to inspect",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(peers) { peer ->
                Surface(
                    onClick = { onAuthorClick("${peer.author.name}|${peer.author.id}") },
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.22f)),
                    modifier = Modifier.width(190.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.author.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = peer.author.institution,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentTeal.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "H ${peer.hIndex}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentTeal,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = peer.connectionPath.ifBlank { "Suggested collaborator" },
                            color = AccentTeal,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentAmber.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${peer.totalPublications} papers",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentAmber,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentRose.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${peer.papersCollaborated} joint",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AccentRose,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
