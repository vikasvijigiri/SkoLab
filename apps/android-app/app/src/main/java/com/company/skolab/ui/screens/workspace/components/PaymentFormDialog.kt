package com.company.skolab.ui.screens.workspace.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.company.skolab.ui.theme.AccentTeal
import com.company.skolab.ui.theme.BgElevated
import com.company.skolab.ui.theme.BorderLight
import com.company.skolab.ui.theme.TextOnAccent
import com.company.skolab.ui.theme.TextPrimary
import com.company.skolab.ui.theme.TextSecondary

/**
 * Real billing (Stripe/Play Billing) isn't wired up yet -- this used to be a
 * full credit-card entry form that collected a card number, expiry, CVV, and
 * cardholder name, then just faked a 2-second delay and marked the account
 * upgraded locally with nothing actually charged or verified anywhere. That
 * asked real users to type real-looking payment details into a form that
 * went nowhere (2026-09-12 no-slop audit). Replaced with an honest "coming
 * soon" state until real payment processing exists -- no card fields, no
 * fake success, `onPaymentSuccess` is intentionally never called here.
 */
@Composable
fun PaymentFormDialog(
    targetTier: String,
    onDismiss: () -> Unit,
    onPaymentSuccess: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = BgElevated,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = AccentTeal.copy(alpha = 0.15f),
                    modifier = Modifier.size(64.dp),
                    border = BorderStroke(2.dp, AccentTeal),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = AccentTeal,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "$targetTier billing is coming soon",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "We're finishing secure payment processing for this tier. Your account hasn't been charged or changed.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal, contentColor = TextOnAccent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Got it", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
