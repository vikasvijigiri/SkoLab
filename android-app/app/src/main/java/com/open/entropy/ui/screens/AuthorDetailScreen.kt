package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.model.MockData
import com.open.entropy.ui.components.CareerArcChart
import com.open.entropy.ui.components.PaperCard
import com.open.entropy.ui.components.RadarChart
import com.open.entropy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorDetailScreen(
    authorName: String,
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit
) {
    val author = MockData.authors.find { it.name == authorName } ?: return

    Scaffold(
        containerColor = BgPrimary,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = ResQitTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            item {
                AuthorProfileHeader(author)
            }

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "RESEARCH FINGERPRINT",
                        style = Typography.labelSmall,
                        color = ResQitTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    RadarChart(scores = author.radarScores, color = ResQitPrimary)
                    
                    // Legend-like labels for Radar
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadarLabel("Disruption", ResQitPrimary)
                        RadarLabel("Velocity", ResQitGold)
                        RadarLabel("Novelty", ResQitSecondary)
                    }
                }
            }

            item {
                Column {
                    Text(
                        text = "CAREER DISRUPTION ARC",
                        style = Typography.labelSmall,
                        color = ResQitTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val careerData = author.topPapers.map { it.year to it.disruptionScore }.sortedBy { it.first }
                    CareerArcChart(data = careerData)
                }
            }

            item {
                Text(
                    text = "TOP PUBLICATIONS",
                    style = Typography.labelSmall,
                    color = ResQitTextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            items(author.topPapers) { paper ->
                PaperCard(
                    paper = paper,
                    onClick = { onPaperClick(paper.id) },
                    compact = true
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun AuthorProfileHeader(author: com.open.entropy.model.Author) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(AccentTealLight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = author.name.split(" ").mapNotNull { it.firstOrNull() }.joinToString(""),
                style = Typography.displaySmall,
                color = AccentTeal
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = author.name,
            style = Typography.displaySmall,
            color = ResQitTextPrimary,
            fontSize = 20.sp
        )
        
        Text(
            text = author.institution,
            style = Typography.labelSmall,
            color = ResQitTextSecondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            color = if (author.fingerprintType == "Trailblazer") ResQitPrimary.copy(alpha = 0.1f) else ResQitGold.copy(alpha = 0.1f),
            shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, 
                if (author.fingerprintType == "Trailblazer") ResQitPrimary else ResQitGold
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (author.fingerprintType == "Trailblazer") Icons.Default.Bolt else Icons.Default.Verified,
                    contentDescription = null,
                    tint = if (author.fingerprintType == "Trailblazer") ResQitPrimary else ResQitGold,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = author.fingerprintType.uppercase(),
                    style = Typography.labelSmall,
                    color = if (author.fingerprintType == "Trailblazer") ResQitPrimary else ResQitGold,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RadarLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, style = Typography.labelSmall, color = ResQitTextSecondary, fontSize = 9.sp)
    }
}
