package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.zIndex
import com.company.skolab.ui.components.ConfettiCelebration
import com.company.skolab.ui.components.MarkdownText
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.DailyDiscoveryViewModel
import com.company.skolab.viewmodel.DiscoveryItem
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyDiscoveryScreen(
    onBack: () -> Unit,
    onPaperSaved: (String) -> Unit,
    onDiscussClick: (String) -> Unit = {},
    viewModel: DailyDiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val discoveryItems = uiState.discoveryItems
    val isLoading = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var savedIds by remember { mutableStateOf(setOf<String>()) }
    var confettiVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentAmber)
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ACADEMIC SIGNAL TIMEOUT",
                        color = AccentRose,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        color = EntropiColors.Text2,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
                    ) {
                        Text("Go Back", color = Color.White)
                    }
                }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { discoveryItems.size })
            
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = EntropiColors.Text1
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Daily Discovery Feed",
                            color = EntropiColors.Text1,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = SpaceGroteskFontFamily
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} of ${discoveryItems.size} papers",
                            color = AccentAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                // Vertical Pager with Perspective Cascade Geometry
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, start = 20.dp, end = 20.dp),
                    pageSpacing = 16.dp
                ) { page ->
                    // Calculate fractional page offset relative to current page
                    val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageHeight = size.height
                                
                                if (pageOffset > 0) {
                                    // Slide off upwards with slight scale & fade
                                    translationY = -pageOffset * pageHeight * 0.15f
                                    alpha = (1f - pageOffset).coerceIn(0f, 1f)
                                    scaleX = 1f - (pageOffset * 0.05f)
                                    scaleY = 1f - (pageOffset * 0.05f)
                                } else {
                                    // Stacked underneath cascade
                                    // Cancel default vertical scroll alignment offset
                                    translationY = pageOffset * pageHeight
                                    
                                    // Stagger downward slightly based on index difference
                                    val idxDiff = pageOffset.absoluteValue
                                    translationY += idxDiff * 24.dp.toPx()
                                    
                                    // Scale down background cards slightly
                                    val scale = 1f - (idxDiff * 0.05f).coerceIn(0f, 0.15f)
                                    scaleX = scale
                                    scaleY = scale
                                    
                                    // Mute/fade background cards
                                    alpha = (1f - (idxDiff * 0.25f)).coerceIn(0.1f, 1f)
                                }
                            }
                            // Ensure background cards draw behind the active card
                            .zIndex(if (pageOffset.absoluteValue < 1f) 1f - pageOffset.absoluteValue else 0f)
                    ) {
                        val item = discoveryItems[page]
                        val isSaved = savedIds.contains(item.id)
                        
                        DiscoveryCard(
                            item = item,
                            isSaved = isSaved,
                            onSaveClick = {
                                val newSaved = !isSaved
                                savedIds = if (newSaved) savedIds + item.id else savedIds - item.id
                                onPaperSaved(item.id)
                                if (newSaved) {
                                    confettiVisible = true
                                    Toast.makeText(context, "Saved to Vault", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Removed from Vault", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDiscussClick = { onDiscussClick(item.title) },
                            onShareClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Paper Title", "${item.title} - ${item.authors}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Title & Authors copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // Overlay Confetti celebration for delightful feedback
        ConfettiCelebration(
            visible = confettiVisible,
            onFinished = { confettiVisible = false }
        )
    }
}

@Composable
fun DiscoveryCard(
    item: DiscoveryItem,
    isSaved: Boolean,
    onSaveClick: () -> Unit,
    onDiscussClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    
    val accentColor = remember(item.tags) {
        val firstTag = item.tags.firstOrNull()?.lowercase() ?: ""
        when {
            firstTag.contains("quantum") || firstTag.contains("physics") -> AccentViolet
            firstTag.contains("machine") || firstTag.contains("intelligence") || firstTag.contains("deep") || firstTag.contains("ai") -> AccentTeal
            firstTag.contains("climate") || firstTag.contains("environmental") -> AccentEmerald
            firstTag.contains("bio") || firstTag.contains("gen") -> AccentCyan
            firstTag.contains("math") -> AccentIndigo
            else -> AccentAmber
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.82f), // Matte glassmorphic slate background
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.02f)
                )
            )
        ),
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Dynamic Accent Category Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor, accentColor.copy(alpha = 0.3f))
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header row: Journal & Relevance match
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Text(
                            text = "${item.journal} (${item.year})",
                            color = EntropiColors.Text2,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .widthIn(max = 180.dp)
                        )
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.12f),
                        border = BorderStroke(0.5.dp, accentColor.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "${item.relevanceScore}% Match",
                                color = accentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = SpaceGroteskFontFamily
                            )
                        }
                    }
                }

                // Title
                MarkdownText(
                    markdown = item.title,
                    color = EntropiColors.Text1,
                    fontSize = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                // Authors
                Text(
                    text = "By ${item.authors}",
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Tags row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = EntropiColors.Text2,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

                // Abstract details section
                Text(
                    text = "ABSTRACT & INSIGHTS",
                    color = EntropiColors.Text3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                MarkdownText(
                    markdown = item.abstractText,
                    color = EntropiColors.Text2,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Quick Interaction Buttons Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Discuss button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onDiscussClick() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Discuss with AI",
                            tint = AccentViolet,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Discuss", color = EntropiColors.Text3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Read PDF / Web Reader button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        if (!item.pdfUrl.isNullOrBlank()) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.pdfUrl))
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "Full PDF not available for this article", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (!item.pdfUrl.isNullOrBlank()) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.02f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = "Read PDF",
                            tint = if (!item.pdfUrl.isNullOrBlank()) AccentCyan else Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Read PDF", color = if (!item.pdfUrl.isNullOrBlank()) EntropiColors.Text3 else Color.White.copy(alpha = 0.2f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Save button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSaveClick() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isSaved) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Save",
                            tint = if (isSaved) accentColor else EntropiColors.Text1,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (isSaved) "Saved" else "Save", color = EntropiColors.Text3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Share button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onShareClick() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = AccentAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Share", color = EntropiColors.Text3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
