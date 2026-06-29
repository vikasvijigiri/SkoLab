package com.company.skolab.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.company.skolab.R
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.auth.AuthManager
import com.company.skolab.di.AppDependencies
import com.company.skolab.ui.components.BrandMark
import com.company.skolab.ui.components.BrandTagline
import com.company.skolab.ui.components.primitives.SkoLabPrimaryButton
import com.company.skolab.ui.components.primitives.GoogleSignInButton
import com.company.skolab.ui.components.primitives.SkoLabTextField
import com.company.skolab.ui.layout.screenHorizontalPadding
import com.company.skolab.ui.layout.screenSafeArea
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.autofill.ContentType
import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onAuthSuccess: () -> Unit) {
    var isSignIn by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // Use application-scoped singleton — AppDependencies.authManager is initialized
    // with applicationContext in SkoLabApplication, safe to use here.
    val authManager = AppDependencies.authManager
    var authError by remember { mutableStateOf<String?>(null) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Analytics: log onboarding started when screen is launched
    LaunchedEffect(Unit) {
        SkoLabAnalytics.logOnboardingStarted()
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
                    scope.launch {
                        isGoogleLoading = true
                        val result = authManager.initiateGoogleSignIn(context)
                        isGoogleLoading = false
                        if (result.isSuccess) {
                            // Identify user in analytics so all events are attributed correctly
                            authManager.currentUser?.uid?.let { SkoLabAnalytics.identify(it) }
                            SkoLabAnalytics.logLoginSuccess("google")
                            onAuthSuccess()
                        } else {
                            authError = result.exceptionOrNull()?.message ?: "Sign-in failed"
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

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Privacy Policy & Terms of Service",
                style = Typography.labelMedium,
                color = SkoLabDisruption,
                modifier = Modifier.clickable { showPolicyDialog = true }.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))
        }

        if (showPolicyDialog) {
            PolicyDialog(onDismiss = { showPolicyDialog = false })
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
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val emailPattern = remember { Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$") }
    val isEmailValid = email.isNotEmpty() && emailPattern.matches(email.trim())
    val isPasswordValid = password.length >= 6 && password.any { it.isDigit() } && password.any { it.isLetter() }

    Column(modifier = Modifier.fillMaxWidth()) {
        SkoLabTextField(
            value = email,
            onValueChange = { 
                email = it
                emailError = null
                localError = null
            },
            label = "Email",
            errorText = emailError,
            isSuccess = isEmailValid,
            autofillType = ContentType.EmailAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(
            value = password,
            onValueChange = { 
                password = it
                passwordError = null
                localError = null
            },
            label = "Password",
            errorText = passwordError,
            isSuccess = isPasswordValid,
            visualTransformation = if (!showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = SkoLabTextSecondary
                    )
                }
            },
            autofillType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
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
                var hasError = false
                
                if (!isEmailValid) {
                    emailError = "Please enter a valid email address."
                    hasError = true
                }
                if (!isPasswordValid) {
                    passwordError = "Password must be at least 6 characters and contain both letters and numbers."
                    hasError = true
                }
                
                if (hasError) return@Button

                emailError = null
                passwordError = null
                localError = null
                isLoading = true
                scope.launch {
                    val result = authManager.signInWithEmail(cleanEmail, cleanPassword)
                    isLoading = false
                    if (result.isSuccess) {
                        authManager.currentUser?.uid?.let { SkoLabAnalytics.identify(it) }
                        SkoLabAnalytics.logLoginSuccess("email")
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
    var showPassword by remember { mutableStateOf(false) }
    var selectedDomain by remember { mutableStateOf("Select Domain") }
    var showDomainSheet by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var hasConsented by remember { mutableStateOf(false) }
    var isOver18 by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val domains = listOf("Physics", "Biology", "Computer Science", "AI", "Genetics", "Neuroscience", "Economics")

    val isNameValid = fullName.trim().isNotEmpty()
    val emailPattern = remember { Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$") }
    val isEmailValid = email.isNotEmpty() && emailPattern.matches(email.trim())
    val isPasswordValid = password.length >= 6 && password.any { it.isDigit() } && password.any { it.isLetter() }

    Column(modifier = Modifier.fillMaxWidth()) {
        SkoLabTextField(
            value = fullName,
            onValueChange = { 
                fullName = it
                nameError = null
                localError = null
            },
            label = "Full Name",
            errorText = nameError,
            isSuccess = isNameValid,
            autofillType = ContentType.PersonFullName,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(
            value = email,
            onValueChange = { 
                email = it
                emailError = null
                localError = null
            },
            label = "Email",
            errorText = emailError,
            isSuccess = isEmailValid,
            autofillType = ContentType.EmailAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkoLabTextField(
            value = password,
            onValueChange = { 
                password = it
                passwordError = null
                localError = null
            },
            label = "Password",
            errorText = passwordError,
            isSuccess = isPasswordValid,
            visualTransformation = if (!showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = SkoLabTextSecondary
                    )
                }
            },
            autofillType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )
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
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = hasConsented,
                onCheckedChange = { hasConsented = it },
                colors = CheckboxDefaults.colors(checkedColor = SkoLabDisruption)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I consent to academic data tracking",
                style = Typography.bodyMedium,
                color = SkoLabTextPrimary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isOver18,
                onCheckedChange = { isOver18 = it },
                colors = CheckboxDefaults.colors(checkedColor = SkoLabDisruption)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I confirm that I am 18 years of age or older",
                style = Typography.bodyMedium,
                color = SkoLabTextPrimary
            )
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
                var hasError = false
                
                if (cleanName.isEmpty()) {
                    nameError = "Please enter your full name."
                    hasError = true
                }
                if (!isEmailValid) {
                    emailError = "Please enter a valid email address."
                    hasError = true
                }
                if (!isPasswordValid) {
                    passwordError = "Password must be at least 6 characters and contain both letters and numbers."
                    hasError = true
                }
                if (selectedDomain == "Select Domain") {
                    localError = "Please select a research domain."
                    hasError = true
                }
                if (!hasConsented) {
                    localError = "You must consent to academic data tracking to register."
                    hasError = true
                }
                if (!isOver18) {
                    localError = "You must confirm you are 18 years of age or older."
                    hasError = true
                }
                
                if (hasError) return@Button
                
                nameError = null
                emailError = null
                passwordError = null
                localError = null
                isLoading = true
                scope.launch {
                    val result = authManager.registerWithEmail(cleanEmail, cleanPassword, cleanName, selectedDomain)
                    isLoading = false
                    if (result.isSuccess) {
                        authManager.currentUser?.uid?.let { SkoLabAnalytics.identify(it) }
                        SkoLabAnalytics.logLoginSuccess("email")
                        onAuthSuccess()
                    } else {
                        localError = result.exceptionOrNull()?.localizedMessage ?: "Failed to create account. Please try again."
                    }
                }
            },
            enabled = !isLoading && hasConsented && isOver18,
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

// SkoLabTextField is now imported from com.company.skolab.ui.components.primitives.SkoLabTextField

@Composable
fun PolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Legal & Attributions",
                style = Typography.titleLarge,
                color = SkoLabTextPrimary,
                fontWeight = FontWeight.Bold
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
                        color = SkoLabDisruption,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "SkoLab respects your academic data privacy. We collect your research focus and OpenAlex ID solely to personalize feed recommendations and calculate impact metrics. We do not sell your personal data to third parties.",
                        style = Typography.bodyMedium,
                        color = SkoLabTextSecondary
                    )
                }

                Column {
                    Text(
                        text = "Terms of Service",
                        style = Typography.titleSmall,
                        color = SkoLabDisruption,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By using SkoLab, you agree to comply with our academic integrity policies. You may not scrape our services or abuse LLM endpoint access.",
                        style = Typography.bodyMedium,
                        color = SkoLabTextSecondary
                    )
                }

                Column {
                    Text(
                        text = "Licensing & Attributions",
                        style = Typography.titleSmall,
                        color = SkoLabDisruption,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Academic metrics and profile data are powered by the OpenAlex API (Creative Commons CC0). Research summaries are processed using Groq Llama-3 models.",
                        style = Typography.bodyMedium,
                        color = SkoLabTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SkoLabDisruption)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SkoLabSurfaceElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

