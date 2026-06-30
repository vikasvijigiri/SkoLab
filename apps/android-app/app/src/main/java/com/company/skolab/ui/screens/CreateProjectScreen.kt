package com.company.skolab.ui.screens

import android.widget.Toast
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
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

data class CreateProjectMember(val uid: String, val name: String, val email: String, val phone: String = "")
data class CollaboratorSuggestion(val name: String, val email: String, val isRegistered: Boolean, val researchFocus: String = "", val uid: String = "")

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
    var memberPhoneInput by remember { mutableStateOf("") }
    var isSearchingMember by remember { mutableStateOf(false) }
    var membersList by remember { mutableStateOf<List<CreateProjectMember>>(emptyList()) }

    var isSaving by remember { mutableStateOf(false) }

    val onAddPhoneMember: () -> Unit = {
        val phone = memberPhoneInput.trim()
        if (phone.isNotBlank()) {
            if (membersList.any { it.phone == phone }) {
                Toast.makeText(context, "Phone number already added", Toast.LENGTH_SHORT).show()
            } else {
                membersList = membersList + CreateProjectMember(
                    uid = "phone_${System.currentTimeMillis()}",
                    name = phone,
                    email = "",
                    phone = phone
                )
                memberPhoneInput = ""
            }
        }
    }

    // Suggested users close to the user's research focus
    var suggestedUsers by remember { mutableStateOf<List<SkoLabUser>>(emptyList()) }

    var hasContactsPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasContactsPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasContactsPermission) {
            requestPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

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

    var deviceContactSuggestions by remember { mutableStateOf<List<CollaboratorSuggestion>>(emptyList()) }

    LaunchedEffect(memberEmailInput, hasContactsPermission) {
        val q = memberEmailInput.trim()
        if (hasContactsPermission && q.length >= 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val results = mutableListOf<CollaboratorSuggestion>()
                try {
                    val contentResolver = context.contentResolver
                    val emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
                    val emailProjection = arrayOf(
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
                    )
                    val cursor = contentResolver.query(
                        emailUri,
                        emailProjection,
                        "${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$q%", "%$q%"),
                        null
                    )
                    cursor?.use { c ->
                        val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val email = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (email.isNotEmpty()) {
                                results.add(
                                    CollaboratorSuggestion(
                                        name = name.ifEmpty { "Device Contact" },
                                        email = email,
                                        isRegistered = false
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                deviceContactSuggestions = results.distinctBy { it.email }.take(8)
            }
        } else {
            deviceContactSuggestions = emptyList()
        }
    }

    var backendSuggestions by remember { mutableStateOf<List<CollaboratorSuggestion>>(emptyList()) }

    LaunchedEffect(memberEmailInput) {
        val q = memberEmailInput.trim()
        if (q.length >= 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val base = com.company.skolab.network.ServerLocator.baseUrl.value ?: "http://10.0.2.2:8080"
                    val url = "$base/api/v1/recommendations/peers?query=" +
                              android.net.Uri.encode(q) +
                              "&user_id=" + android.net.Uri.encode(currentUserId)
                              
                    val client = OkHttpClient.Builder()
                        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body()?.string()
                            if (bodyStr != null) {
                                val jsonArray = JSONArray(bodyStr)
                                val list = mutableListOf<CollaboratorSuggestion>()
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.getJSONObject(i)
                                    list.add(
                                        CollaboratorSuggestion(
                                            name = obj.getString("name"),
                                            email = obj.optString("email"),
                                            isRegistered = obj.getBoolean("is_registered"),
                                            researchFocus = obj.optString("research_focus"),
                                            uid = obj.optString("uid"),
                                            username = obj.optString("username")
                                        )
                                    )
                                }
                                backendSuggestions = list
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            backendSuggestions = emptyList()
        }
    }

    val filteredSuggestions = remember(memberEmailInput, suggestedUsers, deviceContactSuggestions, backendSuggestions) {
        val q = memberEmailInput.lowercase().trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            val registeredList = suggestedUsers
                .filter {
                    it.name.lowercase().contains(q) ||
                    it.email.lowercase().contains(q) ||
                    it.username.lowercase().contains(q) ||
                    it.authorName.lowercase().contains(q) ||
                    it.phone.contains(q)
                }
                .map {
                    CollaboratorSuggestion(
                        name = it.name,
                        email = it.email,
                        isRegistered = true,
                        researchFocus = it.researchFocus,
                        uid = it.uid,
                        username = it.username
                    )
                }
            (backendSuggestions + registeredList + deviceContactSuggestions).distinctBy { it.email }
        }
    }

    val onAddMember: () -> Unit = {
        val email = memberEmailInput.trim()
        if (email.isNotBlank() && !isSearchingMember) {
            isSearchingMember = true
            db.collection("researchers")
                .whereEqualTo("email", email)
                .get()
                .addOnSuccessListener { querySnapshot ->
                    isSearchingMember = false
                    val doc = querySnapshot.documents.firstOrNull()
                    if (doc != null) {
                        val researcher = doc.toObject(SkoLabUser::class.java)
                        if (researcher != null) {
                            if (researcher.uid == currentUserId) {
                                Toast.makeText(context, "You are automatically added as the owner.", Toast.LENGTH_SHORT).show()
                            } else if (membersList.none { it.email == researcher.email }) {
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
                        // Not registered, but allow adding as a pending external email invitation
                        if (membersList.none { it.email == email }) {
                            membersList = membersList + CreateProjectMember(
                                uid = "pending_${System.currentTimeMillis()}",
                                name = email.substringBefore("@"),
                                email = email
                            )
                            memberEmailInput = ""
                            Toast.makeText(context, "Added external invite for $email", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    isSearchingMember = false
                    if (membersList.none { it.email == email }) {
                        membersList = membersList + CreateProjectMember(
                            uid = "pending_${System.currentTimeMillis()}",
                            name = email.substringBefore("@"),
                            email = email
                        )
                        memberEmailInput = ""
                    }
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
                                mapOf("uid" to it.uid, "name" to it.name, "email" to it.email, "phone" to it.phone)
                            }
                            val allMembers = listOf(
                                mapOf("uid" to currentUserId, "name" to currentUserName, "email" to currentUserEmail, "phone" to "")
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

            // Email Row
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

            // Autocomplete dropdown matching Gmail contacts & similar registered researchers
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
                        filteredSuggestions.take(5).forEach { suggestion ->
                            val descText = if (suggestion.isRegistered) {
                                "Registered • ${suggestion.researchFocus}"
                            } else {
                                "Gmail Contact"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (membersList.none { it.email == suggestion.email }) {
                                            membersList = membersList + CreateProjectMember(
                                                uid = if (suggestion.isRegistered) suggestion.uid else "pending_${System.currentTimeMillis()}",
                                                name = suggestion.name,
                                                email = suggestion.email
                                            )
                                            // Log invitation to backend recommendation engine in background
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    val base = com.company.skolab.network.ServerLocator.baseUrl.value ?: "http://10.0.2.2:8080"
                                                    val url = "$base/api/v1/recommendations/peers/invite"
                                                    val client = OkHttpClient()
                                                    val jsonBody = JSONObject().apply {
                                                        put("user_id", currentUserId)
                                                        put("peer_email", suggestion.email)
                                                        if (suggestion.isRegistered) {
                                                            put("peer_uid", suggestion.uid)
                                                        }
                                                    }
                                                    val requestBody = RequestBody.create(
                                                        MediaType.parse("application/json; charset=utf-8"),
                                                        jsonBody.toString()
                                                    )
                                                    val request = Request.Builder().url(url).post(requestBody).build()
                                                    client.newCall(request).execute().use { /* ignore */ }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                                        }
                                        memberEmailInput = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (suggestion.isRegistered) Icons.Default.PersonAdd else Icons.Default.ContactPage,
                                    contentDescription = "Select Suggestion",
                                    tint = AccentTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    val displayName = if (suggestion.isRegistered && suggestion.username.isNotEmpty()) {
                                        "${suggestion.name} (@${suggestion.username})"
                                    } else {
                                        suggestion.name
                                    }
                                    Text(displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("${suggestion.email} • $descText", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Phone Row (Both fields present)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = memberPhoneInput,
                    onValueChange = { memberPhoneInput = it },
                    placeholder = { Text("Invite by phone number", color = TextMuted) },
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
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onAddPhoneMember() }
                    )
                )
                IconButton(
                    onClick = onAddPhoneMember,
                    modifier = Modifier
                        .background(BgElevated, RoundedCornerShape(12.dp))
                        .size(56.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, "Add Phone Member", tint = AccentTeal)
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
