package com.company.skolab.ui.components.primitives

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.company.skolab.ui.theme.SkoLabDivider
import com.company.skolab.ui.theme.SkoLabDisruption
import com.company.skolab.ui.theme.SkoLabShapes
import com.company.skolab.ui.theme.SkoLabSurface
import com.company.skolab.ui.theme.SkoLabTextPrimary
import com.company.skolab.ui.theme.SkoLabWarning
import com.company.skolab.ui.theme.AccentEmerald
import com.company.skolab.ui.theme.Typography

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SkoLabTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    isSuccess: Boolean = false,
    isLoading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    autofillType: ContentType? = null,
    enabled: Boolean = true
) {
    val isError = errorText != null

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = Typography.labelMedium) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (autofillType != null) {
                        contentType = autofillType
                    }
                },
            shape = SkoLabShapes.md,
            enabled = enabled,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            leadingIcon = leadingIcon,
            trailingIcon = {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = SkoLabDisruption
                        )
                    }
                    isError -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = SkoLabWarning
                        )
                    }
                    isSuccess -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = AccentEmerald
                        )
                    }
                    else -> trailingIcon?.invoke()
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SkoLabSurface,
                unfocusedContainerColor = SkoLabSurface,
                focusedBorderColor = if (isSuccess) AccentEmerald else SkoLabDisruption,
                unfocusedBorderColor = when {
                    isError -> SkoLabWarning
                    isSuccess -> AccentEmerald
                    else -> SkoLabDivider
                },
                errorBorderColor = SkoLabWarning,
                focusedTextColor = SkoLabTextPrimary,
                unfocusedTextColor = SkoLabTextPrimary,
                errorLabelColor = SkoLabWarning,
                focusedLabelColor = if (isSuccess) AccentEmerald else SkoLabDisruption,
                unfocusedLabelColor = SkoLabDivider
            )
        )

        AnimatedVisibility(visible = isError) {
            if (errorText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorText,
                    style = Typography.bodySmall,
                    color = SkoLabWarning
                )
            }
        }
    }
}
