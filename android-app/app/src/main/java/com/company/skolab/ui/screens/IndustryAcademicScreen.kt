package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.AcademicPaperInfo
import com.company.skolab.network.TieupIdea
import com.company.skolab.network.IndustryAcademicTieupsResponse
import com.company.skolab.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndustryAcademicScreen(
    onNavigateToReader: (String, String) -> Unit,
    onNavigateToAuthor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    val apiService = AppDependencies.apiService
    
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUserId = cachedUser?.uid ?: ""

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tieupsData by remember { mutableStateOf<IndustryAcademicTieupsResponse?>(null) }
    
    // "trending" or "futuristic"
    var selectedTab by remember { mutableStateOf("trending") }

    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            isLoading = true
            errorMessage = null
            try {
                val res = apiService.getIndustryAcademicTieups(currentUserId)
                if (res != null) {
                    tieupsData = res
                } else {
                    errorMessage = "Failed to load tie-up recommendations. Try again later."
                }
            } catch (e: Exception) {
                errorMessage = "Network error: ${e.localizedMessage ?: e.toString()}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgPrimary)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "FRONTIERS",
                    color = PRIMARY,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Industry-Academia Ideas",
                    color = TEXT_PRIMARY,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = SpaceGroteskFontFamily
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BgPrimary)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Custom Navigation Tab Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabOptions = listOf(
                        "trending" to "Trending Tie-ups 🔥",
                        "futuristic" to "Futuristic Horizons 🚀"
                    )
                    tabOptions.forEach { (route, label) ->
                        val isSelected = selectedTab == route
                        val backgroundGlow = if (isSelected) {
                            Brush.linearGradient(listOf(PRIMARY, AccentTeal))
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(backgroundGlow)
                                .clickable { selectedTab = route }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TEXT_SECONDARY,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PRIMARY)
                    }
                } else if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = AccentRose, modifier = Modifier.size(48.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = TEXT_SECONDARY,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    isLoading = true
                                    errorMessage = null
                                    // Trigger refresh
                                    tieupsData = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
                    val ideasList = if (selectedTab == "trending") {
                        tieupsData?.trending ?: emptyList()
                    } else {
                        tieupsData?.futuristic ?: emptyList()
                    }

                    if (ideasList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.TravelExplore, null, tint = TEXT_MUTED, modifier = Modifier.size(48.dp))
                                Text(
                                    text = "No tie-up recommendations found yet. Keep searching papers and updating your research profile to discover matching technologies!",
                                    color = TEXT_SECONDARY,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(ideasList) { idea ->
                                TieupIdeaCard(
                                    idea = idea,
                                    onNavigateToReader = onNavigateToReader,
                                    onNavigateToAuthor = onNavigateToAuthor
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
fun TieupIdeaCard(
    idea: TieupIdea,
    onNavigateToReader: (String, String) -> Unit,
    onNavigateToAuthor: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = idea.title,
                        color = TEXT_PRIMARY,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = SpaceGroteskFontFamily
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = idea.description,
                        color = TEXT_SECONDARY,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            if (idea.papers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(10.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = PRIMARY,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Associated Research Papers",
                            color = TEXT_PRIMARY,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(PRIMARY.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${idea.papers.size}",
                                color = PRIMARY,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TEXT_MUTED,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        idea.papers.forEach { paper ->
                            AssociatedPaperItem(
                                paper = paper,
                                onNavigateToReader = onNavigateToReader,
                                onNavigateToAuthor = onNavigateToAuthor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssociatedPaperItem(
    paper: AcademicPaperInfo,
    onNavigateToReader: (String, String) -> Unit,
    onNavigateToAuthor: (String) -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = SURFACE_SUBTLE,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, BORDER),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val title = paper.title
                val doi = paper.doi ?: ""
                if (title.isNotBlank()) {
                    onNavigateToReader(title, doi)
                } else {
                    Toast.makeText(context, "DRAFT - Paper reader only available for parsed publications", Toast.LENGTH_SHORT).show()
                }
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = paper.title,
                color = TEXT_PRIMARY,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Authors list
                val authorsStr = paper.authors.take(2).joinToString(", ")
                Text(
                    text = if (paper.authors.size > 2) "$authorsStr et al." else authorsStr.ifEmpty { "Unknown Author" },
                    color = TEXT_SECONDARY,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                // Citation / Year stats
                Text(
                    text = "${paper.year ?: 2024} · ⭐ ${paper.citations} Citations",
                    color = TEXT_MUTED,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!paper.journal.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = paper.journal,
                    color = AccentTeal,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
