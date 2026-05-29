package com.open.skolab.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.R
import com.open.skolab.auth.AuthManager
import com.open.skolab.di.AppDependencies
import com.open.skolab.ui.components.BrandMark
import com.open.skolab.ui.components.BrandTagline
import com.open.skolab.ui.components.primitives.SkoLabPrimaryButton
import com.open.skolab.ui.components.primitives.GoogleSignInButton
import com.open.skolab.ui.layout.screenHorizontalPadding
import com.open.skolab.ui.layout.screenSafeArea
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.launch
import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onAuthSuccess: () -> Unit) {
    var isSignIn by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // GoogleSignIn.getClient() requires Activity context — cast once here, locally.
    val activity = context as? ComponentActivity ?: context as Activity
    // Use application-scoped singleton — AppDependencies.authManager is initialized
    // with applicationContext in SkoLabApplication, safe to use here.
    val authManager = AppDependencies.authManager
    var authError by remember { mutableStateOf<String?>(null) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    scope.launch {
                        isGoogleLoading = true
                        val signInResult = authManager.signInWithGoogle(idToken)
                        isGoogleLoading = false
                        if (signInResult.isSuccess) {
                            onAuthSuccess()
                        } else {
                            // Fallback to guest bypass on sign-in error
                            isGoogleLoading = true
                            val ex = signInResult.exceptionOrNull()
                            val exception = if (ex is Exception) ex else Exception(ex?.message ?: "Sign-in failed", ex)
                            authManager.attemptAnonymousFallback(exception)
                            isGoogleLoading = false
                            onAuthSuccess()
                        }
                    }
                } else {
                    scope.launch {
                        isGoogleLoading = true
                        authManager.attemptAnonymousFallback(Exception("Google Sign-In failed: ID Token is null"))
                        isGoogleLoading = false
                        onAuthSuccess()
                    }
                }
            } catch (e: ApiException) {
                Log.e("AuthScreen", "Google SDK Sign-In failed, falling back to guest bypass", e)
                scope.launch {
                    isGoogleLoading = true
                    authManager.attemptAnonymousFallback(e)
                    isGoogleLoading = false
                    onAuthSuccess()
                }
            }
        } else {
            Log.w("AuthScreen", "Google Sign-In cancelled or failed, falling back to guest bypass")
            scope.launch {
                isGoogleLoading = true
                authManager.attemptAnonymousFallback(Exception("Google Sign-In chooser cancelled or failed"))
                isGoogleLoading = false
                onAuthSuccess()
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "piGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "glow"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenSafeArea()
                .screenHorizontalPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            BrandMark(
                style = Typography.displayLarge,
                accentColor = SkoLabDisruption.copy(alpha = glowAlpha)
            )
            BrandTagline(modifier = Modifier.padding(top = 8.dp))

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = androidx.compose.ui.platform.LocalContext.current
                    .getString(R.string.brand_auth_subtitle),
                style = Typography.bodyLarge,
                color = SkoLabTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(36.dp))

            GoogleSignInButton(
                onClick = {
                    authError = null
                    val webClientId = authManager.getWebClientId()
                    if (webClientId != null) {
                        isGoogleLoading = true
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(webClientId)
                            .requestEmail()
                            .build()
                        val googleSignInClient = GoogleSignIn.getClient(activity, gso)
                        googleSignInClient.signOut().addOnCompleteListener {
                            googleSignInLauncher.launch(googleSignInClient.signInIntent)
                        }
                    } else {
                        scope.launch {
                            isGoogleLoading = true
                            val result = authManager.initiateGoogleSignIn()
                            isGoogleLoading = false
                            if (result.isSuccess) {
                                onAuthSuccess()
                            } else {
                                authError = result.exceptionOrNull()?.message ?: "Sign-in failed"
                            }
                        }
                    }
                },
                isLoading = isGoogleLoading
            )

            authError?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = Typography.bodySmall,
                    color = SkoLabWarning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("— or use email —", style = Typography.labelMedium, color = SkoLabTextSecondary)
            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                AuthTabItem("Sign In", isSignIn, Modifier.weight(1f)) { isSignIn = true }
                AuthTabItem("Create Account", !isSignIn, Modifier.weight(1f)) { isSignIn = false }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isSignIn) {
                SignInForm(authManager = authManager, onAuthSuccess = onAuthSuccess)
            } else {
                RegisterForm(authManager = authManager, onAuthSuccess = onAuthSuccess)
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AuthTabItem(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val indicatorWidth by animateDpAsState(if (isSelected) 40.dp else 0.dp, label = "indicator")
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = Typography.titleMedium,
            color = if (isSelected) SkoLabTextPrimary else SkoLabTextSecondary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(indicatorWidth)
                .background(SkoLabDisruption)
        )
    }
}

@Composable
fun SignInForm(authManager: AuthManager, onAuthSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        SkoLabTextField(value = email, onValueChange = { email = it }, label = "Email")
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            isPassword = true,
            showPassword = showPassword,
            onToggleVisibility = { showPassword = !showPassword }
        )
        
        localError?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = Typography.bodySmall,
                color = SkoLabWarning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                val cleanEmail = email.trim()
                val cleanPassword = password.trim()
                if (cleanEmail.isEmpty() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                    localError = "Please enter a valid email address."
                    return@Button
                }
                if (cleanPassword.isEmpty() || cleanPassword.length < 6) {
                    localError = "Password must be at least 6 characters."
                    return@Button
                }
                
                localError = null
                isLoading = true
                scope.launch {
                    val result = authManager.signInWithEmail(cleanEmail, cleanPassword)
                    isLoading = false
                    if (result.isSuccess) {
                        onAuthSuccess()
                    } else {
                        localError = result.exceptionOrNull()?.localizedMessage ?: "Invalid credentials. Please try again."
                    }
                }
            },
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text("Sign In", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterForm(authManager: AuthManager, onAuthSuccess: () -> Unit) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedDomain by remember { mutableStateOf("Select Domain") }
    var showDomainSheet by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val domains = listOf("Physics", "Biology", "Computer Science", "AI", "Genetics", "Neuroscience", "Economics")

    Column(modifier = Modifier.fillMaxWidth()) {
        SkoLabTextField(value = fullName, onValueChange = { fullName = it }, label = "Full Name")
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(value = email, onValueChange = { email = it }, label = "Email")
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(value = password, onValueChange = { password = it }, label = "Password", isPassword = true)
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Research Domain", style = Typography.labelMedium, color = SkoLabTextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { showDomainSheet = true },
            color = SkoLabSurface,
            shape = SkoLabShapes.md,
            border = androidx.compose.foundation.BorderStroke(1.dp, SkoLabDivider)
        ) {
            Text(text = selectedDomain, modifier = Modifier.padding(16.dp), color = SkoLabTextPrimary)
        }
        
        localError?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = Typography.bodySmall,
                color = SkoLabWarning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                val cleanName = fullName.trim()
                val cleanEmail = email.trim()
                val cleanPassword = password.trim()
                if (cleanName.isEmpty()) {
                    localError = "Please enter your full name."
                    return@Button
                }
                if (cleanEmail.isEmpty() || !cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                    localError = "Please enter a valid email address."
                    return@Button
                }
                if (cleanPassword.isEmpty() || cleanPassword.length < 6) {
                    localError = "Password must be at least 6 characters."
                    return@Button
                }
                if (selectedDomain == "Select Domain") {
                    localError = "Please select a research domain."
                    return@Button
                }
                
                localError = null
                isLoading = true
                scope.launch {
                    val result = authManager.registerWithEmail(cleanEmail, cleanPassword, cleanName, selectedDomain)
                    isLoading = false
                    if (result.isSuccess) {
                        onAuthSuccess()
                    } else {
                        localError = result.exceptionOrNull()?.localizedMessage ?: "Failed to create account. Please try again."
                    }
                }
            },
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PRIMARY),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text("Create Account", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }

    if (showDomainSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDomainSheet = false },
            containerColor = SkoLabSurfaceElevated
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Research Domain", style = Typography.titleLarge, color = SkoLabTextPrimary)
                Spacer(modifier = Modifier.height(16.dp))
                domains.forEach { domain ->
                    Text(
                        text = domain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedDomain = domain
                                showDomainSheet = false
                            }
                            .padding(vertical = 12.dp),
                        color = SkoLabTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun SkoLabTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleVisibility: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = Typography.labelMedium) },
        modifier = Modifier.fillMaxWidth(),
        shape = SkoLabShapes.md,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SkoLabSurface,
            unfocusedContainerColor = SkoLabSurface,
            focusedBorderColor = SkoLabDisruption,
            unfocusedBorderColor = SkoLabDivider,
            focusedTextColor = SkoLabTextPrimary,
            unfocusedTextColor = SkoLabTextPrimary
        ),
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = {
            if (isPassword) {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = SkoLabTextSecondary
                    )
                }
            }
        }
    )
}
