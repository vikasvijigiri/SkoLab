package com.company.skolab.ui.screens

import com.company.skolab.ui.screens.discovery.components.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import androidx.compose.runtime.collectAsState
import com.company.skolab.network.*
import com.company.skolab.state.ActiveResearcherState
import com.company.skolab.ui.components.*
import com.company.skolab.ui.theme.*
import com.company.skolab.analytics.SkoLabAnalytics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import android.util.TypedValue
import android.view.View
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import org.commonmark.node.StrongEmphasis
import android.text.style.ForegroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────
// SUGGESTIONS DROPDOWN — scrollable white card, up to 10 results
// ─────────────────────────────────────────────────────────────────

@Composable
fun DropdownSkeletonRow(shimmerAlpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Avatar circle pulse
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(BorderLight.copy(alpha = shimmerAlpha))
        )
        // Two line text pulse
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BorderLight.copy(alpha = shimmerAlpha))
            )
        }
    }
}



// ─────────────────────────────────────────────────────────────────
// LOADING SKELETON
// ─────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────────
// RESEARCHER PROFILE VIEW
// ─────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────────
// HERO CARD
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearcherHeroCard(
    author: AuthorResponse,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onUpdateData: (AuthorResponse) -> Unit
) {
    var isRefreshingInner by remember { mutableStateOf(false) }
    // Animated entry
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -30 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = BgCard,
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Box {
                    // Gradient top strip
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Brush.horizontalGradient(HeroGradient))
                    )
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = author.display_name,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontFamily = DisplayFontFamily
                                )
                                Spacer(Modifier.height(1.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AccountBalance,
                                        null,
                                        tint = AccentTeal,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = author.institution,
                                        fontSize = 13.sp,
                                        color = AccentTeal,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!author.field_of_study.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = author.field_of_study,
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                            // Refresh + avatar column
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Initials avatar
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(HeroGradient)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = author.display_name.take(1).uppercase(),
                                        color = TextOnAccent,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                // Refresh button
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            isRefreshingInner = true
                                            apiService.refreshAuthor(author.display_name)
                                            apiService.searchAuthor(author.display_name, author.id)?.let { onUpdateData(it) }
                                            isRefreshingInner = false
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = AccentTealLight
                                    )
                                ) {
                                    if (isRefreshingInner) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentTeal)
                                    } else {
                                        Icon(Icons.Default.Refresh, null, tint = AccentTeal, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        // Expertise chips
                        if (author.expertise.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                author.expertise.take(5).forEach { tag ->
                                    ExpertiseChip(tag)
                                }
                            }
                        }

                        // Academic history
                        if (author.academic_history.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "CAREER PATH",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = TextMuted,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            author.academic_history.take(3).forEach { hist ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(AccentTeal)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(hist, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        // ── LinkedIn style action buttons ───────────────────
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))
                        
                        var connectionState by remember { mutableStateOf("Connect") }
                        var showMessageDialog by remember { mutableStateOf(false) }
                        var showCollaborateDialog by remember { mutableStateOf(false) }
                        val context = LocalContext.current
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isRequested = connectionState == "Requested"
                            val buttonColor = if (isRequested) BorderLight else PremiumBlue // Premium Blue
                            val contentColor = if (isRequested) TextSecondary else Color.White
                            
                            Button(
                                onClick = {
                                    connectionState = if (isRequested) "Connect" else "Requested"
                                    val msg = if (isRequested) "Connection request cancelled" else "Connection request sent to ${author.display_name}!"
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonColor,
                                    contentColor = contentColor
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isRequested) Icons.Default.Check else Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isRequested) "Requested" else "Connect",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            
                            OutlinedButton(
                                onClick = { showMessageDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentTeal
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Mail, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Message", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            
                            OutlinedButton(
                                onClick = { showCollaborateDialog = true },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(19.dp),
                                border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentIndigo
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Groups, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Collaborate", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }

                        // Message Dialog
                        if (showMessageDialog) {
                            var messageText by remember { mutableStateOf("") }
                            AlertDialog(
                                onDismissRequest = { showMessageDialog = false },
                                title = {
                                    Text(
                                        "Message ${author.display_name}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontFamily = DisplayFontFamily
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Send a direct professional message. They will receive it in their peer inbox.",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        OutlinedTextField(
                                            value = messageText,
                                            onValueChange = { messageText = it },
                                            placeholder = { Text("Write your message here…", fontSize = 13.sp) },
                                            modifier = Modifier.fillMaxWidth().height(100.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentTeal,
                                                unfocusedBorderColor = BorderLight
                                            ),
                                            maxLines = 4
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            if (messageText.isNotBlank()) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Message sent successfully!",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                showMessageDialog = false
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Please type a message first",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                                    ) {
                                        Text("Send Message", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showMessageDialog = false }) {
                                        Text("Cancel", color = TextSecondary)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                containerColor = BgCard
                            )
                        }

                        // Collaborate Dialog
                        if (showCollaborateDialog) {
                            val templates = listOf(
                                "Co-author Paper: Requesting partnership on upcoming publication in ${author.field_of_study ?: "your field"}.",
                                "Joint Grant Proposal: Collaborate on national or global grant funding applications.",
                                "Guest Lecture Invitation: Invite ${author.display_name} to speak at your host institution."
                            )
                            var selectedIndex by remember { mutableStateOf(0) }
                            var collabDetails by remember { mutableStateOf("") }
                            
                            AlertDialog(
                                onDismissRequest = { showCollaborateDialog = false },
                                title = {
                                    Text(
                                        "Collaborate with ${author.display_name}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontFamily = DisplayFontFamily
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Select a collaboration track to build your proposal invitation:",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                        
                                        templates.forEachIndexed { index, template ->
                                            val isSelected = selectedIndex == index
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) AccentIndigo.copy(alpha = 0.08f) else Color.Transparent)
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) AccentIndigo else BorderLight,
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { selectedIndex = index }
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { selectedIndex = index },
                                                    colors = RadioButtonDefaults.colors(selectedColor = AccentIndigo)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = template.substringBefore(":"),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) AccentIndigo else TextPrimary
                                                    )
                                                    Text(
                                                        text = template.substringAfter(": "),
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                        }
                                        
                                        OutlinedTextField(
                                            value = collabDetails,
                                            onValueChange = { collabDetails = it },
                                            placeholder = { Text("Add any personal note or proposal link (optional)…", fontSize = 12.sp) },
                                            modifier = Modifier.fillMaxWidth().height(70.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = AccentIndigo,
                                                unfocusedBorderColor = BorderLight
                                            )
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Collaboration proposal sent to ${author.display_name}!",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            showCollaborateDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
                                    ) {
                                        Text("Send Proposal", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCollaborateDialog = false }) {
                                        Text("Cancel", color = TextSecondary)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                containerColor = BgCard
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpertiseChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentTeal.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            color = AccentTeal,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// METRICS ANALYSIS PENDING — shown when LLM hasn't computed metrics yet
// ─────────────────────────────────────────────────────────────────

@Composable
fun MetricsAnalysisPendingCard(isLlmActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = BgCard,
        border = BorderStroke(1.dp, if (isLlmActive) AccentTeal.copy(alpha = 0.25f) else BorderLight)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isLlmActive) AccentTeal.copy(alpha = 0.12f) else BorderLight.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Science, null,
                    tint = if (isLlmActive) AccentTeal.copy(alpha = alpha) else TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLlmActive) "Deep Analysis Queued" else "AI Metrics Unavailable",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = if (isLlmActive) 
                        "AI metrics (Disruption, Novelty, Future Impact\u2026) are computing in the background. Pull down to refresh."
                    else 
                        "The AI service is currently out of limit or unconfigured. Displaying verified base metadata from OpenAlex.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// STATS QUAD ROW — 4 key numbers
// ─────────────────────────────────────────────────────────────────


@Composable
fun StatsQuadRow(author: AuthorResponse) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); visible = true }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(400)) + expandVertically(tween(400))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("H-Index", author.h_index.toString(), AccentTeal),
                Triple("i10-Index", author.i10_index.toString(), AccentIndigo),
                Triple("Works", author.works_count.toString(), AccentEmerald),
                Triple("Citations", formatCitationsCount(author.cited_by_count), AccentAmber)
            ).forEach { (label, value, color) ->
                StatCard(label = label, value = value, color = color, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = DisplayFontFamily
            )
            Text(
                text = label,
                color = TextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

fun formatCitationsCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000     -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else               -> count.toString()
}

// ─────────────────────────────────────────────────────────────────
// METRICS RADAR RING — animated arc chart for 8 scores
// ─────────────────────────────────────────────────────────────────

data class MetricArcEntry(val label: String, val value: Float, val color: Color, val icon: ImageVector)

@Composable
fun MetricsRadarSection(author: AuthorResponse) {
    val metrics = remember(author) {
        listOf(
            MetricArcEntry("Disruption",  (author.disruption_score.toFloat() / 100f).coerceIn(0f, 1f),    MetricDisruptionColor, Icons.Default.FlashOn),
            MetricArcEntry("Novelty",     (author.semantic_novelty.toFloat() / 100f).coerceIn(0f, 1f),    MetricNoveltyColor,    Icons.Default.AutoGraph),
            MetricArcEntry("Fut. Impact", (author.future_impact_score.toFloat() / 100f).coerceIn(0f, 1f), MetricFutureImpactColor, Icons.Default.Psychology),
            MetricArcEntry("Influence",   (author.network_centrality.toFloat() / 100f).coerceIn(0f, 1f),  MetricInfluenceColor,  Icons.Default.Hub),
            MetricArcEntry("Creativity",  (author.average_creativity.toFloat() / 100f).coerceIn(0f, 1f),  MetricCreativityColor, Icons.Default.Lightbulb),
            MetricArcEntry("Complexity",  (author.average_complexity.toFloat() / 100f).coerceIn(0f, 1f),  MetricComplexityColor, Icons.Default.Science),
            MetricArcEntry("Open Sci.",   (author.open_science_score.toFloat() / 100f).coerceIn(0f, 1f),  MetricOpenScienceColor, Icons.Default.Public),
            MetricArcEntry("Collab.",     (author.collaboration_diversity.toFloat() / 100f).coerceIn(0f, 1f), MetricCollabColor, Icons.Default.Groups),
        )
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); visible = true }

    Column(modifier = Modifier.padding(16.dp)) {
        LightSectionHeader("Frontier Scores", "8 research dimensions", Icons.Default.BubbleChart, AccentTeal)
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, BorderLight)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Ring arc chart
                AnimatedVisibility(visible = visible, enter = fadeIn(tween(600))) {
                    MetricOctagonChart(metrics = metrics)
                }
                Spacer(Modifier.height(16.dp))
                // Legend grid 2x4
                val chunked = metrics.chunked(2)
                chunked.forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { m ->
                            Row(
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(m.color))
                                Spacer(Modifier.width(6.dp))
                                Text(m.label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "${(m.value * 100).toInt()}",
                                    color = m.color,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = DisplayFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricOctagonChart(metrics: List<MetricArcEntry>) {
    val animProgress by rememberInfiniteTransition(label = "").let {
        // We use a once-animated approach instead
        remember { mutableStateOf(0f) }
    }.let { state ->
        // Animate to 1.0 once
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, animationSpec = tween(1200, easing = FastOutSlowInEasing)) }
        anim.asState()
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val radius = minOf(cx, cy) - 24.dp.toPx()
        val n = metrics.size
        val angleStep = 2 * PI.toFloat() / n
        val startAngle = -PI.toFloat() / 2

        // Background polygon
        val bgPath = Path()
        metrics.indices.forEach { i ->
            val angle = startAngle + i * angleStep
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            if (i == 0) bgPath.moveTo(x, y) else bgPath.lineTo(x, y)
        }
        bgPath.close()
        drawPath(bgPath, color = BgElevated)
        drawPath(bgPath, color = BorderLight, style = Stroke(1.dp.toPx()))

        // Grid rings
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { fraction ->
            val gridPath = Path()
            metrics.indices.forEach { i ->
                val angle = startAngle + i * angleStep
                val r = radius * fraction
                val x = cx + r * cos(angle)
                val y = cy + r * sin(angle)
                if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
            }
            gridPath.close()
            drawPath(gridPath, color = BorderLight.copy(alpha = 0.6f), style = Stroke(0.5.dp.toPx()))
        }

        // Spokes
        metrics.indices.forEach { i ->
            val angle = startAngle + i * angleStep
            drawLine(
                color = BorderLight,
                start = Offset(cx, cy),
                end = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // Data polygon (animated)
        val dataPath = Path()
        metrics.forEachIndexed { i, m ->
            val angle = startAngle + i * angleStep
            val r = radius * m.value * animProgress
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, brush = Brush.radialGradient(
            colors = listOf(AccentTeal.copy(alpha = 0.25f), AccentIndigo.copy(alpha = 0.1f)),
            center = Offset(cx, cy),
            radius = radius
        ))
        drawPath(dataPath, color = AccentTeal.copy(alpha = 0.8f), style = Stroke(2.dp.toPx()))

        // Metric dots
        metrics.forEachIndexed { i, m ->
            val angle = startAngle + i * angleStep
            val r = radius * m.value * animProgress
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            drawCircle(color = m.color, radius = 5.dp.toPx(), center = Offset(x, y))
            drawCircle(color = BgCard, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// FRONTIER METRIC PILLS — Flipkart-style compact cards
// ─────────────────────────────────────────────────────────────────

@Composable
fun FrontierMetricPillsSection(author: AuthorResponse) {
    data class MetricPill(val label: String, val value: String, val sub: String, val color: Color, val icon: ImageVector)

    val pills = remember(author) {
        val accelVal = author.citation_acceleration.toInt()
        val accelStr = if (accelVal >= 0) "+$accelVal" else accelVal.toString()
        listOf(
            MetricPill("Disruption",      "${author.disruption_score.toInt()}%",       "Research Disruption",    MetricDisruptionColor,   Icons.Default.FlashOn),
            MetricPill("Novelty",         "${author.semantic_novelty.toInt()}%",        "Semantic Novelty",       MetricNoveltyColor,      Icons.Default.AutoGraph),
            MetricPill("Future Impact",   "${author.future_impact_score.toInt()}%",     "Predicted Impact",       MetricFutureImpactColor, Icons.Default.Psychology),
            MetricPill("Influence",       "${author.network_centrality.toInt()}%",      "Network Centrality",     MetricInfluenceColor,    Icons.Default.Hub),
            MetricPill("Creativity",      "${author.average_creativity.toInt()}%",      "Avg Creativity",         MetricCreativityColor,   Icons.Default.Lightbulb),
            MetricPill("Complexity",      "${author.average_complexity.toInt()}%",      "Avg Complexity",         MetricComplexityColor,   Icons.Default.Science),
            MetricPill("Open Science",    "${author.open_science_score.toInt()}%",      "Openness Score",         MetricOpenScienceColor,  Icons.Default.Public),
            MetricPill("Collaboration",   "${author.collaboration_diversity.toInt()}%", "Diversity Index",        MetricCollabColor,       Icons.Default.Groups),
            MetricPill("Cit. Accel.",     accelStr,                                     "Citation Growth",        MetricInfluenceColor,    Icons.AutoMirrored.Filled.TrendingUp),
            MetricPill("Consistency",     "${author.research_consistency.toInt()}%",    "Research Consistency",   MetricConsistencyColor,  Icons.Default.Timeline),
            MetricPill("Interdiscipl.",   "${author.interdisciplinary_index.toInt()}%", "Cross-domain Reach",     AccentViolet,            Icons.Default.AccountTree),
            MetricPill("Policy Impact",   "${author.policy_patent_score.toInt()}",     "Policy & Patent Score",  MetricPolicyColor,       Icons.Default.Gavel),
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        LightSectionHeader("Metrics Breakdown", "12 dimensions", Icons.Default.Dashboard, AccentIndigo)
        Spacer(Modifier.height(10.dp))

        // 2-column grid with staggered animation
        val pairs = pills.chunked(2)
        pairs.forEachIndexed { rowIdx, pair ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(rowIdx * 80L); visible = true }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 20 }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { pill ->
                        MetricPillCard(pill.label, pill.value, pill.sub, pill.color, pill.icon, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MetricPillCard(
    label: String,
    value: String,
    subtitle: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = BgCard,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Icon box
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    color = color,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = DisplayFontFamily
                )
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                // Mini progress bar
                Spacer(Modifier.height(4.dp))
                val pct = value.replace("%", "").toFloatOrNull()?.div(100f) ?: 0f
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = color,
                    trackColor = color.copy(alpha = 0.12f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// PUBLICATION CARD — light, expandable
// ─────────────────────────────────────────────────────────────────

@Composable
fun LightPublicationCard(
    work: com.company.skolab.network.Work,
    apiService: ApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateToReader: (String, String) -> Unit,
    onDiscussClick: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var summaryData by remember { mutableStateOf<SummaryResponse?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                isExpanded = !isExpanded
                if (isExpanded && summaryData == null && !isSummarizing) {
                    scope.launch {
                        isSummarizing = true
                        summaryData = apiService.summarizeWork(work.title ?: "", work.doi)
                        isSummarizing = false
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title
            MarkdownText(
                markdown = formatScientificTitle(work.title ?: "Untitled"),
                color = TextPrimary,
                fontSize = 13.sp
            )

            // Journal
            if (!work.journal.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = work.journal,
                    color = AccentTeal,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    fontFamily = BodyFontFamily
                )
            }

            // Chips row
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                work.year?.let { PubChip(it.toString(), AccentTeal) }
                PubChip("${work.citations ?: 0} cites", AccentIndigo)
                if ((work.impact_factor ?: 0.0) > 0) PubChip("IF ${work.impact_factor}", AccentAmber)
                if ((work.creativity_score ?: 0.0) > 0) PubChip("Creativity ${work.creativity_score?.toInt()}", AccentViolet)
                val dVal = work.disruption_score ?: 0.0
                val dPct = if (dVal > 0.0 && dVal <= 1.0) (dVal * 100).toInt() else dVal.toInt()
                if (dPct > 0) PubChip("Disruption $dPct%", AccentRose)
                if (work.is_open_access == true) PubChip("Open Access", AccentEmerald)
            }

            // Expand arrow
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(Modifier.height(10.dp))
                    when {
                        isSummarizing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentTeal)
                                Spacer(Modifier.width(8.dp))
                                Text("Loading AI summary…", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                        summaryData != null -> {
                            summaryData!!.bullets.take(3).forEach { bullet ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .offset(y = 5.dp)
                                            .clip(CircleShape)
                                            .background(AccentTeal)
                                    )
                                    MarkdownText(
                                        markdown = bullet,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val url = work.doi?.let { if (it.startsWith("http")) it else "https://doi.org/$it" }
                                            ?: "https://scholar.google.com/scholar?q=${work.title}"
                                        onNavigateToReader(url, work.title ?: "Article")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Read Paper", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { onDiscussClick(work.title ?: "Article") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandWhatsAppTeal),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Discuss", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PubChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// PREDICTION CARD
// ─────────────────────────────────────────────────────────────────

data class ParsedPrediction(
    val frontier: String?,
    val toolkit: List<String>?,
    val toolkitRaw: String?,
    val logic: String?,
    val isParsedSuccessfully: Boolean
)

private fun parsePrediction(raw: String): ParsedPrediction {
    var frontier: String? = null
    var toolkit: List<String>? = null
    var toolkitRaw: String? = null
    var logic: String? = null

    val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
        val cleanLine = line.replace("**", "").replace("*", "").trim()
        if (cleanLine.startsWith("Next Frontier:", ignoreCase = true)) {
            frontier = cleanLine.substring("Next Frontier:".length).trim()
        } else if (cleanLine.startsWith("Toolkit:", ignoreCase = true)) {
            toolkitRaw = cleanLine.substring("Toolkit:".length).trim()
            toolkit = toolkitRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else if (cleanLine.startsWith("Logic:", ignoreCase = true)) {
            logic = cleanLine.substring("Logic:".length).trim()
        }
    }

    if (frontier == null || toolkit == null || logic == null) {
        val frontierRegex = Regex("""(?i)(?:\*+|)Next\s+Frontier(?:\*+|):\s*(.*?)(?=(?:\*+|)Toolkit(?:\*+|):|(?:\*+|)Logic(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)
        val toolkitRegex = Regex("""(?i)(?:\*+|)Toolkit(?:\*+|):\s*(.*?)(?=(?:\*+|)Next\s+Frontier(?:\*+|):|(?:\*+|)Logic(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)
        val logicRegex = Regex("""(?i)(?:\*+|)Logic(?:\*+|):\s*(.*?)(?=(?:\*+|)Next\s+Frontier(?:\*+|):|(?:\*+|)Toolkit(?:\*+|):|$)""", RegexOption.DOT_MATCHES_ALL)

        frontierRegex.find(raw)?.let { match ->
            val value = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (value.isNotEmpty()) frontier = value
        }
        toolkitRegex.find(raw)?.let { match ->
            val toolsStr = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (toolsStr.isNotEmpty()) {
                toolkitRaw = toolsStr
                toolkit = toolsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        logicRegex.find(raw)?.let { match ->
            val value = match.groupValues[1].replace("**", "").replace("*", "").trim()
            if (value.isNotEmpty()) logic = value
        }
    }

    val isParsedSuccessfully = frontier != null || logic != null || toolkit != null
    return ParsedPrediction(
        frontier = frontier,
        toolkit = toolkit,
        toolkitRaw = toolkitRaw,
        logic = logic,
        isParsedSuccessfully = isParsedSuccessfully
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PredictionCard(prediction: String) {
    val parsed = remember(prediction) { parsePrediction(prediction) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LightSectionHeader("Next Prediction", "AI forecast", Icons.Default.TipsAndUpdates, AccentIndigo)
        Spacer(Modifier.height(10.dp))

        if (parsed.isParsedSuccessfully) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 1. Next Frontier Section
                    parsed.frontier?.let { frontierText ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = AccentIndigoLight,
                            border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Explore,
                                        contentDescription = null,
                                        tint = AccentIndigo,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "RESEARCH FRONTIER",
                                        style = Typography.labelSmall,
                                        color = AccentIndigo,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                MarkdownText(
                                    markdown = frontierText,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // 2. Toolkit Section
                    parsed.toolkit?.let { tools ->
                        if (parsed.frontier != null) {
                            Spacer(Modifier.height(16.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = null,
                                    tint = AccentTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "TECHNICAL TOOLKIT",
                                    style = Typography.labelSmall,
                                    color = AccentTeal,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            
                            val allToolsConcise = remember(tools) { tools.all { it.length < 35 } }
                            if (allToolsConcise) {
                                FlowRow(
                                    modifier = Modifier.padding(start = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    tools.forEach { tool ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = AccentTealLight,
                                            border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .background(AccentTeal, CircleShape)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = tool,
                                                    style = Typography.bodySmall,
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                parsed.toolkitRaw?.let { rawToolkit ->
                                    MarkdownText(
                                        markdown = rawToolkit,
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3. Logic Section
                    parsed.logic?.let { logicText ->
                        if (parsed.frontier != null || parsed.toolkit != null) {
                            Spacer(Modifier.height(16.dp))
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = AccentViolet,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "STRATEGIC LOGIC",
                                    style = Typography.labelSmall,
                                    color = AccentViolet,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp)
                                    .height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(AccentViolet.copy(alpha = 0.4f), RoundedCornerShape(1.5.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                MarkdownText(
                                    markdown = logicText,
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Fallback to legacy style if parsing fails
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = AccentIndigoLight,
                border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = AccentIndigo,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    MarkdownText(
                        markdown = prediction,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────

@Composable
fun LightSectionHeader(title: String, subtitle: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = DisplayFontFamily)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DISCOVERY DASHBOARD — shown before search
// ─────────────────────────────────────────────────────────────────

data class DiscoveryCategory(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)


@Composable
fun ConferenceMatchRow() {
    val conferences = remember {
        listOf(
            Triple("NeurIPS 2026", "ML & Computational Neuroscience", "Deadline: 14d"),
            Triple("CVPR 2026", "Computer Vision & Pattern Recog.", "Deadline: 45d"),
            Triple("ICML 2026", "International Conf. on ML", "Deadline: 60d"),
            Triple("KDD 2026", "Knowledge Discovery & Data Mining", "Deadline: 90d")
        )
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        itemsIndexed(conferences) { index, (name, scope, deadline) ->
            val colors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet)
            val color = colors[index % colors.size]
            Surface(
                modifier = Modifier.width(220.dp).height(120.dp),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                            .background(color)
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = name,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = DisplayFontFamily
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = scope,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = color.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = deadline,
                                    color = color,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "${98 - (index * 4)}% match",
                                color = AccentEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResearchGapCard(researchFocus: String) {
    var isGenerating by remember { mutableStateOf(false) }
    var generatedGap by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AccentIndigoLight,
        border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentIndigo.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Psychology, null, tint = AccentIndigo, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        text = "AI Research Gap Finder",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = DisplayFontFamily
                    )
                    Text(
                        text = "Trained on $researchFocus literature",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (generatedGap == null) {
                Text(
                    text = "Analyze recent OpenAlex & Google Scholar publications to discover untapped research domains and missing links in your field.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            delay(1500)
                            generatedGap = "At the intersection of $researchFocus and deep neural systems, there is an unexplored gap in multi-scale molecular dynamics simulation using transformer-based physical priors under high pressure. Existing literature focuses heavily on standard pressure models without thermodynamic extrapolation."
                            isGenerating = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextOnAccent)
                        Spacer(Modifier.width(8.dp))
                        Text("Analyzing publications...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Find Research Gaps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    text = generatedGap!!,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { generatedGap = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = AccentIndigo),
                        border = BorderStroke(1.dp, AccentIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { /* Search gap in app */ },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Explore Papers", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
fun DashboardTile(category: DiscoveryCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "tileScale")

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(110.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, category.color.copy(alpha = 0.20f))
    ) {
        Box(
            modifier = Modifier.background(Brush.verticalGradient(colors = listOf(BgCard, category.color.copy(alpha = 0.03f))))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(category.color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(category.icon, null, tint = category.color, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(category.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = DisplayFontFamily)
                    Text(category.subtitle, color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun FeaturedResearchCard(title: String, authors: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(220.dp).height(120.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Box {
            // Color strip left
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(color)
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                Text(authors, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


fun formatScientificTitle(title: String): String = title
    .replace("&nbsp;", " ")
    .trim()

data class ResearchMetric(val label: String, val value: Int, val color: Color, val icon: ImageVector, val isPercentage: Boolean = true)

// ─────────────────────────────────────────────────────────────────
// SUGGESTED CONNECTIONS SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun SuggestedConnectionsSection(
    visibleCollaborators: List<NetworkCollaborator>,
    onConnect: (NetworkCollaborator) -> Unit,
    onDismiss: (NetworkCollaborator) -> Unit,
    onSelectProfile: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Suggested Connections",
                subtitle = "Co-authors & collaborators",
                icon = Icons.Default.People,
                color = AccentTeal
            )
        }
        Spacer(Modifier.height(10.dp))
        
        if (visibleCollaborators.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No more suggested connections at this time.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(visibleCollaborators, key = { it.id }) { collaborator ->
                    SuggestedConnectionCard(
                        collaborator = collaborator,
                        onConnect = { onConnect(collaborator) },
                        onDismiss = { onDismiss(collaborator) },
                        onCardClick = { onSelectProfile(collaborator.name, collaborator.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestedConnectionCard(
    collaborator: NetworkCollaborator,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    onCardClick: () -> Unit
) {
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(collaborator.id.hashCode()) % avatarColors.size]

    Surface(
        modifier = Modifier
            .width(220.dp)
            .height(180.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clickable { onCardClick() },
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = collaborator.name.take(1).uppercase(),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = DisplayFontFamily
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = collaborator.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = DisplayFontFamily
                    )
                    if (collaborator.institution.isNotBlank() && collaborator.institution != "Independent Researcher") {
                        Text(
                            text = collaborator.institution,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (collaborator.field.isNotBlank()) {
                        Text(
                            text = collaborator.field,
                            color = color,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = AccentTeal,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = collaborator.connection_path,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Match",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                    LinearProgressIndicator(
                        progress = { collaborator.relevance_score / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = AccentTeal,
                        trackColor = BorderLight
                    )
                    Text(
                        text = "${collaborator.relevance_score}%",
                        color = AccentTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandWhatsAppTeal,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Connect",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// COLLABORATORS' NEW ARTICLES SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun CollaboratorsNewArticlesSection(
    collaborators: List<NetworkCollaborator>,
    onPaperClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Collaborators' New Articles",
                subtitle = "Recent works from your research network",
                icon = Icons.AutoMirrored.Filled.Feed,
                color = AccentIndigo
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(collaborators, key = { "art-" + it.id }) { collaborator ->
                val rawPath = collaborator.connection_path
                val paperTitle = if (rawPath.contains("'")) {
                    rawPath.substringAfter("'").substringBefore("'")
                } else {
                    "Analysis of " + collaborator.field + " Dynamics"
                }

                Surface(
                    onClick = { onPaperClick(paperTitle) },
                    modifier = Modifier
                        .width(280.dp)
                        .height(130.dp)
                        .shadow(2.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = collaborator.name,
                                    color = AccentTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = DisplayFontFamily
                                )
                                Surface(
                                    color = AccentIndigo.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "NEW ARTICLE",
                                        color = AccentIndigo,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = paperTitle,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = BodyFontFamily,
                                lineHeight = 16.sp
                            )
                        }
                        Text(
                            text = collaborator.institution,
                            color = TextMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// CITATION HEATMAP SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun CitationHeatmapSection(heatmap: CitationHeatmap) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LightSectionHeader(
            title = "Citation & Work Trend",
            subtitle = "Annual impact analysis",
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            color = AccentIndigo
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = BgCard,
            border = BorderStroke(1.dp, BorderLight),
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = AccentIndigo.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "H-INDEX",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = heatmap.h_index.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = AccentIndigo
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = AccentTeal.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "INSTITUTIONS REACHED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = heatmap.institutional_reach.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = AccentTeal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                val maxVal = kotlin.math.max(
                    1,
                    kotlin.math.max(heatmap.citations.maxOrNull() ?: 1, heatmap.works.maxOrNull() ?: 1)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    heatmap.years.forEachIndexed { idx, year ->
                        val citations = heatmap.citations.getOrElse(idx) { 0 }
                        val works = heatmap.works.getOrElse(idx) { 0 }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(10.dp)
                                        .fillMaxHeight(fraction = (citations.toFloat() / maxVal).coerceIn(0.02f, 1f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(AccentTeal)
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(10.dp)
                                        .fillMaxHeight(fraction = (works.toFloat() / maxVal).coerceIn(0.02f, 1f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(AccentIndigo)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = year.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentTeal)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Citations",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentIndigo)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Publications",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// JOURNAL ADVISOR SECTION
// ─────────────────────────────────────────────────────────────────

@Composable
fun JournalAdvisorSection(recommendations: List<JournalRecommendation>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            LightSectionHeader(
                title = "Journal Venue Advisor",
                subtitle = "Optimized matching venues",
                icon = Icons.Default.Science,
                color = AccentIndigo
            )
        }
        Spacer(Modifier.height(10.dp))

        if (recommendations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No journal recommendations available.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(recommendations, key = { it.journal_name }) { recommendation ->
                    JournalRecommendationCard(recommendation = recommendation)
                }
            }
        }
    }
}

@Composable
fun JournalRecommendationCard(recommendation: JournalRecommendation) {
    Surface(
        modifier = Modifier
            .width(260.dp)
            .height(200.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = recommendation.journal_name,
                        style = Typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = DisplayFontFamily,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AccentIndigo.copy(alpha = 0.08f))
                            .border(BorderStroke(1.dp, AccentIndigo.copy(alpha = 0.2f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${recommendation.match_score}%",
                            color = AccentIndigo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Impact Factor",
                        tint = AccentAmber,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "IF: ${recommendation.estimated_impact_factor}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "SUBMISSION ADVICE",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            markdown = recommendation.submission_tips,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// DASHBOARD PEER SUGGESTION & FRIENDS' ARTICLES COMPOSABLES
// ─────────────────────────────────────────────────────────────────

@Composable
fun DashboardPeerSuggestionCard(
    peer: AuthorSuggestion,
    onClick: () -> Unit,
    onMessageClick: () -> Unit
) {
    var connectionState by remember { mutableStateOf("Connect") }
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(peer.id.hashCode()) % avatarColors.size]
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .height(168.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Brush.verticalGradient(colors = listOf(BorderLight, color.copy(alpha = 0.15f))))
    ) {
        Box(
            modifier = Modifier.background(Brush.verticalGradient(colors = listOf(BgCard, color.copy(alpha = 0.02f))))
        ) {
            Column(
                modifier = Modifier.padding(12.dp).fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.1f))
                            .border(BorderStroke(1.dp, color.copy(alpha = 0.25f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.display_name.take(1).uppercase(),
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            fontFamily = DisplayFontFamily
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.display_name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = DisplayFontFamily
                        )
                        Text(
                            text = peer.institution,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                if (!peer.field_of_study.isNullOrBlank()) {
                    Text(
                        text = peer.field_of_study,
                        color = color,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                
                val isRequested = connectionState == "Requested"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isRequested) SolidColor(BorderLight) 
                                else Brush.horizontalGradient(listOf(AccentTeal, AccentIndigo))
                            )
                            .clickable {
                                connectionState = if (isRequested) "Connect" else "Requested"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isRequested) Icons.Default.Check else Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = if (isRequested) TextSecondary else TextOnAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isRequested) "Requested" else "Connect",
                                color = if (isRequested) TextSecondary else TextOnAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onMessageClick,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentTeal.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Message",
                            tint = AccentTeal,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardFriendArticleCard(
    work: Work,
    onOpenPaper: () -> Unit
) {
    val friendName = work.authors?.firstOrNull() ?: "Researcher"
    
    Surface(
        onClick = onOpenPaper,
        modifier = Modifier
            .width(260.dp)
            .height(130.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = friendName,
                        color = AccentTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DisplayFontFamily
                    )
                    Surface(
                        color = AccentIndigo.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "NEW ARTICLE",
                            color = AccentIndigo,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = work.title ?: "Untitled Paper",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = BodyFontFamily,
                    lineHeight = 16.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${work.journal ?: "Research Journal"} (${work.year ?: ""})",
                    color = TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = onOpenPaper,
                    color = AccentTeal.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "Open Reader",
                        color = AccentTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestedPeersShimmer() {
    val infiniteTransition = rememberInfiniteTransition(label = "peersShimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Surface(
                modifier = Modifier.width(180.dp).height(168.dp),
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BorderLight.copy(alpha = alpha))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(BorderLight.copy(alpha = alpha))
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(BorderLight.copy(alpha = alpha))
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BorderLight.copy(alpha = alpha))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BorderLight.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}


