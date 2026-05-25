package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.SemanticFeedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticPapersScreen(
    onPaperClick: (String) -> Unit,
    viewModel: SemanticFeedViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        // Uniform dot grid background matching overall app aesthetics
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val dotSpacing = 28.dp.toPx()
            val dotRadius = 0.8.dp.toPx()
            val cols = (size.width / dotSpacing).toInt() + 1
            val rows = (size.height / dotSpacing).toInt() + 1
            for (col in 0..cols) {
                for (row in 0..rows) {
                    drawCircle(
                        color = Color(0xFF8891B8).copy(alpha = 0.04f),
                        radius = dotRadius,
                        center = androidx.compose.ui.geometry.Offset(col * dotSpacing, row * dotSpacing)
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                    Text(
                        text = "Semantic Feed",
                        color = EntropiColors.Text,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                    Text(
                        text = "Curated based on: ${uiState.userResearchFocus}",
                        color = EntropiColors.Gold2,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (uiState.isLoading && uiState.semanticPapers.isEmpty()) {
                items(3) {
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        PaperShimmerCard() 
                    }
                }
            } else {
                itemsIndexed(uiState.semanticPapers) { index, paper ->
                    val animatedProgress = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        delay(index * 40L)
                        animatedProgress.animateTo(1f, animationSpec = tween(300))
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .graphicsLayer(
                                alpha = animatedProgress.value,
                                translationY = (1f - animatedProgress.value) * 20.dp.value
                            )
                    ) {
                        val accentColor = when (index % 3) {
                            0 -> EntropiColors.Gold1
                            1 -> EntropiColors.Cyan
                            else -> EntropiColors.Red
                        }
                        PaperFeedCard( 
                            paper = paper,
                            accentColor = accentColor,
                            onClick = { onPaperClick(paper.id) }
                        )
                    }
                }
            }
            
            if (!uiState.isLoading && uiState.semanticPapers.isEmpty()) {
                item {
                    Text(
                        text = "No relevant papers found for your research focus.",
                        color = EntropiColors.Text2,
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    )
                }
            }
        }

        // Scroll to Top FAB
        val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 20.dp)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showFab,
                enter = androidx.compose.animation.scaleIn(spring(stiffness = Spring.StiffnessLow)),
                exit = androidx.compose.animation.scaleOut(spring(stiffness = Spring.StiffnessLow))
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = EntropiColors.Gold1,
                    contentColor = EntropiColors.Background,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Scroll to top",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
