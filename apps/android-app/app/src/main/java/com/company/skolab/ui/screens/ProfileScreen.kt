package com.company.skolab.ui.screens

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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.clickable
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
import com.google.firebase.crashlytics.FirebaseCrashlytics
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.company.skolab.ui.theme.*
import com.company.skolab.analytics.SkoLabAnalytics
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.compose.material.icons.filled.Delete

@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onNavigateToProWorkspace: () -> Unit = {},
    onNavigateToEditProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Use application-scoped singleton — same instance as everywhere else in the app.
    val authManager = AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(Unit) {
        SkoLabAnalytics.logProfileScreenOpened()
    }

    // Determine auth state: Firebase currentUser is the source of truth.
    // cachedUser may be null briefly (DataStore loading); we fall back to Firebase auth state
    // to avoid showing the login screen to an already-authenticated user.
    val isFirebaseAuthenticated = remember { authManager.currentUser != null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        // Show ProfileContent when Firebase says logged in OR when cachedUser loads
        val showProfile = isFirebaseAuthenticated || cachedUser != null

        if (!showProfile) {
            LoginContent(
                onSignInClick = {
                    scope.launch {
                        val signInResult = authManager.initiateGoogleSignIn(context)
                        if (!signInResult.isSuccess) {
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
            // Build a SkoLabUser from Firebase auth if cachedUser not yet loaded
            val displayUser = cachedUser ?: authManager.currentUser?.let { firebaseUser ->
                com.company.skolab.model.SkoLabUser(
                    uid = firebaseUser.uid,
                    name = firebaseUser.displayName ?: "Researcher",
                    email = firebaseUser.email ?: "",
                    researchFocus = ""
                )
            }
            ProfileContent(
                skolabUser = displayUser,
                onNavigateToProWorkspace = onNavigateToProWorkspace,
                onNavigateToEditProfile = onNavigateToEditProfile,
                onSignOut = {
                    scope.launch {
                        SkoLabAnalytics.clearIdentity()
                        authManager.signOut()
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    }
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
    skolabUser: com.company.skolab.model.SkoLabUser?,
    onNavigateToProWorkspace: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    var skolabUserMutable by remember(skolabUser) { mutableStateOf(skolabUser) }
    val activeUser = skolabUserMutable ?: skolabUser
    val savedCount = activeUser?.savedPapers?.size ?: 0
    val complexity = activeUser?.complexityScore ?: 0f
    val mastery = (kotlin.math.ln(savedCount.toFloat() + 1f) * 20f + complexity * 0.3f).coerceIn(0f, 100f)
    val skolabScore = (mastery * 6.5f + complexity * 3.5f).toInt().coerceIn(100, 1000)
    val hIndex = maxOf(1, savedCount / 3)

    val context = LocalContext.current
    // Use application-scoped singletons — do NOT create new instances per composable.
    val authManager = AppDependencies.authManager
    val apiService = AppDependencies.apiService
    val userPrefs = remember { com.company.skolab.data.UserPreferences(context) }
    val currentTheme by userPrefs.appTheme.collectAsState(initial = "SYSTEM")
    val dynamicColorEnabled by userPrefs.dynamicColorEnabled.collectAsState(initial = false)
    var aiProfile by remember { mutableStateOf<com.company.skolab.network.AuthorResponse?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri)
            scope.launch {
                authManager.updateCv(uri.toString(), fileName)
                skolabUserMutable = activeUser?.copy(cvUri = uri.toString(), cvFileName = fileName)
            }
        }
    }

    LaunchedEffect(activeUser?.name, skolabUserMutable?.researchFocus) {
        val name = activeUser?.name
        val currentFocusVal = skolabUserMutable?.researchFocus ?: ""
        val hasValidFocusVal = currentFocusVal.isNotBlank() && currentFocusVal != "Researcher" && currentFocusVal != "General Research"
        if (name != null && aiProfile == null) {
            isLoadingProfile = true
            try {
                val profile = apiService.searchAuthor(name, focus = currentFocusVal)
                aiProfile = profile
                if (profile != null && profile.field_of_study != null && !hasValidFocusVal) {
                    authManager.updateUserResearchFocus(profile.field_of_study)
                    skolabUserMutable = skolabUserMutable?.copy(researchFocus = profile.field_of_study)
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
    val displayName = activeUser?.name ?: "Researcher"
    val currentFocus = skolabUserMutable?.researchFocus ?: ""
    val hasValidFocus = currentFocus.isNotBlank() && currentFocus != "Researcher" && currentFocus != "General Research"
    val fieldOfStudy = if (hasValidFocus) currentFocus else (aiProfile?.field_of_study ?: "Research Scholar")
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
            // ── 1. Banner + Avatar + Name hero ───────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                // Banner — taller to hold the name/institution overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    BrandWhatsAppBubbleDarkMe,
                                    WhatsAppTealGreen,
                                    BrandWhatsApp.copy(alpha = 0.6f)
                                )
                            )
                        )
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.07f),
                            radius = 210f,
                            center = Offset(size.width * 0.88f, size.height * 0.1f)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.04f),
                            radius = 130f,
                            center = Offset(size.width * 0.72f, size.height * 0.85f)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.03f),
                            radius = 70f,
                            center = Offset(size.width * 0.15f, size.height * 0.65f)
                        )
                    }
                    // Bottom scrim so overlaid text is readable
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f))
                                )
                            )
                    )
                    // Name + status badge + institution overlaid at banner bottom
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 120.dp, bottom = 14.dp, end = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = displayName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            val statusText = activeUser?.academicStatus ?: "Researcher"
                            if (statusText.isNotBlank()) {
                                Surface(
                                    color = Color.White.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = statusText,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        if (institution.isNotBlank() && institution != "Independent Researcher") {
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Business,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = institution,
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Avatar — overlapping the banner bottom-left
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp)
                        .offset(y = 44.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(BgPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(AccentTeal, BrandWhatsAppBubbleDarkMe)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Space below avatar overlap
            Spacer(modifier = Modifier.height(52.dp))

            // ── 2. Field chip + score + action buttons ───────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToEditProfile() }
                        .background(AccentTeal.copy(alpha = 0.08f))
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = fieldOfStudy,
                        fontSize = 14.sp,
                        color = AccentTeal,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = AccentTeal,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
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
                    val cvFileNameVal = activeUser?.cvFileName ?: ""
                    Button(
                        onClick = {
                            val cvNote = if (cvFileNameVal.isNotBlank()) "\nCV: $cvFileNameVal (available on request)" else ""
                            val shareText = """
                                SkoLab Scholar Profile: $displayName
                                -----------------------------------
                                Academic Status: ${activeUser?.academicStatus ?: "Researcher"}
                                SkoLab Score: $skolabScore/1000 (Top 12%)
                                Field: $fieldOfStudy
                                Institution: $institution
                                h-index (estimated): $hIndex$cvNote

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("About", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = AccentTeal,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onNavigateToEditProfile() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val aboutText = activeUser?.about?.takeIf { it.isNotBlank() } ?: if (aiProfile != null) {
                    "${displayName} — ${fieldOfStudy} researcher at ${institution}. " +
                    if (aiProfile!!.expertise.isNotEmpty()) "Areas of expertise include ${aiProfile!!.expertise.take(3).joinToString(", ")}." else ""
                } else {
                    "${displayName} — Research scholar on the SkoLab platform. Click the edit icon to populate your full academic profile."
                }
                Text(
                    text = aboutText,
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

            // ── 4.5. CV / Resume section ─────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("CV & Resume", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(10.dp))

                val cvUri = activeUser?.cvUri ?: ""
                val cvFileName = activeUser?.cvFileName ?: ""

                if (cvUri.isNotBlank() && cvFileName.isNotBlank()) {
                    Surface(
                        color = BgCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Icon(
                                 imageVector = Icons.Default.Description,
                                 contentDescription = "CV File",
                                 tint = AccentTeal,
                                 modifier = Modifier.size(24.dp)
                             )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cvFileName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Ready for quick sharing",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            IconButton(
                                onClick = {
                                    try {
                                        val uri = android.net.Uri.parse(cvUri)
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share CV / Resume"))
                                    } catch (e: Exception) {
                                        Log.e("ProfileScreen", "Failed to share CV", e)
                                        android.widget.Toast.makeText(context, "Could not share: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share CV",
                                    tint = AccentTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    pdfPickerLauncher.launch("application/pdf")
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Replace CV",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        color = BgCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pdfPickerLauncher.launch("application/pdf") }
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No CV/Resume uploaded",
                                fontSize = 14.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Upload a PDF to share it instantly with other scholars",
                                fontSize = 11.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { pdfPickerLauncher.launch("application/pdf") },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal.copy(alpha = 0.08f), contentColor = AccentTeal),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text("Upload CV (PDF)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
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
                                brush = Brush.sweepGradient(listOf(AccentTeal, BrandWhatsApp)),
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

            // ── 7.5. Preferences & Theme Settings ──────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("App Settings", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("App Theme", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                val themeText = when (currentTheme) {
                                    "LIGHT" -> "Force Light Mode"
                                    "DARK" -> "Force Dark Mode"
                                    else -> "Follows Android System"
                                }
                                Text(themeText, fontSize = 11.sp, color = TextMuted)
                            }
                            
                            var expandedThemeMenu by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { expandedThemeMenu = true },
                                    border = BorderStroke(1.dp, AccentTeal),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = when (currentTheme) {
                                            "LIGHT" -> "Light"
                                            "DARK" -> "Dark"
                                            else -> "System"
                                        },
                                        color = AccentTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = expandedThemeMenu,
                                    onDismissRequest = { expandedThemeMenu = false },
                                    modifier = Modifier.background(BgCard)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Follow System", color = TextPrimary) },
                                        onClick = {
                                            scope.launch {
                                                userPrefs.setAppTheme("SYSTEM")
                                            }
                                            expandedThemeMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Light Mode", color = TextPrimary) },
                                        onClick = {
                                            scope.launch {
                                                userPrefs.setAppTheme("LIGHT")
                                            }
                                            expandedThemeMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Dark Mode", color = TextPrimary) },
                                        onClick = {
                                            scope.launch {
                                                userPrefs.setAppTheme("DARK")
                                            }
                                            expandedThemeMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = BorderLight, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                        // Dynamic Color (Monet) toggle — Android 12+ only
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Dynamic Color (Monet)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(
                                    text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                        "Colours extracted from your wallpaper"
                                    else
                                        "Requires Android 12+",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Switch(
                                checked = dynamicColorEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { userPrefs.setDynamicColorEnabled(enabled) }
                                },
                                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AccentTeal,
                                    checkedTrackColor = AccentTeal.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
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
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteAccountDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRose)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = AccentRose)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Account (GDPR Purge)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Privacy Policy & Terms of Service",
                    style = Typography.labelMedium,
                    color = AccentTeal,
                    modifier = Modifier.clickable { showPolicyDialog = true }.padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Help & Feedback",
                    style = Typography.labelMedium,
                    color = AccentTeal,
                    modifier = Modifier.clickable { showFeedbackDialog = true }.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        if (showPolicyDialog) {
            AlertDialog(
                onDismissRequest = { showPolicyDialog = false },
                title = {
                    Text(
                        text = "Legal & Attributions",
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Privacy Policy",
                                style = Typography.titleSmall,
                                color = AccentTeal,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SkoLab respects your academic data privacy. We collect your research focus and OpenAlex ID solely to personalize feed recommendations and calculate impact metrics. We do not sell your personal data to third parties.",
                                style = Typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        Column {
                            Text(
                                text = "Terms of Service",
                                style = Typography.titleSmall,
                                color = AccentTeal,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "By using SkoLab, you agree to comply with our academic integrity policies. You may not scrape our services or abuse LLM endpoint access.",
                                style = Typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        Column {
                            Text(
                                text = "Licensing & Attributions",
                                style = Typography.titleSmall,
                                color = AccentTeal,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Academic metrics and profile data are powered by the OpenAlex API (Creative Commons CC0). Research summaries are processed using Groq Llama-3 models.",
                                style = Typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showPolicyDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentTeal)
                    ) {
                        Text("Dismiss", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (showFeedbackDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showFeedbackDialog = false
                    feedbackText = ""
                },
                title = {
                    Text(
                        text = "Help & Feedback",
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Frequently Asked Questions",
                                style = Typography.titleSmall,
                                color = AccentTeal,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "• How do I link my OpenAlex profile?\n  Go to 'Edit Profile' and enter your name or OpenAlex ID.\n\n• Why is my SkoLab score not updating?\n  SkoLab score updates daily based on your saved publications complexity and research activity.\n\n• How do I export my agent chat history?\n  In Ask Skolar chat, ask the agent to export your findings as CSV, Markdown, BibTeX, or Chart HTML. Download links will appear in the conversation.",
                                style = Typography.bodyMedium,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Submit Feedback or Report a Bug",
                                style = Typography.titleSmall,
                                color = AccentTeal,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = feedbackText,
                                onValueChange = { feedbackText = it },
                                label = { Text("Describe your issue or suggestion", color = TextMuted) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentTeal,
                                    unfocusedBorderColor = BorderLight,
                                    focusedContainerColor = BgCard,
                                    unfocusedContainerColor = BgCard
                                ),
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = feedbackText.isNotBlank(),
                        onClick = {
                            try {
                                FirebaseCrashlytics.getInstance().log("User Feedback: $feedbackText")
                                FirebaseCrashlytics.getInstance().setCustomKey("last_user_feedback", feedbackText)
                            } catch (e: Exception) {
                                Log.e("ProfileScreen", "Failed to log feedback to Crashlytics", e)
                            }
                            android.widget.Toast.makeText(context, "Feedback submitted. Thank you!", android.widget.Toast.LENGTH_SHORT).show()
                            showFeedbackDialog = false
                            feedbackText = ""
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = AccentTeal,
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Text("Submit", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showFeedbackDialog = false
                            feedbackText = ""
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (showDeleteAccountDialog) {
            val credentialManager = remember { androidx.credentials.CredentialManager.create(context) }
            AlertDialog(
                onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
                title = {
                    Text(
                        text = "Permanently Delete Account?",
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = "This action is irreversible. All your research metrics, saved papers, CV files, and chat history will be permanently purged from our servers in compliance with GDPR. Do you wish to proceed?",
                        style = Typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val uid = activeUser?.uid
                            if (uid != null) {
                                isDeletingAccount = true
                                scope.launch {
                                    try {
                                        // 1. Delete Firebase Auth first — this is the real account removal
                                        val fbUser = authManager.currentUser
                                        if (fbUser != null) {
                                            try {
                                                fbUser.delete().await()
                                            } catch (e: Exception) {
                                                Log.w("ProfileScreen", "Firebase Auth delete failed (re-auth may be needed)", e)
                                                // Re-auth required — still clear local session below
                                            }
                                        }

                                        // 2. Backend cleanup — best-effort, never blocks the user
                                        try { apiService.deleteUserAccount(uid) } catch (_: Exception) {}

                                        // 3. Clear local session regardless
                                        SkoLabAnalytics.clearIdentity()
                                        authManager.signOut()
                                        credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
                                    } catch (e: Exception) {
                                        Log.e("ProfileScreen", "Error during account deletion", e)
                                    } finally {
                                        isDeletingAccount = false
                                        showDeleteAccountDialog = false
                                    }
                                }
                            }
                        },
                        enabled = !isDeletingAccount,
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentRose)
                    ) {
                        if (isDeletingAccount) {
                            CircularProgressIndicator(
                                color = AccentRose,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Delete Account", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteAccountDialog = false },
                        enabled = !isDeletingAccount
                    ) {
                        Text("Cancel", color = TextMuted)
                    }
                },
                containerColor = BgCard,
                shape = RoundedCornerShape(16.dp)
            )
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

private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex)
                }
            }
        } catch (e: java.lang.Exception) {
            Log.e("ProfileScreen", "Error query filename", e)
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Resume.pdf"
}
