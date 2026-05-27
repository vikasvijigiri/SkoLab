package com.open.entropy.ui.components.primitives

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.open.entropy.ui.theme.SkoLabDisruption
import com.open.entropy.ui.theme.SkoLabShapes
import com.open.entropy.ui.theme.SkoLabTextPrimary
import com.open.entropy.ui.theme.GlassBorder
import com.open.entropy.ui.theme.ObsidianBlack
import com.open.entropy.ui.theme.Typography

@Composable
fun SkoLabPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        shape = SkoLabShapes.md,
        colors = ButtonDefaults.buttonColors(
            containerColor = SkoLabDisruption,
            contentColor = ObsidianBlack,
            disabledContainerColor = SkoLabDisruption.copy(alpha = 0.3f)
        )
    ) {
        Text(text = text, style = Typography.labelLarge)
    }
}

@Composable
fun SkoLabOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = SkoLabShapes.md,
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(listOf(SkoLabDisruption.copy(alpha = 0.6f), GlassBorder))
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SkoLabTextPrimary)
    ) {
        Text(text = text, style = Typography.labelMedium)
    }
}

@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled && !isLoading,
        shape = SkoLabShapes.md,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF1F1F1F)
        )
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.height(22.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF1F1F1F)
            )
        } else {
            Text("Continue with Google", style = Typography.labelLarge, color = Color(0xFF1F1F1F))
        }
    }
}
