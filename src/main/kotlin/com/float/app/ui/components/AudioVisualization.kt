package com.float.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Real-time audio waveform visualization component.
 * Provides immediate visual feedback for audio capture and processing.
 */
@Composable
fun AudioWaveform(
    audioData: StateFlow<ByteArray?>,
    isProcessing: StateFlow<Boolean>,
    modifier: Modifier = Modifier
) {
    val currentAudioData by audioData.collectAsState()
    val isCurrentlyProcessing by isProcessing.collectAsState()
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                Color.Black.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isCurrentlyProcessing && currentAudioData != null) {
            WaveformCanvas(
                audioData = currentAudioData,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Idle state - show flat line
            IdleWaveform(
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Canvas component for drawing real-time audio waveform.
 */
@Composable
private fun WaveformCanvas(
    audioData: ByteArray,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        
        // Convert audio bytes to samples
        val samples = audioData.chunked(2).map { bytes ->
            ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        }
        
        if (samples.isNotEmpty()) {
            // Create waveform path
            val path = Path()
            val stepX = width / samples.size
            
            path.moveTo(0f, centerY)
            
            samples.forEachIndexed { index, sample ->
                val x = (index * stepX).toFloat()
                val normalizedSample = sample.toFloat() / 32768f
                val y = centerY - (normalizedSample * centerY * 0.8f) // Scale to 80% of height
                
                if (index == 0) {
                    path.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            
            // Draw waveform with gradient effect
            drawPath(
                path = path,
                color = Color.Cyan.copy(alpha = 0.8f),
                style = androidx.compose.ui.graphics.drawscope.DrawStyle.Stroke(
                    width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.drawscope.StrokeCap.Round
                )
            )
            
            // Add glow effect
            drawPath(
                path = path,
                color = Color.Cyan.copy(alpha = 0.3f),
                style = androidx.compose.ui.graphics.drawscope.DrawStyle.Stroke(
                    width = 4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.drawscope.StrokeCap.Round
                )
            )
        }
    }
}

/**
 * Idle waveform display when no audio is being processed.
 */
@Composable
private fun IdleWaveform(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerY = size.height / 2
        
        drawLine(
            start = androidx.compose.ui.geometry.Offset(0f, centerY),
            end = androidx.compose.ui.geometry.Offset(size.width, centerY),
            color = Color.Gray.copy(alpha = 0.5f),
            strokeWidth = 1.dp.toPx(),
            cap = androidx.compose.ui.graphics.drawscope.StrokeCap.Round
        )
    }
}

/**
 * Microphone activity indicator with animated glow effect.
 * Provides immediate visual feedback for microphone state.
 */
@Composable
fun MicrophoneIndicator(
    isRecording: StateFlow<Boolean>,
    isProcessing: StateFlow<Boolean>,
    modifier: Modifier = Modifier
) {
    val isCurrentlyRecording by isRecording.collectAsState()
    val isCurrentlyProcessing by isProcessing.collectAsState()
    
    val isActive = isCurrentlyRecording || isCurrentlyProcessing
    val targetColor = when {
        isCurrentlyRecording -> Color.Red
        isCurrentlyProcessing -> Color.Green
        else -> Color.Gray
    }
    
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow effect
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        targetColor.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
        }
        
        // Inner circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    targetColor,
                    shape = CircleShape
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Microphone icon
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Mic,
                contentDescription = "Microphone",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        
        // Pulsing animation for active state
        if (isActive) {
            var pulseScale by remember { mutableStateOf(1f) }
            
            LaunchedEffect(isActive) {
                while (true) {
                    animate(
                        initialValue = 1f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        )
                    ) { scale, _ ->
                        pulseScale = scale
                    }
                }
            }
            
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulseScale)
                    .background(
                        targetColor.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Latency indicator showing real-time performance metrics.
 * Critical for monitoring system performance and user experience.
 */
@Composable
fun LatencyIndicator(
    latencyMetrics: StateFlow<com.float.app.audio.SeamlessM4T_Processor.LatencyMetrics?>,
    modifier: Modifier = Modifier
) {
    val currentMetrics by latencyMetrics.collectAsState()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "System Performance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            currentMetrics?.let { metrics ->
                LatencyMetricRow(
                    label = "Total Latency",
                    value = "${metrics.totalLatencyMs.toInt()}ms",
                    isGood = metrics.totalLatencyMs < 1500
                )
                
                LatencyMetricRow(
                    label = "Network Latency",
                    value = "${metrics.networkLatencyMs.toInt()}ms",
                    isGood = metrics.networkLatencyMs < 800
                )
                
                LatencyMetricRow(
                    label = "Processing Latency",
                    value = "${metrics.processingLatencyMs.toInt()}ms",
                    isGood = metrics.processingLatencyMs < 700
                )
            } ?: run {
                Text(
                    text = "Waiting for metrics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Individual latency metric row with color coding.
 */
@Composable
private fun LatencyMetricRow(
    label: String,
    value: String,
    isGood: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isGood -> Color.Green
                else -> Color.Red
            }
        )
    }
}

/**
 * Speech activity indicator showing real-time VAD state.
 */
@Composable
fun SpeechActivityIndicator(
    isSpeechActive: StateFlow<Boolean>,
    speechProbability: StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    val isCurrentlyActive by isSpeechActive.collectAsState()
    val currentProbability by speechProbability.collectAsState()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentlyActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Speech Detection",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = "Probability: ${(currentProbability * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Activity indicator dot
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        color = when {
                            isCurrentlyActive -> Color.Green
                            else -> Color.Gray
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}