package com.company.skolab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.company.skolab.data.ShadowProfileManager
import com.company.skolab.ui.theme.BgCard
import com.company.skolab.ui.theme.BgPrimary
import com.company.skolab.ui.theme.EntropiColors
import com.company.skolab.ui.theme.TextPrimary
import com.company.skolab.utils.InviteIntentManager
import kotlinx.coroutines.launch

@Composable
fun ExternalInviteDialog(
    researcherName: String,
    researcherOpenAlexId: String,
    currentUserId: String,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPinging by remember { mutableStateOf(false) }
    var pingSuccess by remember { mutableStateOf<Boolean?>(null) }
    val shadowManager = remember { ShadowProfileManager() }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Not on SkoLab Yet",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${researcherName.split(" ").firstOrNull() ?: researcherName} hasn't claimed their profile yet. How would you like to connect?",
                    color = EntropiColors.Text2,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Email Invite Button
                Button(
                    onClick = {
                        InviteIntentManager.sendCollabEmailInvite(context, researcherName)
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = EntropiColors.Blue1),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send Email Invite", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Shadow Ping Button
                OutlinedButton(
                    onClick = {
                        isPinging = true
                        scope.launch {
                            val success = shadowManager.pingShadowProfile(currentUserId, researcherOpenAlexId, researcherName)
                            pingSuccess = success
                            isPinging = false
                            if (success) {
                                kotlinx.coroutines.delay(1500)
                                onDismissRequest()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EntropiColors.Border),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                    } else if (pingSuccess == true) {
                        Text("Connection Registered!", color = EntropiColors.Green)
                    } else {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Register Interest in App")
                    }
                }
            }
        }
    }
}
