package com.company.skolab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.skolab.ui.components.PaperCard
import com.company.skolab.ui.components.StaggeredVisibility
import com.company.skolab.ui.components.primitives.EmptyState
import com.company.skolab.ui.components.primitives.ErrorState
import com.company.skolab.ui.components.primitives.GlassSearchBar
import com.company.skolab.ui.layout.ScreenInsets
import com.company.skolab.ui.layout.screenHorizontalPadding
import com.company.skolab.ui.theme.*
import com.company.skolab.viewmodel.SearchUiState
import com.company.skolab.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    onPaperClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    searchQuery: String = "",
    showSearchBar: Boolean = true,
    viewModel: SearchViewModel = viewModel()
) {
    var searchMode by remember { mutableStateOf(0) } // 0 = Papers, 1 = Profiles
    var localQuery by remember { mutableStateOf("") }
    val query = if (showSearchBar) localQuery else searchQuery
    
    // Sync external query to viewModel when showSearchBar is false
    LaunchedEffect(searchQuery, showSearchBar) {
        if (!showSearchBar) {
            viewModel.onSearchQueryChanged(searchQuery)
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .screenHorizontalPadding()
                .padding(top = 16.dp, bottom = 12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = BgCard.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Papers", "Profiles").forEachIndexed { index, title ->
                        val isSelected = searchMode == index
                        Surface(
                            onClick = { searchMode = index },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(9.dp),
                            color = if (isSelected) AccentTeal else Color.Transparent
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) TextOnAccent else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        when (searchMode) {
            0 -> {
                if (showSearchBar) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .screenHorizontalPadding()
                            .padding(bottom = 8.dp)
                    ) {
                        GlassSearchBar(
                            value = localQuery,
                            onValueChange = {
                                localQuery = it
                                viewModel.onSearchQueryChanged(it)
                            },
                            placeholder = "Search papers, topics, authors...",
                            onClear = {
                                localQuery = ""
                                viewModel.onSearchQueryChanged("")
                            }
                        )
                    }
                }

                when (val state = uiState) {
                    is SearchUiState.Idle -> {
                        val recentList by SearchViewModel.recentSearches.collectAsStateWithLifecycle()
                        val trendingSearches = listOf(
                            "Artificial Intelligence",
                            "Quantum Computing",
                            "CRISPR Gene Editing",
                            "Climate Change",
                            "Superconductivity"
                        )
                        
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .screenHorizontalPadding()
                                .padding(top = 16.dp)
                        ) {
                            if (recentList.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Recent Searches",
                                        style = Typography.titleMedium,
                                        color = SkoLabTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(onClick = { SearchViewModel.clearRecentSearches() }) {
                                        Text("Clear All", color = SkoLabDisruption, style = Typography.labelMedium)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    recentList.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    localQuery = item
                                                    viewModel.onSearchQueryChanged(item)
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = "Recent",
                                                tint = SkoLabTextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = item,
                                                style = Typography.bodyLarge,
                                                color = SkoLabTextPrimary
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                            
                            Text(
                                text = "Trending Topics",
                                style = Typography.titleMedium,
                                color = SkoLabTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val chunked = trendingSearches.chunked(2)
                                chunked.forEach { rowItems ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowItems.forEach { item ->
                                            Surface(
                                                onClick = {
                                                    localQuery = item
                                                    viewModel.onSearchQueryChanged(item)
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                color = SkoLabSurface,
                                                border = BorderStroke(1.dp, SkoLabDivider),
                                                modifier = Modifier.height(38.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                     Icon(
                                                         imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                                         contentDescription = "Trending",
                                                         tint = SkoLabNovelty,
                                                         modifier = Modifier.size(16.dp)
                                                     )
                                                    Text(
                                                        text = item,
                                                        color = SkoLabTextPrimary,
                                                        style = Typography.labelMedium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.weight(1f))
                            
                            EmptyState(
                                title = "Explore the literature",
                                message = "Search OpenAlex to surface papers and research quality signals.",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                            )
                        }
                    }
                    is SearchUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentTeal)
                        }
                    }
                    is SearchUiState.Error -> {
                        ErrorState(
                            message = state.message,
                            modifier = Modifier.fillMaxSize(),
                            onRetry = { viewModel.onSearchQueryChanged(query) }
                        )
                    }
                    is SearchUiState.Success -> {
                        if (state.results.isEmpty() && query.isNotBlank()) {
                            EmptyState(
                                title = "No matches",
                                message = "No results for \"$query\". Try a broader query.",
                                modifier = Modifier.fillMaxSize(),
                                action = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                    ) {
                                        Text(
                                            text = "Or explore one of these popular topics:",
                                            style = Typography.labelMedium,
                                            color = SkoLabTextSecondary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            listOf("Artificial Intelligence", "Quantum Computing").forEach { item ->
                                                Surface(
                                                    onClick = {
                                                        localQuery = item
                                                        viewModel.onSearchQueryChanged(item)
                                                    },
                                                    shape = RoundedCornerShape(20.dp),
                                                    color = SkoLabSurface,
                                                    border = BorderStroke(1.dp, SkoLabDivider),
                                                    modifier = Modifier.height(38.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                                                        Text(text = item, color = SkoLabTextPrimary, style = Typography.labelMedium)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        } else if (state.results.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = ScreenInsets.horizontal,
                                    end = ScreenInsets.horizontal,
                                    bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(state.results, key = { _, paper -> paper.id }) { index, paper ->
                                    StaggeredVisibility(index = index) {
                                        PaperCard(
                                            paper = paper,
                                            onClick = { onPaperClick(paper.id) },
                                            modifier = Modifier.fillMaxWidth(),
                                            searchQuery = query
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                SearchProfilesScreen(
                    onAuthorClick = onAuthorClick,
                    searchQuery = query,
                    showSearchBar = showSearchBar
                )
            }
        }
    }
}

