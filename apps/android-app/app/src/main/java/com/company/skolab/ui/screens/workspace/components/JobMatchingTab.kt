package com.company.skolab.ui.screens.workspace.components

import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*

import androidx.compose.ui.platform.LocalContext
import com.company.skolab.data.UserPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import com.company.skolab.network.ChatMessage

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.*
import com.company.skolab.ui.components.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.company.skolab.model.*
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JobMatchingTab() {
    val jobs = remember {
        mutableStateListOf(
            JobPosting("Research Scientist - Reasoning", "OpenAI", "San Francisco, CA (Hybrid)", "96%", "Apply Now"),
            JobPosting("Senior AI Resident", "Google DeepMind", "London, UK (Relocation)", "92%", "Apply Now"),
            JobPosting("Quantum Computing Postdoc", "Rigetti Computing", "Berkeley, CA", "88%", "Apply Now")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("💡", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("B2B Talent Sync", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        Text("These career placements are automatically tailored by indexing your local academic complexity score, saved publications, and domain mastery.", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }

        items(jobs) { job ->
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (job.match == "96%") AccentAmber else BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(job.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                            Text("${job.company} • ${job.location}", fontSize = 13.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AccentTealLight,
                            border = BorderStroke(0.5.dp, AccentTeal)
                        ) {
                            Text(
                                text = "${job.match} Match",
                                color = AccentTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Skills: LaTeX Proofing, Quantum Sim, PyTorch",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        
                        Button(
                            onClick = {
                                val idx = jobs.indexOf(job)
                                if (idx != -1) {
                                    jobs[idx] = job.copy(buttonText = "Application Submitted")
                                }
                            },
                            enabled = job.buttonText == "Apply Now",
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (job.buttonText == "Apply Now") AccentTeal else AccentEmerald,
                                contentColor = if (job.buttonText == "Apply Now") TextOnAccent else AccentEmerald
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(job.buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

data class JobPosting(val title: String, val company: String, val location: String, val match: String, val buttonText: String)

