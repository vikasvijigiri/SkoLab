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
