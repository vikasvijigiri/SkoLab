package com.open.entropy.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.open.entropy.model.MockData
import com.open.entropy.model.Paper
import com.open.entropy.network.ApiService
import com.open.entropy.network.SummaryResponse
import com.open.entropy.ui.components.*
import com.open.entropy.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleReaderScreen(
    url: String,
    title: String,
    onClose: () -> Unit
) {
    val apiService = remember { ApiService() }
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    
    val paper = remember(title) { MockData.papers.find { it.title == title } }
    var summaryData by remember { mutableStateOf<SummaryResponse?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }
    var showCockpit by remember { mutableStateOf(false) }

    LaunchedEffect(title) {
        if (summaryData == null && !isSummarizing) {
            isSummarizing = true
            summaryData = apiService.summarizeWork(title, paper?.doi)
            isSummarizing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = Typography.titleMedium,
                            color = ResQitTextPrimary,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (paper != null) {
                            Text(
                                text = paper.authors.joinToString(", "),
                                style = Typography.labelSmall,
                                color = ResQitTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ResQitTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showCockpit = !showCockpit }) {
                        Icon(
                            imageVector = if (showCockpit) Icons.Default.AutoAwesome else Icons.Default.Science,
                            contentDescription = "Intelligence",
                            tint = if (showCockpit) ResQitAiInsight else ResQitTextPrimary
                        )
                    }
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ResQitPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ResQitBg.copy(alpha = 0.9f),
                    titleContentColor = ResQitTextPrimary
                )
            )
        },
        containerColor = ResQitBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(ResQitBg)
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                            }
                        }
                        loadUrl(url)
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.loadUrl(url)
                }
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = ResQitPrimary,
                    trackColor = ResQitDivider
                )
            }

            // COCKPIT OVERLAY
            AnimatedVisibility(
                visible = showCockpit,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f),
                    color = ResQitBg.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(ResQitDivider, RoundedCornerShape(50))
                                .align(Alignment.CenterHorizontally)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SCIENTIFIC COCKPIT",
                                    style = Typography.labelSmall,
                                    color = ResQitTextSecondary,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Intelligence active for: $title",
                                    style = Typography.bodyMedium,
                                    color = ResQitTextPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 2
                                )
                            }
                            
                            FrontierScoreOrb(
                                score = paper?.disruptionScore ?: 0.85f,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (isSummarizing) {
                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ResQitAiInsight)
                            }
                        } else {
                            summaryData?.let { summary ->
                                IntelligenceBriefCard(summary = summary)
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                
                                Text(
                                    text = "METRICS",
                                    style = Typography.labelSmall,
                                    color = ResQitTextSecondary,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    AnalyticBox(
                                        label = "Creativity",
                                        value = "${summary.metrics.creativity}%",
                                        color = ResQitNovelty,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AnalyticBox(
                                        label = "Complexity",
                                        value = "${summary.metrics.complexity}%",
                                        color = ResQitDisruption,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
