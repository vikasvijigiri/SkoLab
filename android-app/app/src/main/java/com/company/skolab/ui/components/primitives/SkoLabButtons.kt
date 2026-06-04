package com.company.skolab.ui.components.primitives

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
import com.company.skolab.ui.theme.SkoLabDisruption
import com.company.skolab.ui.theme.SkoLabShapes
import com.company.skolab.ui.theme.SkoLabTextPrimary
import com.company.skolab.ui.theme.GlassBorder
import com.company.skolab.ui.theme.ObsidianBlack
import com.company.skolab.ui.theme.Typography
import com.company.skolab.ui.theme.BrandGoogleText
import com.company.skolab.ui.theme.SkoLabWarning

@Composable
fun SkoLabPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    isError: Boolean = false
) {
    Button(
        onClick = if (isLoading || isError) { {} } else onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled && !isLoading,
        shape = SkoLabShapes.md,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isError) SkoLabWarning else SkoLabDisruption,
            contentColor = if (isError) Color.White else ObsidianBlack,
            disabledContainerColor = if (isError) SkoLabWarning.copy(alpha = 0.3f) else SkoLabDisruption.copy(alpha = 0.3f),
            disabledContentColor = if (isError) Color.White.copy(alpha = 0.5f) else ObsidianBlack.copy(alpha = 0.5f)
        )
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.height(22.dp),
                strokeWidth = 2.dp,
                color = if (isError) Color.White else ObsidianBlack
            )
        } else {
            Text(text = if (isError) "Failed. Try Again?" else text, style = Typography.labelLarge)
        }
    }
}

@Composable
fun SkoLabOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    isError: Boolean = false
) {
    OutlinedButton(
        onClick = if (isLoading || isError) { {} } else onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled && !isLoading,
        shape = SkoLabShapes.md,
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                if (isError) {
                    listOf(SkoLabWarning.copy(alpha = 0.8f), SkoLabWarning.copy(alpha = 0.4f))
                } else {
                    listOf(SkoLabDisruption.copy(alpha = 0.6f), GlassBorder)
                }
            )
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (isError) SkoLabWarning else SkoLabTextPrimary,
            disabledContentColor = (if (isError) SkoLabWarning else SkoLabTextPrimary).copy(alpha = 0.5f)
        )
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
                color = if (isError) SkoLabWarning else SkoLabTextPrimary
            )
        } else {
            Text(text = if (isError) "Failed" else text, style = Typography.labelMedium)
        }
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
            contentColor = BrandGoogleText
        )
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.height(22.dp),
                strokeWidth = 2.dp,
                color = BrandGoogleText
            )
        } else {
            Text("Continue with Google", style = Typography.labelLarge, color = BrandGoogleText)
        }
    }
}
