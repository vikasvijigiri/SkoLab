package com.open.skolab.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.model.Conjecture
import com.open.skolab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class Sparkle(
    val x: Float,
    val y: Float,
    val color: Color,
    val vx: Float,
    val vy: Float,
    val size: Float
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ConjectureCard(
    conjecture: Conjecture,
    onConjectureSolved: (Int) -> Unit, // passes streak count
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var selectedIndex by remember(conjecture.id) { mutableStateOf<Int?>(null) }
    var isSolved by remember(conjecture.id) { mutableStateOf(false) }
    var attempts by remember(conjecture.id) { mutableIntStateOf(0) }
    val particles = remember { mutableStateListOf<Sparkle>() }

    // Shake animation offset
    val shakeOffset = remember { Animatable(0f) }

    // Trigger shake animation on incorrect answer
    fun triggerShake() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            repeat(4) {
                shakeOffset.animateTo(12f, spring(stiffness = 1500f))
                shakeOffset.animateTo(-12f, spring(stiffness = 1500f))
            }
            shakeOffset.animateTo(0f, spring(stiffness = 1000f))
        }
    }

    // Trigger particle explosion on correct answer
    fun triggerExplosion(centerX: Float, centerY: Float) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val palette = listOf(AccentTeal, AccentEmerald, AccentCyan, Color.White)
        val list = List(40) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = Random.nextFloat() * 18f + 5f
            Sparkle(
                x = centerX,
                y = centerY,
                color = palette.random(),
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 3f, // initial upward push
                size = Random.nextFloat() * 8f + 4f
            )
        }
        particles.addAll(list)
    }

    // Confetti particles update loop
    if (particles.isNotEmpty()) {
        LaunchedEffect(Unit) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 1200) {
                withFrameNanos {
                    val updated = particles.map { p ->
                        p.copy(
                            x = p.x + p.vx,
                            y = p.y + p.vy,
                            vy = p.vy + 0.35f, // gravity
                            vx = p.vx * 0.98f // air resistance
                        )
                    }
                    particles.clear()
                    particles.addAll(updated)
                }
            }
            particles.clear()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard.copy(alpha = 0.95f)),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    colors = if (isSolved) listOf(AccentTeal.copy(alpha = 0.4f), Color.Transparent)
                    else listOf(BorderLight, Color.Transparent)
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = shakeOffset.value.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Category & Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScientificBadge(text = conjecture.category, color = AccentTeal)
                    
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isSolved) AccentEmerald else AccentOrange)
                        )
                        Text(
                            text = if (isSolved) "PROVEN" else "UNSOLVED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = if (isSolved) AccentEmerald else AccentOrange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = conjecture.title.uppercase(),
                    style = Typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Hypothesis description with LaTeX support
                MarkdownText(
                    markdown = conjecture.hypothesis,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Choice Options
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    conjecture.options.forEachIndexed { index, option ->
                        val isSelected = selectedIndex == index
                        val isCorrectOption = index == conjecture.correctOptionIndex
                        
                        val optionBgColor = when {
                            isSolved && isCorrectOption -> AccentEmerald.copy(alpha = 0.08f)
                            isSolved && isSelected && !isCorrectOption -> AccentRose.copy(alpha = 0.08f)
                            isSelected && !isSolved -> AccentTeal.copy(alpha = 0.05f)
                            else -> BgElevated.copy(alpha = 0.5f)
                        }

                        val optionBorderColor = when {
                            isSolved && isCorrectOption -> AccentEmerald.copy(alpha = 0.4f)
                            isSolved && isSelected && !isCorrectOption -> AccentRose.copy(alpha = 0.4f)
                            isSelected && !isSolved -> AccentTeal.copy(alpha = 0.5f)
                            else -> BorderLight
                        }

                        Surface(
                            onClick = {
                                if (isSolved) return@Surface
                                selectedIndex = index
                                attempts += 1
                                if (index == conjecture.correctOptionIndex) {
                                    isSolved = true
                                    triggerExplosion(300f, 200f) // Spawns sparkles
                                    onConjectureSolved(attempts)
                                } else {
                                    triggerShake()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = optionBgColor,
                            border = BorderStroke(1.dp, optionBorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = ('A' + index).toString(),
                                    color = if (isSolved && isCorrectOption) AccentEmerald else if (isSelected) AccentTeal else TextMuted,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp
                                )

                                MarkdownText(
                                    markdown = option,
                                    color = if (isSolved && isCorrectOption) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isSolved && isCorrectOption) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Correct",
                                        tint = AccentEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else if (isSolved && isSelected && !isCorrectOption) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Incorrect",
                                        tint = AccentRose,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Proof decryption explanation (expanded on solve)
                AnimatedVisibility(
                    visible = isSolved,
                    enter = fadeIn(animationSpec = tween(400)) + expandVertically(animationSpec = tween(400)),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    ) {
                        Surface(
                            color = AccentTeal.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AccentTeal.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.WorkspacePremium,
                                        contentDescription = null,
                                        tint = AccentTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "PROOF DECRYPTION",
                                        color = AccentTeal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                MarkdownText(
                                    markdown = conjecture.explanation,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Share results button (Wordle style)
                        Button(
                            onClick = {
                                val emojiGrid = when (attempts) {
                                    1 -> "🟩 ⬜ ⬜ ⬜"
                                    2 -> "🟥 🟩 ⬜ ⬜"
                                    3 -> "🟥 🟥 🟩 ⬜"
                                    else -> "🟥 🟥 🟥 🟩"
                                }
                                val shareText = """
                                    SkoLab Conjecture Proven! 🧠⚡
                                    Conjecture: ${conjecture.title}
                                    Result: $emojiGrid (Attempts: $attempts)
                                    Solve the daily scientific duels on SkoLab!
                                """.trimIndent()

                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("SkoLab Conjecture", shareText)
                                clipboard.setPrimaryClip(clip)

                                Toast.makeText(context, "Results copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SHARE PROOF RESULTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextOnAccent
                            )
                        }
                    }
                }
            }
        }

        // Particle Canvas Overlay
        if (particles.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                particles.forEach { p ->
                    drawCircle(
                        color = p.color,
                        radius = p.size,
                        center = Offset(p.x, p.y)
                    )
                }
            }
        }
    }
}
