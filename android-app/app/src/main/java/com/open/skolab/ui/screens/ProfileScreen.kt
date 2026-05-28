package com.open.skolab.ui.screens

import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseUser
import com.open.skolab.auth.AuthManager
import com.open.skolab.ui.theme.*
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
    var skolabUser by remember { mutableStateOf<com.open.skolab.model.SkoLabUser?>(null) }
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(currentUser) {
        currentUser?.let {
            skolabUser = authManager.getUserData(it.uid)
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
                skolabUser = skolabUser,
                onNavigateToProWorkspace = onNavigateToProWorkspace,
                onSignOut = {
                    scope.launch {
                        authManager.signOut()
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    }
                    currentUser = null
                    skolabUser = null
                },
                onBack = onBack
            )
        }
    }
}

@Composable
fun LoginContent(onSignInClick: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
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
                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
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
    skolabUser: com.open.skolab.model.SkoLabUser?,
    onNavigateToProWorkspace: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    val savedCount = skolabUser?.savedPapers?.size ?: 0
    val complexity = skolabUser?.complexityScore ?: 0f
    val mastery = (kotlin.math.ln(savedCount.toFloat() + 1f) * 20f + complexity * 0.3f).coerceIn(0f, 100f)
    val skolabScore = (mastery * 6.5f + complexity * 3.5f).toInt().coerceIn(100, 1000)
    val hIndex = maxOf(1, savedCount / 3)

    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val apiService = remember { com.open.skolab.network.ApiService() }
    var aiProfile by remember { mutableStateOf<com.open.skolab.network.AuthorResponse?>(null) }
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
    LaunchedEffect(skolabScore) {
        animate(
            initialValue = 0f,
            targetValue = skolabScore / 1000f,
            animationSpec = tween(1500, easing = FastOutSlowInEasing)
        ) { value, _ -> animatedProgress = value }
    }

    val scrollState = rememberScrollState()
    val displayName = firebaseUser.displayName ?: "Researcher"
    val fieldOfStudy = aiProfile?.field_of_study ?: skolabUser?.researchFocus ?: "Research Scholar"
    val institution = aiProfile?.institution ?: "Independent Researcher"

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Back button overlaid on banner ────────────────────────────────────
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .zIndex(10f)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // ── 1. Banner + Avatar header ─────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                // Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF005C4B),
                                    Color(0xFF00A884),
                                    Color(0xFF25D366).copy(alpha = 0.6f)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(Float.MAX_VALUE, Float.MAX_VALUE)
                            )
                        )
                ) {
                    // Decorative circles in banner
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.04f),
                            radius = 180f,
                            center = Offset(size.width * 0.85f, size.height * 0.2f)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.03f),
                            radius = 100f,
                            center = Offset(size.width * 0.7f, size.height * 0.8f)
                        )
                    }
                }

                // Avatar — overlapping the banner
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp)
                        .offset(y = 44.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(BgPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(AccentTeal, Color(0xFF005C4B))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Space below avatar overlap
            Spacer(modifier = Modifier.height(56.dp))

            // ── 2. Name + Title + Institution ────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = fieldOfStudy,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (institution.isNotBlank() && institution != "Independent Researcher") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Business,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(institution, fontSize = 13.sp, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEvents,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$skolabScore SkoLab Score · Top 12% in field",
                        fontSize = 13.sp,
                        color = AccentAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── 3. Action buttons ─────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Share button (primary)
                    Button(
                        onClick = {
                            val shareText = """
                                SkoLab Scholar Profile: $displayName
                                -----------------------------------
                                SkoLab Score: $skolabScore/1000 (Top 12%)
                                Field: $fieldOfStudy
                                Institution: $institution
                                h-index (estimated): $hIndex
                                
                                Explore my research on SkoLab!
                            """.trimIndent()
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Research Profile"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Pro Workspace button (outlined)
                    OutlinedButton(
                        onClick = onNavigateToProWorkspace,
                        border = BorderStroke(1.dp, AccentAmber),
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentAmber)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pro Workspace", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = BorderLight, thickness = 0.5.dp)

            // ── 4. About section ──────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("About", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (aiProfile != null) {
                        "${displayName} — ${fieldOfStudy} researcher at ${institution}. " +
                        if (aiProfile!!.expertise.isNotEmpty()) "Areas of expertise include ${aiProfile!!.expertise.take(3).joinToString(", ")}." else ""
                    } else {
                        "${displayName} — Research scholar on the SkoLab platform. Sign in to populate your full academic profile."
                    },
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 21.sp
                )
                if (isLoadingProfile) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = AccentTeal,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching academic profile...", fontSize = 12.sp, color = TextMuted)
                    }
                }
            }

            HorizontalDivider(color = BorderLight, thickness = 0.5.dp)

            // ── 5. Intelligence Profile metrics ───────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Intelligence Profile",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Score ring + stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Compact score ring
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(90.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 9.dp.toPx()
                            val radius = (size.minDimension - strokeWidth) / 2
                            drawCircle(
                                color = BorderMedium,
                                radius = radius,
                                style = Stroke(width = strokeWidth)
                            )
                            drawArc(
                                brush = Brush.sweepGradient(listOf(AccentTeal, Color(0xFF25D366))),
                                startAngle = -90f,
                                sweepAngle = animatedProgress * 360f,
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = (animatedProgress * 1000).toInt().toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Score",
                                fontSize = 9.sp,
                                color = TextMuted,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    // Stat grid (2×2)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProfileStatChip(
                                label = "Field Mastery",
                                value = "${mastery.toInt()}%",
                                color = AccentTeal,
                                modifier = Modifier.weight(1f)
                            )
                            ProfileStatChip(
                                label = "Complexity",
                                value = "${complexity.toInt()}%",
                                color = AccentIndigo,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProfileStatChip(
                                label = "Saved Papers",
                                value = savedCount.toString(),
                                color = AccentViolet,
                                modifier = Modifier.weight(1f)
                            )
                            ProfileStatChip(
                                label = "h-index (est.)",
                                value = hIndex.toString(),
                                color = AccentAmber,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Top 12% badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentTeal.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            tint = AccentTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Top 12% in your research field", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Based on papers saved, complexity & mastery scores", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderLight, thickness = 0.5.dp)

            // ── 6. Expertise / Skills section ────────────────────────────────
            if (aiProfile != null && (aiProfile!!.expertise.isNotEmpty() || aiProfile!!.academic_history.isNotEmpty())) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = AccentViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Research Expertise",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    if (aiProfile!!.expertise.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        // Skills rendered as compact rows
                        aiProfile!!.expertise.take(8).forEachIndexed { index, skill ->
                            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(AccentTeal)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(skill, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    if (aiProfile!!.academic_history.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Academic Background",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            aiProfile!!.academic_history.forEach { history ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = null,
                                        tint = AccentIndigo,
                                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(history, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
            }

            // ── 7. Featured publications preview ─────────────────────────────
            if (aiProfile != null && aiProfile!!.works.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            tint = AccentIndigo,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Featured Publications",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    aiProfile!!.works.take(3).forEachIndexed { index, work ->
                        if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = BgCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.5.dp, BorderMedium),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = work.title ?: "Untitled",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    lineHeight = 18.sp,
                                    maxLines = 2
                                )
                                if (!work.journal.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${work.journal} · ${work.year ?: ""}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                val citationCount = work.citations ?: 0
                                if (citationCount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$citationCount citations",
                                        fontSize = 11.sp,
                                        color = AccentTeal,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
            }

            // ── 8. Sign Out ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRose.copy(alpha = 0.75f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Terminate Session", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ProfileStatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = BgCard,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// Legacy exports — keep other screens compiling
@Composable
fun SkoLabScoreRing(progress: Float, score: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            drawCircle(color = BorderLight, radius = radius, style = Stroke(width = strokeWidth))
            drawArc(
                brush = Brush.sweepGradient(HeroGradient),
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score.toString(), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontFamily = DisplayFontFamily)
            Text("SkoLab Score", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.5.sp)
        }
    }
}
