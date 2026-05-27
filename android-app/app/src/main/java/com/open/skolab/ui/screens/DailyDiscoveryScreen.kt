package com.open.skolab.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.ui.theme.DisplayFontFamily
import com.open.skolab.ui.theme.EntropiColors
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.open.skolab.ui.components.MarkdownText

// Mock Data for the stack
data class DiscoveryItem(
    val id: String,
    val title: String,
    val authors: String,
    val abstractText: String,
    val tags: List<String>
)

val mockDiscoveryStack = listOf(
    DiscoveryItem("1", "Attention Is All You Need", "Vaswani et al.", "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks...", listOf("Deep Learning", "NLP")),
    DiscoveryItem("2", "BERT: Pre-training of Deep Bidirectional Transformers", "Devlin et al.", "We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers...", listOf("NLP", "Transformers")),
    DiscoveryItem("3", "Generative Adversarial Nets", "Goodfellow et al.", "We propose a new framework for estimating generative models via an adversarial process, in which we simultaneously train two models...", listOf("GANs", "Computer Vision")),
    DiscoveryItem("4", "Adam: A Method for Stochastic Optimization", "Kingma et al.", "We introduce Adam, an algorithm for first-order gradient-based optimization of stochastic objective functions...", listOf("Optimization", "Machine Learning")),
    DiscoveryItem("5", "ResNet: Deep Residual Learning for Image Recognition", "He et al.", "Deeper neural networks are more difficult to train. We present a residual learning framework to ease the training of networks...", listOf("Computer Vision", "Deep Learning"))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyDiscoveryScreen(
    onBack: () -> Unit,
    onPaperSaved: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { mockDiscoveryStack.size })
    val coroutineScope = rememberCoroutineScope()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {
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
                        text = "${pagerState.currentPage + 1} of ${mockDiscoveryStack.size}",
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
                    item = mockDiscoveryStack[page],
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
                                if (pagerState.currentPage < mockDiscoveryStack.size - 1) {
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
                            onPaperSaved(mockDiscoveryStack[pagerState.currentPage].id)
                            coroutineScope.launch {
                                if (pagerState.currentPage < mockDiscoveryStack.size - 1) {
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
