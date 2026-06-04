package com.company.skolab.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.company.skolab.model.Author
import com.company.skolab.network.ApiService
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.AuthorResponse
import com.company.skolab.network.AuthorSuggestion
import com.company.skolab.network.ToolUsage
import com.company.skolab.ui.components.primitives.EmptyState
import com.company.skolab.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorDetailScreen(
    authorName: String,
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    apiService: ApiService = AppDependencies.apiService
) {
    var authorData by remember { mutableStateOf<AuthorResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    LaunchedEffect(authorName) {
        val parts = authorName.split("|")
        val displayName = parts.firstOrNull() ?: authorName
        val authorId = if (parts.size > 1) parts[1] else null

        val cacheKey = authorId ?: displayName
        val cached = apiService.getCachedAuthorProfile(cacheKey)
        if (cached != null) {
            authorData = cached
            isLoading = false
        } else {
            isLoading = true
        }

        try {
            if (cached == null || !cached.metrics_computed) {
                // Attempt dynamic OpenAlex / backend database lookup
                var data: com.company.skolab.network.AuthorResponse? = null
                try {
                    data = apiService.searchAuthor(displayName, authorId, forceRefresh = cached != null)
                } catch (e: Exception) {
                    Log.e("AuthorDetailScreen", "API searchAuthor failed", e)
                }
                
                if (data == null && cached == null) {
                    // Stop falling back to MockData (dummy profiles)
                    android.widget.Toast.makeText(context, "SkoLab server unreachable. Please try again later.", android.widget.Toast.LENGTH_LONG).show()
                }
                
                if (data != null) {
                    authorData = data
                }
            }
        } catch (e: Exception) {
            Log.e("AuthorDetailScreen", "Failed to retrieve researcher metrics", e)
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        containerColor = BgPrimary
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            when {
                isLoading -> {
                    LoadingResearcherSkeleton()
                }
                authorData != null -> {
                    ResearcherProfileView(
                        author = authorData!!,
                        apiService = apiService,
                        scope = scope,
                        onNavigateToReader = { _, doi ->
                            onPaperClick(doi)
                        },
                        onUpdateData = { authorData = it },
                        onSelectResearcher = { name, id ->
                            isLoading = true
                            scope.launch {
                                var newData: com.company.skolab.network.AuthorResponse? = null
                                try {
                                    newData = apiService.searchAuthor(name, id)
                                } catch (e: Exception) {
                                    Log.e("AuthorDetailScreen", "onSelectResearcher API searchAuthor failed", e)
                                }
                                if (newData == null) {
                                    android.widget.Toast.makeText(context, "SkoLab server unreachable. Please try again later.", android.widget.Toast.LENGTH_LONG).show()
                                }
                                authorData = newData
                                isLoading = false
                            }
                        }
                    )
                }
                else -> {
                    EmptyState(
                        title = "Researcher not found",
                        message = "No record found for \"$authorName\". Check connection and try again.",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// toAuthorResponse() removed — was dead code producing hardcoded fake metrics.
// cacheAuthorProfile() is a no-op so this was never shown to users.
// Author profile data is fetched live from the backend in LaunchedEffect above.

