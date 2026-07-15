package com.company.skolab.ui.screens.feed.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.skolab.model.Conjecture
import com.company.skolab.ui.theme.*

@Composable
fun DailyChallengeCard(
    conjecture: Conjecture,
    onOpenChallenge: () -> Unit
) {
    Surface(
        onClick = onOpenChallenge,
        shape = RoundedCornerShape(18.dp),
        color = BgCard,
        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(AccentAmber.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = "Daily challenge",
                        tint = AccentAmber,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Today's challenge",
                        color = AccentAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = conjecture.category,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AccentAmber.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Open",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = AccentAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = conjecture.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = conjecture.hypothesis,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                conjecture.options.take(2).forEach { option ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AccentTeal.copy(alpha = 0.08f)
                    ) {
                        Text(
                            text = option,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = AccentTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
