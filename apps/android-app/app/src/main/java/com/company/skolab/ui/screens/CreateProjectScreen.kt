package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import com.company.skolab.model.SkoLabUser
import com.company.skolab.ui.theme.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class CreateProjectMember(val uid: String, val name: String, val email: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateProjectScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance() }

    val cachedUser by authManager.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUserId = cachedUser?.uid ?: ""
    val currentUserName = cachedUser?.name ?: "SkoLab User"
    val currentUserEmail = cachedUser?.email ?: ""

    var newProjName by remember { mutableStateOf("") }
    var newProjDesc by remember { mutableStateOf("") }

    var nameError by remember { mutableStateOf<String?>(null) }

    var memberEmailInput by remember { mutableStateOf("") }
    var isSearchingMember by remember { mutableStateOf(false) }
    var membersList by remember { mutableStateOf<List<CreateProjectMember>>(emptyList()) }

    var isSaving by remember { mutableStateOf(false) }

    // Suggested users close to the user's research focus
    var suggestedUsers by remember { mutableStateOf<List<SkoLabUser>>(emptyList()) }

    LaunchedEffect(cachedUser) {
        val focus = cachedUser?.researchFocus
        if (!focus.isNullOrBlank()) {
            db.collection("researchers")
                .limit(100)
                .get()
                .addOnSuccessListener { snapshot ->
                    val list = snapshot.toObjects(SkoLabUser::class.java)
                    val currentWords = focus.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
                    suggestedUsers = list.filter { other ->
                        other.uid != currentUserId && (
                            other.researchFocus.lowercase().contains(focus.lowercase()) ||
                            focus.lowercase().contains(other.researchFocus.lowercase()) ||
                            currentWords.any { word -> other.researchFocus.lowercase().contains(word) }
                        )
                    }
                }
        }
    }

    val query = memberEmailInput.lowercase().trim()
    val filteredSuggestions = remember(memberEmailInput, suggestedUsers) {
        if (query.isEmpty()) {
            emptyList()
        } else {
            suggestedUsers.filter {
                it.name.lowercase().contains(query) || it.email.lowercase().contains(query)
            }
        }
    }

    val onAddMember: () -> Unit = {
        if (memberEmailInput.isNotBlank() && !isSearchingMember) {
            isSearchingMember = true
            db.collection("researchers")
                .whereEqualTo("email", memberEmailInput.trim())
                .get()
                .addOnSuccessListener { querySnapshot ->
                    isSearchingMember = false
                    val doc = querySnapshot.documents.firstOrNull()
                    if (doc != null) {
                        val researcher = doc.toObject(SkoLabUser::class.java)
                        if (researcher != null) {
                            if (researcher.uid == currentUserId) {
                                Toast.makeText(context, "You are automatically added as the owner.", Toast.LENGTH_SHORT).show()
                            } else if (membersList.none { it.uid == researcher.uid }) {
                                membersList = membersList + CreateProjectMember(
                                    uid = researcher.uid,
                                    name = researcher.name,
                                    email = researcher.email
                                )
                                memberEmailInput = ""
                            } else {
                                Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "This user is not registered in the app.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    isSearchingMember = false
                    Toast.makeText(context, "Error looking up user: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Co-Lab",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (newProjName.isBlank()) {
                        nameError = "Project name cannot be empty"
                        return@ExtendedFloatingActionButton
                    }
                    if (currentUserId.isBlank()) {
                        Toast.makeText(context, "Still loading your account. Please wait.", Toast.LENGTH_SHORT).show()
                        return@ExtendedFloatingActionButton
                    }
                    isSaving = true
                    scope.launch {
                        try {
                            val newId = db.collection("collabs_groups").document().id
                            val memberMaps = membersList.map {
                                mapOf("uid" to it.uid, "name" to it.name, "email" to it.email)
                            }
                            val allMembers = listOf(
                                mapOf("uid" to currentUserId, "name" to currentUserName, "email" to currentUserEmail)
                            ) + memberMaps
                            val allUids = listOf(currentUserId) + membersList.map { it.uid }

                            val projectData = hashMapOf(
                                "id" to newId,
                                "name" to newProjName.trim(),
                                "description" to newProjDesc.trim(),
                                "ownerUid" to currentUserId,
                                "ownerName" to currentUserName,
                                "members" to allMembers,
                                "memberUids" to allUids,
                                "recentEquations" to "\\mathcal{H} = J \\sum \\mathbf{S}_i \\cdot \\mathbf{S}_j",
                                "manuscriptProgress" to 0.0f,
                                "createdAt" to System.currentTimeMillis()
                            )

                            db.collection("collabs_groups").document(newId).set(projectData).await()
                            Toast.makeText(context, "Co-Lab created!", Toast.LENGTH_SHORT).show()
                            onBack()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed to create project. Please try again.", Toast.LENGTH_SHORT).show()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                containerColor = AccentTeal,
                contentColor = Color.White
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create Lab", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Project Identity
            OutlinedTextField(
                value = newProjName,
                onValueChange = { 
                    newProjName = it 
                    if (it.isNotBlank()) nameError = null
                },
                label = { Text("Co-Lab Name", color = TextMuted) },
                isError = nameError != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderLight,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorBorderColor = SkoLabWarning
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            OutlinedTextField(
                value = newProjDesc,
                onValueChange = { newProjDesc = it },
                label = { Text("Objective (Optional)", color = TextMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderLight,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = false,
                maxLines = 3
            )

            // Members Input
            Text(
                "Collaborators",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = memberEmailInput,
                    onValueChange = { memberEmailInput = it },
                    placeholder = { Text("Invite by email", color = TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentTeal,
                        unfocusedBorderColor = BorderLight,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onAddMember() }
                    )
                )
                IconButton(
                    onClick = onAddMember,
                    modifier = Modifier
                        .background(BgElevated, RoundedCornerShape(12.dp))
                        .size(56.dp),
                    enabled = !isSearchingMember
                ) {
                    if (isSearchingMember) {
                        CircularProgressIndicator(color = AccentTeal, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PersonAdd, "Add Member", tint = AccentTeal)
                    }
                }
            }

            AnimatedVisibility(visible = filteredSuggestions.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = BgElevated,
                    border = BorderStroke(1.dp, BorderLight),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        filteredSuggestions.take(5).forEach { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (membersList.none { it.uid == user.uid }) {
                                            membersList = membersList + CreateProjectMember(
                                                uid = user.uid,
                                                name = user.name,
                                                email = user.email
                                            )
                                        } else {
                                            Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                                        }
                                        memberEmailInput = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, null, tint = AccentTeal, modifier = Modifier.size(18.dp))
                                Column {
                                    Text(user.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("${user.email} • ${user.researchFocus}", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = membersList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    membersList.forEach { member ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = BgElevated,
                            border = BorderStroke(1.dp, BorderLight)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(member.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { membersList = membersList.filter { it.uid != member.uid } },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Remove", tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp)) // padding for FAB
        }
    }
}
