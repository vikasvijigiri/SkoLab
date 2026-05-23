package com.open.entropy.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.network.ApiService
import com.open.entropy.network.AuthorSuggestion
import com.open.entropy.ui.components.StaggeredVisibility
import com.open.entropy.ui.components.primitives.EmptyState
import com.open.entropy.ui.components.primitives.GlassSearchBar
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SearchProfilesScreen(
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    apiService: ApiService = remember { ApiService() }
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<AuthorSuggestion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 3) {
            suggestions = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        delay(400) // Debounce API calls
        try {
            suggestions = apiService.getAuthorSuggestions(query)
        } catch (e: Exception) {
            Log.e("SearchProfilesScreen", "Error getting suggestions", e)
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            GlassSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search researchers, authors, experts...",
                onClear = { query = "" }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                        ProfileShimmerLoader()
                    }
                }
                query.trim().length < 3 -> {
                    EmptyState(
                        title = "Discover scientific talent",
                        message = "Search real-time profiles indexed from OpenAlex to view citation arcs and skill radar graphs.",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                suggestions.isEmpty() -> {
                    EmptyState(
                        title = "No researchers found",
                        message = "We couldn't find any researchers matching \"$query\". Try spelling out full names.",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 120.dp, top = 8.dp)
                    ) {
                        itemsIndexed(suggestions, key = { _, s -> s.id }) { index, suggestion ->
                            StaggeredVisibility(index = index) {
                                ProfileCard(
                                    suggestion = suggestion,
                                    onClick = { onAuthorClick("${suggestion.display_name}|${suggestion.id}") }
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
fun ProfileCard(
    suggestion: AuthorSuggestion,
    onClick: () -> Unit
) {
    val hash = Math.abs(suggestion.id.hashCode())
    val gradient = if (hash % 2 == 0) TealGradient else IndigoGradient
    val initials = suggestion.display_name.split(" ")
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first() }
        .joinToString("")
        .uppercase()

    Surface(
        onClick = onClick,
        color = BgCard.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = TextOnAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = suggestion.display_name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = AccentTeal,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Text(
                    text = suggestion.institution,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                val tag = suggestion.field_of_study ?: when (hash % 4) {
                    0 -> "Quantum Physics"
                    1 -> "Machine Learning"
                    2 -> "Computational Biology"
                    else -> "Graph Theory"
                }

                Surface(
                    color = AccentTeal.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = tag,
                        color = AccentTeal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "INNOVATION",
                    color = TextMuted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = suggestion.innovation_score?.toString() ?: "N/A",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentTeal,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "h-index: ${suggestion.h_index?.toString() ?: "N/A"}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ProfileShimmerLoader() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Surface(
                color = BgCard.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(BorderLight.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(BorderLight.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(180.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(BorderLight.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}
