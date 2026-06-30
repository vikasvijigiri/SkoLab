package com.company.skolab.ui.screens

import android.widget.Toast
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Check
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
data class DeviceContact(val name: String, val email: String = "", val phone: String = "", val isRegistered: Boolean = false)

@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier) {
    val initials = remember(name) {
        name.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
    }
    
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
            // 1. Instantly parse device contacts offline
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempMap = mutableMapOf<String, DeviceContact>()
                try {
                    val contentResolver = context.contentResolver
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
                
                val simulatedContacts = listOf(
                    DeviceContact(name = "IIT Bombay Physics Dept", email = "sumiran.pujari@physics.iitb.ac.in", phone = ""),
                    DeviceContact(name = "Institute for Advanced Study", email = "nisheeta.desai@ias.edu", phone = ""),
                    DeviceContact(name = "Cambridge Cavendish Lab", email = "paulson.kg@cam.ac.uk", phone = ""),
                    DeviceContact(name = "Oxford Condensed Matter", email = "saptarshi.mandal@oxford.ac.uk", phone = ""),
                    DeviceContact(name = "Princeton Theoretical Physics", email = "albert.einstein@princeton.edu", phone = ""),
                    DeviceContact(name = "Caltech Physics Division", email = "richard.feynman@caltech.edu", phone = ""),
                    DeviceContact(name = "Sorbonne Chemistry Faculty", email = "marie.curie@sorbonne.fr", phone = ""),
                    DeviceContact(name = "Cambridge Applied Maths", email = "stephen.hawking@cam.ac.uk", phone = ""),
                    DeviceContact(name = "Niels Bohr Institute", email = "niels.bohr@nbi.ku.dk", phone = ""),
                    DeviceContact(name = "Max Planck Institute", email = "werner.heisenberg@mpg.de", phone = "")
                )
                val combined = tempMap.values.toList() + simulatedContacts
                
                // Show offline alphabetical list first
                allDeviceContacts = combined.distinctBy { it.email.ifEmpty { it.phone } }.sortedBy { it.name.lowercase() }
            }

            // 2. Fetch registered researchers from backend to decorate and sort the list
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val emailsList = allDeviceContacts.mapNotNull { it.email.ifEmpty { null } }
                    val phonesList = allDeviceContacts.mapNotNull { it.phone.ifEmpty { null } }
                    
                    val base = com.company.skolab.network.ServerLocator.baseUrl.value ?: "http://10.0.2.2:8080"
                    val url = "$base/api/v1/recommendations/peers/check-registered"
                    
                    val jsonBody = org.json.JSONObject().apply {
                        val emailsArr = org.json.JSONArray()
                        emailsList.forEach { emailsArr.put(it) }
                        put("emails", emailsArr)
                        
                        val phonesArr = org.json.JSONArray()
                        phonesList.forEach { phonesArr.put(it) }
                        put("phones", phonesArr)
                    }
                    
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val requestBody = jsonBody.toString().toRequestBody(mediaType)
                    val request = Request.Builder().url(url).post(requestBody).build()
                    
                    val client = OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (bodyStr != null) {
                                val resObj = org.json.JSONObject(bodyStr)
                                val regEmailsArr = resObj.getJSONArray("registered_emails")
                                val regPhonesArr = resObj.getJSONArray("registered_phones")
                                
                                val emailsSet = mutableSetOf<String>()
                                for (i in 0 until regEmailsArr.length()) {
                                    emailsSet.add(regEmailsArr.getString(i).lowercase().trim())
                                }
                                val phonesSet = mutableSetOf<String>()
                                for (i in 0 until regPhonesArr.length()) {
                                    phonesSet.add(regPhonesArr.getString(i).trim())
                                }
                                
                                // Map registered state and resort
                                val decorated = allDeviceContacts.map { contact ->
                                    val isReg = (contact.email.isNotEmpty() && emailsSet.contains(contact.email.lowercase().trim())) ||
                                                (contact.phone.isNotEmpty() && phonesSet.contains(contact.phone.trim()))
                                    contact.copy(isRegistered = isReg)
                                }
                                
                                // Update state on main thread
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    allDeviceContacts = decorated.sortedWith(
                                        compareByDescending<DeviceContact> { it.isRegistered }
                                            .thenBy { it.name.lowercase() }
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
            endY = 600f
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
                    .height(200.dp)
                    .background(backgroundBrush)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category Tag
                item {
                    Text(
                        text = "NEW SPACE CREATION",
                        color = AccentTeal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SyneFontFamily,
                        letterSpacing = 1.2.sp,
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
                        label = { Text("Co-Lab Name", color = TextMuted, fontSize = 12.sp) },
                        isError = nameError != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
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
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
                
                item {
                    OutlinedTextField(
                        value = newProjDesc,
                        onValueChange = { newProjDesc = it },
                        label = { Text("Objective / Research Scope", color = TextMuted, fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedContainerColor = BgElevated.copy(alpha = 0.4f),
                            unfocusedContainerColor = BgElevated.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                }

                // Members Input Header
                item {
                    Text(
                        "Collaborators",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SyneFontFamily,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Unified Collaborator Row (Email or Phone)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = memberEmailInput,
                            onValueChange = { memberEmailInput = it },
                            placeholder = { Text("Search, email, or phone number", color = TextMuted, fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated.copy(alpha = 0.4f),
                                unfocusedContainerColor = BgElevated.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
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
                                .background(BgElevated, RoundedCornerShape(12.dp))
                                .size(48.dp),
                            enabled = !isSearchingMember
                        ) {
                            if (isSearchingMember) {
                                CircularProgressIndicator(color = AccentTeal, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PersonAdd, "Add Collaborator", tint = AccentTeal, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Autocomplete dropdown matching Gmail contacts & similar registered researchers
                item {
                    AnimatedVisibility(visible = filteredSuggestions.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
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
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Compact avatar inside autocomplete suggestion list
                                        ContactAvatar(suggestion.name, modifier = Modifier.size(30.dp))
                                        
                                        Column(modifier = Modifier.weight(1f)) {
                                            val displayName = if (suggestion.isRegistered && suggestion.username.isNotEmpty()) {
                                                "${suggestion.name} (@${suggestion.username})"
                                            } else {
                                                suggestion.name
                                            }
                                            Text(displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("${suggestion.email} • $descText", color = TextMuted, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Chips List of Selected Members (Slack Style - Compacted)
                item {
                    AnimatedVisibility(visible = membersList.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            membersList.forEach { member ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = BgElevated,
                                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Compacted avatar inside selection chip
                                        ContactAvatar(member.name, modifier = Modifier.size(20.dp))
                                        Text(member.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        IconButton(
                                            onClick = { membersList = membersList.filter { it.uid != member.uid } },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Default.Close, "Remove", tint = TextMuted, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Contacts Header Row (Compacted)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Contacts Sync",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        IconButton(
                            onClick = { syncTrigger++ },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Contacts",
                                tint = AccentTeal,
                                modifier = Modifier.size(18.dp)
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
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasContactsPermission) "No contacts found" else "Contacts permission not granted",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(
                        items = inlineContacts,
                        key = { contact -> contact.phone + "_" + contact.email + "_" + contact.name }
                    ) { contact ->
                        val isAlreadyAdded = membersList.any { 
                            (contact.email.isNotEmpty() && it.email == contact.email) || 
                            (contact.phone.isNotEmpty() && it.phone == contact.phone) 
                        }
                        
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Compact Colorful Initials Circle Avatar
                                    ContactAvatar(contact.name, modifier = Modifier.size(32.dp))
                                    
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(contact.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            if (contact.isRegistered) {
                                                // Premium FAANG-style badge indicating registered SkoLab researcher
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = AccentTeal.copy(alpha = 0.15f),
                                                    modifier = Modifier.padding(top = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "REG",
                                                        color = AccentTeal,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (contact.email.isNotEmpty()) {
                                                Text(contact.email, color = TextMuted, fontSize = 10.sp)
                                            }
                                            if (contact.phone.isNotEmpty()) {
                                                Text(contact.phone, color = TextMuted, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                                
                                if (isAlreadyAdded) {
                                    // Already added checkmark feedback
                                    IconButton(
                                        onClick = {
                                            membersList = membersList.filter { 
                                                !(contact.email.isNotEmpty() && it.email == contact.email) &&
                                                !(contact.phone.isNotEmpty() && it.phone == contact.phone)
                                            }
                                            Toast.makeText(context, "Removed ${contact.name}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(AccentTeal.copy(alpha = 0.2f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Added",
                                            tint = AccentTeal,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    if (contact.isRegistered) {
                                        // Registered user - show PersonAdd icon button
                                        IconButton(
                                            onClick = {
                                                if (contact.email.isNotEmpty()) {
                                                    membersList = membersList + CreateProjectMember(
                                                        uid = "pending_${System.currentTimeMillis()}",
                                                        name = contact.name,
                                                        email = contact.email,
                                                        phone = contact.phone
                                                    )
                                                } else if (contact.phone.isNotEmpty()) {
                                                    membersList = membersList + CreateProjectMember(
                                                        uid = "phone_${System.currentTimeMillis()}",
                                                        name = contact.name,
                                                        email = "",
                                                        phone = contact.phone
                                                    )
                                                }
                                                Toast.makeText(context, "Added ${contact.name}", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(AccentTeal.copy(alpha = 0.1f), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PersonAdd,
                                                contentDescription = "Add",
                                                tint = AccentTeal,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else {
                                        // Unregistered user - show compact FAANG-style Invite button
                                        Button(
                                            onClick = {
                                                if (contact.email.isNotEmpty()) {
                                                    membersList = membersList + CreateProjectMember(
                                                        uid = "pending_${System.currentTimeMillis()}",
                                                        name = contact.name,
                                                        email = contact.email,
                                                        phone = contact.phone
                                                    )
                                                    // 1. Log invitation to recommendation engine
                                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                        try {
                                                            val base = com.company.skolab.network.ServerLocator.baseUrl.value ?: "http://10.0.2.2:8080"
                                                            val url = "$base/api/v1/recommendations/peers/invite"
                                                            val client = OkHttpClient()
                                                            val jsonBody = JSONObject().apply {
                                                                put("user_id", currentUserId)
                                                                put("peer_email", contact.email)
                                                            }
                                                            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                                                            val requestBody = jsonBody.toString().toRequestBody(mediaType)
                                                            val request = Request.Builder().url(url).post(requestBody).build()
                                                            client.newCall(request).execute().use { /* ignore */ }
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                    }
                                                    // 2. Launch Email invite intent
                                                    try {
                                                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                                            data = Uri.parse("mailto:")
                                                            putExtra(Intent.EXTRA_EMAIL, arrayOf(contact.email))
                                                            putExtra(Intent.EXTRA_SUBJECT, "Join my research Co-Lab on SkoLab!")
                                                            putExtra(Intent.EXTRA_TEXT, "Hey ${contact.name},\n\nI'm creating a new Co-Lab group for our research project on SkoLab. Join me here to collaborate: http://10.0.2.2:8080/invite")
                                                        }
                                                        context.startActivity(Intent.createChooser(emailIntent, "Send Invite Email"))
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Could not open email app", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else if (contact.phone.isNotEmpty()) {
                                                    membersList = membersList + CreateProjectMember(
                                                        uid = "phone_${System.currentTimeMillis()}",
                                                        name = contact.name,
                                                        email = "",
                                                        phone = contact.phone
                                                    )
                                                    // 2. Launch SMS invite intent
                                                    try {
                                                        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                                            data = Uri.parse("smsto:${contact.phone}")
                                                            putExtra("sms_body", "Hey ${contact.name}, I'm creating a new Co-Lab research group on SkoLab. Join me here: http://10.0.2.2:8080/invite")
                                                        }
                                                        context.startActivity(smsIntent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Could not open SMS app", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                Toast.makeText(context, "Invited ${contact.name}", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.Transparent,
                                                contentColor = AccentTeal
                                            ),
                                            border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.4f)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Invite", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = BorderLight.copy(alpha = 0.15f),
                                thickness = 0.5.dp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            )
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
