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
import androidx.compose.runtime.collectAsState
import com.company.skolab.ui.theme.*
import com.company.skolab.ui.screens.workspace.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProWorkspaceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferences(context) }
    
    // Persistent subscription type ("Basic", "Pro", or "Labs")
    val subscriptionType by userPrefs.subscriptionType.collectAsState(initial = "Basic")
    
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

// ═══════════════════════════════════════════════════════════════
// Tab 2: Scoop Shield (Draft Protection)
// ═══════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════
// Tab 3: Labs & Collaborative Vault
// ═══════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════
// Tab 4: B2B Job Matching Board
// ═══════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════
// Placeholder card when tier is Basic
// ═══════════════════════════════════════════════════════════════
// Tab 3.5: Academic Scheduler & deadlines Calendar
// ═══════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════
// Payment Dialogue Overlay with Full Interactive Mock Form
// ═══════════════════════════════════════════════════════════════
