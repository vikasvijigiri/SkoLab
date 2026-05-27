package com.open.entropy.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationGraphScreen(paperId: String, onBack: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale *= zoom
                        offset += pan
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        ) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            
            // Center Node (Current Paper)
            drawCircle(SkoLabSecondary, radius = 24f, center = Offset(centerX, centerY))
            
            // Surrounding Nodes (Citations)
            val nodes = 12
            val radius = 300f
            for (i in 0 until nodes) {
                val angle = (2 * Math.PI / nodes * i).toFloat()
                val nodeX = centerX + radius * cos(angle)
                val nodeY = centerY + radius * sin(angle)
                
                val color = if (i % 3 == 0) SkoLabPrimary else SkoLabTextSecondary.copy(alpha = 0.5f)
                
                // Edge
                drawLine(
                    color = SkoLabDivider,
                    start = Offset(centerX, centerY),
                    end = Offset(nodeX, nodeY),
                    strokeWidth = 2f
                )
                
                // Node
                drawCircle(color, radius = 16f, center = Offset(nodeX, nodeY))
                
                // 2nd Degree Nodes
                val subNodes = 3
                val subRadius = 80f
                for (j in 0 until subNodes) {
                    val subAngle = angle + (2 * Math.PI / subNodes * j).toFloat() / 4
                    val subX = nodeX + subRadius * cos(subAngle)
                    val subY = nodeY + subRadius * sin(subAngle)
                    
                    drawLine(SkoLabDivider, start = Offset(nodeX, nodeY), end = Offset(subX, subY), strokeWidth = 1f)
                    drawCircle(SkoLabTextSecondary.copy(alpha = 0.3f), radius = 8f, center = Offset(subX, subY))
                }
            }
        }
        
        // UI Overlays
        TopAppBar(
            title = { Text("CITATION GRAPH", style = Typography.labelSmall, color = SkoLabTextSecondary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SkoLabTextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
        
        // Legend
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            color = SkoLabSurfaceElevated.copy(alpha = 0.9f),
            shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(1.dp, SkoLabDivider)
        ) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem("Disruptive", SkoLabPrimary)
                LegendItem("Developmental", SkoLabTextSecondary.copy(alpha = 0.5f))
                LegendItem("Current", SkoLabSecondary)
            }
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = Typography.labelSmall, color = SkoLabTextSecondary, fontSize = 10.sp)
    }
}
