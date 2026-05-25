import os

path = 'app/src/main/java/com/open/entropy/viewmodel/FeedViewModel.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace setResearchFilter
content = content.replace(
    '''    fun setResearchFilter(filter: ResearchFilter) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
    }''',
    '''    fun setResearchFilter(filter: ResearchFilter) {
        if (_uiState.value.selectedFilter == filter) return
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
        loadAllFeedData()
    }'''
)

# Replace loadMoreConnections network call
old_call = '''                val newNetwork = apiService.getNetworkCollaborators(expandId, limit = 10, offset = currentOffset, excludeIds = excludeIds)'''
new_call = '''                val selectedField = currentState.selectedFilter.label.let { if (it == "All Fields") null else it }
                val newNetwork = apiService.getNetworkCollaborators(expandId, limit = 10, offset = currentOffset, excludeIds = excludeIds, field = selectedField)'''
content = content.replace(old_call, new_call)

# Replace placeholder mappings in loadMoreConnections (18 papers)
old_map = '''                                radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                                careerArc = emptyList(),
                                topPapers = emptyList(),
                                collaborators = emptyList(),
                                totalPapers = 18,
                                avgDisruptionScore = 0.75f'''
new_map = '''                                radarScores = mapOf("Disruption" to (collab.relevance_score / 100f), "Novelty" to (collab.relevance_score / 100f * 0.9f)),
                                careerArc = emptyList(),
                                topPapers = emptyList(),
                                collaborators = emptyList(),
                                totalPapers = collab.total_publications ?: 10,
                                avgDisruptionScore = collab.relevance_score / 100f'''
content = content.replace(old_map, new_map)

# LoadAllFeedData mapping block
old_block = '''                try {
                    Log.i("FeedViewModel", "Searching author profile for name: $name")
                    userAuthorProfile = apiService.searchAuthor(name)
                    if (userAuthorProfile != null) {
                        Log.i("FeedViewModel", "Fetching network collaborators for id: ${userAuthorProfile.id}")
                        val networkList = apiService.getNetworkCollaborators(userAuthorProfile.id, limit = 10, offset = 0)
                        if (networkList.isNotEmpty()) {
                            connections = networkList.map { collab ->
                                val isDepth1 = collab.connection_path.contains("Co-authored")
                                Connection(
                                    author = Author(
                                        id = collab.id,
                                        name = collab.name,
                                        institution = collab.institution,
                                        country = "US",
                                        orcidId = null,
                                        fingerprintType = collab.field ?: focus,
                                        radarScores = mapOf("Disruption" to 0.85f, "Novelty" to 0.72f),
                                        careerArc = emptyList(),
                                        topPapers = emptyList(),
                                        collaborators = emptyList(),
                                        totalPapers = 18,
                                        avgDisruptionScore = 0.75f
                                    ),
                                    depth = if (isDepth1) 1 else 2,
                                    mutualCount = collab.relevance_score,
                                    tags = listOf(collab.field ?: "Collaborator"),
                                    connectionPath = collab.connection_path,
                                    openStatus = "Available for Collaboration",
                                    papersCollaborated = collab.papers_collaborated ?: 0,
                                    totalPublications = collab.total_publications ?: 0,
                                    hIndex = collab.h_index ?: 0
                                )
                            }
                        }
                    } else {
                        Log.w("FeedViewModel", "Failed to find author profile for $name, no connections to show.")
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Error fetching connections from OpenAlex API", e)
                }'''

new_block = '''                try {
                    Log.i("FeedViewModel", "Searching author profile for name: $name")
                    userAuthorProfile = apiService.searchAuthor(name)
                    val targetId = userAuthorProfile?.id ?: "fallback_seed"
                    val selectedField = _uiState.value.selectedFilter.label.let { if (it == "All Fields") null else it }
                    Log.i("FeedViewModel", "Fetching network collaborators for id: $targetId, field: $selectedField")
                    val networkList = apiService.getNetworkCollaborators(targetId, limit = 10, offset = 0, field = selectedField)
                    if (networkList.isNotEmpty()) {
                        connections = networkList.map { collab ->
                            val isDepth1 = collab.connection_path.contains("Co-authored")
                            Connection(
                                author = Author(
                                    id = collab.id,
                                    name = collab.name,
                                    institution = collab.institution,
                                    country = "US",
                                    orcidId = null,
                                    fingerprintType = collab.field ?: focus,
                                    radarScores = mapOf("Disruption" to (collab.relevance_score / 100f), "Novelty" to (collab.relevance_score / 100f * 0.9f)),
                                    careerArc = emptyList(),
                                    topPapers = emptyList(),
                                    collaborators = emptyList(),
                                    totalPapers = collab.total_publications ?: 10,
                                    avgDisruptionScore = collab.relevance_score / 100f
                                ),
                                depth = if (isDepth1) 1 else 2,
                                mutualCount = collab.relevance_score,
                                tags = listOf(collab.field ?: "Collaborator"),
                                connectionPath = collab.connection_path,
                                openStatus = "Available for Collaboration",
                                papersCollaborated = collab.papers_collaborated ?: 0,
                                totalPublications = collab.total_publications ?: 0,
                                hIndex = collab.h_index ?: 0
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FeedViewModel", "Error fetching connections from OpenAlex API", e)
                }'''

content = content.replace(old_block, new_block)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Updated FeedViewModel.kt')
