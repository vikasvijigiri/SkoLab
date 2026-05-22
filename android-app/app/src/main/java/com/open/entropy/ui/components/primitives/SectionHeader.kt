package com.open.entropy.ui.components.primitives

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
import com.open.entropy.ui.theme.ResQitDisruption
import com.open.entropy.ui.theme.ResQitTextPrimary
import com.open.entropy.ui.theme.ResQitTextSecondary
import com.open.entropy.ui.theme.Typography

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentColor: androidx.compose.ui.graphics.Color = ResQitDisruption
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
                color = ResQitTextPrimary
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
        color = ResQitTextSecondary,
        letterSpacing = 1.5.sp
    )
}
