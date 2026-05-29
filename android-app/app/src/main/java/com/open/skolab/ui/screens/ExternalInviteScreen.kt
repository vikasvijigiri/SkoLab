package com.open.skolab.ui.screens

import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.ui.theme.*
import com.open.skolab.network.ServerLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalInviteScreen(
    collaboratorName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var emailInput by remember { mutableStateOf(collaboratorName.lowercase().replace(" ", "") + "@university.edu") }
    var phoneInput by remember { mutableStateOf("") }
    var resolvedInstitution by remember { mutableStateOf("") }
    var isResolvingEmail by remember { mutableStateOf(false) }

    // Dynamic backend OpenAlex/Scraping lookup for collaborator's email
    LaunchedEffect(collaboratorName) {
        if (collaboratorName.isNotEmpty()) {
            isResolvingEmail = true
            withContext(Dispatchers.IO) {
                try {
                    val base = ServerLocator.baseUrl.value ?: "http://10.0.2.2:8000"
                    val url = "$base/api/v1/authors/resolve_email?name=" +
                              android.net.Uri.encode(collaboratorName)
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

    val requestPermissionLauncher = rememberLauncherForActivityResult(
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

    LaunchedEffect(hasContactsPermission) {
        if (hasContactsPermission) {
            withContext(Dispatchers.IO) {
                val tempMap = mutableMapOf<String, Triple<String, String, String>>()
                try {
                    val contentResolver = context.contentResolver
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

    LaunchedEffect(emailInput, hasContactsPermission) {
        if (hasContactsPermission && emailInput.length >= 2) {
            withContext(Dispatchers.IO) {
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

    LaunchedEffect(phoneInput, hasContactsPermission) {
        if (hasContactsPermission && phoneInput.length >= 2) {
            withContext(Dispatchers.IO) {
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

    val phonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                try {
                    val cursor = context.contentResolver.query(
                        it,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
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

    val emailPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                try {
                    val cursor = context.contentResolver.query(
                        it,
                        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Contact $collaboratorName",
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
        containerColor = EntropiColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "$collaboratorName hasn't joined SkoLab yet. Send an invitation to connect securely in-app, or route the call through other popular services below.",
                color = TextSecondary,
                fontSize = 15.sp
            )

            // Dynamic Resolve Card
            if (isResolvingEmail || resolvedInstitution.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isResolvingEmail) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AccentTeal.copy(alpha = 0.08f))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentTeal,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Scraping OpenAlex for academic email...",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else if (resolvedInstitution.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AccentAmber.copy(alpha = 0.08f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = "Institution Resolved",
                                    tint = AccentAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Resolved Institution: $resolvedInstitution",
                                    color = AccentAmber,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Permissions Card
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!hasContactsPermission) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BorderLight.copy(alpha = 0.15f))
                                .clickable {
                                    requestPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = "Contacts Permission",
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Smart Contact Suggestions",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Find Truecaller and Gmail contacts as you type.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Grant Permission",
                                tint = TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentTeal.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Permission Active",
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Smart contact suggestions enabled!",
                                color = AccentTeal,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (hasContactsPermission && allDeviceContacts.isNotEmpty()) {
                        Text(
                            text = "📇 QUICK-LINK FROM PHONE CONTACTS",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        
                        Surface(
                            color = BgElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(allDeviceContacts) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(BgCard)
                                            .clickable {
                                                emailInput = contact.third.ifEmpty { emailInput }
                                                phoneInput = contact.second.ifEmpty { phoneInput }
                                                Toast.makeText(
                                                    context,
                                                    "🔗 Linked to ${contact.first}!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.first,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (contact.second.isNotEmpty()) {
                                                    Text(
                                                        text = "📱 ${contact.second}",
                                                        color = TextSecondary,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                                if (contact.third.isNotEmpty()) {
                                                    Text(
                                                        text = "✉️ ${contact.third}",
                                                        color = TextSecondary,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = "Link profile",
                                            tint = AccentTeal,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Invite Channels Card
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
                        text = "INVITE TO SKOLAB",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val subject = "Invitation to collaborate on SkoLab"
                                val body = "Hi $collaboratorName,\n\nI would love to collaborate with you on our research papers using SkoLab. SkoLab offers secure, encrypted voice/video synchronization, real-time LaTeX blackboards, and joint manuscript editing.\n\nJoin me on SkoLab here: https://skolab.open/invite\n\nBest regards,\nResearcher"
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:")
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(emailInput.trim()))
                                    putExtra(Intent.EXTRA_SUBJECT, subject)
                                    putExtra(Intent.EXTRA_TEXT, body)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(intent, "Send Email via..."))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No email client found.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Email, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Gmail", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val smsText = "Hi $collaboratorName, join me on SkoLab for secure, encrypted audio/video calling, real-time LaTeX blackboards, and joint manuscript editing: https://skolab.open/invite"
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
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
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Sms, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("SMS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        val intent = Intent(
                                            Intent.ACTION_PICK,
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

                        if (matchedGmailSenders.isNotEmpty()) {
                            Surface(
                                color = BgElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "📬 MATCHED SUGGESTIONS:",
                                        color = AccentTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        matchedGmailSenders.forEach { sender ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(BgCard)
                                                    .clickable { emailInput = sender.first }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = sender.first, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                    Text(text = sender.second, color = TextSecondary, fontSize = 11.sp)
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Select suggestion",
                                                    tint = AccentTeal,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        val intent = Intent(
                                            Intent.ACTION_PICK,
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

                        if (matchedPhoneContacts.isNotEmpty()) {
                            Surface(
                                color = BgElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "📱 MATCHED SUGGESTIONS:",
                                        color = AccentTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        matchedPhoneContacts.forEach { contact ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(BgCard)
                                                    .clickable { phoneInput = contact.first }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = contact.second, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                    Text(text = contact.first, color = TextSecondary, fontSize = 11.sp)
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Select suggestion",
                                                    tint = AccentTeal,
                                                    modifier = Modifier.size(20.dp)
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

            // Direct Calls Card
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
                        text = "📞 DIRECT MULTI-SERVICE CALLING",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val number = phoneInput.trim().ifBlank { "+1234567890" }
                                val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3B43)),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Phone, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Direct Cellular Voice Call", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val number = phoneInput.trim().replace("+", "").replace("-", "").replace(" ", "").ifBlank { "1234567890" }
                                val url = "https://wa.me/$number"
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Call / Chat on WhatsApp", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://meet.google.com/new"))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.VideoCall, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Launch Google Meet Call", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
