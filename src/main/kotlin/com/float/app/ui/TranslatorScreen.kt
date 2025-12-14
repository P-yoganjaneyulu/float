package com.float.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.float.app.data.ConnectionState
import com.float.app.data.ErrorModel
import com.float.app.service.AudioCaptureState
import com.float.app.service.FloatServiceState
import com.float.app.ui.components.ErrorBanner
import com.float.app.ui.components.AudioVisualization
import com.float.app.ui.components.AudioVisualization.AudioWaveform
import com.float.app.ui.components.AudioVisualization.MicrophoneIndicator
import com.float.app.ui.components.AudioVisualization.LatencyIndicator
import com.float.app.ui.components.AudioVisualization.SpeechActivityIndicator
import com.float.app.ui.viewmodel.TranslatorViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Subtitle data for display.
 */
data class SubtitleItem(
    val id: String,
    val text: String,
    val isSource: Boolean, // true for source language, false for target language
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float? = null,
    val isPartial: Boolean = false
)

/**
 * Main translator screen with glassmorphism design.
 * Displays translation results, connection status, and controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    viewModel: TranslatorViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    
    // Collect states from service
    val serviceState by viewModel.serviceState.collectAsState()
    val audioState by viewModel.audioState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentError by viewModel.currentError.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    
    val lazyListState = rememberLazyListState()
    
    // Auto-scroll to bottom when new subtitles arrive
    LaunchedEffect(subtitles) {
        if (subtitles.isNotEmpty()) {
            delay(100) // Small delay for smooth scrolling
            lazyListState.animateScrollToItem(subtitles.size - 1)
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E).copy(alpha = 0.95f),
                        Color(0xFF16213E).copy(alpha = 0.98f),
                        Color(0xFF0F3460).copy(alpha = 1.0f)
                    ),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .imePadding()
        ) {
            // Header with connection status
            TranslatorHeader(
                serviceState = serviceState,
                connectionState = connectionState,
                onLanguageChange = { source, target ->
                    viewModel.changeLanguagePair(source, target)
                },
                onSettingsClick = { viewModel.openSettings() }
            )
            
            // Real-time audio visualization and indicators
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Audio waveform visualization
                AudioWaveform(
                    audioData = viewModel.audioData,
                    isProcessing = viewModel.isProcessing,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Microphone and speech indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MicrophoneIndicator(
                        isRecording = viewModel.isRecording,
                        isProcessing = viewModel.isProcessing,
                        modifier = Modifier.weight(1f)
                    )
                    
                    SpeechActivityIndicator(
                        isSpeechActive = viewModel.isSpeechActive,
                        speechProbability = viewModel.speechProbability,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Latency indicator
                LatencyIndicator(
                    latencyMetrics = viewModel.latencyMetrics,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Subtitle display area
            SubtitleDisplayArea(
                subtitles = subtitles,
                lazyListState = lazyListState,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Control panel
            ControlPanel(
                serviceState = serviceState,
                audioState = audioState,
                isOnline = connectionState == ConnectionState.CONNECTED,
                onStartStop = { isActive ->
                    if (isActive) {
                        viewModel.startTranslation()
                    } else {
                        viewModel.stopTranslation()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Error banner overlay
        ErrorBanner(
            error = currentError,
            onDismiss = { viewModel.clearError() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
        
        // Privacy indicator overlay
        PrivacyIndicator(
            isRecording = audioState.isCapturing,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
        
        // Offline mode banner
        if (connectionState != ConnectionState.CONNECTED && serviceState is FloatServiceState.OfflineMode) {
            OfflineModeBanner(
                reason = serviceState.reason,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Header component with connection status and language selector.
 */
@Composable
private fun TranslatorHeader(
    serviceState: FloatServiceState,
    connectionState: ConnectionState,
    onLanguageChange: (String, String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Service status and connection indicator
                Column {
                    Text(
                        text = when (serviceState) {
                            is FloatServiceState.Active -> "FLOAT Ready"
                            is FloatServiceState.Recording -> "Translating..."
                            is FloatServiceState.Processing -> "Processing..."
                            is FloatServiceState.OfflineMode -> "Offline Mode"
                            is FloatServiceState.Error -> "Error"
                            else -> "Initializing..."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = when (connectionState) {
                                        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                        ConnectionState.RECONNECTING -> Color(0xFFFF9800)
                                        ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                                        ConnectionState.ERROR -> Color(0xFFF44336)
                                        else -> Color(0xFF9E9E9E)
                                    },
                                    shape = CircleShape
                                )
                        )
                        
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> "Connected"
                                ConnectionState.RECONNECTING -> "Reconnecting..."
                                ConnectionState.DISCONNECTED -> "Disconnected"
                                ConnectionState.ERROR -> "Connection Error"
                                else -> "Unknown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // Settings button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .background(
                            Color.White.copy(alpha = 0.1f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Language pair selector
            LanguagePairSelector(
                onLanguageChange = onLanguageChange
            )
        }
    }
}

/**
 * Language pair selector with swap functionality.
 */
@Composable
private fun LanguagePairSelector(
    onLanguageChange: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceLanguage by remember { mutableStateOf("English") }
    var targetLanguage by remember { mutableStateOf("Hindi") }
    
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Source language
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.15f)
            )
        ) {
            Text(
                text = sourceLanguage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
        
        // Swap button
        IconButton(
            onClick = {
                val temp = sourceLanguage
                sourceLanguage = targetLanguage
                targetLanguage = temp
                onLanguageChange(sourceLanguage, targetLanguage)
            },
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.2f),
                    CircleShape
                )
                .size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = "Swap languages",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        
        // Target language
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.15f)
            )
        ) {
            Text(
                text = targetLanguage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Subtitle display area with scrolling.
 */
@Composable
private fun SubtitleDisplayArea(
    subtitles: List<SubtitleItem>,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(subtitles) { subtitle ->
                SubtitleItem(
                    subtitle = subtitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItemPlacement()
                )
            }
        }
    }
}

/**
 * Individual subtitle item with speaker direction.
 */
@Composable
private fun SubtitleItem(
    subtitle: SubtitleItem,
    modifier: Modifier = Modifier
) {
    val isRTL = subtitle.text.any { it.code in 0x0600..0x06FF } // Arabic/Urdu range
    
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (subtitle.isSource) {
            Arrangement.Start
        } else {
            Arrangement.End
        }
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (subtitle.isSource) {
                    Color.White.copy(alpha = 0.2f)
                } else {
                    Color(0xFF4CAF50).copy(alpha = 0.3f)
                }
            ),
            shape = RoundedCornerShape(
                topStart = if (subtitle.isSource) 4.dp else 16.dp,
                topEnd = if (subtitle.isSource) 16.dp else 4.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 4.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = subtitle.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = if (subtitle.isPartial) FontWeight.Normal else FontWeight.Medium,
                    fontFamily = if (isRTL) FontFamily.Serif else FontFamily.Default,
                    textAlign = if (isRTL) TextAlign.End else TextAlign.Start,
                    textDirection = if (isRTL) TextDirection.Rtl else TextDirection.Ltr
                )
                
                // Confidence indicator for partial results
                if (subtitle.isPartial && subtitle.confidence != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = subtitle.confidence,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color.White.copy(alpha = 0.6f),
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

/**
 * Control panel with start/stop button.
 */
@Composable
private fun ControlPanel(
    serviceState: FloatServiceState,
    audioState: AudioCaptureState,
    isOnline: Boolean,
    onStartStop: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = serviceState is FloatServiceState.Recording || 
                   serviceState is FloatServiceState.Processing
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicators
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Recording indicator
                    if (audioState.isCapturing) {
                        RecordingIndicator()
                    }
                    
                    // Processing indicator
                    if (audioState.isProcessing) {
                        ProcessingIndicator()
                    }
                    
                    // Speaking indicator
                    if (audioState.isSpeaking) {
                        SpeakingIndicator()
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = when {
                        audioState.isCapturing -> "Listening..."
                        audioState.isProcessing -> "Processing..."
                        audioState.isSpeaking -> "Speaking..."
                        !isOnline -> "Offline Mode"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            
            // Start/Stop button
            FloatingActionButton(
                onClick = { onStartStop(!isActive) },
                containerColor = if (isActive) {
                    Color(0xFFF44336) // Red for stop
                } else {
                    Color(0xFF4CAF50) // Green for start
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = if (isActive) {
                        Icons.Default.Stop
                    } else {
                        Icons.Default.Mic
                    },
                    contentDescription = if (isActive) "Stop" else "Start",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Recording indicator with pulsing animation.
 */
@Composable
private fun RecordingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    Color.Red.copy(alpha = alpha),
                    CircleShape
                )
        )
        Text(
            text = "REC",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Red.copy(alpha = alpha),
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Processing indicator with rotating animation.
 */
@Composable
private fun ProcessingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = "Processing",
        tint = Color.White.copy(alpha = 0.8f),
        modifier = Modifier
            .size(16.dp)
            .rotate(rotation)
    )
}

/**
 * Speaking indicator with wave animation.
 */
@Composable
private fun SpeakingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Icon(
        imageVector = Icons.Default.VolumeUp,
        contentDescription = "Speaking",
        tint = Color.White.copy(alpha = 0.8f),
        modifier = Modifier.size(16.dp).scale(scale)
    )
}

/**
 * Privacy indicator overlay.
 */
@Composable
private fun PrivacyIndicator(
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isRecording,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Red.copy(alpha = 0.9f)
            ),
            shape = CircleShape,
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            )
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Recording",
                tint = Color.White,
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp)
            )
        }
    }
}

/**
 * Offline mode banner.
 */
@Composable
private fun OfflineModeBanner(
    reason: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF9800).copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = "Offline",
                tint = Color.White
            )
            
            Column {
                Text(
                    text = "Offline Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}