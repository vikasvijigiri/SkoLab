package com.open.entropy.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.open.entropy.R
import com.open.entropy.ui.theme.SkoLabDisruption
import com.open.entropy.ui.theme.SkoLabTextPrimary
import com.open.entropy.ui.theme.Typography

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    style: TextStyle = Typography.displayLarge,
    primaryColor: Color = com.open.entropy.ui.theme.AccentViolet,
    accentColor: Color = com.open.entropy.ui.theme.AccentCyan
) {
    val context = LocalContext.current
    val prefix = context.getString(R.string.brand_mark_prefix)
    val accent = context.getString(R.string.brand_mark_accent)
    val suffix = context.getString(R.string.brand_mark_suffix)

    androidx.compose.foundation.layout.Row(modifier = modifier) {
        Text(prefix, style = style, color = primaryColor, fontWeight = FontWeight.Bold)
        Text(accent, style = style, color = accentColor, fontWeight = FontWeight.Bold)
        Text(suffix, style = style, color = primaryColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BrandTagline(
    modifier: Modifier = Modifier,
    color: Color = com.open.entropy.ui.theme.SkoLabTextSecondary
) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.brand_tagline).uppercase(),
        modifier = modifier,
        style = Typography.labelSmall,
        color = color,
        letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)
    )
}
