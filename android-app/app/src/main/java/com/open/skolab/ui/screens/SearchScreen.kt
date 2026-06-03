package com.open.skolab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.open.skolab.ui.components.PaperCard
import com.open.skolab.ui.components.StaggeredVisibility
import com.open.skolab.ui.components.primitives.EmptyState
import com.open.skolab.ui.components.primitives.ErrorState
import com.open.skolab.ui.components.primitives.GlassSearchBar
import com.open.skolab.ui.layout.ScreenInsets
import com.open.skolab.ui.layout.screenHorizontalPadding
import com.open.skolab.ui.theme.*
import com.open.skolab.viewmodel.SearchUiState
import com.open.skolab.viewmodel.SearchViewModel

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
                        EmptyState(
                            title = "Explore the literature",
                            message = "Search OpenAlex to surface papers and research quality signals.",
                            modifier = Modifier.fillMaxSize()
                        )
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
                                modifier = Modifier.fillMaxSize()
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
                                            modifier = Modifier.fillMaxWidth()
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

