package com.company.skolab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.company.skolab.di.AppDependencies
import com.company.skolab.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val authManager = AppDependencies.authManager
    val cachedUser by authManager.cachedUser.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var nameText by remember { mutableStateOf("") }
    var focusText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var aboutText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(cachedUser) {
        if (cachedUser != null && nameText.isBlank()) {
            nameText = cachedUser?.name.orEmpty()
            focusText = cachedUser?.researchFocus.orEmpty()
            statusText = cachedUser?.academicStatus.orEmpty().ifBlank { "Researcher" }
            aboutText = cachedUser?.about.orEmpty()
        }
    }

    val canSave = nameText.isNotBlank() && focusText.isNotBlank() && !isSaving

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentTeal,
        unfocusedBorderColor = BorderLight,
        focusedContainerColor = BgCard,
        unfocusedContainerColor = BgCard,
        focusedLabelColor = AccentTeal,
        unfocusedLabelColor = TextMuted,
        cursorColor = AccentTeal
    )

    Scaffold(
        containerColor = BgPrimary,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Edit Profile",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimary,
                            fontFamily = DisplayFontFamily
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        if (isSaving) {
                            Box(
                                modifier = Modifier.padding(end = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = AccentTeal,
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    if (!canSave) return@TextButton
                                    isSaving = true
                                    scope.launch {
                                        authManager.updateAcademicProfile(
                                            name = nameText.trim(),
                                            focus = focusText.trim(),
                                            academicStatus = statusText.trim(),
                                            about = aboutText.trim()
                                        )
                                        isSaving = false
                                        onBack()
                                    }
                                },
                                enabled = canSave,
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Text(
                                    "Save",
                                    color = if (canSave) AccentTeal else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
                )
                HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Name ──────────────────────────────────────────────────────────
            FieldSection(label = "Display Name") {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = { Text("Your full name", color = TextMuted) },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // ── Academic Status ───────────────────────────────────────────────
            FieldSection(label = "Academic Status") {
                val options = listOf("PhD Candidate", "Postdoctoral Fellow", "Professor", "Researcher", "Student")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { option ->
                        val selected = statusText.equals(option, ignoreCase = true)
                        Surface(
                            onClick = { statusText = option },
                            shape = RoundedCornerShape(50.dp),
                            color = if (selected) AccentTeal else BgCard,
                            border = BorderStroke(1.dp, if (selected) AccentTeal else BorderLight),
                            tonalElevation = 0.dp
                        ) {
                            Text(
                                text = option,
                                color = if (selected) BgPrimary else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }

            // ── Research Focus ────────────────────────────────────────────────
            FieldSection(label = "Research Focus") {
                OutlinedTextField(
                    value = focusText,
                    onValueChange = { focusText = it },
                    placeholder = { Text("e.g. Quantum Computing", color = TextMuted) },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                val suggestions = listOf("Physics", "Machine Learning", "Genomics",
                    "Quantum Computing", "Neuroscience", "Chemistry", "Bioinformatics")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestions.forEach { sug ->
                        val selected = focusText.equals(sug, ignoreCase = true)
                        Surface(
                            onClick = { focusText = sug },
                            shape = RoundedCornerShape(50.dp),
                            color = if (selected) AccentTeal.copy(alpha = 0.12f) else BgCard,
                            border = BorderStroke(0.5.dp, if (selected) AccentTeal else BorderLight)
                        ) {
                            Text(
                                text = sug,
                                color = if (selected) AccentTeal else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            FieldSection(label = "About") {
                OutlinedTextField(
                    value = aboutText,
                    onValueChange = { aboutText = it },
                    placeholder = { Text("A short bio visible to other researchers...", color = TextMuted) },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 4,
                    maxLines = 8
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FieldSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        content()
    }
}
