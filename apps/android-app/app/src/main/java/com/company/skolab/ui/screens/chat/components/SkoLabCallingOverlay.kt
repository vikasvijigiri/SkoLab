package com.company.skolab.ui.screens.chat.components

import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*

import androidx.compose.ui.platform.LocalContext
import com.company.skolab.data.UserPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import com.company.skolab.network.ChatMessage

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.*
import com.company.skolab.ui.components.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.company.skolab.model.*
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SkoLabCallingOverlay(
    peerName: String,
    mode: String,
    callState: String,
    callConnectedTime: Int,
    micMuted: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    color: Color,
    onMuteToggle: () -> Unit,
    onCameraToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit,
    onJoinRealJitsi: () -> Unit
) {
    val initials = peerName.split(" ").map { it.take(1) }.joinToString("").uppercase()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        color = DarkSheetBg.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Secure Indicator
            Surface(
                color = WhatsAppCallBg,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure",
                        tint = BrandWhatsApp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "End-to-End Encrypted SkoLab Call",
                        color = BrandWhatsApp,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Peer info & Ringing / Calling state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Pulsating Avatar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(180.dp)
                ) {
                    if (callState == "calling" || callState == "ringing") {
                        Box(
                            modifier = Modifier
                                .size((120 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.15f))
                        )
                        Box(
                            modifier = Modifier
                                .size((140 * pulseScale).dp)
                                .clip(CircleShape)
                                .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.25f))
                            .border(2.dp, color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = when (callState) {
                        "calling" -> "SkoLab secure calling..."
                        "ringing" -> "Ringing..."
                        "connected" -> "Connected"
                        else -> "Disconnected"
                    },
                    color = if (callState == "connected") BrandWhatsApp else Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (callState == "connected") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = String.format("%02d:%02d", callConnectedTime / 60, callConnectedTime % 60),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Real Voice/Video Jitsi transition trigger
            if (callState == "connected") {
                Button(
                    onClick = onJoinRealJitsi,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Real Voice/Video Room",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Controls Panel
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Mic Button
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (micMuted) CallEndRed else BrandWhatsAppInputBg)
                ) {
                    Icon(
                        imageVector = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                // Video/Camera Button
                if (mode == "video") {
                    IconButton(
                        onClick = onCameraToggle,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (!cameraOn) CallEndRed else BrandWhatsAppInputBg)
                    ) {
                        Icon(
                            imageVector = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Camera",
                            tint = Color.White
                        )
                    }
                }

                // Speaker Button
                IconButton(
                    onClick = onSpeakerToggle,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (speakerOn) AccentTeal else BrandWhatsAppInputBg)
                ) {
                    Icon(
                        imageVector = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = "Speaker",
                        tint = Color.White
                    )
                }

                // Red Hang-up button
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(CallEndRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Hang Up",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}


