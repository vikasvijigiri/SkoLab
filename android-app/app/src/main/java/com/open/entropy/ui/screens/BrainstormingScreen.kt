package com.open.entropy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open.entropy.ui.theme.EntropiColors
import com.open.entropy.ui.theme.SyneFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainstormingScreen(
    onSaveIdea: (String) -> Unit = {}
) {
    var ideaText by remember { mutableStateOf("") }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EntropiColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .padding(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Brainstorming Space",
                color = EntropiColors.Text,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            
            Text(
                text = "Draft your next breakthrough hypothesis. The AI will help you connect the dots.",
                color = EntropiColors.Text2,
                fontSize = 14.sp
            )
            
            // Notepad Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = EntropiColors.Card,
                border = androidx.compose.foundation.BorderStroke(1.dp, EntropiColors.Border)
            ) {
                TextField(
                    value = ideaText,
                    onValueChange = { ideaText = it },
                    modifier = Modifier.fillMaxSize(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = EntropiColors.Gold1,
                        focusedTextColor = EntropiColors.Text,
                        unfocusedTextColor = EntropiColors.Text
                    ),
                    placeholder = {
                        Text(
                            text = "I'm thinking about the intersection of quantum gravity and...",
                            color = EntropiColors.Text3
                        )
                    }
                )
            }
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onSaveIdea(ideaText) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EntropiColors.Card2),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = EntropiColors.Text2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Draft", color = EntropiColors.Text2, fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = { /* TODO: Hook up AI generation */ },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EntropiColors.Gold1),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = EntropiColors.Background, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("AI Spark", color = EntropiColors.Background, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
