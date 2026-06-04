package com.company.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.company.skolab.data.UserPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProWorkspaceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }
    
    // Persistent subscription type ("Basic", "Pro", or "Labs")
    val subscriptionType by userPrefs.subscriptionType.collectAsStateWithLifecycle(initialValue = "Basic")
    
    // UI tabs state
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Membership", "Scoop Shield", "Labs & Vault", "Scheduler", "Careers")
    
    // Show Payment Dialog
    var showPaymentDialog by remember { mutableStateOf(false) }
    var targetUpgradeTier by remember { mutableStateOf("Pro") } // "Pro" or "Labs"

    // Background Gradient matching SkoLab Pro Theme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // Premium Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "INTELLIGENCE WORKSPACE",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentAmber,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SkoLab ",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (subscriptionType) {
                                "Labs" -> AccentEmerald
                                "Pro" -> AccentAmber
                                else -> TextMuted
                            },
                            border = BorderStroke(
                                1.dp,
                                when (subscriptionType) {
                                    "Labs" -> AccentEmerald
                                    "Pro" -> AccentAmber
                                    else -> TextMuted
                                }
                            )
                        ) {
                            Text(
                                text = subscriptionType.uppercase(),
                                color = when (subscriptionType) {
                                    "Labs" -> AccentEmerald
                                    "Pro" -> AccentAmber
                                    else -> TextSecondary
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = "Secure Encryption",
                    tint = AccentTeal,
                    modifier = Modifier.size(24.dp)
                )
            }

            SecondaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = BgPrimary,
                contentColor = AccentTeal,
                edgePadding = 16.dp,
                divider = { HorizontalDivider(color = BorderLight) }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        },
                        selectedContentColor = TextPrimary,
                        unselectedContentColor = TextSecondary
                    )
                }
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> BillingPortalTab(
                        currentTier = subscriptionType,
                        onUpgradeClick = { tier ->
                            targetUpgradeTier = tier
                            showPaymentDialog = true
                        },
                        onDowngradeClick = {
                            scope.launch {
                                userPrefs.setSubscriptionType("Basic")
                            }
                        }
                    )
                    1 -> ScoopShieldTab(isEnabled = subscriptionType != "Basic")
                    2 -> LabsWorkspaceTab(isEnabled = subscriptionType == "Labs")
                    3 -> SchedulerTab(isEnabled = subscriptionType != "Basic")
                    4 -> JobMatchingTab()
                }
            }
        }
    }

    // Payment Form Dialog
    if (showPaymentDialog) {
        PaymentFormDialog(
            targetTier = targetUpgradeTier,
            onDismiss = { showPaymentDialog = false },
            onPaymentSuccess = {
                scope.launch {
                    userPrefs.setSubscriptionType(targetUpgradeTier)
                    showPaymentDialog = false
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Tab 1: Billing & Portal
// ═══════════════════════════════════════════════════════════════
@Composable
fun BillingPortalTab(
    currentTier: String,
    onUpgradeClick: (String) -> Unit,
    onDowngradeClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            // Main Status Display Card
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    when (currentTier) {
                        "Labs" -> AccentEmerald
                        "Pro" -> AccentAmber
                        else -> BorderLight
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "CURRENT MEMBERSHIP TIER",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (currentTier) {
                            "Labs" -> "SkoLab Labs Workspace"
                            "Pro" -> "SkoLab Professional"
                            else -> "SkoLab Basic (Free Tier)"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (currentTier) {
                            "Labs" -> "Ultimate plan for academic labs, shared vaults, cross-author synergy matrices & B2B recruiters."
                            "Pro" -> "Advanced tools for independent researchers, real-time Scoop Shield, and priority logic duels."
                            else -> "Basic paper discovery, logic timeline tracking, and simple connection messaging."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    
                    if (currentTier != "Basic") {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = BorderLight)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Billing Frequency", fontSize = 10.sp, color = TextMuted)
                                Text("Monthly Auto-Renewal", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onDowngradeClick,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
                                border = BorderStroke(0.5.dp, AccentRose),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Cancel Tier", color = AccentRose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Available Tiers Section Header
        item {
            Text(
                text = "UPGRADE WORKSPACE CAPABILITIES",
                style = MaterialTheme.typography.labelSmall,
                color = AccentTeal,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Tier Option: SkoLab Pro
        item {
            SubscriptionTierCard(
                title = "SkoLab Pro",
                price = "$29/mo",
                features = listOf(
                    "Real-time Scoop Shield for Overleaf & Github",
                    "Unlimited Literature Cross-Examiner (Contradiction Finder)",
                    "Double Cognitive Mastery Points per logic duel",
                    "Early-Access B2B Job Board Applications"
                ),
                color = AccentAmber,
                isActive = currentTier == "Pro",
                onActionClick = { onUpgradeClick("Pro") }
            )
        }

        // Tier Option: SkoLab Labs
        item {
            SubscriptionTierCard(
                title = "SkoLab Labs",
                price = "$99/mo",
                features = listOf(
                    "All Pro features included",
                    "Collaborative Workspaces for up to 10 Researchers",
                    "Shared Paper Vaults & synced review boards",
                    "Dynamic Cross-Author Synergy Matrices",
                    "Featured placement on Enterprise recruiter portal"
                ),
                color = AccentEmerald,
                isActive = currentTier == "Labs",
                onActionClick = { onUpgradeClick("Labs") }
            )
        }
    }
}

@Composable
fun SubscriptionTierCard(
    title: String,
    price: String,
    features: List<String>,
    color: Color,
    isActive: Boolean,
    onActionClick: () -> Unit
) {
    Surface(
        color = BgCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (isActive) 2.dp else 1.dp,
            if (isActive) color else BorderLight
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
                Text(
                    text = price,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            features.forEach { feature ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = feature,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onActionClick,
                enabled = !isActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) TextMuted else color,
                    contentColor = TextOnAccent
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isActive) "Active Plan" else "Subscribe Now",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Tab 2: Scoop Shield (Draft Protection)
// ═══════════════════════════════════════════════════════════════
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
    
    val pushAlertsEnabled by userPrefs.pushNotificationsEnabled.collectAsStateWithLifecycle(initialValue = false)
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
                                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
}

data class LinkedDraft(val title: String, val url: String, val status: String)

// ═══════════════════════════════════════════════════════════════
// Tab 3: Labs & Collaborative Vault
// ═══════════════════════════════════════════════════════════════
@Composable
fun LabsWorkspaceTab(isEnabled: Boolean) {
    if (!isEnabled) {
        PremiumPlaceholderCard(
            requiredTier = "SkoLab Labs",
            featureDesc = "SkoLab Labs is the unified workspace for research groups and departments. Create shared paper vaults, invite lab collaborators, leave annotations on papers, and build automatic synergy matrices."
        )
        return
    }

    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUserName = cachedUser?.name ?: "SkoLab User"
    val currentUserEmail = cachedUser?.email ?: "user@university.edu"
    val userFirstName = cachedUser?.firstName ?: "User"

    var collaboratorEmail by remember { mutableStateOf("") }
    val collaborators = remember(currentUserName, currentUserEmail) {
        mutableStateListOf(
            LabMember("Prof. $currentUserName", currentUserEmail, "Director / PI"),
            LabMember("Dr. Ananya Rao", "ananya@iith.ac.in", "Postdoc Fellow"),
            LabMember("Sundeep Sen", "sundeep@iith.ac.in", "PhD Researcher")
        )
    }

    var sharedPaperTitle by remember { mutableStateOf("") }
    val sharedVault = remember(userFirstName) {
        mutableStateListOf(
            SharedPaper("Entropy Collapse in LLM Logs", "Added by Sundeep, 2 hours ago"),
            SharedPaper("Topological Quantum Field Theory in 2D", "Added by Prof. $userFirstName, 1 day ago")
        )
    }

    var showSynergyMatrix by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Lab Members Section
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LAB RESEARCH GROUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentEmerald,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    collaborators.forEach { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = AccentEmerald,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        member.name.split(" ").last().take(1),
                                        color = AccentEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(member.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(member.email, fontSize = 11.sp, color = TextSecondary)
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = BgSubtle,
                                border = BorderStroke(0.5.dp, BorderLight)
                            ) {
                                Text(
                                    member.role,
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = collaboratorEmail,
                            onValueChange = { collaboratorEmail = it },
                            placeholder = { Text("Enter collaborator email") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (collaboratorEmail.isNotBlank() && collaboratorEmail.contains("@")) {
                                    val name = collaboratorEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                                    collaborators.add(LabMember(name, collaboratorEmail, "Researcher"))
                                    collaboratorEmail = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Invite", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Shared Paper Vault Section
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SHARED PAPER VAULT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    sharedVault.forEach { paper ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(paper.title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(paper.meta, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = sharedPaperTitle,
                            onValueChange = { sharedPaperTitle = it },
                            placeholder = { Text("Enter paper title to share") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (sharedPaperTitle.isNotBlank()) {
                                    sharedVault.add(SharedPaper(sharedPaperTitle, "Added by You, Just Now"))
                                    sharedPaperTitle = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Share", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Synergy matrix trigger
        item {
            Button(
                onClick = { showSynergyMatrix = !showSynergyMatrix },
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.TableChart, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (showSynergyMatrix) "Hide Comparison Matrix" else "Synthesize Comparison Matrix",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Dynamic Comparison Synergy Matrix
        if (showSynergyMatrix) {
            item {
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AccentAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            "CROSS-PAPER SYNERGY MATRIX",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Render Matrix Table
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Dimension", width = 100.dp, header = true)
                            TableTextCell("Entropy Collapse", width = 140.dp, header = true)
                            TableTextCell("TQFT in 2D", width = 140.dp, header = true)
                        }
                        HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Core Model", width = 100.dp, header = false)
                            TableTextCell("Autoregressive LLM", width = 140.dp, header = false)
                            TableTextCell("Chern-Simons", width = 140.dp, header = false)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Key Metric", width = 100.dp, header = false)
                            TableTextCell("Perplexity Bounds", width = 140.dp, header = false)
                            TableTextCell("Berry Curvature", width = 140.dp, header = false)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TableTextCell("Limitation", width = 100.dp, header = false)
                            TableTextCell("Finite Context", width = 140.dp, header = false)
                            TableTextCell("Gauge Ambiguity", width = 140.dp, header = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TableTextCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean) {
    Text(
        text = text,
        fontSize = if (header) 11.sp else 12.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        color = if (header) TextPrimary else TextSecondary,
        modifier = Modifier
            .width(width)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        maxLines = 2
    )
}

data class LabMember(val name: String, val email: String, val role: String)
data class SharedPaper(val title: String, val meta: String)

// ═══════════════════════════════════════════════════════════════
// Tab 4: B2B Job Matching Board
// ═══════════════════════════════════════════════════════════════
@Composable
fun JobMatchingTab() {
    val jobs = remember {
        mutableStateListOf(
            JobPosting("Research Scientist - Reasoning", "OpenAI", "San Francisco, CA (Hybrid)", "96%", "Apply Now"),
            JobPosting("Senior AI Resident", "Google DeepMind", "London, UK (Relocation)", "92%", "Apply Now"),
            JobPosting("Quantum Computing Postdoc", "Rigetti Computing", "Berkeley, CA", "88%", "Apply Now")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("💡", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("B2B Talent Sync", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        Text("These career placements are automatically tailored by indexing your local academic complexity score, saved publications, and domain mastery.", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }

        items(jobs) { job ->
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (job.match == "96%") AccentAmber else BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(job.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                            Text("${job.company} • ${job.location}", fontSize = 13.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AccentTealLight,
                            border = BorderStroke(0.5.dp, AccentTeal)
                        ) {
                            Text(
                                text = "${job.match} Match",
                                color = AccentTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Skills: LaTeX Proofing, Quantum Sim, PyTorch",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        
                        Button(
                            onClick = {
                                val idx = jobs.indexOf(job)
                                if (idx != -1) {
                                    jobs[idx] = job.copy(buttonText = "Application Submitted")
                                }
                            },
                            enabled = job.buttonText == "Apply Now",
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (job.buttonText == "Apply Now") AccentTeal else AccentEmerald,
                                contentColor = if (job.buttonText == "Apply Now") TextOnAccent else AccentEmerald
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(job.buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

data class JobPosting(val title: String, val company: String, val location: String, val match: String, val buttonText: String)

// ═══════════════════════════════════════════════════════════════
// Placeholder card when tier is Basic
// ═══════════════════════════════════════════════════════════════
@Composable
fun PremiumPlaceholderCard(requiredTier: String, featureDesc: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = BgCard,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Feature Locked",
                    tint = AccentAmber,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$requiredTier Upgrade Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = featureDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Tab 3.5: Academic Scheduler & deadlines Calendar
// ═══════════════════════════════════════════════════════════════
data class AcademicEvent(val title: String, val time: String, val type: String, val invitee: String, val date: Int)

@Composable
fun SchedulerTab(isEnabled: Boolean) {
    if (!isEnabled) {
        PremiumPlaceholderCard(
            requiredTier = "SkoLab Pro",
            featureDesc = "Meetings Scheduler & Conference Calendar permits scheduling advisor meetings, booking 1-on-1 lab consults, and tracking upcoming conference abstract countdowns with automated LaTeX schedule exports."
        )
        return
    }

    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsStateWithLifecycle(initialValue = null)
    val currentUserName = cachedUser?.name ?: "SkoLab User"

    var selectedDay by remember { mutableStateOf(24) }
    
    // Core dynamic event list
    val eventsList = remember(currentUserName) {
        mutableStateListOf(
            AcademicEvent("1-on-1 Advisor Check-in", "10:00 AM - 10:30 AM", "Advisor check-in", "Prof. $currentUserName", 24),
            AcademicEvent("Weekly Lab Journal Club", "02:00 PM - 03:30 PM", "Journal Club", "IIT Hyderabad Group", 27),
            AcademicEvent("NeurIPS Abstract Deadline", "11:59 PM EST", "Conference Deadline", "Global", 28),
            AcademicEvent("Preprint Brainstorming", "11:00 AM - 12:00 PM", "Brainstorm", "Dr. Ananya Rao", 24)
        )
    }

    // Input fields for scheduling
    var meetingTitle by remember { mutableStateOf("") }
    var meetingTime by remember { mutableStateOf("") }
    var meetingInvitee by remember(currentUserName) { mutableStateOf("Prof. $currentUserName") }
    var meetingType by remember { mutableStateOf("Advisor check-in") }

    val collaboratorsList = remember(currentUserName) {
        listOf("Prof. $currentUserName", "Dr. Ananya Rao", "Sundeep Sen", "IIT Hyderabad Group")
    }
    val meetingTypes = listOf("Advisor check-in", "Journal Club", "Brainstorm", "Colloquium Presentation")

    // Conference Countdowns
    var timeRemainingNeurips by remember { mutableStateOf("Calculating...") }
    var timeRemainingIcml by remember { mutableStateOf("Calculating...") }
    
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            
            // NeurIPS: June 5, 2026 13:00 UTC
            val neuripsTarget = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(2026, 5, 5, 13, 0, 0)
            }.timeInMillis
            val diffNeurips = neuripsTarget - now
            timeRemainingNeurips = if (diffNeurips > 0) {
                val days = diffNeurips / (24 * 60 * 60 * 1000)
                val hours = (diffNeurips / (60 * 60 * 1000)) % 24
                val minutes = (diffNeurips / (60 * 1000)) % 60
                val seconds = (diffNeurips / 1000) % 60
                "${days}d ${hours}h ${minutes}m ${seconds}s"
            } else {
                "Submissions Closed"
            }

            // ICML: July 12, 2026 13:00 UTC
            val icmlTarget = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(2026, 6, 12, 13, 0, 0)
            }.timeInMillis
            val diffIcml = icmlTarget - now
            timeRemainingIcml = if (diffIcml > 0) {
                val days = diffIcml / (24 * 60 * 60 * 1000)
                val hours = (diffIcml / (60 * 60 * 1000)) % 24
                val minutes = (diffIcml / (60 * 1000)) % 60
                val seconds = (diffIcml / 1000) % 60
                "${days}d ${hours}h ${minutes}m ${seconds}s"
            } else {
                "Submissions Closed"
            }
            
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Interactive Calendar Card
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header month
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = TextPrimary)
                        }
                        Text("MAY 2026", fontWeight = FontWeight.Black, color = TextPrimary, fontSize = 15.sp, letterSpacing = 1.sp)
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextPrimary)
                        }
                    }
                    
                    // Weekday headers
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
                            Text(
                                text = day,
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Grid cells
                    val daysInMonth = 31
                    val startPadding = 4 // May 1, 2026 is Friday
                    val totalCells = daysInMonth + startPadding
                    val rows = (totalCells + 6) / 7
                    
                    for (r in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            for (c in 0 until 7) {
                                val cellIndex = r * 7 + c
                                val dayNumber = cellIndex - startPadding + 1
                                if (cellIndex < startPadding || dayNumber > daysInMonth) {
                                    Spacer(modifier = Modifier.size(36.dp))
                                } else {
                                    val isSelected = selectedDay == dayNumber
                                    val dayEvents = eventsList.filter { it.date == dayNumber }
                                    val hasEvent = dayEvents.isNotEmpty()
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) AccentTeal else if (hasEvent) BgSubtle else BgPrimary)
                                            .border(
                                                if (isSelected) 0.dp else 1.dp,
                                                if (isSelected) BgPrimary else if (hasEvent) AccentTeal else BorderLight,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedDay = dayNumber },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = dayNumber.toString(),
                                                color = if (isSelected) TextOnAccent else TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected || hasEvent) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (hasEvent) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 2.dp)
                                                        .size(4.dp)
                                                        .background(if (isSelected) TextOnAccent else AccentAmber, CircleShape)
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

        // Daily Agenda Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AGENDA FOR MAY $selectedDay, 2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentTeal,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                // Exporter Button
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        val latexString = StringBuilder().apply {
                            append("% SkoLab Academic Agenda\n")
                            append("\\begin{table}[h]\n")
                            append("\\centering\n")
                            append("\\caption{SkoLab Scholar Agenda - May 2026}\n")
                            append("\\begin{tabular}{lll}\n")
                            append("\\hline\n")
                            append("Date & Time & Scheduled Event (Host/Invitee) \\\\\n")
                            append("\\hline\n")
                            eventsList.sortedBy { it.date }.forEach { ev ->
                                append("May ${ev.date} & ${ev.time} & ${ev.title} (${ev.invitee}) \\\\\n")
                            }
                            append("\\hline\n")
                            append("\\end{tabular}\n")
                            append("\\end{table}")
                        }.toString()
                        
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("LaTeX Agenda", latexString)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "LaTeX Table copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export LaTeX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val dayEvents = eventsList.filter { it.date == selectedDay }
        if (dayEvents.isEmpty()) {
            item {
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No scheduled research sessions today. Coordinate meeting slots below.",
                        modifier = Modifier.padding(16.dp),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(dayEvents) { ev ->
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when(ev.type) {
                                "Conference Deadline" -> AccentRose
                                "Journal Club" -> AccentEmerald
                                else -> AccentTeal
                            },
                            border = BorderStroke(1.dp, when(ev.type) {
                                "Conference Deadline" -> AccentRose
                                "Journal Club" -> AccentEmerald
                                else -> AccentTeal
                            })
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Icon(
                                    imageVector = when(ev.type) {
                                        "Conference Deadline" -> Icons.Default.Science
                                        "Journal Club" -> Icons.Default.Groups
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    tint = when(ev.type) {
                                        "Conference Deadline" -> AccentRose
                                        "Journal Club" -> AccentEmerald
                                        else -> AccentTeal
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text(ev.time, color = TextMuted, fontSize = 11.sp)
                            Text("Invitee: ${ev.invitee}", color = TextSecondary, fontSize = 12.sp)
                        }
                        
                        IconButton(onClick = { eventsList.remove(ev) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel Slot", tint = AccentRose)
                        }
                    }
                }
            }
        }

        // Schedule Slot Form
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "COORDINATE RESEARCH MEETING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = meetingTitle,
                        onValueChange = { meetingTitle = it },
                        label = { Text("Meeting Title / Agenda") },
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
                        value = meetingTime,
                        onValueChange = { meetingTime = it },
                        label = { Text("Time Slot (e.g. 03:00 PM - 04:00 PM)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Invitee Select Card
                    Text("Select Host / Invitee", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        collaboratorsList.forEach { col ->
                            val isSelected = meetingInvitee == col
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentTealLight else BgSubtle,
                                border = BorderStroke(1.dp, if (isSelected) AccentTeal else BorderLight),
                                modifier = Modifier.clickable { meetingInvitee = col }
                            ) {
                                Text(
                                    text = col,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Meeting Type Select Card
                    Text("Select Session Type", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        meetingTypes.forEach { type ->
                            val isSelected = meetingType == type
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentTealLight else BgSubtle,
                                border = BorderStroke(1.dp, if (isSelected) AccentTeal else BorderLight),
                                modifier = Modifier.clickable { meetingType = type }
                            ) {
                                Text(
                                    text = type,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            if (meetingTitle.isNotBlank() && meetingTime.isNotBlank()) {
                                eventsList.add(AcademicEvent(meetingTitle, meetingTime, meetingType, meetingInvitee, selectedDay))
                                meetingTitle = ""
                                meetingTime = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Lock Schedule Slot", color = TextOnAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Live Countdown Card
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACTIVE SCHOLAR CONFERENCES", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextPrimary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("NeurIPS 2026", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Abstract Registration", fontSize = 11.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentRose,
                            border = BorderStroke(0.5.dp, AccentRose)
                        ) {
                            Text(
                                text = timeRemainingNeurips,
                                color = AccentRose,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ICML 2026", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Full Paper Submission", fontSize = 11.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentTealLight,
                            border = BorderStroke(0.5.dp, AccentTeal)
                        ) {
                            Text(
                                text = timeRemainingIcml,
                                color = AccentTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Payment Dialogue Overlay with Full Interactive Mock Form
// ═══════════════════════════════════════════════════════════════
@Composable
fun PaymentFormDialog(
    targetTier: String,
    onDismiss: () -> Unit,
    onPaymentSuccess: () -> Unit
) {
    var cardNumber by remember { mutableStateOf("") }
    var expiryDate by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }
    var cardName by remember { mutableStateOf("") }
    
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var paymentStage by remember { mutableStateOf(0) } // 0 = edit, 1 = success animation

    val focusManager = LocalFocusManager.current

    Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
        Surface(
            color = BgElevated,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            if (paymentStage == 1) {
                // Success screen inside the dialog
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Surface(
                        shape = CircleShape,
                        color = AccentEmerald,
                        modifier = Modifier.size(72.dp),
                        border = BorderStroke(2.dp, AccentEmerald)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, contentDescription = "Success", tint = AccentEmerald, modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Payment Authorized!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Your workspace has been successfully upgraded to $targetTier status.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onPaymentSuccess,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unlock Portal", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            } else {
                // Credit Card Input Fields Form
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Checkout: $targetTier",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        IconButton(onClick = onDismiss, enabled = !isProcessing) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Card Visual Mock
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ProWorkspaceNavy,
                        border = BorderStroke(1.dp, ProWorkspaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("SkoLab Premium", color = ProWorkspaceIndigo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.CreditCard, contentDescription = null, tint = Color.White)
                            }
                            
                            Text(
                                text = if (cardNumber.isBlank()) "•••• •••• •••• ••••" else cardNumber.chunked(4).joinToString(" "),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("CARDHOLDER", color = ProWorkspaceSlateText, fontSize = 8.sp)
                                    Text(if (cardName.isBlank()) "YOUR NAME" else cardName.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("EXPIRES", color = ProWorkspaceSlateText, fontSize = 8.sp)
                                    Text(if (expiryDate.isBlank()) "MM/YY" else expiryDate, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Input: Cardholder Name
                    OutlinedTextField(
                        value = cardName,
                        onValueChange = { cardName = it },
                        label = { Text("Cardholder Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Input: Card Number
                    OutlinedTextField(
                        value = cardNumber,
                        onValueChange = { if (it.length <= 16 && it.all { char -> char.isDigit() }) cardNumber = it },
                        label = { Text("16-Digit Card Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = expiryDate,
                            onValueChange = { if (it.length <= 5) expiryDate = it },
                            label = { Text("Expiry (MM/YY)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Right) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1.5f)
                        )
                        OutlinedTextField(
                            value = cvv,
                            onValueChange = { if (it.length <= 3 && it.all { char -> char.isDigit() }) cvv = it },
                            label = { Text("CVV") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (errorMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage, color = AccentRose, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    val scope = rememberCoroutineScope()
                    Button(
                        onClick = {
                            if (cardName.isBlank()) {
                                errorMessage = "Cardholder name is required."
                            } else if (cardNumber.length < 16) {
                                errorMessage = "Invalid card number: must be 16 digits."
                            } else if (expiryDate.length < 5 || !expiryDate.contains("/")) {
                                errorMessage = "Invalid expiry date: use MM/YY format."
                            } else if (cvv.length < 3) {
                                errorMessage = "Invalid CVV: must be 3 digits."
                            } else {
                                isProcessing = true
                                errorMessage = ""
                                scope.launch {
                                    delay(2000)
                                    isProcessing = false
                                    paymentStage = 1
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = TextOnAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Submit Payment", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
