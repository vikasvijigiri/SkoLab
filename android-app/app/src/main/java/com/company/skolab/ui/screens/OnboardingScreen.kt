package com.company.skolab.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.company.skolab.ui.components.ScoreArcMeter
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

@Composable
fun OnboardingScreen(onFinish: (Boolean) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(modifier = Modifier.fillMaxSize().background(SkoLabBg).systemBarsPadding()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ProblemPage()
                1 -> SolutionPage()
                2 -> ValuePage(onFinish)
            }
        }

        // Skip Button - Positioned with better padding and safe area awareness
        if (pagerState.currentPage < 2) {
            TextButton(
                onClick = { onFinish(false) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp) // Adjusted padding
            ) {
                Text(
                    text = androidx.compose.ui.platform.LocalContext.current.getString(com.company.skolab.R.string.onboarding_skip),
                    color = SkoLabTextSecondary,
                    style = Typography.labelMedium
                )
            }
        }

        // Dot Indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) SkoLabPrimary else SkoLabDivider)
                )
            }
        }
    }
}

@Composable
fun ProblemPage() {
    val particles = remember { List(40) { OnboardingParticle() } }
    val infiniteTransition = rememberInfiniteTransition(label = "onboarding")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "frame"
    )

    var countTarget by remember { mutableIntStateOf(0) }
    val animatedCount by animateIntAsState(
        targetValue = countTarget,
        animationSpec = tween(1500, easing = EaseOutQuart),
        label = "count"
    )

    LaunchedEffect(Unit) {
        delay(500)
        countTarget = 5000000
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background Gradient
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to OnboardingDarkPurple,
                    1f to SkoLabBg,
                    center = Offset.Zero
                )
            )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val drive = frame
            particles.forEach { p ->
                p.update(size.width, size.height)
                drawCircle(p.color, p.radius, Offset(p.x, p.y))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 64.dp), // Vertical padding to respect top/bottom UI elements
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format(Locale.getDefault(), "%,d", animatedCount),
                style = Typography.displayLarge,
                color = SkoLabTextPrimary,
                fontSize = 48.sp
            )
            Text(
                text = androidx.compose.ui.platform.LocalContext.current.getString(com.company.skolab.R.string.onboarding_papers_published),
                style = Typography.titleLarge,
                color = SkoLabTextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = androidx.compose.ui.platform.LocalContext.current
                    .getString(com.company.skolab.R.string.brand_onboarding_signal),
                style = Typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = SkoLabTextPrimary
            )
        }
    }
}

@Composable
fun SolutionPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 64.dp)
            .verticalScroll(rememberScrollState()), // Ensure content is accessible on short screens
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ScoreArcMeter(
            score = 0.82f,
            label = context.getString(com.company.skolab.R.string.onboarding_impact_score),
            color = SkoLabPrimary,
            modifier = Modifier.size(200.dp)
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = context.getString(com.company.skolab.R.string.onboarding_frontier_intelligence),
            style = Typography.displaySmall,
            color = SkoLabTextPrimary
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        FeatureRow(Icons.Default.Timeline, context.getString(com.company.skolab.R.string.onboarding_disruption_title), context.getString(com.company.skolab.R.string.onboarding_disruption_desc), 200)
        FeatureRow(Icons.Default.BubbleChart, context.getString(com.company.skolab.R.string.onboarding_novelty_title), context.getString(com.company.skolab.R.string.onboarding_novelty_desc), 400)
        FeatureRow(Icons.Default.Bolt, context.getString(com.company.skolab.R.string.onboarding_velocity_title), context.getString(com.company.skolab.R.string.onboarding_velocity_desc), 600)
    }
}

@Composable
fun FeatureRow(icon: ImageVector, title: String, body: String, delayMillis: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)) + slideInHorizontally(tween(500)) { -20 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = SkoLabSurfaceElevated,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = SkoLabSecondary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = Typography.labelLarge, color = SkoLabTextPrimary)
                Text(text = body, style = Typography.labelSmall, color = SkoLabTextSecondary)
            }
        }
    }
}

@Composable
fun ValuePage(onFinish: (Boolean) -> Unit) {
    var hasConsented by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 64.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        @Suppress("UNUSED_VARIABLE")
        // Glassmorphic Pulse Engine Visualizer (0% Placeholders)
        Box(
            modifier = Modifier
                .size(260.dp, 180.dp)
                .graphicsLayer { rotationZ = -5f }
                .alpha(0.85f)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            OnboardingAmberGlow,
                            OnboardingVioletGlow
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = SkoLabSecondary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = context.getString(com.company.skolab.R.string.onboarding_engine_ready),
                    color = SkoLabTextPrimary,
                    style = Typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = context.getString(com.company.skolab.R.string.onboarding_frontier_analytics),
                    color = SkoLabTextSecondary,
                    style = Typography.labelSmall
                )
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = context.getString(com.company.skolab.R.string.onboarding_see_fog),
            style = Typography.displayMedium,
            color = SkoLabTextPrimary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
 
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = hasConsented,
                onCheckedChange = { hasConsented = it },
                colors = CheckboxDefaults.colors(checkedColor = SkoLabDisruption)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = context.getString(com.company.skolab.R.string.onboarding_consent),
                    style = Typography.bodyMedium,
                    color = SkoLabTextPrimary
                )
                Text(
                    text = context.getString(com.company.skolab.R.string.onboarding_policy_terms),
                    style = Typography.labelSmall,
                    color = SkoLabDisruption,
                    modifier = Modifier.clickable { showPolicyDialog = true }
                )
            }
        }
        
        Button(
            onClick = { onFinish(hasConsented) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SkoLabPrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(context.getString(com.company.skolab.R.string.onboarding_get_started), style = Typography.titleLarge, color = Color.White)
        }
 
        if (showPolicyDialog) {
            OnboardingPolicyDialog(onDismiss = { showPolicyDialog = false })
        }
    }
}

@Composable
fun OnboardingPolicyDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(com.company.skolab.R.string.onboarding_legal_title),
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
                        text = context.getString(com.company.skolab.R.string.onboarding_privacy_title),
                        style = Typography.titleSmall,
                        color = SkoLabDisruption,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = context.getString(com.company.skolab.R.string.onboarding_privacy_body),
                        style = Typography.bodyMedium,
                        color = SkoLabTextSecondary
                    )
                }

                Column {
                    Text(
                        text = context.getString(com.company.skolab.R.string.onboarding_terms_title),
                        style = Typography.titleSmall,
                        color = SkoLabDisruption,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = context.getString(com.company.skolab.R.string.onboarding_terms_body),
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
                Text(context.getString(com.company.skolab.R.string.onboarding_dismiss), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SkoLabSurfaceElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

private class OnboardingParticle {
    var x = 0f
    var y = 0f
    var radius = 0f
    var color = Color.White
    private var vx = 0f
    private var vy = 0f
    private var initialized = false

    fun update(width: Float, height: Float) {
        if (!initialized && width > 0) {
            x = Random.nextFloat() * width
            y = Random.nextFloat() * height
            radius = Random.nextFloat() * 2f + 1f
            vx = (Random.nextFloat() - 0.5f) * 1f
            vy = (Random.nextFloat() - 0.5f) * 1f
            color = Color.White.copy(alpha = Random.nextFloat() * 0.5f + 0.2f)
            initialized = true
        }
        if (initialized) {
            x += vx
            y += vy
            if (x < 0 || x > width) vx *= -1
            if (y < 0 || y > height) vy *= -1
        }
    }
}
