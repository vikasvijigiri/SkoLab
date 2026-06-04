package com.company.skolab.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import com.company.skolab.ui.theme.AccentTeal
import com.company.skolab.ui.theme.BgCard
import com.company.skolab.ui.theme.BgElevated
import com.company.skolab.ui.theme.BorderLight
import com.company.skolab.ui.theme.SyneFontFamily
import com.company.skolab.ui.theme.TextMuted
import com.company.skolab.ui.theme.TextPrimary
import com.company.skolab.ui.theme.TextSecondary
import com.company.skolab.ui.theme.CallEndRed
import kotlinx.coroutines.launch

data class CreateProjectMember(val uid: String, val name: String, val email: String)

@OptIn(ExperimentalMaterial3Api::class)
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
    var newProjEq by remember { mutableStateOf("") }

    var memberEmailInput by remember { mutableStateOf("") }
    var isSearchingMember by remember { mutableStateOf(false) }
    var membersList by remember { mutableStateOf<List<CreateProjectMember>>(emptyList()) }

    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create Project Group",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = com.company.skolab.ui.theme.EntropiColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section: Details
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Project Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = AccentTeal
                    )
                    OutlinedTextField(
                        value = newProjName,
                        onValueChange = { newProjName = it },
                        label = { Text("Project Name", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedContainerColor = BgElevated,
                            unfocusedContainerColor = BgElevated
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newProjDesc,
                        onValueChange = { newProjDesc = it },
                        label = { Text("Description", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedContainerColor = BgElevated,
                            unfocusedContainerColor = BgElevated
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newProjEq,
                        onValueChange = { newProjEq = it },
                        label = { Text("Recent Equations (LaTeX format)", color = TextMuted) },
                        placeholder = { Text("e.g. \\mathcal{H} = J \\sum ...", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedContainerColor = BgElevated,
                            unfocusedContainerColor = BgElevated
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // Section: Members
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Initial Members (App Users only)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = AccentTeal
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = memberEmailInput,
                            onValueChange = { memberEmailInput = it },
                            placeholder = { Text("user@example.com", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (memberEmailInput.isBlank()) return@Button
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
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !isSearchingMember,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (isSearchingMember) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Add", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (membersList.isNotEmpty()) {
                        membersList.forEach { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BgElevated, RoundedCornerShape(10.dp))
                                    .border(1.dp, BorderLight, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(text = member.email, color = TextSecondary, fontSize = 12.sp)
                                }
                                IconButton(
                                    onClick = { membersList = membersList.filter { it.uid != member.uid } },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = CallEndRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Save Button
            Button(
                onClick = {
                    if (newProjName.isBlank()) {
                        Toast.makeText(context, "Project name cannot be empty.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSaving = true
                    val newId = db.collection("collabs_groups").document().id
                    
                    val memberMaps = membersList.map { 
                        mapOf("uid" to it.uid, "name" to it.name, "email" to it.email) 
                    }
                    val allMembers = listOf(mapOf("uid" to currentUserId, "name" to currentUserName, "email" to currentUserEmail)) + memberMaps
                    val allUids = listOf(currentUserId) + membersList.map { it.uid }

                    val projectData = hashMapOf(
                        "id" to newId,
                        "name" to newProjName.trim(),
                        "description" to newProjDesc.trim(),
                        "ownerUid" to currentUserId,
                        "ownerName" to currentUserName,
                        "members" to allMembers,
                        "memberUids" to allUids,
                        "recentEquations" to newProjEq.trim().ifBlank { "\\mathcal{H} = J \\sum \\mathbf{S}_i \\cdot \\mathbf{S}_j" },
                        "manuscriptProgress" to 0.0f,
                        "createdAt" to System.currentTimeMillis()
                    )
                    
                    db.collection("collabs_groups").document(newId).set(projectData)
                        .addOnSuccessListener {
                            Toast.makeText(context, "Project group created!", Toast.LENGTH_SHORT).show()
                            isSaving = false
                            onBack()
                        }
                        .addOnFailureListener {
                            isSaving = false
                            Toast.makeText(context, "Failed to create: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("Create Project", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

