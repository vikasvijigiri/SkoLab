package com.open.skolab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.auth.AuthManager
import com.open.skolab.di.AppDependencies
import com.open.skolab.ui.components.StreakCard
import com.open.skolab.ui.components.ConjectureCard
import com.open.skolab.ui.theme.*
import kotlin.math.floor

@Composable
fun LogicEngineScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val authManager = AppDependencies.authManager
    
    val apiService = remember { com.open.skolab.network.ApiService() }
    val userPrefs = remember { com.open.skolab.data.UserPreferences(context) }
    val scope = rememberCoroutineScope()
    
    var conjecture by remember { mutableStateOf<com.open.skolab.model.Conjecture?>(null) }
    var isConjectureLoading by remember { mutableStateOf(true) }
    
    var localUserMastery by remember { mutableStateOf(42f) }
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    
    var conjectureError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cachedUser) {
        val uid = cachedUser?.uid ?: ""
        val name = cachedUser?.name ?: "SkoLab User"
        isConjectureLoading = true
        conjectureError = null
        try {
            conjecture = apiService.getDailyConjecture(uid, name)
        } catch (e: Exception) {
            conjectureError = e.message ?: "Failed to load daily conjecture."
            conjecture = null
        } finally {
            isConjectureLoading = false
        }
    }

    val totalDays = 30
    val daysCompleted = floor((localUserMastery / 100f) * totalDays).toInt()
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }

                    Column {
                        Text(
                            text = "30-DAY LOGIC ENGINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentTeal,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "Mastery Protocol",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp
                        )
                    }
                }
                
                Surface(
                    color = BgCard.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight.copy(alpha = 0.3f)),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = AccentTeal,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            // Minimalist Progress Bar
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "PHASE ${ (daysCompleted / 7) + 1 }",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "${localUserMastery.toInt()}% COMPLETE",
                        color = AccentTeal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(BgCard, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(localUserMastery / 100f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(listOf(AccentTeal, AccentIndigo)),
                                CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Timeline
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    StreakCard()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    if (isConjectureLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentTeal)
                        }
                    } else if (conjectureError != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3F1F25).copy(alpha = 0.85f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠️ SERVICE ERROR", color = Color(0xFFFF5252), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.5.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(conjectureError ?: "Conjecture Service Offline", color = TextPrimary, fontSize = 13.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isConjectureLoading = true
                                            conjectureError = null
                                            try {
                                                conjecture = apiService.getDailyConjecture(cachedUser?.uid ?: "", cachedUser?.name ?: "SkoLab User")
                                            } catch (e: Exception) {
                                                conjectureError = e.message ?: "Failed to load daily conjecture."
                                                conjecture = null
                                            } finally {
                                                isConjectureLoading = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Retry Connection", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        conjecture?.let { conj ->
                            ConjectureCard(
                                conjecture = conj,
                                onConjectureSolved = { attempts ->
                                    scope.launch {
                                        userPrefs.incrementStreakAndCheckIn()
                                        localUserMastery = minOf(100f, localUserMastery + 20f)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                val curriculum = List(30) { i -> 
                    when(i) {
                        0 -> "Foundations of Quantum Information"
                        7 -> "Advanced Topology in Matter"
                        14 -> "Neural Network Architectures"
                        21 -> "Experimental Design & Analysis"
                        29 -> "Final Synthesis & Publication"
                        else -> "Specialized Research Module ${i + 1}"
                    }
                }
                
                itemsIndexed(curriculum) { index, item ->
                    val isCurrent = index == daysCompleted
                    val isCompleted = index < daysCompleted
                    
                    if (index % 7 == 0) {
                        val weekNum = (index / 7) + 1
                        if (weekNum <= 4) {
                            ModernWeekHeader("WEEK $weekNum", if (isCompleted) AccentTeal else if (index / 7 == daysCompleted / 7) TextPrimary else TextMuted)
                        } else if (index == 28) {
                            ModernWeekHeader("FINAL STAGE", AccentRose)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        // Vertical Path
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .weight(1f)
                                    .background(
                                        if (isCompleted) AccentTeal.copy(alpha = 0.5f) else BorderLight.copy(alpha = 0.2f)
                                    )
                            )
                            
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (isCompleted) AccentTeal else if (isCurrent) BgPrimary else Color.Transparent,
                                        CircleShape
                                    )
                                    .border(
                                        if (isCurrent) 2.dp else 1.dp,
                                        if (isCurrent) AccentTeal else if (isCompleted) AccentTeal else BorderLight.copy(alpha = 0.5f),
                                        CircleShape
                                    )
                            )

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .weight(1f)
                                    .background(
                                        if (index < daysCompleted) AccentTeal.copy(alpha = 0.5f) else BorderLight.copy(alpha = 0.2f)
                                    )
                            )
                        }

                        // Module Content
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, bottom = 24.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DAY ${index + 1}",
                                    fontSize = 10.sp,
                                    color = if (isCurrent) AccentTeal else TextMuted,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                                if (isCurrent) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = AccentTeal,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "ACTIVE",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = BgPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCompleted) TextPrimary.copy(alpha = 0.5f) else TextPrimary,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            
                            if (isCurrent) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    color = BgCard.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🙋‍♂️", fontSize = 24.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                "YOU ARE HERE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black,
                                                color = AccentTeal
                                            )
                                            Text(
                                                "Keep pushing, Researcher.",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}

@Composable
fun ModernWeekHeader(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = color,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
    )
}

@Composable
fun WeekHeader(text: String, color: Color) {
    Row(
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color.copy(alpha = 0.1f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(end = 12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
    }
}

