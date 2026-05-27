package com.open.entropy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.model.Paper
import com.open.entropy.ui.theme.*
import java.util.Locale

@Composable
fun PaperCard(
    paper: Paper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    ScientificCard(
        modifier = modifier,
        onClick = onClick,
        glowColor = if (paper.disruptionScore > 0.9f) SkoLabPrimary.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScientificBadge(
                    text = paper.journal,
                    color = SkoLabDisruption
                )
                Text(
                    text = paper.year.toString(),
                    style = Typography.labelSmall,
                    color = SkoLabTextSecondary,
                    fontFamily = MonoFontFamily
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            MarkdownText(
                markdown = paper.title,
                color = SkoLabTextPrimary,
                fontSize = if (compact) 15.sp else 16.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = paper.authors.joinToString(", "),
                style = Typography.labelSmall,
                color = SkoLabTextSecondary,
                maxLines = 1
            )

            if (!compact) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricHighlight(
                        label = "D-INDEX",
                        value = String.format(Locale.US, "%.2f", paper.disruptionScore),
                        color = SkoLabDisruption,
                        modifier = Modifier.weight(1f)
                    )
                    MetricHighlight(
                        label = "S-INDEX",
                        value = String.format(Locale.US, "%.2f", paper.noveltyScore),
                        color = SkoLabNovelty,
                        modifier = Modifier.weight(1f)
                    )
                    ScoreArcMeter(
                        score = paper.disruptionScore,
                        label = "",
                        size = 40.dp,
                        color = SkoLabDisruption
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "D: ${String.format(Locale.US, "%.2f", paper.disruptionScore)}",
                        style = Typography.labelSmall,
                        color = SkoLabDisruption,
                        fontFamily = MonoFontFamily
                    )
                    Text(
                        text = "S: ${String.format(Locale.US, "%.2f", paper.noveltyScore)}",
                        style = Typography.labelSmall,
                        color = SkoLabNovelty,
                        fontFamily = MonoFontFamily
                    )
                }
            }
        }
    }
}
