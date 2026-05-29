package com.open.skolab.ui.components.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import com.open.skolab.ui.theme.SkoLabDisruption
import com.open.skolab.ui.theme.SkoLabMotion
import com.open.skolab.ui.theme.SkoLabShapes
import com.open.skolab.ui.theme.SkoLabTextSecondary
import com.open.skolab.ui.theme.GlassBorder
import com.open.skolab.ui.theme.GlassSurface
import com.open.skolab.ui.theme.LocalSkoLabSpacing
import com.open.skolab.ui.theme.BgPrimary
import com.open.skolab.ui.theme.BorderLight

data class DockItem(
    val route: String,
    val icon: ImageVector? = null,
    val drawableResId: Int? = null,
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
    val spacing = LocalSkoLabSpacing.current
    val view = LocalView.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BgPrimary // Matches BgPrimary WhatsApp Dark Background
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Horizontal border/divider at the top of the bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(BorderLight)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(56.dp)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    val tint by animateColorAsState(
                        targetValue = if (selected) com.open.skolab.ui.theme.PRIMARY else com.open.skolab.ui.theme.TEXT_MUTED,
                        animationSpec = tween(SkoLabMotion.fast),
                        label = "navTint"
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
                            Box {
                                if (item.drawableResId != null) {
                                    Image(
                                        painter = androidx.compose.ui.res.painterResource(id = item.drawableResId),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp).padding(horizontal = 2.dp, vertical = 0.dp),
                                        alpha = if (selected) 1f else 0.5f
                                    )
                                } else if (item.icon != null) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp).padding(horizontal = 2.dp, vertical = 0.dp),
                                        tint = tint
                                    )
                                }
                                if (item.badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(16.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(com.open.skolab.ui.theme.NOTIFICATION_DOT),
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
                                            .background(com.open.skolab.ui.theme.NOTIFICATION_DOT)
                                    )
                                }
                            }
                        }
                    Text(
                        text = item.label,
                        modifier = Modifier.offset(y = (-3).dp),
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
}
