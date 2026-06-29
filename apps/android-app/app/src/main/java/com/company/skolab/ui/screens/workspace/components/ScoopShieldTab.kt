package com.company.skolab.ui.screens.workspace.components

import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*

import androidx.compose.ui.platform.LocalContext
import com.company.skolab.data.UserPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import com.company.skolab.network.ChatMessage

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.*
import com.company.skolab.ui.components.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.company.skolab.model.*
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ScoopShieldTab(isEnabled: Boolean) {
    if (!isEnabled) {
        PremiumPlaceholderCard(
            requiredTier = "SkoLab Pro",
            featureDesc = "Scoop Shield monitors your active manuscript drafts on Overleaf and code projects on GitHub to proactively alert you the instant a conflicting preprint is uploaded on arXiv, bioRxiv, or OpenAlex."
        )
        return
    }

    var draftUrl by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    
    // In-memory linked list of manuscripts
    val linkedDrafts = remember {
        mutableStateListOf(
            LinkedDraft("Quantum Key Distribution Simulation", "https://github.com/iith/qkd-simulation", "Active Shielding"),
            LinkedDraft("Topological Phase Transitions Paper", "https://www.overleaf.com/project/6642d992f81", "No Conflicts Found")
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }
    
    val pushAlertsEnabled by userPrefs.pushNotificationsEnabled.collectAsState(initial = false)
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                userPrefs.setPushNotificationsEnabled(true)
                com.company.skolab.utils.SkoLabNotificationManager.createNotificationChannel(context)
                com.company.skolab.utils.SkoLabNotificationManager.scheduleDailyReminder(context)
                com.company.skolab.utils.SkoLabNotificationManager.showReminderNotification(context)
            }
        }
    }
    var showNotificationRationaleDialog by remember { mutableStateOf(false) }

    var emailAlertsEnabled by remember { mutableStateOf(true) }
    
    var isScanning by remember { mutableStateOf(false) }
    var lastScanResult by remember { mutableStateOf("No conflicts detected. Last scanned 2 hours ago.") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            // Dynamic Scan Controls
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active Shielding Status", fontSize = 11.sp, color = AccentAmber, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(lastScanResult, fontSize = 13.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            isScanning = true
                            lastScanResult = "Scanning arXiv & bioRxiv databases..."
                        },
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(color = TextOnAccent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Scan Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Launched scanner simulator
        if (isScanning) {
            item {
                LaunchedEffect(Unit) {
                    delay(2500)
                    isScanning = false
                    lastScanResult = "Completed: 0 conflicts found out of 1,248 preprints scanned today."
                }
            }
        }

        // Add Manuscript Form
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LINK MANUSCRIPT / REPOSITORY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("Manuscript Title / Short Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftUrl,
                        onValueChange = { draftUrl = it },
                        label = { Text("Overleaf/GitHub Project URL") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            if (draftName.isNotBlank() && draftUrl.isNotBlank()) {
                                linkedDrafts.add(LinkedDraft(draftName, draftUrl, "Active Shielding"))
                                draftName = ""
                                draftUrl = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Initiate Real-time Shielding", color = TextOnAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // List of Active Shields
        item {
            Text(
                text = "PROTECTED PROJECTS (${linkedDrafts.size})",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        items(linkedDrafts) { draft ->
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(draft.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        Text(draft.url, color = TextMuted, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(AccentEmerald, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(draft.status, fontSize = 11.sp, color = AccentEmerald, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { linkedDrafts.remove(draft) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Shield", tint = AccentRose)
                    }
                }
            }
        }

        // Alarm settings
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "COMMUNICATION PROTOCOLS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Emergency Email Alert", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Receive paper citations when scoops occur", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = emailAlertsEnabled,
                            onCheckedChange = { emailAlertsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentTeal)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Push Notifications", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Instant mobile warnings on local thread", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = pushAlertsEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.POST_NOTIFICATIONS
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        
                                        if (hasPermission) {
                                            scope.launch {
                                                userPrefs.setPushNotificationsEnabled(true)
                                                com.company.skolab.utils.SkoLabNotificationManager.createNotificationChannel(context)
                                                com.company.skolab.utils.SkoLabNotificationManager.scheduleDailyReminder(context)
                                                com.company.skolab.utils.SkoLabNotificationManager.showReminderNotification(context)
                                            }
                                        } else {
                                            showNotificationRationaleDialog = true
                                        }
                                    } else {
                                        scope.launch {
                                            userPrefs.setPushNotificationsEnabled(true)
                                            com.company.skolab.utils.SkoLabNotificationManager.createNotificationChannel(context)
                                            com.company.skolab.utils.SkoLabNotificationManager.scheduleDailyReminder(context)
                                            com.company.skolab.utils.SkoLabNotificationManager.showReminderNotification(context)
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        userPrefs.setPushNotificationsEnabled(false)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentTeal)
                        )
                    }
                }
            }
        }
    }

    if (showNotificationRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationRationaleDialog = false },
            title = {
                Text(
                    text = "Enable Push Notifications",
                    style = Typography.titleLarge,
                    color = SkoLabTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "SkoLab needs notifications permission to alert you instantly when draft protection conflicts are found or when you receive responses in research chats.",
                    style = Typography.bodyMedium,
                    color = SkoLabTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotificationRationaleDialog = false
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkoLabPrimary)
                ) {
                    Text("Enable", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationRationaleDialog = false }) {
                    Text("Not Now", color = SkoLabTextSecondary)
                }
            },
            containerColor = SkoLabSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

data class LinkedDraft(val title: String, val url: String, val status: String)

