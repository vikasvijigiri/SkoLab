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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.company.skolab.model.SkoLabUser
import com.company.skolab.ui.theme.AccentTeal
import com.company.skolab.ui.theme.BgCard
import com.company.skolab.ui.theme.BgElevated
import com.company.skolab.ui.theme.BorderLight
import com.company.skolab.ui.theme.SyneFontFamily
import com.company.skolab.ui.theme.TextMuted
import com.company.skolab.ui.theme.TextPrimary
import com.company.skolab.ui.theme.TextSecondary
import kotlinx.coroutines.tasks.await
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.company.skolab.ui.theme.SkoLabWarning
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.GatewayClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteMemberScreen(
    projectId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val currentUserId = remember { AppDependencies.authManager.currentUser?.uid ?: "" }
    
    var inviteEmailInput by remember { mutableStateOf("") }
    var isInvitingMember by remember { mutableStateOf(false) }
    var isLoadingProject by remember { mutableStateOf(true) }
    var projectName by remember { mutableStateOf("") }
    var currentMembers by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var currentMemberUids by remember { mutableStateOf<List<String>>(emptyList()) }
    var emailError by remember { mutableStateOf<String?>(null) }

    val onInvite: () -> Unit = {
        val emailToSearch = inviteEmailInput.trim()
        if (emailToSearch.isBlank()) {
            emailError = "Email cannot be empty"
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailToSearch).matches()) {
            emailError = "Please enter a valid email address"
        } else {
            emailError = null
            // Check if already a member
            val alreadyMember = currentMembers.any { 
                val memberEmail = it["email"] as? String ?: ""
                memberEmail.equals(emailToSearch, ignoreCase = true) 
            }
            if (alreadyMember) {
                Toast.makeText(context, "User is already a member of this project.", Toast.LENGTH_SHORT).show()
            } else {
                isInvitingMember = true
                db.collection("researchers")
                    .whereEqualTo("email", emailToSearch)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        val doc = querySnapshot.documents.firstOrNull()
                        if (doc != null) {
                            val researcher = doc.toObject(SkoLabUser::class.java)
                            if (researcher != null) {
                                val newMember = mapOf(
                                    "uid" to researcher.uid,
                                    "name" to researcher.name,
                                    "email" to researcher.email
                                )
                                val updatedMembers = currentMembers + newMember
                                val updatedUids = currentMemberUids + researcher.uid

                                db.collection("collabs_groups").document(projectId)
                                    .update(
                                        "members", updatedMembers,
                                        "memberUids", updatedUids
                                    )
                                    .addOnSuccessListener {
                                        // Log invitation to backend recommendation engine in background
                                        if (currentUserId.isNotEmpty()) {
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val base = com.company.skolab.network.ServerLocator.baseUrl.value ?: "http://10.0.2.2:8080"
                                                    val url = "$base/api/v1/recommendations/peers/invite"
                                                    val client = GatewayClient.instance
                                                    val jsonBody = JSONObject().apply {
                                                        put("user_id", currentUserId)
                                                        put("peer_email", researcher.email)
                                                        put("peer_uid", researcher.uid)
                                                    }
                                                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                                                    val requestBody = jsonBody.toString().toRequestBody(mediaType)
                                                    val request = Request.Builder().url(url).post(requestBody).build()
                                                    client.newCall(request).execute().use { /* ignore */ }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        }
                                        isInvitingMember = false
                                        Toast.makeText(context, "${researcher.name} added to project!", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                    .addOnFailureListener { e ->
                                        isInvitingMember = false
                                        Toast.makeText(context, "Failed to update project: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            } else {
                                isInvitingMember = false
                                Toast.makeText(context, "Error parsing user data.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            isInvitingMember = false
                            Toast.makeText(context, "This user is not registered in the app.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        isInvitingMember = false
                        Toast.makeText(context, "Error looking up user: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    LaunchedEffect(projectId) {
        try {
            val doc = db.collection("collabs_groups").document(projectId).get().await()
            if (doc.exists()) {
                projectName = doc.getString("name") ?: "Project"
                @Suppress("UNCHECKED_CAST")
                val membersList = doc.get("members") as? List<Map<String, Any>> ?: emptyList()
                currentMembers = membersList
                @Suppress("UNCHECKED_CAST")
                currentMemberUids = doc.get("memberUids") as? List<String> ?: emptyList()
            }
        } catch (e: Exception) {
            // Handle error silently or show toast
        } finally {
            isLoadingProject = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Invite to Project",
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
        containerColor = com.company.skolab.ui.theme.SkoLabColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (isLoadingProject) {
                        CircularProgressIndicator(color = AccentTeal, modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        Text(
                            text = "Add collaborators to '$projectName' by their email. They must be registered in the app.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )

                        OutlinedTextField(
                            value = inviteEmailInput,
                            onValueChange = { 
                                inviteEmailInput = it 
                                if (it.isNotBlank()) emailError = null
                            },
                            placeholder = { Text("collaborator@example.com", color = TextMuted) },
                            isError = emailError != null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated,
                                errorBorderColor = SkoLabWarning,
                                errorLabelColor = SkoLabWarning
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { onInvite() }
                            )
                        )
                        if (emailError != null) {
                            Text(
                                text = emailError ?: "",
                                color = SkoLabWarning,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Button(
                            onClick = onInvite,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isInvitingMember && !isLoadingProject,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            if (isInvitingMember) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Add Member", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
