package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open.entropy.ui.theme.EntropiColors
import com.open.entropy.viewmodel.IndustryViewModel
import com.open.entropy.viewmodel.OpportunityType
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.open.entropy.data.UserPreferences

@Composable
fun IndustryScreen(
    viewModel: IndustryViewModel = viewModel(),
    onNavigateToReader: (String, String) -> Unit = { _, _ -> }
) {
    val opportunities by viewModel.opportunities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)

    LaunchedEffect(cachedUser) {
        val focus = cachedUser?.researchFocus ?: "AI"
        viewModel.loadOpportunities(focus)
    }

    Scaffold(
        containerColor = EntropiColors.Background,
        topBar = {
            IndustryTopBar()
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    // Subtle glowing background effect
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF00A884).copy(alpha = 0.05f), Color.Transparent),
                            startY = 0f,
                            endY = 300f
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading && opportunities.isEmpty()) {
                CircularProgressIndicator(color = EntropiColors.Blue1)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(opportunities) { opp ->
                        IndustryOpportunityCard(opp)
                    }
                }
            }
        }
    }
}

@Composable
fun IndustryTopBar() {
    Surface(
        color = EntropiColors.Background.copy(alpha = 0.95f),
        border = BorderStroke(0.5.dp, EntropiColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BusinessCenter,
                contentDescription = null,
                tint = EntropiColors.Gold1,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Industry Connect",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Bridging Academia & Industry",
                    color = EntropiColors.Text2,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun IndustryOpportunityCard(opp: com.open.entropy.viewmodel.IndustryOpportunity) {
    val context = LocalContext.current
    val (icon, tintColor) = when (opp.type) {
        OpportunityType.JOB -> Icons.Filled.Work to Color(0xFF42A5F5)
        OpportunityType.FUNDING -> Icons.Filled.AttachMoney to Color(0xFF66BB6A)
        OpportunityType.REQUIREMENT -> Icons.Filled.TipsAndUpdates to Color(0xFFFFA726)
    }

    Surface(
        color = EntropiColors.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, EntropiColors.Border),
        modifier = Modifier.clickable {
            if (opp.url.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(opp.url))
                context.startActivity(intent)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tintColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = opp.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = opp.companyOrFunder,
                        color = EntropiColors.Gold1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = opp.postedAgo,
                    color = EntropiColors.Text2,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = opp.description,
                color = EntropiColors.Text2,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                opp.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(EntropiColors.Background)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tag,
                            color = tintColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
