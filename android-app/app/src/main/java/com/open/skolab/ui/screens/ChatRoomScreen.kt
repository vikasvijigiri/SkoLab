package com.open.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.network.ApiService
import com.open.skolab.network.ChatMessage
import com.open.skolab.data.ChatStorage
import com.open.skolab.auth.AuthManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import com.open.skolab.ui.theme.*


import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.open.skolab.ui.components.MarkdownText
import com.open.skolab.ui.components.MessageBubbleWrapper
import com.open.skolab.ui.components.ReactionBadge
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import android.media.ToneGenerator
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.ContactsContract
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.ScreenShare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    peerName: String,
    peerId: String,
    onBack: () -> Unit
) {
    val apiService = remember { ApiService() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    var messageText by remember { mutableStateOf("") }
    var chatHistory by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isPeerTyping by remember { mutableStateOf(false) }
    var showMediaPanel by remember { mutableStateOf(false) }
    var selectedMediaTab by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current



    
    var isPeerSkoLab by remember { mutableStateOf(false) }
    var checkingSkoLabStatus by remember { mutableStateOf(true) }
    var peerUserData by remember { mutableStateOf<com.open.skolab.model.SkoLabUser?>(null) }

    DisposableEffect(peerId) {
        if (peerId.isNotEmpty() &&
            !peerId.startsWith("https://openalex.org/") &&
            peerId != "sumiran_uid" &&
            peerId != "nisheeta_uid" &&
            peerId != "paulson_uid" &&
            peerId != "saptarshi_uid" &&
            peerId != "you_uid" &&
            peerId != "default_owner"
        ) {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val listener = db.collection("researchers").document(peerId)
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null && snapshot.exists()) {
                        peerUserData = snapshot.toObject(com.open.skolab.model.SkoLabUser::class.java)
                        isPeerSkoLab = true
                    } else {
                        peerUserData = null
                        isPeerSkoLab = false
                    }
                    checkingSkoLabStatus = false
                }
            onDispose {
                listener.remove()
            }
        } else {
            peerUserData = null
            isPeerSkoLab = false
            checkingSkoLabStatus = false
            onDispose {}
        }
    }

    // Video Call simulation
    var showCallingOverlay by remember { mutableStateOf(false) }
    var callingOverlayMode by remember { mutableStateOf("voice") } // "voice" or "video"
    var callConnectedTime by remember { mutableStateOf(0) }
    var callState by remember { mutableStateOf("calling") } // "calling", "ringing", "connected", "disconnected"
    var micMuted by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(true) }
    var speakerOn by remember { mutableStateOf(false) }

    // Ringing sound simulation using ToneGenerator
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay && (callState == "calling" || callState == "ringing")) {
            val toneGenerator = try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            } catch (e: Exception) {
                null
            }
            if (toneGenerator != null) {
                scope.launch {
                    try {
                        while (showCallingOverlay && (callState == "calling" || callState == "ringing")) {
                            toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                            delay(2000)
                            toneGenerator.stopTone()
                            delay(3000)
                        }
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        toneGenerator.release()
                    }
                }
            }
        }
    }

    // Auto-connect call simulation after a few seconds
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay) {
            if (callState == "calling") {
                delay(2000)
                callState = "ringing"
            }
            if (callState == "ringing") {
                delay(3000)
                callState = "connected"
                callConnectedTime = 0
            }
        }
    }

    // Call active timer
    LaunchedEffect(showCallingOverlay, callState) {
        if (showCallingOverlay && callState == "connected") {
            while (showCallingOverlay && callState == "connected") {
                delay(1000)
                callConnectedTime++
            }
        }
    }

    var showNonSkoLabDialog by remember { mutableStateOf(false) }
    var selectedCallTypeForDialog by remember { mutableStateOf("voice") }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var replyingToMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val userUid = cachedUser?.uid ?: ""
    val chatStorage = remember(userUid) { if (userUid.isNotEmpty()) ChatStorage(context, userUid) else null }

    val triggerPeerResponse: (String, List<ChatMessage>) -> Unit = { userMsg, updatedHistory ->
        scope.launch {
            delay(600)
            isPeerTyping = true
            val response = apiService.chatWithAuthor(
                authorId = peerId,
                paperTitle = "General Discussion",
                userMessage = userMsg,
                history = updatedHistory.takeLast(10)
            )
            delay(800)
            isPeerTyping = false
            if (response != null && !response.reply.isNullOrBlank()) {
                val finalHistory = chatHistory + ChatMessage(role = "assistant", content = response.reply)
                chatHistory = finalHistory
                chatStorage?.saveChatHistory(peerId, finalHistory)
            } else {
                val fallback = chatHistory + ChatMessage(
                    role = "assistant",
                    content = "I've considered your point. Based on my research models, that's an area with significant convergence potential."
                )
                chatHistory = fallback
                chatStorage?.saveChatHistory(peerId, fallback)
            }
        }
    }

    var emailInput by remember(peerName) { mutableStateOf(peerName.lowercase().replace(" ", "") + "@university.edu") }
    var phoneInput by remember { mutableStateOf("") }
    var resolvedInstitution by remember { mutableStateOf("") }
    var isResolvingEmail by remember { mutableStateOf(false) }

    // Dynamic backend OpenAlex/Scraping lookup for peer's email
    LaunchedEffect(peerName) {
        if (peerName.isNotEmpty() && peerName != "SkoLab User") {
            isResolvingEmail = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Fetch from our backend resolve_email endpoint
                    val url = "http://10.0.2.2:8000/api/v1/authors/resolve_email?name=" + 
                              android.net.Uri.encode(peerName)
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (bodyStr != null) {
                                val json = org.json.JSONObject(bodyStr)
                                val email = json.optString("email")
                                val inst = json.optString("institution")
                                if (email.isNotEmpty()) {
                                    emailInput = email
                                }
                                if (inst.isNotEmpty()) {
                                    resolvedInstitution = inst
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isResolvingEmail = false
                }
            }
        }
    }

    var hasContactsPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val requestPermissionLauncherForChat = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasContactsPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "✅ Contacts access granted for smart suggestions!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "⚠️ Contacts permission denied. Using simulated database.", Toast.LENGTH_SHORT).show()
        }
    }

    var localContactsList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var localPhoneList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allDeviceContacts by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }

    // Fetch all contacts from phone once permission is active
    LaunchedEffect(hasContactsPermission) {
        if (hasContactsPermission) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempMap = mutableMapOf<String, Triple<String, String, String>>() // Key: Name, Value: (Name, Phone, Email)
                try {
                    val contentResolver = context.contentResolver
                    // Fetch phone contacts
                    val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    val phoneProjection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val phoneCursor = contentResolver.query(phoneUri, phoneProjection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                    phoneCursor?.use { c ->
                        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (name.isNotEmpty()) {
                                tempMap[name] = Triple(name, number, "")
                            }
                        }
                    }

                    // Fetch email contacts and merge
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
                            if (name.isNotEmpty()) {
                                val existing = tempMap[name]
                                if (existing != null) {
                                    tempMap[name] = Triple(name, existing.second, email)
                                } else {
                                    tempMap[name] = Triple(name, "", email)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                allDeviceContacts = tempMap.values.toList().sortedBy { it.first }
            }
        } else {
            allDeviceContacts = emptyList()
        }
    }

    // Dynamic search for email contacts in background thread
    LaunchedEffect(emailInput, hasContactsPermission) {
        if (hasContactsPermission && emailInput.length >= 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val results = mutableListOf<Pair<String, String>>()
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
                        arrayOf("%$emailInput%", "%$emailInput%"),
                        null
                    )
                    cursor?.use { c ->
                        val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val email = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (email.isNotEmpty()) {
                                results.add(email to (name.ifEmpty { "Device Contact" }))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                localContactsList = results.distinctBy { it.first }.take(10)
            }
        } else {
            localContactsList = emptyList()
        }
    }

    // Dynamic search for phone contacts in background thread
    LaunchedEffect(phoneInput, hasContactsPermission) {
        if (hasContactsPermission && phoneInput.length >= 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val results = mutableListOf<Pair<String, String>>()
                try {
                    val contentResolver = context.contentResolver
                    val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    val phoneProjection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val cursor = contentResolver.query(
                        phoneUri,
                        phoneProjection,
                        "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$phoneInput%", "%$phoneInput%"),
                        null
                    )
                    cursor?.use { c ->
                        val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        while (c.moveToNext()) {
                            val number = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                            val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                            if (number.isNotEmpty()) {
                                results.add(number to (name.ifEmpty { "Device Contact" }))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                localPhoneList = results.distinctBy { it.first }.take(10)
            }
        } else {
            localPhoneList = emptyList()
        }
    }

    // Simulated Academic senders from user's Gmail
    val simulatedGmailSenders = remember {
        listOf(
            "sumiran.pujari@physics.iitb.ac.in" to "IIT Bombay Physics Dept",
            "nisheeta.desai@ias.edu" to "Institute for Advanced Study",
            "paulson.kg@cam.ac.uk" to "Cambridge Cavendish Lab",
            "saptarshi.mandal@oxford.ac.uk" to "Oxford Condensed Matter",
            "albert.einstein@princeton.edu" to "Princeton Theoretical Physics",
            "richard.feynman@caltech.edu" to "Caltech Physics Division",
            "marie.curie@sorbonne.fr" to "Sorbonne Chemistry Faculty",
            "stephen.hawking@cam.ac.uk" to "Cambridge Applied Maths",
            "niels.bohr@nbi.ku.dk" to "Niels Bohr Institute",
            "werner.heisenberg@mpg.de" to "Max Planck Institute"
        )
    }

    val matchedGmailSenders = remember(emailInput, localContactsList) {
        if (emailInput.isEmpty() || emailInput.contains("@") || emailInput.length < 2) {
            emptyList()
        } else {
            val filteredSimulated = simulatedGmailSenders.filter {
                it.first.lowercase().contains(emailInput.lowercase()) ||
                it.second.lowercase().contains(emailInput.lowercase())
            }
            (localContactsList + filteredSimulated).distinctBy { it.first }.take(10)
        }
    }

    val matchedPhoneContacts = remember(phoneInput, localPhoneList) {
        if (phoneInput.isEmpty() || phoneInput.length < 2) {
            emptyList()
        } else {
            localPhoneList.take(10)
        }
    }

    // Native phone contact picker launcher
    val phonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                try {
                    val cursor = context.contentResolver.query(
                        it,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null, null, null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val phone = if (numIdx >= 0) c.getString(numIdx) else ""
                            if (phone.isNotEmpty()) {
                                phoneInput = phone
                                Toast.makeText(context, "📱 Phone number loaded!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Native email contact picker launcher
    val emailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                try {
                    val cursor = context.contentResolver.query(
                        it,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Email.ADDRESS
                        ),
                        null, null, null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val emailIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            val email = if (emailIdx >= 0) c.getString(emailIdx) else ""
                            if (email.isNotEmpty()) {
                                emailInput = email
                                Toast.makeText(context, "📧 Email address loaded!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Initials color
    val avatarColors = listOf(AccentTeal, AccentIndigo, AccentEmerald, AccentViolet, AccentAmber, AccentOrange, AccentRose, AccentCyan)
    val color = avatarColors[kotlin.math.abs(peerId.hashCode()) % avatarColors.size]

    // Load initial greeting message or history
    LaunchedEffect(peerId, chatStorage) {
        if (chatStorage != null) {
            val history = chatStorage.getChatHistory(peerId)
            if (history.isEmpty()) {
                val initial = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "Hi there! I am modeled after $peerName's research profile. Ask me anything about my publication topics or methodologies!"
                    )
                )
                chatStorage.saveChatHistory(peerId, initial)
                chatHistory = initial
            } else {
                chatHistory = history
            }
        }
    }

    // Scroll to bottom when history updates
    LaunchedEffect(chatHistory.size, isPeerTyping) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = BgPrimary,
        topBar = {
            Surface(
                color = BgCard,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button + Avatar Clickable Row (WhatsApp style)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { onBack() }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        
                        Spacer(Modifier.width(4.dp))
                        
                        // Avatar (Circular Profile Pic with Online Dot option)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peerName.take(1).uppercase(),
                                color = color,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                fontFamily = DisplayFontFamily
                            )
                            
                            // Online Indicator Green Dot (WhatsApp style)
                            val peer = peerUserData
                            if (isPeerTyping || (peer?.isOnline == true && peer.emailVerified == true)) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .align(Alignment.BottomEnd)
                                        .border(1.5.dp, BgCard, CircleShape)
                                        .background(AccentEmerald, CircleShape)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Name and Status (WhatsApp style, also clickable to go back/view)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onBack() }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = peerName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = DisplayFontFamily
                        )
                        val peer = peerUserData
                        val statusText = when {
                            isPeerTyping -> "typing..."
                            peer?.isOnline == true && peer.emailVerified == true -> "online"
                            peer != null && peer.emailVerified == false -> "unverified account"
                            peer != null && peer.isOnline == false -> "offline"
                            else -> "not on skolab"
                        }
                        val statusColor = when (statusText) {
                            "typing..." -> AccentTeal
                            "online" -> AccentEmerald
                            else -> TextSecondary
                        }
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    
                    // Action icons
                    IconButton(
                        onClick = {
                            if (isPeerSkoLab) {
                                callingOverlayMode = "video"
                                callState = "calling"
                                showCallingOverlay = true
                            } else {
                                selectedCallTypeForDialog = "video"
                                showNonSkoLabDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video Call",
                            tint = if (isPeerSkoLab) AccentTeal else TextSecondary.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isPeerSkoLab) {
                                callingOverlayMode = "voice"
                                callState = "calling"
                                showCallingOverlay = true
                            } else {
                                selectedCallTypeForDialog = "voice"
                                showNonSkoLabDialog = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Voice Call",
                            tint = if (isPeerSkoLab) AccentTeal else TextSecondary.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = 4.dp)
        ) {
            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(chatHistory) { msg ->
                    val isMe = msg.role == "user"
                    ChatBubble(
                        message = msg,
                        isMe = isMe,
                        onReact = { emoji ->
                            val updated = chatHistory.map {
                                if (it == msg) it.copy(reaction = if (it.reaction == emoji) null else emoji) else it
                            }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        },
                        onReply = {
                            replyingToMessage = msg
                        },
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(msg.content))
                        },
                        onToggleStar = {
                            val updated = chatHistory.map {
                                if (it == msg) it.copy(isStarred = !it.isStarred) else it
                            }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        },
                        onDelete = {
                            val updated = chatHistory.filter { it != msg }
                            chatHistory = updated
                            chatStorage?.saveChatHistory(peerId, updated)
                        }
                    )
                }
                
                if (isPeerTyping) {
                    item {
                        TypingIndicator(peerName = peerName)
                    }
                }
            }

            // Input Bar
            Surface(
                color = BgCard
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (replyingToMessage != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgElevated)
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left accent bar
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(AccentTeal, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (replyingToMessage!!.role == "user") "You" else peerName,
                                    color = AccentTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replyingToMessage!!.content,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (isPeerSkoLab) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Rounded Text Field Container (WhatsApp style)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 36.dp, max = 100.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(BgElevated)
                                    .border(BorderStroke(1.dp, BorderLight.copy(alpha = 0.4f)), RoundedCornerShape(24.dp))
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (!showMediaPanel) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        }
                                        showMediaPanel = !showMediaPanel
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showMediaPanel) Icons.Default.Keyboard else Icons.Default.SentimentSatisfiedAlt,
                                        contentDescription = "Toggle Media Panel",
                                        tint = if (showMediaPanel) AccentTeal else TextMuted
                                    )
                                }
                                
                                Spacer(Modifier.width(6.dp))
                                
                                BasicTextField(
                                    value = messageText,
                                    onValueChange = { messageText = it },
                                    textStyle = TextStyle(
                                        color = TextPrimary, 
                                        fontSize = 14.5.sp,
                                        fontFamily = DisplayFontFamily
                                    ),
                                    cursorBrush = SolidColor(AccentTeal),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 8.dp)
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                showMediaPanel = false
                                            }
                                        },
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (messageText.isEmpty()) {
                                                Text("Message", color = TextMuted, fontSize = 14.5.sp)
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                                
                                Spacer(Modifier.width(6.dp))
                                
                                IconButton(onClick = {}, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.AttachFile, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = {}, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.CameraAlt, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                                }
                            }
                            
                            // Circular Green Send FAB with Shadow
                            FloatingActionButton(
                                onClick = {
                                    if (messageText.isNotBlank()) {
                                        val userMsg = if (replyingToMessage != null) {
                                            "> ${replyingToMessage!!.content.replace("\n", "\n> ")}\n\n${messageText.trim()}"
                                        } else {
                                            messageText.trim()
                                        }
                                        replyingToMessage = null
                                        
                                        val updatedHistory = chatHistory + ChatMessage(role = "user", content = userMsg)
                                        chatHistory = updatedHistory
                                        chatStorage?.saveChatHistory(peerId, updatedHistory)
                                        messageText = ""
                                        
                                        // Trigger peer reply
                                        triggerPeerResponse(userMsg, updatedHistory)
                                    }
                                },
                                containerColor = AccentEmerald,
                                contentColor = TextOnAccent,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(40.dp)
                                    .graphicsLayer {
                                        shadowElevation = 4f
                                        shape = CircleShape
                                        clip = true
                                    }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        // Notice card for non-SkoLab user
                        Surface(
                            color = BgCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                                    .background(BgElevated, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(1.dp, BorderLight.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Not on SkoLab",
                                        tint = AccentAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "$peerName is not on SkoLab. Messaging is disabled.",
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = DisplayFontFamily,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // Media panel (Emoji / GIF / Sticker options)
                    AnimatedVisibility(
                        visible = showMediaPanel,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .background(BgPrimary)
                                .border(BorderStroke(0.5.dp, BorderLight))
                        ) {
                            // Tabs
                            SecondaryTabRow(
                                selectedTabIndex = selectedMediaTab,
                                containerColor = BgCard,
                                contentColor = AccentTeal
                            ) {
                                Tab(
                                    selected = selectedMediaTab == 0,
                                    onClick = { selectedMediaTab = 0 },
                                    text = { Text("Emojis", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                )
                                Tab(
                                    selected = selectedMediaTab == 1,
                                    onClick = { selectedMediaTab = 1 },
                                    text = { Text("GIFs", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                )
                                Tab(
                                    selected = selectedMediaTab == 2,
                                    onClick = { selectedMediaTab = 2 },
                                    text = { Text("Stickers", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(8.dp)
                            ) {
                                when (selectedMediaTab) {
                                    0 -> {
                                        val emojis = remember {
                                            listOf(
                                                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                                                "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
                                                "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
                                                "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
                                                "🔬", "🧪", "🧬", "📚", "💻", "🧠", "🎓", "🌌", "🚀", "🛰️",
                                                "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👋", "👏"
                                            )
                                        }
                                        LazyVerticalGrid(
                                            columns = GridCells.Adaptive(minSize = 40.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(emojis) { emoji ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { 
                                                            messageText += emoji 
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(text = emoji, fontSize = 22.sp)
                                                }
                                            }
                                        }
                                    }
                                    1 -> {
                                        val gifs = remember {
                                            listOf(
                                                "Eureka Moment" to "🌌",
                                                "Quantum Leap" to "🚀",
                                                "Brain Storm" to "🧠",
                                                "DNA Double Helix" to "🧬",
                                                "Lab Explosion" to "🧪",
                                                "Microscope Zoom" to "🔬"
                                            )
                                        }
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(gifs) { gif ->
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = BgElevated),
                                                    border = BorderStroke(1.dp, BorderLight),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(80.dp)
                                                        .clickable {
                                                            val gifText = "[GIF: ${gif.first}]"
                                                            val updatedHistory = chatHistory + ChatMessage(role = "user", content = gifText)
                                                            chatHistory = updatedHistory
                                                            chatStorage?.saveChatHistory(peerId, updatedHistory)
                                                            showMediaPanel = false
                                                            triggerPeerResponse(gifText, updatedHistory)
                                                        }
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(
                                                                Brush.linearGradient(
                                                                    colors = listOf(AccentTeal.copy(alpha = 0.1f), AccentIndigo.copy(alpha = 0.1f))
                                                                )
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Text(text = gif.second, fontSize = 24.sp)
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(
                                                                text = gif.first,
                                                                color = TextPrimary,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Text(
                                                                text = "GIF",
                                                                color = AccentTeal,
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.Black
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> {
                                        val stickers = remember {
                                            listOf(
                                                "Superconducting" to "🧲",
                                                "Peer Reviewed" to "📝",
                                                "Schrödinger's Cat" to "🐱",
                                                "Coffee Powered" to "☕",
                                                "Publish or Perish" to "💀",
                                                "Lab Partner" to "🤝"
                                            )
                                        }
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(3),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(stickers) { sticker ->
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = BgElevated),
                                                    border = BorderStroke(1.dp, BorderLight),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(70.dp)
                                                        .clickable {
                                                            val stickerText = "[Sticker: ${sticker.first}]"
                                                            val updatedHistory = chatHistory + ChatMessage(role = "user", content = stickerText)
                                                            chatHistory = updatedHistory
                                                            chatStorage?.saveChatHistory(peerId, updatedHistory)
                                                            showMediaPanel = false
                                                            triggerPeerResponse(stickerText, updatedHistory)
                                                        }
                                                ) {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Text(text = sticker.second, fontSize = 22.sp)
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(
                                                                text = sticker.first,
                                                                color = TextSecondary,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNonSkoLabDialog) {
        AlertDialog(
            onDismissRequest = { showNonSkoLabDialog = false },
            title = {
                Text(
                    text = "Contact $peerName",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AccentTeal
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "$peerName hasn't joined SkoLab yet. Link their contact details below from your system, Gmail, or Truecaller contacts to route secure voice calling and messaging fallback channels.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    if (isResolvingEmail) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentTeal.copy(alpha = 0.08f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AccentTeal,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scraping OpenAlex for academic email...",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    } else if (resolvedInstitution.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentAmber.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = "Institution Resolved",
                                tint = AccentAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resolved Institution: $resolvedInstitution",
                                color = AccentAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                                )
                            }
                        }


                    // Smart Contact permission gateway banner
                    if (!hasContactsPermission) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BorderLight.copy(alpha = 0.15f))
                                .clickable {
                                    requestPermissionLauncherForChat.launch(android.Manifest.permission.READ_CONTACTS)
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = "Contacts Permission",
                                tint = AccentTeal,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Smart Contact Suggestions",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Find Truecaller and Gmail contacts as you type.",
                                    color = TextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Grant Permission",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentTeal.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Permission Active",
                                tint = AccentTeal,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Smart contact suggestions enabled!",
                                color = AccentTeal,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Show all device contacts for one-click linking
                    if (hasContactsPermission && allDeviceContacts.isNotEmpty()) {
                        Text(
                            text = "📇 QUICK-LINK FROM PHONE CONTACTS (1-CLICK)",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        
                        Surface(
                            color = BgCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderLight),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(allDeviceContacts) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BgElevated)
                                            .clickable {
                                                emailInput = contact.third.ifEmpty { emailInput }
                                                phoneInput = contact.second.ifEmpty { phoneInput }
                                                Toast.makeText(
                                                    context,
                                                    "🔗 Linked to ${contact.first}!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.first,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (contact.second.isNotEmpty()) {
                                                    Text(
                                                        text = "📱 ${contact.second}",
                                                        color = TextSecondary,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                                if (contact.third.isNotEmpty()) {
                                                    Text(
                                                        text = "✉️ ${contact.third}",
                                                        color = TextSecondary,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = "Link profile",
                                            tint = AccentTeal,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // --- SECTION 1: INVITATION CHANNELS ---
                    Text(
                        text = "INVITE TO SKOLAB",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Gmail Invite
                        Button(
                            onClick = {
                                val subject = "Invitation to collaborate on SkoLab"
                                val body = "Hi $peerName,\n\nI would love to collaborate with you on our research papers using SkoLab. SkoLab offers secure, encrypted voice/video synchronization, real-time LaTeX blackboards, and joint manuscript editing.\n\nJoin me on SkoLab here: https://skolab.open/invite\n\nBest regards,\nResearcher"
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:")
                                    putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(emailInput.trim()))
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                                    putExtra(android.content.Intent.EXTRA_TEXT, body)
                                }
                                try {
                                    context.startActivity(android.content.Intent.createChooser(intent, "Send Email via..."))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No email client found.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Email, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Gmail Invite", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // SMS Invite
                        Button(
                            onClick = {
                                val smsText = "Hi $peerName, join me on SkoLab for secure, encrypted audio/video calling, real-time LaTeX blackboards, and joint manuscript editing: https://skolab.open/invite"
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("smsto:${phoneInput.trim()}")
                                    putExtra("sms_body", smsText)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No SMS client found.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Sms, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SMS Invite", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Text fields for Gmail and Phone input with Contact pickers and Smart suggestions
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Collaborator Email", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_PICK,
                                            ContactsContract.CommonDataKinds.Email.CONTENT_URI
                                        )
                                        emailPickerLauncher.launch(intent)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContactPage,
                                        contentDescription = "Pick Contact Email",
                                        tint = AccentTeal
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        // Smart Gmail sender suggestions
                        if (matchedGmailSenders.isNotEmpty()) {
                            Surface(
                                color = BgCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "📬 GMAIL SENDERS & CONTACTS MATCHED:",
                                        color = AccentTeal,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        matchedGmailSenders.forEach { sender ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(BgElevated)
                                                    .clickable {
                                                        emailInput = sender.first
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = sender.first, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    Text(text = sender.second, color = TextSecondary, fontSize = 9.sp)
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Select suggestion",
                                                    tint = AccentTeal,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Collaborator Phone", color = TextMuted) },
                            placeholder = { Text("e.g. +1234567890", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedContainerColor = BgElevated,
                                unfocusedContainerColor = BgElevated
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_PICK,
                                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                                        )
                                        phonePickerLauncher.launch(intent)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContactPage,
                                        contentDescription = "Pick Contact Phone",
                                        tint = AccentTeal
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        // Smart Phone suggestions
                        if (matchedPhoneContacts.isNotEmpty()) {
                            Surface(
                                color = BgCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "📱 MOBILE CONTACTS & TRUECALLER MATCHED:",
                                        color = AccentTeal,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        matchedPhoneContacts.forEach { contact ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(BgElevated)
                                                    .clickable {
                                                        phoneInput = contact.first
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = contact.second, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    Text(text = contact.first, color = TextSecondary, fontSize = 9.sp)
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Select suggestion",
                                                    tint = AccentTeal,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // --- SECTION 2: MULTI-SERVICE CALLING ROUTER ---
                    Text(
                        text = "📞 DIRECT MULTI-SERVICE CALLING",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    // Cellular Voice Call
                    Button(
                        onClick = {
                            showNonSkoLabDialog = false
                            val number = phoneInput.trim().ifBlank { "+1234567890" }
                            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3B43)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Phone, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Direct Cellular Voice Call", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // WhatsApp Call
                    Button(
                        onClick = {
                            showNonSkoLabDialog = false
                            val number = phoneInput.trim().replace("+", "").replace("-", "").replace(" ", "").ifBlank { "1234567890" }
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$number"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Call / Chat on WhatsApp", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Google Meet conference
                    Button(
                        onClick = {
                            showNonSkoLabDialog = false
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://meet.google.com/new"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.VideoCall, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Launch Google Meet Call", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNonSkoLabDialog = false
                        callingOverlayMode = selectedCallTypeForDialog
                        callState = "calling"
                        showCallingOverlay = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentTeal)
                ) {
                    Text("Start Demo Call", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNonSkoLabDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgCard,
            shape = RoundedCornerShape(16.dp)
        )
    }

    AnimatedVisibility(
        visible = showCallingOverlay,
        enter = fadeIn(animationSpec = tween(500)) + expandIn(expandFrom = Alignment.Center),
        exit = fadeOut(animationSpec = tween(500)) + shrinkOut(shrinkTowards = Alignment.Center)
    ) {
        SkoLabCallingOverlay(
            peerName = peerName,
            mode = callingOverlayMode,
            callState = callState,
            callConnectedTime = callConnectedTime,
            micMuted = micMuted,
            cameraOn = cameraOn,
            speakerOn = speakerOn,
            color = color,
            onMuteToggle = { micMuted = !micMuted },
            onCameraToggle = { cameraOn = !cameraOn },
            onSpeakerToggle = { speakerOn = !speakerOn },
            onEndCall = {
                callState = "disconnected"
                scope.launch {
                    delay(500)
                    showCallingOverlay = false
                }
            },
            onJoinRealJitsi = {
                val roomName = "SkoLabSecure_" + peerId.hashCode().toString()
                val jitsiUrl = "https://meet.jit.si/$roomName"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(jitsiUrl))
                context.startActivity(intent)
            }
        )
    }
}
}



@Composable
fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit
) {
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }
    
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val timestamp = remember(message.timestamp) { 
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)) 
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = alignment
    ) {
        MessageBubbleWrapper(
            message = message,
            onReact = onReact,
            onReply = onReply,
            onCopy = onCopy,
            onToggleStar = onToggleStar,
            onDelete = onDelete
        ) {
            Box {
                Surface(
                    shape = bubbleShape,
                    color = if (isMe) AccentTeal.copy(alpha = 0.12f) else BgCard,
                    border = if (isMe) BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f)) else BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        MarkdownText(
                            markdown = message.content,
                            color = if (isMe) TextPrimary else TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (message.isStarred) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Starred",
                                    tint = AccentAmber,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            Text(
                                text = timestamp,
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                            if (isMe) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Read",
                                    tint = AccentTeal,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                // Render reaction badge if present
                if (!message.reaction.isNullOrEmpty()) {
                    val badgeAlignment = if (isMe) Alignment.BottomStart else Alignment.BottomEnd
                    Box(
                        modifier = Modifier
                            .align(badgeAlignment)
                            .offset(y = 10.dp, x = if (isMe) (-4).dp else 4.dp)
                    ) {
                        ReactionBadge(reaction = message.reaction)
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator(peerName: String) {
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.widthIn(max = 200.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$peerName is typing",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            val infiniteTransition = rememberInfiniteTransition(label = "dots")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f, label = "dotAlpha",
                animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse)
            )
            Text(
                text = "...",
                color = AccentTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.scale(alpha)
            )
        }
    }
}

private fun Modifier.scale(scale: Float) = this.scale(scale, scale)
private fun Modifier.scale(scaleX: Float, scaleY: Float) = this.graphicsLayer(scaleX = scaleX, scaleY = scaleY)

@Composable
fun SkoLabCallingOverlay(
    peerName: String,
    mode: String,
    callState: String,
    callConnectedTime: Int,
    micMuted: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    color: Color,
    onMuteToggle: () -> Unit,
    onCameraToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit,
    onJoinRealJitsi: () -> Unit
) {
    val initials = peerName.split(" ").map { it.take(1) }.joinToString("").uppercase()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        color = Color(0xFF0C1013).copy(alpha = 0.96f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Secure Indicator
            Surface(
                color = Color(0xFF1B3B2B),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure",
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End-to-End Encrypted SkoLab Call",
                        color = Color(0xFF25D366),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Peer info & Ringing / Calling state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsating Avatar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(180.dp)
                ) {
                    if (callState == "calling" || callState == "ringing") {
                        Box(
                            modifier = Modifier
                                .size((120 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.15f))
                        )
                        Box(
                            modifier = Modifier
                                .size((140 * pulseScale).dp)
                                .clip(CircleShape)
                                .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.25f))
                            .border(2.dp, color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = when (callState) {
                        "calling" -> "SkoLab secure calling..."
                        "ringing" -> "Ringing..."
                        "connected" -> "Connected"
                        else -> "Disconnected"
                    },
                    color = if (callState == "connected") Color(0xFF25D366) else Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (callState == "connected") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Real Voice/Video Jitsi transition trigger
            if (callState == "connected") {
                Button(
                    onClick = onJoinRealJitsi,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Real Voice/Video Room",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Controls Panel
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Mic Button
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (micMuted) Color(0xFFEA0038) else Color(0xFF2E3B43))
                ) {
                    Icon(
                        imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                // Video/Camera Button
                if (mode == "video") {
                    IconButton(
                        onClick = onCameraToggle,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (!cameraOn) Color(0xFFEA0038) else Color(0xFF2E3B43))
                    ) {
                        Icon(
                            imageVector = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Camera",
                            tint = Color.White
                        )
                    }
                }

                // Speaker Button
                IconButton(
                    onClick = onSpeakerToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (speakerOn) AccentTeal else Color(0xFF2E3B43))
                ) {
                    Icon(
                        imageVector = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Speaker",
                        tint = Color.White
                    )
                }

                // Red Hang-up button
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEA0038))
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Hang Up",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
