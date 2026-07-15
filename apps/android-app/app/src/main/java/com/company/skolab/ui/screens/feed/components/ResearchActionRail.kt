package com.company.skolab.ui.screens.feed.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.ui.theme.*

private data class HomeAction(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit
)

@Composable
fun ResearchActionRail(
    onNavigateToAgent: () -> Unit,
    onNavigateToCollabs: () -> Unit,
    onNavigateToMetrics: () -> Unit,
    onNavigateToIndustry: () -> Unit,
    onNavigateToPapers: () -> Unit,
    onNavigateToDailyDiscovery: () -> Unit
) {
    val actions = listOf(
        HomeAction("Ask Agent", Icons.Default.AutoAwesome, AccentViolet, onNavigateToAgent),
        HomeAction("Team Pulse", Icons.Default.People, AccentTeal, onNavigateToCollabs),
        HomeAction("Metrics", Icons.Default.AutoGraph, AccentAmber, onNavigateToMetrics),
        HomeAction("Industry", Icons.Default.BusinessCenter, AccentEmerald, onNavigateToIndustry),
        HomeAction("Papers", Icons.AutoMirrored.Filled.Article, AccentRose, onNavigateToPapers),
        HomeAction("Discovery", Icons.Default.Search, AccentIndigo, onNavigateToDailyDiscovery)
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(actions) { action ->
            Surface(
                onClick = action.onClick,
                shape = RoundedCornerShape(16.dp),
                color = BgCard,
                border = BorderStroke(1.dp, action.tint.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(action.tint.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = action.tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = action.label,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
