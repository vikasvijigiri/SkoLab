package com.open.entropy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.ui.components.PaperCard
import com.open.entropy.ui.components.StaggeredVisibility
import com.open.entropy.ui.components.primitives.EmptyState
import com.open.entropy.ui.components.primitives.ErrorState
import com.open.entropy.ui.components.primitives.GlassSearchBar
import com.open.entropy.ui.components.primitives.SectionCaption
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.layout.screenHorizontalPadding
import com.open.entropy.ui.theme.*
import com.open.entropy.viewmodel.SearchUiState
import com.open.entropy.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    onPaperClick: (String) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    var query by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .screenHorizontalPadding()
                .padding(top = 12.dp, bottom = 8.dp)
        ) {
            SectionCaption(text = "OpenAlex Papers", modifier = Modifier.padding(bottom = 8.dp))
            GlassSearchBar(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.onSearchQueryChanged(it)
                },
                placeholder = "Search papers, topics, authors…",
                onClear = {
                    query = ""
                    viewModel.onSearchQueryChanged("")
                }
            )
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
                    CircularProgressIndicator(color = ResQitDisruption)
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
                            bottom = ScreenInsets.bottomNavClearance
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
}
