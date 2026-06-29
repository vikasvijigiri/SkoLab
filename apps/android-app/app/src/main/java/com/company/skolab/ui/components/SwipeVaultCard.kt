package com.company.skolab.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.network.Work
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipeVaultCard(
    papers: List<Work>,
    onSavePaper: (Work) -> Unit,
    onSkipPaper: (Work) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember(papers) { mutableIntStateOf(0) }
    
    if (currentIndex >= papers.size || papers.isEmpty()) {
        // Empty state when stack is exhausted
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(130.dp),
            shape = RoundedCornerShape(16.dp),
            color = BgCard,
            border = BorderStroke(1.dp, BorderLight)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Swipe,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Swipe Stack Exhausted",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Check back later for fresh papers",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
        return
    }

    val paper = papers[currentIndex]
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    
    val rotation = offsetX * 0.05f
    val scale = (1f - (kotlin.math.abs(offsetX) / 1000f)).coerceIn(0.9f, 1f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .rotate(rotation)
            .pointerInput(paper) {
                detectDragGestures(
                    onDragEnd = {
                        if (offsetX > 250f) {
                            // Swipe Right -> Save
                            onSavePaper(paper)
                            currentIndex++
                        } else if (offsetX < -250f) {
                            // Swipe Left -> Skip
                            onSkipPaper(paper)
                            currentIndex++
                        }
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                colors = listOf(
                    BorderLight,
                    if (offsetX > 50f) AccentEmerald.copy(alpha = (offsetX / 300f).coerceIn(0.1f, 0.6f))
                    else if (offsetX < -50f) AccentRose.copy(alpha = (-offsetX / 300f).coerceIn(0.1f, 0.6f))
                    else BorderLight
                )
            )
        )
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(BgCard, BgSubtle)
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SWIPE VAULT",
                            color = AccentTeal,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = AccentIndigo.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = paper.journal ?: "Journal",
                                    color = AccentIndigo,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = paper.year?.toString() ?: "",
                                color = TextMuted,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = paper.title ?: "Untitled Research",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                }

                // Interactive control strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Action Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                onSkipPaper(paper)
                                currentIndex++
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AccentRose.copy(alpha = 0.12f))
                        ) {
                            Icon(Icons.Default.Close, null, tint = AccentRose, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                onSavePaper(paper)
                                currentIndex++
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AccentEmerald.copy(alpha = 0.12f))
                        ) {
                            Icon(Icons.Default.Favorite, null, tint = AccentEmerald, modifier = Modifier.size(14.dp))
                        }
                    }
                    
                    // Swipe helper indicator
                    Text(
                        text = if (offsetX > 100f) "Release to SAVE"
                               else if (offsetX < -100f) "Release to SKIP"
                               else "Swipe left to skip, right to save",
                        color = if (offsetX > 100f) AccentEmerald
                                else if (offsetX < -100f) AccentRose
                                else TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
