package com.company.skolab.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.DisplayFontFamily
import com.company.skolab.ui.theme.EntropiColors
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.company.skolab.ui.components.MarkdownText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.skolab.viewmodel.DailyDiscoveryViewModel
import com.company.skolab.viewmodel.DiscoveryItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyDiscoveryScreen(
    onBack: () -> Unit,
    onPaperSaved: (String) -> Unit,
    viewModel: DailyDiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val discoveryItems = uiState.discoveryItems
    val isLoading = uiState.isLoading
    val errorMessage = uiState.errorMessage
    val coroutineScope = rememberCoroutineScope()


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = EntropiColors.Gold2)
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
                        color = Color.Red,
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
                        colors = ButtonDefaults.buttonColors(containerColor = EntropiColors.Gold2)
                    ) {
                        Text("Go Back", color = Color.Black)
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = EntropiColors.Text1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Daily Discovery",
                            color = EntropiColors.Text1,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DisplayFontFamily
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} of ${discoveryItems.size}",
                            color = EntropiColors.Gold2,
                            fontSize = 14.sp
                        )
                    }
                }
                
                // Pager for Swipeable Cards
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    pageSpacing = 16.dp
                ) { page ->
                    val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    
                    DiscoveryCard(
                        item = discoveryItems[page],
                        modifier = Modifier
                            .graphicsLayer {
                                // Scale effect
                                val scale = 1f - (pageOffset.absoluteValue * 0.15f).coerceIn(0f, 0.15f)
                                scaleX = scale
                                scaleY = scale
                                // Alpha effect
                                alpha = 1f - (pageOffset.absoluteValue * 0.5f).coerceIn(0f, 0.5f)
                            }
                    )
                }
                
                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pass Button
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(EntropiColors.Card, CircleShape)
                            .clickable {
                                coroutineScope.launch {
                                    if (pagerState.currentPage < discoveryItems.size - 1) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Pass", tint = EntropiColors.Text2, modifier = Modifier.size(32.dp))
                    }
                    
                    // Save Button
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Brush.linearGradient(listOf(EntropiColors.Gold1, EntropiColors.Gold2)),
                                CircleShape
                            )
                            .clickable {
                                onPaperSaved(discoveryItems[pagerState.currentPage].id)
                                coroutineScope.launch {
                                    if (pagerState.currentPage < discoveryItems.size - 1) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    } else {
                                        onBack() // All done!
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Save", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveryCard(item: DiscoveryItem, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = EntropiColors.Card,
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            MarkdownText(
                markdown = item.title,
                color = EntropiColors.Text1,
                fontSize = 24.sp,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = item.authors,
                color = EntropiColors.Gold2,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .background(EntropiColors.Gold1, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = tag, color = EntropiColors.Gold1, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "ABSTRACT",
                color = EntropiColors.Text3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            MarkdownText(
                markdown = item.abstractText,
                color = EntropiColors.Text2,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
