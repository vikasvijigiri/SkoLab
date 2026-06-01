package com.open.skolab.ui.components.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.ui.theme.*

@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
    isLoading: Boolean = false,
    onSearch: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isFocused || value.isNotEmpty()) AccentTeal else BorderLight,
        animationSpec = tween(200),
        label = "searchBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isFocused || value.isNotEmpty()) BgCard else BgCard,
        animationSpec = tween(200),
        label = "searchBg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(width = if (isFocused || value.isNotEmpty()) 1.5.dp else 1.dp, color = borderColor, shape = SkoLabShapes.md),
        color = containerColor,
        shape = SkoLabShapes.md,
        shadowElevation = if (isFocused) 4.dp else 1.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = if (isFocused || value.isNotEmpty()) AccentTeal else TextMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextMuted,
                        fontSize = 15.sp,
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = BodyFontFamily
                    ),
                    cursorBrush = SolidColor(AccentTeal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() })
                )
            }
            if (value.isNotEmpty() || isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AccentTeal
                        )
                    }
                    if (value.isNotEmpty()) {
                        IconButton(
                            onClick = { onClear?.invoke() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
