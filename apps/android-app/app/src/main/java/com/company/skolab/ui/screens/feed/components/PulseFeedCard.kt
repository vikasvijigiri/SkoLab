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
fun PulseFeedCard(
    title: String,
    authors: List<String>,
    journal: String,
    year: Int,
    publicationDate: String? = null,
    relevanceScore: Int,
    recommendationReason: String,
    abstractText: String?,
    methodology: String?,
    toolsUsed: List<String>?,
    keyFindings: String?,
    onPaperClick: () -> Unit,
    onDiscussClick: () -> Unit,
    onSaveClick: () -> Unit,
    isSaved: Boolean,
    onShareClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isLiked by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val scale by animateFloatAsState(
        targetValue = if (isLiked) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "likeScale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SkoLabColors.Card,
        border = BorderStroke(1.dp, SkoLabColors.Border.copy(alpha = 0.5f)),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SkoLabColors.Blue1.copy(alpha = 0.08f))
                            .border(0.5.dp, SkoLabColors.Blue1.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = journal.take(2).uppercase(),
                            color = SkoLabColors.Blue1,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 13.sp
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = journal,
                            color = SkoLabColors.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val formattedDate = remember(publicationDate, year) {
                            if (!publicationDate.isNullOrBlank()) {
                                try {
                                    val parts = publicationDate.split("-")
                                    if (parts.size >= 3) {
                                        val yearPart = parts[0]
                                        val monthPart = parts[1].toIntOrNull()
                                        val dayPart = parts[2].toIntOrNull()
                                        val monthName = when (monthPart) {
                                            1 -> "Jan"
                                            2 -> "Feb"
                                            3 -> "Mar"
                                            4 -> "Apr"
                                            5 -> "May"
                                            6 -> "Jun"
                                            7 -> "Jul"
                                            8 -> "Aug"
                                            9 -> "Sep"
                                            10 -> "Oct"
                                            11 -> "Nov"
                                            12 -> "Dec"
                                            else -> null
                                        }
                                        if (monthName != null && dayPart != null) {
                                            "$monthName $dayPart, $yearPart"
                                        } else {
                                            publicationDate
                                        }
                                    } else {
                                        publicationDate
                                    }
                                } catch (e: Exception) {
                                    publicationDate
                                }
                            } else {
                                year.toString()
                            }
                        }
                        Text(
                            text = "$formattedDate · Academic Feed",
                            color = SkoLabColors.Text3,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PRIMARY.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, PRIMARY.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = PRIMARY,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "$relevanceScore% Match",
                            color = PRIMARY,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                }
            }

            // Title & Authors
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPaperClick() },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    color = SkoLabColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontFamily = SpaceGroteskFontFamily
                )
                Text(
                    text = authors.map { it.split("|").first() }.joinToString(", "),
                    color = SkoLabColors.Text2,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }

            // AI Insight Brief
            if (!recommendationReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SkoLabColors.Blue1.copy(alpha = 0.04f),
                    border = BorderStroke(0.5.dp, SkoLabColors.Blue1.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val strokeWidthPx = 3.dp.toPx()
                            drawLine(
                                color = SkoLabColors.Blue1,
                                start = Offset(strokeWidthPx / 2, 0f),
                                end = Offset(strokeWidthPx / 2, size.height),
                                strokeWidth = strokeWidthPx
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = SkoLabColors.Blue1,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "SKOLAR INSIGHT BRIEF",
                                color = SkoLabColors.Blue1,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = SpaceGroteskFontFamily
                            )
                        }
                        Text(
                            text = recommendationReason,
                            color = SkoLabColors.Text,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                }
            }

            // Abstract Text
            if (!abstractText.isNullOrBlank() && abstractText != "No abstract available.") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = abstractText,
                        color = SkoLabColors.Text2,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isExpanded) "Show less" else "...see more",
                        color = SkoLabColors.Blue1,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { isExpanded = !isExpanded }
                    )
                }
            }

            // Parsed Details Grid
            val hasDetails = !methodology.isNullOrBlank() || !toolsUsed.isNullOrEmpty() || !keyFindings.isNullOrBlank()
            if (hasDetails) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SkoLabColors.Card2.copy(alpha = 0.4f),
                    border = BorderStroke(0.5.dp, SkoLabColors.Border.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!methodology.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = "Methodology",
                                    tint = SkoLabColors.Blue1,
                                    modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                )
                                Column {
                                    Text(
                                        text = "METHODOLOGY",
                                        color = SkoLabColors.Text3,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp,
                                        fontFamily = SpaceGroteskFontFamily
                                    )
                                    Text(
                                        text = methodology,
                                        color = SkoLabColors.Text,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        if (!toolsUsed.isNullOrEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "Tools",
                                    tint = SkoLabColors.Blue1,
                                    modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                )
                                Column {
                                    Text(
                                        text = "TOOLS & INFRASTRUCTURE",
                                        color = SkoLabColors.Text3,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp,
                                        fontFamily = SpaceGroteskFontFamily
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    androidx.compose.foundation.layout.FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        toolsUsed.forEach { tool ->
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = SkoLabColors.Blue1.copy(alpha = 0.08f),
                                                border = BorderStroke(0.5.dp, SkoLabColors.Blue1.copy(alpha = 0.2f))
                                            ) {
                                                Text(
                                                    text = tool,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                    color = SkoLabColors.Blue1,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = SpaceGroteskFontFamily
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!keyFindings.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Key Findings",
                                    tint = SkoLabColors.Green,
                                    modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                )
                                Column {
                                    Text(
                                        text = "KEY FINDINGS & OUTCOME",
                                        color = SkoLabColors.Text3,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp,
                                        fontFamily = SpaceGroteskFontFamily
                                    )
                                    Text(
                                        text = keyFindings,
                                        color = SkoLabColors.Text,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = SkoLabColors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Like / React
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isLiked = !isLiked }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) ButtonDeleteRed else SkoLabColors.Text3,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer(scaleX = scale, scaleY = scale)
                    )
                    Text(
                        text = if (isLiked) "Liked" else "React",
                        color = if (isLiked) ButtonDeleteRed else SkoLabColors.Text2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGroteskFontFamily
                    )
                }

                // Discuss with Skolar
                Surface(
                    onClick = onDiscussClick,
                    shape = RoundedCornerShape(8.dp),
                    color = AccentTeal.copy(alpha = 0.08f),
                    border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Comment,
                            contentDescription = "Ask Skolar",
                            tint = AccentTeal,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Discuss",
                            color = AccentTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                    }
                }

                // Bookmark / Save
                IconButton(
                    onClick = onSaveClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Save",
                        tint = if (isSaved) SkoLabColors.Gold1 else SkoLabColors.Text3,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Share
                IconButton(
                    onClick = onShareClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = SkoLabColors.Text3,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
