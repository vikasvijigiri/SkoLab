package com.open.entropy.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.components.ScientificCard
import com.open.entropy.ui.components.ScientificBadge
import com.open.entropy.ui.layout.ScreenInsets
import com.open.entropy.ui.theme.*

// ─────────────────────────────────────────────────────────────────
// LIBRARY SCREEN — Saved Papers + Grants + Nexus Bridges + Alerts
// ─────────────────────────────────────────────────────────────────

@Composable
fun LibraryScreen(onPaperClick: (String) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("SAVED", "GRANTS 🏆", "BRIDGES", "ALERTS")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp)
        ) {
            Text(
                text = "INTEL VAULT",
                style = Typography.labelSmall,
                color = AccentTeal,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Your Research Library",
                style = Typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AccentTeal,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = AccentTeal,
                            height = 2.dp
                        )
                    }
                },
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = Typography.labelSmall,
                                color = if (selectedTab == index) AccentTeal else TextMuted,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SavedPapersTab(onPaperClick)
                1 -> GrantFeedTab()
                2 -> NexusBridgesTab()
                3 -> AlertsTab()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// SAVED PAPERS TAB (existing, cleaned up)
// ─────────────────────────────────────────────────────────────────

@Composable
fun SavedPapersTab(onPaperClick: (String) -> Unit) {
    val papers = listOf(
        "Non-Abelian Statistics in Moiré Superlattices" to "Quantum",
        "Neural Scaling Laws for Multi-Modal Generalization" to "AI Theory",
        "Room Temperature Superconductivity in Hydride Compounds" to "Materials"
    )
    if (papers.isEmpty()) {
        EmptyStateView(Icons.Default.Bookmark, "No saved papers yet", "Save papers from the search tab")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            items(papers) { (title, cat) ->
                Surface(
                    onClick = { onPaperClick("1") },
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = Typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = AccentTealLight) {
                            Text(
                                cat,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = AccentTeal,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// GRANTS TAB
// ─────────────────────────────────────────────────────────────────

data class GrantOpportunity(
    val title: String,
    val agency: String,
    val agencyColor: Color,
    val daysLeft: Int,
    val amount: String,
    val field: String,
    val matchScore: Int,    // %
    val url: String
)

@Composable
fun GrantFeedTab() {
    // TODO: replace with live grant API (SERB, DST, NIH, NSF scraper)
    val grants = remember {
        listOf(
            GrantOpportunity(
                "Core Research Grant (CRG) 2025–26",
                "SERB", AccentTeal, 23, "₹50–90 Lakh", "All STEM", 92,
                "https://www.serbonline.in/"
            ),
            GrantOpportunity(
                "National Science Foundation — CAREER Award",
                "NSF", AccentIndigo, 41, "\$500K–1M", "CS/Engineering", 78,
                "https://www.nsf.gov/funding/opportunities/career-faculty-early-career-development-program"
            ),
            GrantOpportunity(
                "DST INSPIRE Faculty Award",
                "DST", AccentEmerald, 58, "₹35 Lakh/yr", "Science & Tech", 85,
                "https://dst.gov.in/scientific-programmes/scientific-engineering-research/inspire"
            ),
            GrantOpportunity(
                "NIH R01 Research Project Grant",
                "NIH", AccentRose, 67, "\$250K–1.5M", "Biomedical", 61,
                "https://grants.nih.gov/grants/funding/r01.htm"
            ),
            GrantOpportunity(
                "Prime Minister's Research Fellows (PMRF)",
                "MoE", AccentAmber, 89, "₹80K/month + grants", "PhD Scholars", 88,
                "https://www.pmrf.in/"
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AccentAmberLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.EmojiEvents, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                    Text(
                        "${grants.size} grants matching your research profile",
                        color = AccentAmber,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        itemsIndexed(grants) { _, grant ->
            GrantCard(grant)
        }
    }
}

@Composable
fun GrantCard(grant: GrantOpportunity) {
    val urgencyColor = when {
        grant.daysLeft < 30 -> AccentRose
        grant.daysLeft < 60 -> AccentAmber
        else                -> AccentEmerald
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Top colored bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(grant.agencyColor)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                // Agency + deadline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = grant.agencyColor.copy(alpha = 0.1f)) {
                        Text(
                            grant.agency,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = grant.agencyColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = urgencyColor.copy(alpha = 0.1f)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer, null, tint = urgencyColor, modifier = Modifier.size(11.dp))
                            Text(
                                "${grant.daysLeft}d left",
                                color = urgencyColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(grant.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AttachMoney, null, tint = AccentEmerald, modifier = Modifier.size(13.dp))
                            Text(grant.amount, color = AccentEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(grant.field, color = TextMuted, fontSize = 11.sp)
                    }
                    // Match score ring
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { grant.matchScore / 100f },
                            modifier = Modifier.size(40.dp),
                            color = grant.agencyColor,
                            trackColor = BorderLight,
                            strokeWidth = 3.dp
                        )
                        Text(
                            "${grant.matchScore}%",
                            color = grant.agencyColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// NEXUS BRIDGES TAB (existing, cleaned up)
// ─────────────────────────────────────────────────────────────────

@Composable
fun NexusBridgesTab() {
    val bridges = listOf("Quantum ↔ AI", "Bio ↔ Materials", "Neuro ↔ Logic")
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        items(bridges) { bridge ->
            ScientificCard(glowColor = AccentViolet.copy(alpha = 0.05f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(text = bridge, style = Typography.titleLarge, color = TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Text("Discovered semantic connection with high potential.", style = Typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// ALERTS TAB
// ─────────────────────────────────────────────────────────────────

@Composable
fun AlertsTab() {
    EmptyStateView(Icons.Default.Notifications, "No alerts yet", "We'll notify you when papers in your field get cited heavily")
}

// ─────────────────────────────────────────────────────────────────
// EMPTY STATE
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmptyStateView(icon: ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = BgElevated) {
                Icon(icon, null, modifier = Modifier.size(56.dp).padding(14.dp), tint = TextMuted)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = Typography.titleMedium, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Typography.bodySmall, color = TextMuted)
        }
    }
}
