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
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import android.os.Build

@Composable
fun SkoLabPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    isError: Boolean = false
) {
    val view = LocalView.current
    Button(
        onClick = {
            if (isError) {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            if (!(isLoading || isError)) {
                onClick()
            }
        },
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
    val view = LocalView.current
    OutlinedButton(
        onClick = {
            if (isError) {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            if (!(isLoading || isError)) {
                onClick()
            }
        },
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
    val view = LocalView.current
    Button(
        onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (!isLoading) {
                onClick()
            }
        },
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

/**
 * **DEPRECATED** — Use [SkoLabPrimaryButton] or [SkoLabOutlinedButton] instead.
 *
 * This shim exists purely to surface compiler warnings at call sites that were written
 * before the Design System v2 button refactor (2026-06). It delegates directly to
 * [SkoLabPrimaryButton] so it remains functionally correct until migrated.
 *
 * Migration guide:
 *   - Replace `LegacySkoLabButton(text, onClick)` with `SkoLabPrimaryButton(text, onClick)`.
 *   - For secondary actions use `SkoLabOutlinedButton(text, onClick)`.
 *
 * Tracked in: DESIGN_SYSTEM_CHANGELOG.md §v2.1
 */
@Deprecated(
    message = "Use SkoLabPrimaryButton or SkoLabOutlinedButton from the Design System v2 palette. " +
              "LegacySkoLabButton will be removed in the next major release.",
    replaceWith = ReplaceWith(
        expression = "SkoLabPrimaryButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)",
        imports = ["com.company.skolab.ui.components.primitives.SkoLabPrimaryButton"]
    ),
    level = DeprecationLevel.WARNING
)
@Composable
fun LegacySkoLabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    SkoLabPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    )
}
