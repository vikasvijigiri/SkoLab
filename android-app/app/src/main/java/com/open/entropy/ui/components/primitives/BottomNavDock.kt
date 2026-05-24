package com.open.entropy.ui.components.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import com.open.entropy.ui.theme.ResQitDisruption
import com.open.entropy.ui.theme.ResQitMotion
import com.open.entropy.ui.theme.ResQitShapes
import com.open.entropy.ui.theme.ResQitTextSecondary
import com.open.entropy.ui.theme.GlassBorder
import com.open.entropy.ui.theme.GlassSurface
import com.open.entropy.ui.theme.LocalResQitSpacing

data class DockItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0,
    val hasBadgeDot: Boolean = false
)

@Composable
fun BottomNavDock(
    items: List<DockItem>,
    currentRoute: String?,
    onItemClick: (DockItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalResQitSpacing.current
    val view = LocalView.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF0B141A) // WhatsApp Dark Background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(80.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val tint by animateColorAsState(
                    targetValue = if (selected) Color(0xFFC4FCEF) else Color(0xFFF1F1F2),
                    animationSpec = tween(ResQitMotion.fast),
                    label = "navTint"
                )
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 64.dp else 0.dp,
                    animationSpec = tween(ResQitMotion.normal),
                    label = "pillW"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onItemClick(item)
                        }
                        .semantics { contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(pillWidth, 32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF0F3D30)) // WhatsApp Dark Green Pill
                            )
                        }
                        Box {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).padding(2.dp),
                                tint = tint
                            )
                            if (item.badgeCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(16.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFF25D366)), // WhatsApp Bright Green
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = item.badgeCount.toString(),
                                        color = Color.White, // Using white text as requested
                                        fontSize = 9.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            } else if (item.hasBadgeDot) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(10.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFF25D366))
                                )
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        color = tint,
                        maxLines = 1,
                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
        }
    }
}
