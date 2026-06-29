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

data class AcademicEvent(val title: String, val time: String, val type: String, val invitee: String, val date: Int)

@Composable
fun SchedulerTab(isEnabled: Boolean) {
    if (!isEnabled) {
        PremiumPlaceholderCard(
            requiredTier = "SkoLab Pro",
            featureDesc = "Meetings Scheduler & Conference Calendar permits scheduling advisor meetings, booking 1-on-1 lab consults, and tracking upcoming conference abstract countdowns with automated LaTeX schedule exports."
        )
        return
    }

    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    val cachedUser by userPrefs.cachedUser.collectAsState(initial = null)
    val currentUserName = cachedUser?.name ?: "SkoLab User"

    var selectedDay by remember { mutableStateOf(24) }
    var calendarYear by remember { mutableStateOf(2026) }
    var calendarMonth0 by remember { mutableStateOf(4) }  // 0-indexed; 4 = May

    // Core dynamic event list
    val eventsList = remember(currentUserName) {
        mutableStateListOf(
            AcademicEvent("1-on-1 Advisor Check-in", "10:00 AM - 10:30 AM", "Advisor check-in", "Prof. $currentUserName", 24),
            AcademicEvent("Weekly Lab Journal Club", "02:00 PM - 03:30 PM", "Journal Club", "IIT Hyderabad Group", 27),
            AcademicEvent("NeurIPS Abstract Deadline", "11:59 PM EST", "Conference Deadline", "Global", 28),
            AcademicEvent("Preprint Brainstorming", "11:00 AM - 12:00 PM", "Brainstorm", "Dr. Ananya Rao", 24)
        )
    }

    // Input fields for scheduling
    var meetingTitle by remember { mutableStateOf("") }
    var meetingTime by remember { mutableStateOf("") }
    var meetingInvitee by remember(currentUserName) { mutableStateOf("Prof. $currentUserName") }
    var meetingType by remember { mutableStateOf("Advisor check-in") }

    val collaboratorsList = remember(currentUserName) {
        listOf("Prof. $currentUserName", "Dr. Ananya Rao", "Sundeep Sen", "IIT Hyderabad Group")
    }
    val meetingTypes = listOf("Advisor check-in", "Journal Club", "Brainstorm", "Colloquium Presentation")

    // Conference Countdowns
    var timeRemainingNeurips by remember { mutableStateOf("Calculating...") }
    var timeRemainingIcml by remember { mutableStateOf("Calculating...") }
    
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            
            // NeurIPS: June 5, 2026 13:00 UTC
            val neuripsTarget = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(2026, 5, 5, 13, 0, 0)
            }.timeInMillis
            val diffNeurips = neuripsTarget - now
            timeRemainingNeurips = if (diffNeurips > 0) {
                val days = diffNeurips / (24 * 60 * 60 * 1000)
                val hours = (diffNeurips / (60 * 60 * 1000)) % 24
                val minutes = (diffNeurips / (60 * 1000)) % 60
                val seconds = (diffNeurips / 1000) % 60
                "${days}d ${hours}h ${minutes}m ${seconds}s"
            } else {
                "Submissions Closed"
            }

            // ICML: July 12, 2026 13:00 UTC
            val icmlTarget = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(2026, 6, 12, 13, 0, 0)
            }.timeInMillis
            val diffIcml = icmlTarget - now
            timeRemainingIcml = if (diffIcml > 0) {
                val days = diffIcml / (24 * 60 * 60 * 1000)
                val hours = (diffIcml / (60 * 60 * 1000)) % 24
                val minutes = (diffIcml / (60 * 1000)) % 60
                val seconds = (diffIcml / 1000) % 60
                "${days}d ${hours}h ${minutes}m ${seconds}s"
            } else {
                "Submissions Closed"
            }
            
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // Interactive Calendar Card
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header month
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (calendarMonth0 == 0) { calendarMonth0 = 11; calendarYear -= 1 }
                            else calendarMonth0 -= 1
                        }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = TextPrimary)
                        }
                        val monthNames = listOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")
                        Text("${monthNames[calendarMonth0]} $calendarYear", fontWeight = FontWeight.Black, color = TextPrimary, fontSize = 15.sp, letterSpacing = 1.sp)
                        IconButton(onClick = {
                            if (calendarMonth0 == 11) { calendarMonth0 = 0; calendarYear += 1 }
                            else calendarMonth0 += 1
                        }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextPrimary)
                        }
                    }
                    
                    // Weekday headers
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
                            Text(
                                text = day,
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Grid cells
                    val calGrid = java.util.Calendar.getInstance().apply { set(calendarYear, calendarMonth0, 1) }
                    val daysInMonth = calGrid.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    // DAY_OF_WEEK: 1=Sun,2=Mon,...,7=Sat → Mon=0 basis
                    val startPadding = (calGrid.get(java.util.Calendar.DAY_OF_WEEK) - 2 + 7) % 7
                    val totalCells = daysInMonth + startPadding
                    val rows = (totalCells + 6) / 7
                    
                    for (r in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            for (c in 0 until 7) {
                                val cellIndex = r * 7 + c
                                val dayNumber = cellIndex - startPadding + 1
                                if (cellIndex < startPadding || dayNumber > daysInMonth) {
                                    Spacer(modifier = Modifier.size(36.dp))
                                } else {
                                    val isSelected = selectedDay == dayNumber
                                    val dayEvents = eventsList.filter { it.date == dayNumber }
                                    val hasEvent = dayEvents.isNotEmpty()
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) AccentTeal else if (hasEvent) BgSubtle else BgPrimary)
                                            .border(
                                                if (isSelected) 0.dp else 1.dp,
                                                if (isSelected) BgPrimary else if (hasEvent) AccentTeal else BorderLight,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedDay = dayNumber },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = dayNumber.toString(),
                                                color = if (isSelected) TextOnAccent else TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected || hasEvent) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (hasEvent) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 2.dp)
                                                        .size(4.dp)
                                                        .background(if (isSelected) TextOnAccent else AccentAmber, CircleShape)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Daily Agenda Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AGENDA FOR MAY $selectedDay, 2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentTeal,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                // Exporter Button
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        val latexString = StringBuilder().apply {
                            append("% SkoLab Academic Agenda\n")
                            append("\\begin{table}[h]\n")
                            append("\\centering\n")
                            append("\\caption{SkoLab Scholar Agenda - May 2026}\n")
                            append("\\begin{tabular}{lll}\n")
                            append("\\hline\n")
                            append("Date & Time & Scheduled Event (Host/Invitee) \\\\\n")
                            append("\\hline\n")
                            eventsList.sortedBy { it.date }.forEach { ev ->
                                append("May ${ev.date} & ${ev.time} & ${ev.title} (${ev.invitee}) \\\\\n")
                            }
                            append("\\hline\n")
                            append("\\end{tabular}\n")
                            append("\\end{table}")
                        }.toString()
                        
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("LaTeX Agenda", latexString)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "LaTeX Table copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export LaTeX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val dayEvents = eventsList.filter { it.date == selectedDay }
        if (dayEvents.isEmpty()) {
            item {
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No scheduled research sessions today. Coordinate meeting slots below.",
                        modifier = Modifier.padding(16.dp),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(dayEvents) { ev ->
                Surface(
                    color = BgCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when(ev.type) {
                                "Conference Deadline" -> AccentRose
                                "Journal Club" -> AccentEmerald
                                else -> AccentTeal
                            },
                            border = BorderStroke(1.dp, when(ev.type) {
                                "Conference Deadline" -> AccentRose
                                "Journal Club" -> AccentEmerald
                                else -> AccentTeal
                            })
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Icon(
                                    imageVector = when(ev.type) {
                                        "Conference Deadline" -> Icons.Default.Science
                                        "Journal Club" -> Icons.Default.Groups
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    tint = when(ev.type) {
                                        "Conference Deadline" -> AccentRose
                                        "Journal Club" -> AccentEmerald
                                        else -> AccentTeal
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text(ev.time, color = TextMuted, fontSize = 11.sp)
                            Text("Invitee: ${ev.invitee}", color = TextSecondary, fontSize = 12.sp)
                        }
                        
                        IconButton(onClick = { eventsList.remove(ev) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel Slot", tint = AccentRose)
                        }
                    }
                }
            }
        }

        // Schedule Slot Form
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "COORDINATE RESEARCH MEETING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = meetingTitle,
                        onValueChange = { meetingTitle = it },
                        label = { Text("Meeting Title / Agenda") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = meetingTime,
                        onValueChange = { meetingTime = it },
                        label = { Text("Time Slot (e.g. 03:00 PM - 04:00 PM)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentTeal,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Invitee Select Card
                    Text("Select Host / Invitee", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        collaboratorsList.forEach { col ->
                            val isSelected = meetingInvitee == col
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentTealLight else BgSubtle,
                                border = BorderStroke(1.dp, if (isSelected) AccentTeal else BorderLight),
                                modifier = Modifier.clickable { meetingInvitee = col }
                            ) {
                                Text(
                                    text = col,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Meeting Type Select Card
                    Text("Select Session Type", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        meetingTypes.forEach { type ->
                            val isSelected = meetingType == type
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentTealLight else BgSubtle,
                                border = BorderStroke(1.dp, if (isSelected) AccentTeal else BorderLight),
                                modifier = Modifier.clickable { meetingType = type }
                            ) {
                                Text(
                                    text = type,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            if (meetingTitle.isNotBlank() && meetingTime.isNotBlank()) {
                                eventsList.add(AcademicEvent(meetingTitle, meetingTime, meetingType, meetingInvitee, selectedDay))
                                meetingTitle = ""
                                meetingTime = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Lock Schedule Slot", color = TextOnAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Live Countdown Card
        item {
            Surface(
                color = BgCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACTIVE SCHOLAR CONFERENCES", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextPrimary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("NeurIPS 2026", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Abstract Registration", fontSize = 11.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentRose,
                            border = BorderStroke(0.5.dp, AccentRose)
                        ) {
                            Text(
                                text = timeRemainingNeurips,
                                color = AccentRose,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = BorderLight)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ICML 2026", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Full Paper Submission", fontSize = 11.sp, color = TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentTealLight,
                            border = BorderStroke(0.5.dp, AccentTeal)
                        ) {
                            Text(
                                text = timeRemainingIcml,
                                color = AccentTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

