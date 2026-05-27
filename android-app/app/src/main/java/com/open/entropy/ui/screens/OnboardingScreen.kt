package com.open.entropy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.open.entropy.ui.components.PaperCard
import com.open.entropy.model.MockData
import com.open.entropy.ui.components.ScoreArcMeter
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
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
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp) // Adjusted padding
            ) {
                Text("Skip", color = SkoLabTextSecondary, style = Typography.labelMedium)
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
                    0f to Color(0xFF1A1030),
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
                text = String.format(Locale.US, "%,d", animatedCount),
                style = Typography.displayLarge,
                color = SkoLabTextPrimary,
                fontSize = 48.sp
            )
            Text(
                text = "papers published every year",
                style = Typography.titleLarge,
                color = SkoLabTextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = androidx.compose.ui.platform.LocalContext.current
                    .getString(com.open.entropy.R.string.brand_onboarding_signal),
                style = Typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = SkoLabTextPrimary
            )
        }
    }
}

@Composable
fun SolutionPage() {
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
            label = "IMPACT SCORE",
            color = SkoLabPrimary,
            modifier = Modifier.size(200.dp)
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Frontier Intelligence",
            style = Typography.displaySmall,
            color = SkoLabTextPrimary
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        FeatureRow(Icons.Default.Timeline, "Disruption Score", "Measures paradigm shift", 200)
        FeatureRow(Icons.Default.BubbleChart, "Semantic Novelty", "Measures conceptual distance", 400)
        FeatureRow(Icons.Default.Bolt, "Citation Velocity", "Measures acceleration", 600)
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
fun ValuePage(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 64.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Paper Card Preview (Mock)
        PaperCard(
            paper = MockData.papers[0],
            onClick = {},
            modifier = Modifier.alpha(0.6f).graphicsLayer { rotationZ = -5f }
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "See through the fog.",
            style = Typography.displayMedium,
            color = SkoLabTextPrimary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SkoLabPrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Get Started", style = Typography.titleLarge, color = Color.White)
        }
    }
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
