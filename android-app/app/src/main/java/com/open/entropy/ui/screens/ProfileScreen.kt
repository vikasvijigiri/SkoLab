package com.open.entropy.ui.screens

import android.util.Log
import android.content.Intent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseUser
import com.open.entropy.auth.AuthManager
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onNavigateToProWorkspace: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { AuthManager(context) }
    var currentUser by remember { mutableStateOf(authManager.currentUser) }
    var resQitUser by remember { mutableStateOf<com.open.entropy.model.SkoLabUser?>(null) }
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(currentUser) {
        currentUser?.let {
            resQitUser = authManager.getUserData(it.uid)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        if (currentUser == null) {
            LoginContent(
                onSignInClick = {
                    scope.launch {
                        val signInResult = authManager.initiateGoogleSignIn()
                        if (signInResult.isSuccess) {
                            currentUser = signInResult.getOrNull()
                        } else {
                            val exception = signInResult.exceptionOrNull()
                            Log.e("ProfileScreen", "Google Sign-In failed: ${exception?.message}", exception)
                            android.widget.Toast.makeText(
                                context,
                                "Sign-In Error: ${exception?.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                onBack = onBack
            )
        } else {
            ProfileContent(
                firebaseUser = currentUser!!,
                resQitUser = resQitUser,
                onNavigateToProWorkspace = onNavigateToProWorkspace,
                onSignOut = {
                    scope.launch {
                        authManager.signOut()
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    }
                    currentUser = null
                    resQitUser = null
                },
                onBack = onBack
            )
        }
    }
}

@Composable
fun LoginContent(onSignInClick: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                color = BgCard,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = AccentTeal,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Join the Frontier",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Text(
                text = "Sign in to track your research impact and mastery curriculum.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onSignInClick,
                colors = ButtonDefaults.buttonColors(containerColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Sign in with Google", color = TextOnAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ProfileContent(
    firebaseUser: FirebaseUser,
    resQitUser: com.open.entropy.model.SkoLabUser?,
    onNavigateToProWorkspace: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    val savedCount = resQitUser?.savedPapers?.size ?: 0
    val complexity = resQitUser?.complexityScore ?: 0f
    val mastery = (kotlin.math.ln(savedCount.toFloat() + 1f) * 20f + complexity * 0.3f).coerceIn(0f, 100f)
    val resQitScore = (mastery * 6.5f + complexity * 3.5f).toInt().coerceIn(100, 1000)

    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val apiService = remember { com.open.entropy.network.ApiService() }
    var aiProfile by remember { mutableStateOf<com.open.entropy.network.AuthorResponse?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    LaunchedEffect(firebaseUser.displayName) {
        val name = firebaseUser.displayName
        if (name != null) {
            isLoadingProfile = true
            try {
                val profile = apiService.searchAuthor(name)
                aiProfile = profile
                if (profile != null && profile.field_of_study != null) {
                    authManager.updateUserResearchFocus(profile.field_of_study)
                }
            } catch (e: Exception) {
                Log.e("ProfileScreen", "Failed to fetch AI profile", e)
            } finally {
                isLoadingProfile = false
            }
        }
    }

    var animatedProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(resQitScore) {
        animate(
            initialValue = 0f,
            targetValue = resQitScore / 1000f,
            animationSpec = tween(1500, easing = FastOutSlowInEasing)
        ) { value, _ ->
            animatedProgress = value
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "SETTINGS",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Profile Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.3f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = firebaseUser.displayName?.take(1) ?: "U",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = AccentTeal
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(20.dp))
                
                Column {
                    Text(
                        text = firebaseUser.displayName ?: "Researcher",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = firebaseUser.email ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        
            // Pro Workspace Card
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                onClick = onNavigateToProWorkspace,
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFF4B400))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SkoLab Pro & Labs Workspace",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0x33F4B400),
                                border = BorderStroke(0.5.dp, Color(0xFFF4B400))
                            ) {
                                Text(
                                    text = "PRO",
                                    color = Color(0xFFF4B400),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Manage subscription, scoop shield alerts, collaborative workspaces & job matching.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = "Access Pro",
                        tint = Color(0xFFF4B400),
                        modifier = Modifier.size(24.dp).padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "INTELLIGENCE PROFILE",
                style = MaterialTheme.typography.labelSmall,
                color = AccentTeal,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            SkoLabScoreCard(
                mastery = mastery,
                complexity = complexity,
                savedCount = savedCount,
                resQitScore = resQitScore,
                animatedProgress = animatedProgress
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            ExpertiseProfileCard(aiProfile = aiProfile, isLoading = isLoadingProfile)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            ShareProfileButton(
                displayName = firebaseUser.displayName ?: "Researcher",
                score = resQitScore,
                mastery = mastery,
                complexity = complexity,
                savedCount = savedCount
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.6f))
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Terminate Session", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SkoLabScoreRing(progress: Float, score: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            
            // Draw background track ring
            drawCircle(
                color = BorderLight,
                radius = radius,
                style = Stroke(width = strokeWidth)
            )
            
            // Draw animated progress arc
            drawArc(
                brush = Brush.sweepGradient(HeroGradient),
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                fontFamily = DisplayFontFamily
            )
            Text(
                text = "SkoLab Score",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun SkoLabScoreCard(
    mastery: Float,
    complexity: Float,
    savedCount: Int,
    resQitScore: Int,
    animatedProgress: Float
) {
    Surface(
        color = BgCard,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SkoLabScoreRing(progress = animatedProgress, score = (animatedProgress * 1000).toInt())
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                shape = RoundedCornerShape(50),
                color = AccentTealLight,
                border = BorderStroke(0.5.dp, AccentTeal.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "🏆 Top 12% in your field",
                    color = AccentTealDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = BorderLight)
            Spacer(modifier = Modifier.height(16.dp))
            
            val hIndex = maxOf(1, savedCount / 3)
            val subMetrics = listOf(
                Pair("Field Mastery", "${mastery.toInt()}%"),
                Pair("Complexity", "${complexity.toInt()}%"),
                Pair("Saved Papers", savedCount.toString()),
                Pair("h-index (est)", hIndex.toString())
            )
            
            val chunked = subMetrics.chunked(2)
            chunked.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { (label, value) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = BgSubtle,
                            border = BorderStroke(1.dp, BorderLight)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShareProfileButton(displayName: String, score: Int, mastery: Float, complexity: Float, savedCount: Int) {
    val context = LocalContext.current
    val hIndex = maxOf(1, savedCount / 3)
    
    Button(
        onClick = {
            val shareText = """
                SkoLab Scholar Profile: $displayName
                -----------------------------------
                SkoLab Score: $score/1000 (Top 12%)
                Field Mastery: ${mastery.toInt()}%
                Complexity Index: ${complexity.toInt()}%
                h-index (estimated): $hIndex
                Total Papers Saved: $savedCount
                
                Explore my research on SkoLab app!
            """.trimIndent()
            
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share Research Profile")
            context.startActivity(shareIntent)
        },
        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Icon(Icons.Default.Share, contentDescription = null, tint = TextOnAccent)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Share My Research Card", color = TextOnAccent, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpertiseProfileCard(aiProfile: com.open.entropy.network.AuthorResponse?, isLoading: Boolean) {
    Surface(
        color = BgCard,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI Extracted", tint = AccentViolet, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI EXTRACTED RESEARCH FOCUS", fontSize = 10.sp, color = AccentViolet, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                CircularProgressIndicator(color = AccentTeal, modifier = Modifier.size(24.dp))
                Text("Analyzing publications...", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
            } else if (aiProfile != null) {
                Text(
                    text = aiProfile.field_of_study ?: "Multi-disciplinary",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )

                if (aiProfile.expertise.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("AREAS OF INTEREST & SKILLS", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        aiProfile.expertise.forEach { skill ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AccentTeal.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
                            ) {
                                Text(
                                    text = skill,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = AccentTeal,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                if (aiProfile.academic_history.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("ACADEMIC BACKGROUND", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        aiProfile.academic_history.forEach { history ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", color = AccentViolet, modifier = Modifier.padding(end = 8.dp))
                                Text(history, color = TextPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "No published works found to extract profile.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
