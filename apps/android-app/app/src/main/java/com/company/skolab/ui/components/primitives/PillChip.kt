package com.company.skolab.ui.components.primitives

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.company.skolab.ui.theme.SkoLabDisruption
import com.company.skolab.ui.theme.SkoLabMotion
import com.company.skolab.ui.theme.SkoLabShapes
import com.company.skolab.ui.theme.SkoLabTextSecondary
import com.company.skolab.ui.theme.ObsidianBlack
import com.company.skolab.ui.theme.Typography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) SkoLabDisruption else Color.Transparent,
        animationSpec = tween(SkoLabMotion.fast),
        label = "chipBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) ObsidianBlack else SkoLabTextSecondary,
        animationSpec = tween(SkoLabMotion.fast),
        label = "chipText"
    )
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = containerColor,
        shape = SkoLabShapes.orb,
        border = if (selected) null else BorderStroke(1.dp, SkoLabTextSecondary.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = Typography.labelMedium,
            color = textColor
        )
    }
}

@Composable
fun FilterChipRow(
    filters: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters) { filter ->
            PillChip(
                text = filter,
                selected = filter == selected,
                onClick = { onSelect(filter) }
            )
        }
    }
}
