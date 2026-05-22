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

data class DockItem(val route: String, val icon: ImageVector, val label: String)

@Composable
fun BottomNavDock(
    items: List<DockItem>,
    currentRoute: String?,
    onItemClick: (DockItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalResQitSpacing.current
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .height(72.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    shadowElevation = 20f
                    spotShadowColor = ResQitDisruption.copy(alpha = 0.2f)
                },
            color = GlassSurface,
            shape = ResQitShapes.pill,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, GlassBorder)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val selected = currentRoute == item.route
                    val tint by animateColorAsState(
                        targetValue = if (selected) ResQitDisruption else ResQitTextSecondary,
                        animationSpec = tween(ResQitMotion.fast),
                        label = "navTint"
                    )
                    val pillWidth by animateDpAsState(
                        targetValue = if (selected) 56.dp else 0.dp,
                        animationSpec = tween(ResQitMotion.normal),
                        label = "pillW"
                    )

                    Column(
                        modifier = Modifier
                            .width(64.dp)
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
                                        .size(pillWidth, 36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(ResQitDisruption.copy(alpha = 0.12f))
                                )
                            }
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = tint
                            )
                        }
                        Text(
                            text = item.label,
                            fontSize = 9.sp,
                            color = tint,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
