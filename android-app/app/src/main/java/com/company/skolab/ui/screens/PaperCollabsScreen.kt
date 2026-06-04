package com.company.skolab.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import com.google.firebase.firestore.FirebaseFirestore
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.OrbitMetrics
import com.company.skolab.ui.components.MarkdownText
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CollabMember(
    val uid: String = "",
    val name: String = "",
    val email: String = ""
)

data class ProjectCollab(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val members: List<CollabMember> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val recentEquations: String = "",
    val manuscriptProgress: Float = 0f,
    val createdAt: Long = 0L
) {
    val activeCoAuthors: List<String>
        @com.google.firebase.firestore.Exclude
        get() = members.map { it.name }.filter { name ->
            name.lowercase() != "you"
        }
}

data class CollabTask(
    val id: String = "",
    val title: String = "",
    val assignee: String = "",
    @field:JvmField val isCompleted: Boolean = false
)

data class CollabEvent(
    val author: String,
    val action: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperCollabsScreen(
    savedStateHandle: SavedStateHandle? = null,
    startTab: String = "orbit_network", // "orbit_network" or "workspaces"
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    onNavigateToCreateProject: () -> Unit = {},
    onNavigateToInviteMember: (String) -> Unit = {},
    onNavigateToCreateTask: (String) -> Unit = {},
    onNavigateToExternalInvite: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUser = authManager.currentUser
    val currentUserId = currentUser?.uid ?: ""
    val currentUserName = currentUser?.displayName ?: (cachedUser?.name ?: "SkoLab User")
    val currentUserEmail = currentUser?.email ?: (cachedUser?.email ?: "user@university.edu")

    val db = remember { FirebaseFirestore.getInstance() }
    var dbProjects by remember { mutableStateOf<List<ProjectCollab>>(emptyList()) }

    // Tab is locked to startTab — each bottom-nav tab shows only its own mode
    var currentTab by remember { mutableStateOf(startTab) } // "orbit_network" or "workspaces"

    DisposableEffect(currentUserId) {
        if (currentUserId.isEmpty()) {
            onDispose {}
        } else {
            val listener = db.collection("collabs_groups")
                .whereArrayContains("memberUids", currentUserId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        android.util.Log.e("PaperCollabsScreen", "Error listening to collab groups", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.toObjects(ProjectCollab::class.java)
                        dbProjects = list.sortedByDescending { it.createdAt }
                    }
                }
            onDispose {
                listener.remove()
            }
        }
    }

    val defaultProjects = emptyList<ProjectCollab>()

    val projects = remember(dbProjects) {
        dbProjects.ifEmpty { defaultProjects }
    }

    var selectedProjectIndex by remember { mutableStateOf(0) }
    val currentProject = projects.getOrNull(selectedProjectIndex)
    var showProjectDropdown by remember { mutableStateOf(false) }

    var suggestedCollaborators by remember { mutableStateOf<List<com.company.skolab.network.AuthorSuggestion>>(emptyList()) }
    var similarResearchers by remember { mutableStateOf<List<com.company.skolab.network.AuthorSuggestion>>(emptyList()) }
    var isLoadingSuggestions by remember { mutableStateOf(false) }

    // Real orbit metrics from OpenAlex
    var orbitMetrics by remember { mutableStateOf<OrbitMetrics?>(null) }
    var isLoadingOrbitMetrics by remember { mutableStateOf(false) }
    var userOpenAlexId by remember { mutableStateOf("") }

    val apiService = com.company.skolab.di.AppDependencies.apiService
    val userFocus = cachedUser?.researchFocus ?: ""
    val userName = currentUserName

    // Fetch real orbit metrics: first resolve OpenAlex author ID, then fetch metrics
    LaunchedEffect(userName, userFocus) {
        if (userName.isNotBlank() && userName != "SkoLab User") {
            isLoadingOrbitMetrics = true
            try {
                // Resolve the user's OpenAlex author ID
                val profile = apiService.searchAuthor(userName, focus = userFocus.ifBlank { null })
                val authorId = profile?.id ?: ""
                userOpenAlexId = authorId
                if (authorId.isNotBlank()) {
                    orbitMetrics = apiService.getOrbitMetrics(authorId)
                }
            } catch (e: Exception) {
                android.util.Log.e("PaperCollabsScreen", "Failed to fetch orbit metrics", e)
            } finally {
                isLoadingOrbitMetrics = false
            }
        }
    }

    LaunchedEffect(userFocus) {
        if (userFocus.isNotEmpty() && userFocus != "Researcher" && userFocus != "General Research") {
            isLoadingSuggestions = true
            try {
                val list = apiService.getSimilarAuthors(userFocus, limit = 8)
                if (list.isNotEmpty()) {
                    suggestedCollaborators = list.take(4)
                    similarResearchers = list.drop(4).take(4)
                } else {
                    suggestedCollaborators = emptyList()
                    similarResearchers = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("PaperCollabsScreen", "Failed to fetch similar authors", e)
            } finally {
                isLoadingSuggestions = false
            }
        }
    }

    var membersPresence by remember { mutableStateOf<Map<String, com.company.skolab.model.SkoLabUser>>(emptyMap()) }

    DisposableEffect(currentProject?.id) {
        val proj = currentProject
        if (proj == null || proj.id.isEmpty()) {
            membersPresence = emptyMap()
            onDispose {}
        } else {
            val uids = proj.memberUids.filter { it != currentUserId && !it.startsWith("default_") && it != "you_uid" }
            if (uids.isEmpty()) {
                membersPresence = emptyMap()
                onDispose {}
            } else {
                val listener = db.collection("researchers")
                    .whereIn("uid", uids)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.e("PaperCollabsScreen", "Error listening to members presence", error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            val presenceMap = snapshot.toObjects(com.company.skolab.model.SkoLabUser::class.java)
                                .associateBy { it.uid }
                            membersPresence = presenceMap
                        }
                    }
                onDispose {
                    listener.remove()
                }
            }
        }
    }

    var tasks by remember { mutableStateOf<List<CollabTask>>(emptyList()) }
    var timelineLogs by remember { mutableStateOf<List<CollabEvent>>(emptyList()) }
    val chatMessages = remember { mutableStateListOf<String>() }

    DisposableEffect(currentProject?.id) {
        var tasksListener: com.google.firebase.firestore.ListenerRegistration? = null
        var activityListener: com.google.firebase.firestore.ListenerRegistration? = null
        var messagesListener: com.google.firebase.firestore.ListenerRegistration? = null

        val proj = currentProject
        if (proj == null || proj.id.isEmpty() || proj.id.startsWith("default_")) {
            tasks = emptyList()
            timelineLogs = emptyList()
            chatMessages.clear()
            onDispose {}
        } else {
            tasks = emptyList()
            timelineLogs = emptyList()
            chatMessages.clear()

            tasksListener = db.collection("collabs_groups").document(proj.id)
                .collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        tasks = snapshot.toObjects(CollabTask::class.java)
                    }
                }
            
            activityListener = db.collection("collabs_groups").document(proj.id)
                .collection("activity")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        timelineLogs = snapshot.documents.map { doc ->
                            CollabEvent(
                                author = doc.getString("author") ?: "",
                                action = doc.getString("action") ?: "",
                                time = doc.getString("time") ?: "just now"
                            )
                        }
                    }
                }

            messagesListener = db.collection("collabs_groups").document(proj.id)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        chatMessages.clear()
                        snapshot.documents.forEach { doc ->
                            val sender = doc.getString("senderName") ?: ""
                            val text = doc.getString("text") ?: ""
                            chatMessages.add("$sender: $text")
                        }
                    }
                }
        }

        onDispose {
            tasksListener?.remove()
            activityListener?.remove()
            messagesListener?.remove()
        }
    }
    
    var groupMessageInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Video Call simulation
    var showVideoSync by remember { mutableStateOf(false) }
    var callConnectedTime by remember { mutableStateOf(0) }

    if (showVideoSync) {
        LaunchedEffect(Unit) {
            while (showVideoSync) {
                delay(1000)
                callConnectedTime++
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Header Screen Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = if (startTab == "workspaces") "COLLABS" else "ORBIT",
                            color = PRIMARY,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (startTab == "workspaces") "Active Workspaces" else "Relationship Intelligence",
                            color = TEXT_PRIMARY,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            if (currentTab == "orbit_network") {
                // ORBIT NETWORK TAB — Real Data, Industry-Standard Intelligence Dashboard
                val context = LocalContext.current

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {

                    // ── HERO METRICS CARD — Real data from OpenAlex ─────────────────
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(PRIMARY, AccentTeal)
                                    )
                                )
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "YOUR RESEARCH ORBIT",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp
                                    )
                                    if (isLoadingOrbitMetrics) {
                                        CircularProgressIndicator(
                                            color = Color.White.copy(alpha = 0.7f),
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White.copy(alpha = 0.15f))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "LIVE",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    val metrics = orbitMetrics
                                    OrbitMetricCell(
                                        count = if (metrics != null) "${metrics.collaborator_count}" else "—",
                                        label = "Co-Authors",
                                        tint = Color.White
                                    )
                                    OrbitMetricCell(
                                        count = if (metrics != null) "${metrics.institution_count}" else "—",
                                        label = "Institutions",
                                        tint = Color.White
                                    )
                                    OrbitMetricCell(
                                        count = if (metrics != null) "${metrics.works_count}" else "—",
                                        label = "Publications",
                                        tint = Color.White
                                    )
                                    OrbitMetricCell(
                                        count = if (metrics != null) "h${metrics.h_index}" else "—",
                                        label = "h-Index",
                                        tint = StarGold
                                    )
                                }
                                if (orbitMetrics != null && orbitMetrics!!.cited_by_count > 0) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    androidx.compose.material3.HorizontalDivider(
                                        color = Color.White.copy(alpha = 0.2f),
                                        thickness = 0.5.dp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = StarGold,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${orbitMetrics!!.cited_by_count.let { if (it > 1000) "${it / 1000}k+" else "$it" }} total citations across your publications",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── ORBIT CANVAS — Real co-author node names ───────────────────
                    item {
                        Column {
                            Text(
                                text = "Your Research Orbit Network",
                                color = TEXT_PRIMARY,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Live co-author nodes orbiting your research center — inner ring shows primary collaborators.",
                                color = TEXT_SECONDARY,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    item {
                        Surface(
                            color = SURFACE,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, BORDER),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val realInnerNodes = orbitMetrics?.top_coauthors?.take(3)?.map {
                                    val parts = it.split(" ")
                                    if (parts.size >= 2) "${parts[0].take(1)}. ${parts.last()}" else it.take(12)
                                } ?: listOf("Loading...", "", "")
                                val realOuterNodes = orbitMetrics?.top_coauthors?.drop(3)?.take(4)?.map {
                                    val parts = it.split(" ")
                                    if (parts.size >= 2) "${parts[0].take(1)}. ${parts.last()}" else it.take(12)
                                } ?: listOf("", "", "", "")
                                RelationshipOrbitCanvas(
                                    innerNodeLabels = realInnerNodes,
                                    outerNodeLabels = realOuterNodes,
                                    centerLabel = currentUserName.split(" ").firstOrNull() ?: "You"
                                )
                            }
                        }
                    }

                    // ── POTENTIAL COLLABORATORS ────────────────────────────────────
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.People,
                                    contentDescription = null,
                                    tint = PRIMARY,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Potential Collaborators",
                                    color = TEXT_PRIMARY,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (suggestedCollaborators.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PRIMARY.copy(alpha = 0.1f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        "${suggestedCollaborators.size} found",
                                        color = PRIMARY,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(2) {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth().height(120.dp)
                                    ) {}
                                }
                            }
                        } else if (suggestedCollaborators.isEmpty()) {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PersonSearch,
                                        contentDescription = null,
                                        tint = TEXT_MUTED,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = if (userFocus.isBlank())
                                            "Set your research focus in your profile to discover matching collaborators"
                                        else
                                            "No matching collaborators found for '$userFocus' — try a broader research focus",
                                        color = TEXT_SECONDARY,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                suggestedCollaborators.forEachIndexed { index, sugg ->
                                    // Real match score: factor in h-index and field match
                                    val matchScore = when {
                                        sugg.field_of_study?.contains(userFocus, ignoreCase = true) == true ->
                                            (88 + (sugg.h_index ?: 0).coerceAtMost(9)).coerceAtMost(97)
                                        sugg.h_index != null && sugg.h_index > 20 -> 84
                                        sugg.h_index != null && sugg.h_index > 10 -> 76
                                        sugg.h_index != null -> 68
                                        else -> 62 - index * 2
                                    }
                                    val reason = buildString {
                                        if (!sugg.field_of_study.isNullOrBlank()) {
                                            append("Specialises in ${sugg.field_of_study}")
                                            if (userFocus.isNotBlank()) append(", overlapping with your focus in $userFocus")
                                        } else if (userFocus.isNotBlank()) {
                                            append("Active researcher in the $userFocus domain")
                                        } else {
                                            append("Research profile aligns with your publication network")
                                        }
                                        sugg.h_index?.takeIf { it > 0 }?.let { append(". h-index: $it") }
                                    }
                                    val tags = buildList {
                                        sugg.field_of_study?.take(24)?.let { add(it) }
                                            ?: run { if (userFocus.isNotBlank()) add(userFocus) }
                                        if (sugg.h_index != null && sugg.h_index > 15) add("High Impact")
                                        else if (matchScore >= 85) add("Strong Match")
                                    }
                                    OrbitCollaboratorRecommendationCard(
                                        name = sugg.display_name,
                                        institution = sugg.institution,
                                        match = matchScore,
                                        hIndex = sugg.h_index,
                                        reason = reason,
                                        tags = tags,
                                        onConnect = {
                                            val cleanName = sugg.display_name.lowercase().replace(" ", ".")
                                            val subject = "Collaboration Inquiry — ${userFocus.ifBlank { "Research" }}"
                                            val body = "Dear ${sugg.display_name.split(" ").firstOrNull() ?: "Professor"},\n\nI came across your work in ${sugg.field_of_study ?: userFocus} and believe there may be a great opportunity for collaboration. I would love to connect and explore a potential research partnership.\n\nBest regards,\n$currentUserName"
                                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                                data = Uri.parse("mailto:")
                                                putExtra(Intent.EXTRA_EMAIL, arrayOf("$cleanName@university.edu"))
                                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                                putExtra(Intent.EXTRA_TEXT, body)
                                            }
                                            try { context.startActivity(Intent.createChooser(emailIntent, "Send Collaboration Request")) }
                                            catch (e: Exception) { android.widget.Toast.makeText(context, "No email app found", android.widget.Toast.LENGTH_SHORT).show() }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── RESEARCHERS SIMILAR TO YOU ─────────────────────────────────
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Biotech,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Researchers Similar To You",
                                color = TEXT_PRIMARY,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(2) {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth().height(72.dp)
                                    ) {}
                                }
                            }
                        } else if (similarResearchers.isEmpty()) {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (userFocus.isBlank()) "Update your research focus to see researchers similar to you."
                                    else "No similar researchers found for '$userFocus'.",
                                    color = TEXT_SECONDARY,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                similarResearchers.forEach { sugg ->
                                    OrbitSimilarResearcherCard(
                                        name = sugg.display_name,
                                        institution = sugg.institution,
                                        field = sugg.field_of_study ?: userFocus,
                                        hIndex = sugg.h_index
                                    )
                                }
                            }
                        }
                    }

                    // ── RISING RESEARCHERS — sorted by relevance score ─────────────
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = null,
                                tint = CustomOrangeGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Rising in Your Domain",
                                color = TEXT_PRIMARY,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    item {
                        if (isLoadingSuggestions) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(2) {
                                    Surface(
                                        color = SURFACE,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, BORDER),
                                        modifier = Modifier.fillMaxWidth().height(80.dp)
                                    ) {}
                                }
                            }
                        } else {
                            // Rising = suggestedCollaborators sorted by h_index desc (higher h = more established momentum)
                            val risingList = suggestedCollaborators
                                .sortedByDescending { it.h_index ?: 0 }
                                .take(3)
                            if (risingList.isEmpty()) {
                                Surface(
                                    color = SURFACE,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, BORDER),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (userFocus.isBlank()) "Set your research focus to discover rising researchers."
                                        else "No rising researchers found in '$userFocus'.",
                                        color = TEXT_SECONDARY,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(16.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    risingList.forEach { sugg ->
                                        val hIndexVal = sugg.h_index ?: 0
                                        val momentum = when {
                                            hIndexVal > 30 -> "Highly Established"
                                            hIndexVal > 15 -> "Rapidly Growing"
                                            hIndexVal > 5  -> "Emerging Voice"
                                            else           -> "New Entry"
                                        }
                                        val reason = buildString {
                                            append("Active in ${sugg.field_of_study ?: userFocus}")
                                            if (hIndexVal > 0) append(" · h-index $hIndexVal")
                                            append(" · ${sugg.institution}")
                                        }
                                        OrbitRisingResearcherCard(
                                            name = sugg.display_name,
                                            momentum = momentum,
                                            reason = reason
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // WORKSPACES TAB: The original Firestore collaboration screen!
                Surface(
                    color = SURFACE_SUBTLE,
                    border = BorderStroke(0.5.dp, BORDER),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = currentProject != null) {
                                    showProjectDropdown = !showProjectDropdown
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Workspaces,
                                contentDescription = null,
                                tint = PRIMARY,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentProject?.name ?: "No Active Workspace",
                                color = TEXT_PRIMARY,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                            if (currentProject != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showProjectDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = PRIMARY,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { onNavigateToCreateProject() },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SURFACE)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    val proj = currentProject
                                    if (proj == null) {
                                        Toast.makeText(context, "Please create a project first.", Toast.LENGTH_SHORT).show()
                                    } else if (proj.id.startsWith("default_")) {
                                        Toast.makeText(context, "Call sync requires registered SkoLab co-authors.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showVideoSync = true
                                        callConnectedTime = 0
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (currentProject != null) PRIMARY else BORDER)
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null, tint = if (currentProject != null) TEXT_ON_PRIMARY else TEXT_MUTED, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Project selection dropdown
                    if (currentProject != null) {
                        DropdownMenu(
                            expanded = showProjectDropdown,
                            onDismissRequest = { showProjectDropdown = false },
                            modifier = Modifier
                                .background(SURFACE)
                                .border(BorderStroke(0.5.dp, BORDER), RoundedCornerShape(12.dp))
                        ) {
                            projects.forEachIndexed { index, proj ->
                                DropdownMenuItem(
                                    text = {
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(text = proj.name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(text = proj.description, color = TEXT_SECONDARY, fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedProjectIndex = index
                                        showProjectDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val proj = currentProject
                    if (proj == null) {
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Workspaces,
                                        contentDescription = null,
                                        tint = PRIMARY.copy(alpha = 0.5f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "No Active Workspace",
                                        color = TEXT_PRIMARY,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "Create shared paper drafts, interactive roadmaps, equations blackboard, and dynamic group discussions with your co-authors.",
                                        color = TEXT_SECONDARY,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 19.sp
                                    )
                                    Button(
                                        onClick = { onNavigateToCreateProject() },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Create Workspace Project", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // Collaborators profiles horizontal ring
                        item {
                            Text(
                                text = "ACTIVE COLLABORATORS",
                                color = TEXT_MUTED,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            ) {
                                // You Avatar
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val myInitials = remember(currentUserName) {
                                        currentUserName.split(" ")
                                            .filter { it.isNotEmpty() }
                                            .take(2)
                                            .map { it.first() }
                                            .joinToString("")
                                            .uppercase()
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(PRIMARY.copy(alpha = 0.1f))
                                            .border(1.dp, PRIMARY, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(myInitials.ifEmpty { "ME" }, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("You", color = TEXT_PRIMARY, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                // Co-authors
                                val otherMembers = proj.members.filter {
                                    it.uid != currentUserId && it.name.lowercase() != "you" && !it.name.equals(currentUserName, ignoreCase = true)
                                }

                                otherMembers.forEach { member ->
                                    val initials = member.name.split(" ").map { it.take(1) }.joinToString("").uppercase()
                                    val presence = membersPresence[member.uid]
                                    val isOnline = presence?.isOnline == true

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { onNavigateToChat(member.name, member.uid) }
                                    ) {
                                        Box(contentAlignment = Alignment.BottomEnd) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(SURFACE_SUBTLE)
                                                    .border(1.dp, BORDER, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(initials.ifEmpty { "U" }, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isOnline) WhatsAppTealGreen else Color.Gray)
                                                    .border(1.dp, SURFACE, CircleShape)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(member.name.split(" ").firstOrNull() ?: member.name, color = TEXT_SECONDARY, fontSize = 10.sp)
                                    }
                                }

                                // Add collaborator button
                                IconButton(
                                    onClick = { onNavigateToInviteMember(proj.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(SURFACE)
                                        .border(1.dp, BORDER, CircleShape)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Blackboard math equation
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToWorkspace(proj.name) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.EditNote, contentDescription = null, tint = PRIMARY, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Blackboard Latex Draft", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(PremiumDarkSpace)
                                            .padding(12.dp)
                                    ) {
                                        MarkdownText(
                                            markdown = "$$" + proj.recentEquations + "$$",
                                            color = PremiumLightText,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Manuscript Draft Progress", color = TEXT_SECONDARY, fontSize = 12.sp)
                                        Text("${(proj.manuscriptProgress * 100).toInt()}%", color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { proj.manuscriptProgress },
                                        color = PRIMARY,
                                        trackColor = SURFACE_SUBTLE,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                    )
                                }
                            }
                        }

                        // Roadmap Tasks Checklist
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("ROADMAP & TASKS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Icon(
                                            imageVector = Icons.Default.AddCircleOutline,
                                            contentDescription = null,
                                            tint = PRIMARY,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable { onNavigateToCreateTask(proj.id) }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    tasks.forEachIndexed { idx, task ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (proj.id.startsWith("default_")) {
                                                        tasks = tasks.map { if (it.id == task.id) it.copy(isCompleted = !it.isCompleted) else it }
                                                    } else {
                                                        db.collection("collabs_groups").document(proj.id)
                                                            .collection("tasks").document(task.id)
                                                            .update("isCompleted", !task.isCompleted)
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (task.isCompleted) WhatsAppTealGreen else TEXT_MUTED,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = task.title,
                                                    color = if (task.isCompleted) TEXT_MUTED else TEXT_PRIMARY,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(text = "Assignee: ${task.assignee}", color = TEXT_SECONDARY, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Activity logs
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("COLLABORATION ACTIVITY LOGS", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    timelineLogs.forEach { log ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 4.dp)
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(PRIMARY)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("${log.author} - ${log.time}", color = TEXT_SECONDARY, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                Text(log.action, color = TEXT_PRIMARY, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // In-Workspace Group Discussion Chat board
                        item {
                            Surface(
                                color = SURFACE,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BORDER),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("DISCUSSION BOARD", color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    chatMessages.forEach { msg ->
                                        val splitMsg = msg.split(": ")
                                        val sender = splitMsg.getOrNull(0) ?: ""
                                        val body = splitMsg.getOrNull(1) ?: msg
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(sender, color = PRIMARY, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text(body, color = TEXT_PRIMARY, fontSize = 12.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = groupMessageInput,
                                            onValueChange = { groupMessageInput = it },
                                            placeholder = { Text("Post to blackboard chat...", fontSize = 12.sp) },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = PRIMARY,
                                                unfocusedBorderColor = BORDER
                                            )
                                        )
                                        IconButton(
                                            onClick = {
                                                if (groupMessageInput.isNotBlank()) {
                                                    val text = groupMessageInput.trim()
                                                    groupMessageInput = ""
                                                    if (proj.id.startsWith("default_")) {
                                                        chatMessages.add("You: $text")
                                                    } else {
                                                        val msgId = db.collection("collabs_groups").document(proj.id).collection("messages").document().id
                                                        db.collection("collabs_groups").document(proj.id).collection("messages").document(msgId).set(
                                                            hashMapOf("id" to msgId, "senderName" to currentUserName, "text" to text, "timestamp" to System.currentTimeMillis())
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(PRIMARY)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TEXT_ON_PRIMARY, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Jitsi Sync room simulated overlay
        AnimatedVisibility(
            visible = showVideoSync,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Surface(
                color = PremiumChatRoomBg,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentProject?.name ?: "",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                            color = WhatsAppTealGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(
                                modifier = Modifier.size(120.dp, 150.dp),
                                color = PremiumChatCardBg,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.5.dp, PRIMARY)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("You", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Speaking...", color = PRIMARY, fontSize = 10.sp)
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.size(120.dp, 150.dp),
                                color = PremiumChatCardBg,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.5.dp, BORDER)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Sumiran P.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val roomName = "SkoLabSecure_" + (currentProject?.id?.hashCode()?.toString() ?: "Default")
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.jit.si/$roomName"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(PRIMARY)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("Launch Live Call Sync", color = TEXT_ON_PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { showVideoSync = false },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(CallEndRed)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrbitMetricCell(count: String, label: String, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = count,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = tint
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = tint.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun RelationshipOrbitCanvas(
    innerNodeLabels: List<String> = listOf("Co-Author 1", "Co-Author 2", "Co-Author 3"),
    outerNodeLabels: List<String> = listOf("", "", "", ""),
    centerLabel: String = "You"
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbit")

    val rotationAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart),
        label = "inner"
    )
    val rotationAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(65000, easing = LinearEasing), RepeatMode.Restart),
        label = "outer"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val rad1 = 75.dp.toPx()
        val rad2 = 120.dp.toPx()

        // Glow behind center
        drawCircle(color = PRIMARY.copy(alpha = 0.06f * pulseScale), radius = 44.dp.toPx(), center = center)

        // Orbit tracks (dashed rings)
        drawCircle(
            color = BORDER.copy(alpha = 0.5f), radius = rad1, center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                1.2f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            )
        )
        drawCircle(
            color = BORDER.copy(alpha = 0.3f), radius = rad2, center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                0.8f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 14f), 0f)
            )
        )

        val innerColors = listOf(PRIMARY, AccentTeal, AccentViolet)
        val outerColors = listOf(CustomOrangeGold, BrandPurple, AccentEmerald, AccentCyan)

        // Inner orbit nodes
        val numInner = innerNodeLabels.size.coerceAtMost(3)
        for (i in 0 until numInner) {
            val angleRad = Math.toRadians((rotationAngle1 + (i * 360.0 / numInner)))
            val nc = Offset(
                (center.x + rad1 * Math.cos(angleRad)).toFloat(),
                (center.y + rad1 * Math.sin(angleRad)).toFloat()
            )
            val c = innerColors[i % innerColors.size]
            drawLine(color = c.copy(alpha = 0.25f), start = center, end = nc, strokeWidth = 1f.dp.toPx())
            drawCircle(color = c.copy(alpha = 0.12f * pulseScale), radius = 20.dp.toPx(), center = nc)
            drawCircle(color = c, radius = 8.dp.toPx(), center = nc)
        }

        // Outer orbit nodes
        val numOuter = outerNodeLabels.size.coerceAtMost(4)
        for (i in 0 until numOuter) {
            val angleRad = Math.toRadians((rotationAngle2 + (i * 360.0 / numOuter.coerceAtLeast(1))))
            val nc = Offset(
                (center.x + rad2 * Math.cos(angleRad)).toFloat(),
                (center.y + rad2 * Math.sin(angleRad)).toFloat()
            )
            val c = outerColors[i % outerColors.size]
            drawLine(color = c.copy(alpha = 0.18f), start = center, end = nc, strokeWidth = 0.7f.dp.toPx())
            drawCircle(color = c.copy(alpha = 0.09f), radius = 22.dp.toPx(), center = nc)
            drawCircle(color = c, radius = 5.5.dp.toPx(), center = nc)
        }

        // Center node — user
        drawCircle(color = PRIMARY.copy(alpha = 0.18f * pulseScale), radius = 28.dp.toPx(), center = center)
        drawCircle(color = PRIMARY, radius = 11.dp.toPx(), center = center)
        drawCircle(color = Color.White, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
fun OrbitCollaboratorRecommendationCard(
    name: String,
    institution: String,
    match: Int,
    hIndex: Int? = null,
    reason: String,
    tags: List<String>,
    onConnect: () -> Unit = {}
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Initials avatar
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PRIMARY.copy(alpha = 0.10f))
                            .border(1.dp, PRIMARY.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase(),
                            color = PRIMARY,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = institution, color = TEXT_SECONDARY, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hIndex != null && hIndex > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "h-index: $hIndex",
                                color = TEXT_MUTED,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Color-coded match score badge
                val matchBg = when {
                    match >= 88 -> AccentEmerald.copy(alpha = 0.12f)
                    match >= 75 -> AccentAmber.copy(alpha = 0.12f)
                    else        -> MATCH_SCORE_BG
                }
                val matchTxt = when {
                    match >= 88 -> EmeraldDeeper
                    match >= 75 -> AmberDeeper
                    else        -> MATCH_SCORE_TEXT
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(matchBg)
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "$match%",
                        color = matchTxt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = reason, color = TEXT_SECONDARY, fontSize = 12.sp, lineHeight = 17.sp)

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(3).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SURFACE_SUBTLE)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = tag, color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY, contentColor = TEXT_ON_PRIMARY),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send Collaboration Request", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OrbitSimilarResearcherCard(
    name: String,
    institution: String,
    field: String = "",
    hIndex: Int? = null
) {
    val tealColor = AccentTeal
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tealColor.copy(alpha = 0.10f))
                    .border(1.dp, tealColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase(),
                    color = tealColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = institution, color = TEXT_SECONDARY, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (field.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = field, color = TEXT_MUTED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (hIndex != null && hIndex > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "h$hIndex", color = tealColor, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text(text = "h-index", color = TEXT_MUTED, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun OrbitRisingResearcherCard(
    name: String,
    momentum: String,
    reason: String
) {
    Surface(
        color = SURFACE,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BORDER),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = name, color = TEXT_PRIMARY, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CustomOrangeGold.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = momentum,
                        color = CustomOrangeGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = reason, color = TEXT_SECONDARY, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

