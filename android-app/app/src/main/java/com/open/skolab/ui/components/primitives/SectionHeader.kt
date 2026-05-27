package com.open.skolab.ui.components.primitives

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.skolab.ui.theme.SkoLabDisruption
import com.open.skolab.ui.theme.SkoLabTextPrimary
import com.open.skolab.ui.theme.SkoLabTextSecondary
import com.open.skolab.ui.theme.Typography

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentColor: androidx.compose.ui.graphics.Color = SkoLabDisruption
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = Typography.labelSmall,
            color = accentColor,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = Typography.headlineLarge,
                color = SkoLabTextPrimary
            )
        }
    }
}

@Composable
fun SectionCaption(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = Typography.labelSmall,
        color = SkoLabTextSecondary,
        letterSpacing = 1.5.sp
    )
}
