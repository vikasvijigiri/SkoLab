package com.company.skolab.ui.components.primitives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.company.skolab.ui.components.AnalyticBox

data class MetricCell(val label: String, val value: String, val color: Color)

@Composable
fun MetricGrid(
    metrics: List<MetricCell>,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false
    ) {
        items(metrics) { cell ->
            AnalyticBox(
                label = cell.label,
                value = cell.value,
                color = cell.color,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
