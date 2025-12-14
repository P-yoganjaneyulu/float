package com.float.app.data

import kotlinx.serialization.Serializable

/**
 * Comprehensive error model for the FLOAT application.
 * Represents all 15 failure scenarios as defined in Phase 0.
 */
sealed class ErrorModel {
    abstract val code: String
    abstract val message: String
    abstract val isRecoverable: Boolean
    abstract val suggestedAction: String
    val timestamp: Long = System.currentTimeMillis()

    // Audio Processing Errors (1-4)
    data class MicrophoneBusy(
        override val message: String = "Microphone is currently in use by another application"
    ) : ErrorModel() {
        override val code = "AUDIO_MIC_BUSY"
        override val isRecoverable = true
        override val suggestedAction = "Close other apps using microphone and try again"
    }

    data class AudioRecordFailure(
        override val message: String = "Failed to initialize audio recording"
    ) : ErrorModel() {
        override val code = "AUDIO_RECORD_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Restart the app and check microphone permissions"
    }

    data class AudioProcessingError(
        override val message: String = "Error processing audio stream"
    ) : ErrorModel() {
        override val code = "AUDIO_PROCESSING_ERROR"
        override val isRecoverable = true
        override val suggestedAction = "Restart audio capture and check device audio settings"
    }

    data class EchoCancellationFailure(
        override val message: String = "Echo cancellation system failed"
    ) : ErrorModel() {
        override val code = "AUDIO_EAC_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Move to quieter environment or disable echo cancellation"
    }

    // Speech Recognition Errors (5-7)
    data class VoskModelCorruption(
        override val message: String = "Vosk speech model is corrupted or missing"
    ) : ErrorModel() {
        override val code = "STT_MODEL_CORRUPTION"
        override val isRecoverable = false
        override val suggestedAction = "Reinstall app to restore speech models"
    }

    data class VoskInitializationFailure(
        override val message: String = "Failed to initialize Vosk speech recognition"
    ) : ErrorModel() {
        override val code = "STT_INIT_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Restart app and check available storage"
    }

    data class SpeechRecognitionTimeout(
        override val message: String = "Speech recognition timed out"
    ) : ErrorModel() {
        override val code = "STT_TIMEOUT"
        override val isRecoverable = true
        override val suggestedAction = "Speak more clearly and check microphone"
    }

    // Network and WebSocket Errors (8-11)
    data class WebSocketDisconnected(
        override val message: String = "WebSocket connection lost"
    ) : ErrorModel() {
        override val code = "WS_DISCONNECTED"
        override val isRecoverable = true
        override val suggestedAction = "Check internet connection and retry"
    }

    data class WebSocketConnectionFailure(
        override val message: String = "Failed to establish WebSocket connection"
    ) : ErrorModel() {
        override val code = "WS_CONNECTION_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Check server status and network connectivity"
    }

    data class NetworkTimeout(
        override val message: String = "Network request timed out"
    ) : ErrorModel() {
        override val code = "NETWORK_TIMEOUT"
        override val isRecoverable = true
        override val suggestedAction = "Check network connection and try again"
    }

    data class ServerError(
        override val message: String = "Translation server encountered an error"
    ) : ErrorModel() {
        override val code = "SERVER_ERROR"
        override val isRecoverable = true
        override val suggestedAction = "Try again later or contact support"
    }

    // Translation and TTS Errors (12-15)
    data class TranslationFailure(
        override val message: String = "Translation service failed to process request"
    ) : ErrorModel() {
        override val code = "TRANSLATION_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Try again with different language pair"
    }

    data class UnsupportedLanguagePair(
        override val message: String = "Selected language pair is not supported"
    ) : ErrorModel() {
        override val code = "UNSUPPORTED_LANGUAGE_PAIR"
        override val isRecoverable = true
        override val suggestedAction = "Choose a supported language combination"
    }

    data class TTSEngineFailure(
        override val message: String = "Text-to-speech engine failed"
    ) : ErrorModel() {
        override val code = "TTS_ENGINE_FAILURE"
        override val isRecoverable = true
        override val suggestedAction = "Restart TTS engine or select different voice"
    }

    data class TTSLanguageNotSupported(
        override val message: String = "TTS does not support selected language"
    ) : ErrorModel() {
        override val code = "TTS_LANGUAGE_NOT_SUPPORTED"
        override val isRecoverable = true
        override val suggestedAction = "Select a different TTS language"
    }

    // System and Permission Errors
    data class PermissionDenied(
        override val message: String = "Required permission was denied"
    ) : ErrorModel() {
        override val code = "PERMISSION_DENIED"
        override val isRecoverable = true
        override val suggestedAction = "Grant required permissions in settings"
    }

    data class ServiceNotAvailable(
        override val message: String = "Required system service is not available"
    ) : ErrorModel() {
        override val code = "SERVICE_NOT_AVAILABLE"
        override val isRecoverable = true
        override val suggestedAction = "Restart device or check system settings"
    }

    data class StorageError(
        override val message: String = "Storage operation failed"
    ) : ErrorModel() {
        override val code = "STORAGE_ERROR"
        override val isRecoverable = true
        override val suggestedAction = "Free up storage space and retry"
    }

    data class ConfigurationError(
        override val message: String = "Application configuration error"
    ) : ErrorModel() {
        override val code = "CONFIGURATION_ERROR"
        override val isRecoverable = false
        override val suggestedAction = "Reinstall application"
    }

    data class UnknownError(
        override val message: String = "An unknown error occurred"
    ) : ErrorModel() {
        override val code = "UNKNOWN_ERROR"
        override val isRecoverable = true
        override val suggestedAction = "Restart application and try again"
    }

    companion object {
        /**
         * Create error model from server error code.
         */
        fun fromServerError(errorCode: String, errorMessage: String? = null): ErrorModel {
            return when (errorCode) {
                "MIC_BUSY" -> MicrophoneBusy(errorMessage ?: "Microphone is busy")
                "AUDIO_RECORD_FAILURE" -> AudioRecordFailure(errorMessage ?: "Audio recording failed")
                "STT_MODEL_CORRUPTION" -> VoskModelCorruption(errorMessage ?: "STT model corrupted")
                "TRANSLATION_FAILURE" -> TranslationFailure(errorMessage ?: "Translation failed")
                "UNSUPPORTED_LANGUAGE" -> UnsupportedLanguagePair(errorMessage ?: "Language not supported")
                "TTS_FAILURE" -> TTSEngineFailure(errorMessage ?: "TTS engine failed")
                "NETWORK_TIMEOUT" -> NetworkTimeout(errorMessage ?: "Network timeout")
                "SERVER_ERROR" -> ServerError(errorMessage ?: "Server error")
                else -> UnknownError(errorMessage ?: "Unknown error: $errorCode")
            }
        }

        /**
         * Create error model from exception.
         */
        fun fromException(exception: Throwable): ErrorModel {
            return when (exception) {
                is SecurityException -> PermissionDenied("Permission denied: ${exception.message}")
                is java.io.IOException -> NetworkTimeout("Network error: ${exception.message}")
                is IllegalStateException -> ConfigurationError("Configuration error: ${exception.message}")
                else -> UnknownError("Error: ${exception.message}")
            }
        }

        /**
         * Get all error codes for testing purposes.
         */
        fun getAllErrorCodes(): List<String> {
            return listOf(
                "AUDIO_MIC_BUSY",
                "AUDIO_RECORD_FAILURE", 
                "AUDIO_PROCESSING_ERROR",
                "AUDIO_EAC_FAILURE",
                "STT_MODEL_CORRUPTION",
                "STT_INIT_FAILURE",
                "STT_TIMEOUT",
                "WS_DISCONNECTED",
                "WS_CONNECTION_FAILURE",
                "NETWORK_TIMEOUT",
                "SERVER_ERROR",
                "TRANSLATION_FAILURE",
                "UNSUPPORTED_LANGUAGE_PAIR",
                "TTS_ENGINE_FAILURE",
                "TTS_LANGUAGE_NOT_SUPPORTED",
                "PERMISSION_DENIED",
                "SERVICE_NOT_AVAILABLE",
                "STORAGE_ERROR",
                "CONFIGURATION_ERROR",
                "UNKNOWN_ERROR"
            )
        }
    }
}

/**
 * Error severity levels for UI display and logging.
 */
enum class ErrorSeverity {
    LOW,      // Minor issues, user can continue
    MEDIUM,   // Important issues, may affect functionality
    HIGH,     // Critical issues, prevents core functionality
    CRITICAL  // System-level issues, requires immediate attention
}

/**
 * Extended error information with severity and context.
 */
data class ErrorInfo(
    val error: ErrorModel,
    val severity: ErrorSeverity,
    val context: String? = null,
    val stackTrace: String? = null
) {
    /**
     * Get user-friendly error message based on severity.
     */
    fun getUserMessage(): String {
        return when (severity) {
            ErrorSeverity.LOW -> error.message
            ErrorSeverity.MEDIUM -> "Warning: ${error.message}"
            ErrorSeverity.HIGH -> "Error: ${error.message}"
            ErrorSeverity.CRITICAL -> "Critical Error: ${error.message}"
        }
    }

    /**
     * Check if error should be logged.
     */
    fun shouldLog(): Boolean {
        return severity != ErrorSeverity.LOW
    }

    /**
     * Check if error should be shown to user.
     */
    fun shouldShowToUser(): Boolean {
        return severity != ErrorSeverity.LOW
    }
}