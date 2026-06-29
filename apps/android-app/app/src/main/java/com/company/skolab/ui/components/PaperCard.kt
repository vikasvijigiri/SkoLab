package com.company.skolab.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.model.Paper
import com.company.skolab.ui.theme.*
import androidx.compose.ui.tooling.preview.Preview
import java.util.Locale

@Composable
fun PaperCard(
    paper: Paper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    searchQuery: String = ""
) {
    val highlightedTitle = remember(paper.title, searchQuery) {
        if (searchQuery.isBlank()) {
            paper.title
        } else {
            val regex = Regex("(?i)(" + Regex.escape(searchQuery) + ")")
            paper.title.replace(regex, "**$1**")
        }
    }

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
                markdown = highlightedTitle,
                color = SkoLabTextPrimary,
                fontSize = if (compact) 15.sp else 16.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = paper.authors.map { it.split("|").first() }.joinToString(", "),
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
                        value = String.format(Locale.getDefault(), "%.2f", paper.disruptionScore),
                        color = SkoLabDisruption,
                        modifier = Modifier.weight(1f)
                    )
                    MetricHighlight(
                        label = "S-INDEX",
                        value = String.format(Locale.getDefault(), "%.2f", paper.noveltyScore),
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
                        text = "D: ${String.format(Locale.getDefault(), "%.2f", paper.disruptionScore)}",
                        style = Typography.labelSmall,
                        color = SkoLabDisruption,
                        fontFamily = MonoFontFamily
                    )
                    Text(
                        text = "S: ${String.format(Locale.getDefault(), "%.2f", paper.noveltyScore)}",
                        style = Typography.labelSmall,
                        color = SkoLabNovelty,
                        fontFamily = MonoFontFamily
                    )
                }
            }
        }
    }
}

@Preview(name = "Paper Card Light", showBackground = true)
@Composable
fun PaperCardLightPreview() {
    SkoLabTheme {
        PaperCard(
            paper = Paper(
                id = "1",
                title = "Attention Is All You Need",
                authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"),
                journal = "NeurIPS",
                year = 2017,
                domain = "Computer Science",
                subDomain = "AI",
                abstractText = "The dominant sequence transduction models...",
                disruptionScore = 0.99f,
                noveltyScore = 0.95f,
                citationVelocity = 12.5f,
                hIndex = 42,
                citationCount = 100000,
                journalImpactFactor = 8.5f,
                aiSummary = "Attention mechanisms rule sequence models.",
                keyInsight = "Transformers replace recurrence.",
                bulletPoints = emptyList(),
                methodology = emptyList(),
                latexFormula = null,
                isRetracted = false,
                doi = "https://doi.org/10.48550/arXiv.1706.03762",
                pdfUrl = null
            ),
            onClick = {}
        )
    }
}
