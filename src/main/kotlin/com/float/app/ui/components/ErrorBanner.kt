package com.float.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.float.app.data.ErrorModel
import com.float.app.data.ErrorSeverity
import kotlinx.coroutines.delay

/**
 * Error banner configuration.
 */
data class ErrorBannerConfig(
    val autoDismissDelay: Long = 5000L,  // 5 seconds
    val showIcon: Boolean = true,
    val showDismissButton: Boolean = true,
    val maxLines: Int = 3,
    val enableAnimation: Boolean = true
)

/**
 * Reusable error banner component for displaying error messages.
 * Handles visibility, dismissal, and different error severity levels.
 */
@Composable
fun ErrorBanner(
    error: ErrorModel?,
    config: ErrorBannerConfig = ErrorBannerConfig(),
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    
    // Handle error changes
    LaunchedEffect(error) {
        if (error != null) {
            isVisible = true
            
            // Auto-dismiss after delay if enabled
            if (config.autoDismissDelay > 0 && error.isRecoverable) {
                delay(config.autoDismissDelay)
                if (isVisible) {
                    isVisible = false
                    delay(300) // Wait for animation
                    onDismiss()
                }
            }
        } else {
            isVisible = false
        }
    }
    
    // Animated visibility
    AnimatedVisibility(
        visible = error != null && isVisible,
        enter = if (config.enableAnimation) {
            expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        } else {
            expandVertically()
        },
        exit = if (config.enableAnimation) {
            shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        } else {
            shrinkVertically()
        },
        modifier = modifier
    ) {
        error?.let { errorModel ->
            ErrorBannerContent(
                error = errorModel,
                config = config,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Error banner content with icon, message, and dismiss button.
 */
@Composable
private fun ErrorBannerContent(
    error: ErrorModel,
    config: ErrorBannerConfig,
    onDismiss: () -> Unit
) {
    val severity = getErrorSeverity(error)
    val colors = getErrorColors(severity)
    val icon = getErrorIcon(severity)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.containerColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Error icon
            if (config.showIcon) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Error message
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = getUserFriendlyErrorMessage(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = config.maxLines,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Show suggested action if available
                if (error.suggestedAction.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "💡 ${error.suggestedAction}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textColor.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Dismiss button
            if (config.showDismissButton) {
                IconButton(
                    onClick = {
                        onDismiss()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = colors.iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Multiple error banners stack for handling multiple errors.
 */
@Composable
fun ErrorBannerStack(
    errors: List<ErrorModel>,
    config: ErrorBannerConfig = ErrorBannerConfig(),
    onDismissError: (ErrorModel) -> Unit = {},
    onDismissAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        errors.take(3).forEach { error ->
            ErrorBanner(
                error = error,
                config = config.copy(
                    autoDismissDelay = if (errors.size > 1) 3000L else config.autoDismissDelay
                ),
                onDismiss = { onDismissError(error) }
            )
        }
        
        // Show indicator for additional errors
        if (errors.size > 3) {
            Text(
                text = "+${errors.size - 3} more errors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .align(Alignment.End)
            )
        }
    }
}

/**
 * Compact error banner for limited space scenarios.
 */
@Composable
fun CompactErrorBanner(
    error: ErrorModel?,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ErrorBanner(
        error = error,
        config = ErrorBannerConfig(
            autoDismissDelay = 3000L,
            showIcon = true,
            showDismissButton = false,
            maxLines = 1,
            enableAnimation = true
        ),
        onDismiss = onDismiss,
        modifier = modifier
    )
}

/**
 * Full-screen error view for critical errors.
 */
@Composable
fun FullScreenError(
    error: ErrorModel,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val severity = getErrorSeverity(error)
    val colors = getErrorColors(severity)
    val icon = getErrorIcon(severity)
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.containerColor.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.iconColor,
                modifier = Modifier.size(64.dp)
            )
            
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textColor,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = getUserFriendlyErrorMessage(error),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            if (error.suggestedAction.isNotBlank()) {
                Text(
                    text = "Suggested Action:\n${error.suggestedAction}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textColor.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (error.isRecoverable) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.iconColor
                        )
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
                
                OutlinedButton(
                    onClick = onDismiss,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, colors.iconColor
                    )
                ) {
                    Text("Dismiss", color = colors.iconColor)
                }
            }
        }
    }
}

/**
 * Get error severity based on error type.
 */
private fun getErrorSeverity(error: ErrorModel): ErrorSeverity {
    return when (error) {
        is ErrorModel.MicrophoneBusy -> ErrorSeverity.MEDIUM
        is ErrorModel.AudioRecordFailure -> ErrorSeverity.HIGH
        is ErrorModel.AudioProcessingError -> ErrorSeverity.MEDIUM
        is ErrorModel.EchoCancellationFailure -> ErrorSeverity.LOW
        is ErrorModel.VoskModelCorruption -> ErrorSeverity.CRITICAL
        is ErrorModel.VoskInitializationFailure -> ErrorSeverity.HIGH
        is ErrorModel.SpeechRecognitionTimeout -> ErrorSeverity.MEDIUM
        is ErrorModel.WebSocketDisconnected -> ErrorSeverity.MEDIUM
        is ErrorModel.WebSocketConnectionFailure -> ErrorSeverity.HIGH
        is ErrorModel.NetworkTimeout -> ErrorSeverity.MEDIUM
        is ErrorModel.ServerError -> ErrorSeverity.HIGH
        is ErrorModel.TranslationFailure -> ErrorSeverity.MEDIUM
        is ErrorModel.UnsupportedLanguagePair -> ErrorSeverity.LOW
        is ErrorModel.TTSEngineFailure -> ErrorSeverity.MEDIUM
        is ErrorModel.TTSLanguageNotSupported -> ErrorSeverity.LOW
        is ErrorModel.PermissionDenied -> ErrorSeverity.HIGH
        is ErrorModel.ServiceNotAvailable -> ErrorSeverity.CRITICAL
        is ErrorModel.StorageError -> ErrorSeverity.MEDIUM
        is ErrorModel.ConfigurationError -> ErrorSeverity.CRITICAL
        is ErrorModel.UnknownError -> ErrorSeverity.MEDIUM
    }
}

/**
 * Get colors based on error severity.
 */
private fun getErrorColors(severity: ErrorSeverity): ErrorColors {
    return when (severity) {
        ErrorSeverity.LOW -> ErrorColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ErrorSeverity.MEDIUM -> ErrorColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
            iconColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        ErrorSeverity.HIGH -> ErrorColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer,
            iconColor = MaterialTheme.colorScheme.error
        )
        ErrorSeverity.CRITICAL -> ErrorColors(
            containerColor = Color(0xFFFF5252), // Red
            textColor = Color.White,
            iconColor = Color.White
        )
    }
}

/**
 * Get icon based on error severity.
 */
private fun getErrorIcon(severity: ErrorSeverity): ImageVector {
    return when (severity) {
        ErrorSeverity.LOW -> Icons.Default.Info
        ErrorSeverity.MEDIUM -> Icons.Default.Warning
        ErrorSeverity.HIGH, ErrorSeverity.CRITICAL -> Icons.Default.Error
    }
}

/**
 * Get user-friendly error message.
 */
private fun getUserFriendlyErrorMessage(error: ErrorModel): String {
    return when (error) {
        is ErrorModel.MicrophoneBusy -> "Microphone is being used by another app"
        is ErrorModel.AudioRecordFailure -> "Cannot access microphone"
        is ErrorModel.AudioProcessingError -> "Audio processing failed"
        is ErrorModel.EchoCancellationFailure -> "Echo cancellation not working"
        is ErrorModel.VoskModelCorruption -> "Speech recognition model is damaged"
        is ErrorModel.VoskInitializationFailure -> "Speech recognition failed to start"
        is ErrorModel.SpeechRecognitionTimeout -> "Speech not detected"
        is ErrorModel.WebSocketDisconnected -> "Lost connection to translation service"
        is ErrorModel.WebSocketConnectionFailure -> "Cannot connect to translation service"
        is ErrorModel.NetworkTimeout -> "Network connection too slow"
        is ErrorModel.ServerError -> "Translation service error"
        is ErrorModel.TranslationFailure -> "Translation failed"
        is ErrorModel.UnsupportedLanguagePair -> "Language not supported"
        is ErrorModel.TTSEngineFailure -> "Voice synthesis failed"
        is ErrorModel.TTSLanguageNotSupported -> "Voice not available for this language"
        is ErrorModel.PermissionDenied -> "Permission required"
        is ErrorModel.ServiceNotAvailable -> "System service unavailable"
        is ErrorModel.StorageError -> "Storage error"
        is ErrorModel.ConfigurationError -> "App configuration error"
        is ErrorModel.UnknownError -> "Something went wrong"
    }
}

/**
 * Error colors data class.
 */
private data class ErrorColors(
    val containerColor: Color,
    val textColor: Color,
    val iconColor: Color
)