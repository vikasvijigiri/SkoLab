package com.company.skolab.ui.screens

import android.widget.Toast
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
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
data class CollaboratorSuggestion(val name: String, val email: String, val isRegistered: Boolean, val researchFocus: String = "", val uid: String = "", val username: String = "")
data class DeviceContact(val name: String, val email: String = "", val phone: String = "")

@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier) {
    val initials = name.split("\\s+".toRegex())
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercase() }
        .joinToString("")
    
    val colors = remember(name) {
        val hash = name.hashCode()
        when (kotlin.math.abs(hash) % 4) {
            0 -> listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))
            1 -> listOf(Color(0xFFF12711), Color(0xFFF5AF19))
            2 -> listOf(Color(0xFFB224EF), Color(0xFF7579FF))
            else -> listOf(Color(0xFF11998E), Color(0xFF38EF7D))
        }
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(colors),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials.ifEmpty { "?" },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = SyneFontFamily
        )
    }
}

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

    var allDeviceContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var syncTrigger by remember { mutableStateOf(0) }

    val inlineContacts = remember(memberEmailInput, allDeviceContacts) {
        val q = memberEmailInput.lowercase().trim()
        if (q.isEmpty()) {
            allDeviceContacts
        } else {
            allDeviceContacts.filter {
                it.name.lowercase().contains(q) ||
                it.email.lowercase().contains(q) ||
                it.phone.contains(q)
            }
        }
    }

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

    LaunchedEffect(hasContactsPermission, syncTrigger) {
        if (hasContactsPermission) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempMap = mutableMapOf<String, DeviceContact>()
                try {
                    val contentResolver = context.contentResolver
                    
                    // 1. Fetch all phone contacts
                    val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    val phoneProjection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val phoneCursor = contentResolver.query(phoneUri, phoneProjection, null, null, null)
                    phoneCursor?.use { c ->
                        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (name.isNotEmpty() && number.isNotEmpty()) {
                                tempMap[name] = DeviceContact(name = name, phone = number)
                            }
                        }
                    }

                    // 2. Fetch all email contacts
                    val emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
                    val emailProjection = arrayOf(
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
                    )
                    val emailCursor = contentResolver.query(emailUri, emailProjection, null, null, null)
                    emailCursor?.use { c ->
                        val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val email = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (name.isNotEmpty() && email.isNotEmpty()) {
                                val existing = tempMap[name]
                                if (existing != null) {
                                    tempMap[name] = existing.copy(email = email)
                                } else {
                                    tempMap[name] = DeviceContact(name = name, email = email)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                allDeviceContacts = tempMap.values.toList().sortedBy { it.name }
            }
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
                            val bodyStr = response.body?.string()
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
        val input = memberEmailInput.trim()
        if (input.isNotBlank()) {
            if (input.contains("@")) {
                if (!isSearchingMember) {
                    isSearchingMember = true
                    db.collection("researchers")
                        .whereEqualTo("email", input)
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
                                if (membersList.none { it.email == input }) {
                                    membersList = membersList + CreateProjectMember(
                                        uid = "pending_${System.currentTimeMillis()}",
                                        name = input.substringBefore("@"),
                                        email = input
                                    )
                                    memberEmailInput = ""
                                    Toast.makeText(context, "Added external invite for $input", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .addOnFailureListener {
                            isSearchingMember = false
                            if (membersList.none { it.email == input }) {
                                membersList = membersList + CreateProjectMember(
                                    uid = "pending_${System.currentTimeMillis()}",
                                    name = input.substringBefore("@"),
                                    email = input
                                )
                                memberEmailInput = ""
                            }
                        }
                }
            } else {
                if (membersList.none { it.phone == input }) {
                    membersList = membersList + CreateProjectMember(
                        uid = "phone_${System.currentTimeMillis()}",
                        name = input,
                        email = "",
                        phone = input
                    )
                    memberEmailInput = ""
                    Toast.makeText(context, "Added phone collaborator: $input", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "User already added to the list", Toast.LENGTH_SHORT).show()
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
        val backgroundBrush = Brush.verticalGradient(
            colors = listOf(
                AccentTeal.copy(alpha = 0.08f),
                Color.Transparent
            ),
            startY = 0f,
            endY = 800f
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgPrimary)
        ) {
            // High-end radial dark mode mesh background glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(backgroundBrush)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Category Tag
                item {
                    Text(
                        text = "NEW SPACE CREATION",
                        color = AccentTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SyneFontFamily,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                // Project Identity
                item {
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
                            focusedContainerColor = BgElevated.copy(alpha = 0.4f),
                            unfocusedContainerColor = BgElevated.copy(alpha = 0.2f),
                            errorBorderColor = SkoLabWarning
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )
                }
                
                item {
                    OutlinedTextField(
                        value = newProjDesc,
                        onValueChange = { newProjDesc = it },
                        label = { Text("Objective / Research Scope", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedContainerColor = BgElevated.copy(alpha = 0.4f),
                            unfocusedContainerColor = BgElevated.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                }

                // Members Input Header
                item {
                    Text(
                        "Collaborators",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SyneFontFamily,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Unified Collaborator Row (Email or Phone)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = memberEmailInput,
                            onValueChange = { memberEmailInput = it },
                            placeholder = { Text("Search, email, or phone number", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated.copy(alpha = 0.4f),
                                unfocusedContainerColor = BgElevated.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { onAddMember() }
                            )
                        )
                        IconButton(
                            onClick = onAddMember,
                            modifier = Modifier
                                .background(BgElevated, RoundedCornerShape(14.dp))
                                .size(56.dp),
                            enabled = !isSearchingMember
                        ) {
                            if (isSearchingMember) {
                                CircularProgressIndicator(color = AccentTeal, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PersonAdd, "Add Collaborator", tint = AccentTeal)
                            }
                        }
                    }
                }

                // Autocomplete dropdown matching Gmail contacts & similar registered researchers
                item {
                    AnimatedVisibility(visible = filteredSuggestions.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = BgElevated,
                            border = BorderStroke(1.dp, BorderLight),
                            shadowElevation = 6.dp
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
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
                                                            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                                                            val requestBody = jsonBody.toString().toRequestBody(mediaType)
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
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Premium initials avatar for suggestions
                                        ContactAvatar(suggestion.name, modifier = Modifier.size(36.dp))
                                        
                                        Column(modifier = Modifier.weight(1f)) {
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
                }

                // Chips List of Selected Members (Slack Style)
                item {
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
                                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Dynamic small avatar inside the Slack-style chip
                                        ContactAvatar(member.name, modifier = Modifier.size(24.dp))
                                        Text(member.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                }

                // Contacts Header Row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Contacts Sync",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 16.sp
                        )
                        IconButton(
                            onClick = { syncTrigger++ },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Contacts",
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Inline Contacts list (Unlimited, performance backed by LazyColumn)
                if (inlineContacts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasContactsPermission) "No contacts found" else "Contacts permission not granted",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(inlineContacts) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgElevated.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .border(1.dp, BorderLight.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Initials based Colorful Gradient Circle Avatar
                                ContactAvatar(contact.name, modifier = Modifier.size(42.dp))
                                
                                Column {
                                    Text(contact.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (contact.email.isNotEmpty()) {
                                            Text("✉️ ${contact.email}", color = TextMuted, fontSize = 11.sp)
                                        }
                                        if (contact.phone.isNotEmpty()) {
                                            Text("📱 ${contact.phone}", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (contact.email.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            if (membersList.none { it.email == contact.email }) {
                                                membersList = membersList + CreateProjectMember(
                                                    uid = "pending_${System.currentTimeMillis()}",
                                                    name = contact.name,
                                                    email = contact.email
                                                )
                                            } else {
                                                Toast.makeText(context, "Email already added", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(AccentTeal.copy(alpha = 0.1f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Email, "Add Email", tint = AccentTeal, modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (contact.phone.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            if (membersList.none { it.phone == contact.phone }) {
                                                membersList = membersList + CreateProjectMember(
                                                    uid = "phone_${System.currentTimeMillis()}",
                                                    name = contact.name,
                                                    email = "",
                                                    phone = contact.phone
                                                )
                                            } else {
                                                Toast.makeText(context, "Phone already added", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(AccentTeal.copy(alpha = 0.1f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Phone, "Add Phone", tint = AccentTeal, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(100.dp)) // padding for FAB
                }
            }
        }
    }
}
