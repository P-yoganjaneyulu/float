package com.float.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * WebSocket connection states for the translator service.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Language pair configuration for translation.
 */
@Serializable
data class LanguagePair(
    val source: String,    // Source language code (e.g., "en", "hi", "ta")
    val target: String     // Target language code (e.g., "en", "hi", "ta")
)

/**
 * WebSocket outbound message structure.
 * Follows the bi-directional JSON contract defined in Phase 0.
 */
@Serializable
data class OutboundMessage(
    val session_id: String,
    val chunk_index: Int,
    val language_pair: LanguagePair,
    val audio_chunk: String,  // Base64 encoded audio data
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * WebSocket inbound message structure.
 * Handles partial transcripts, final transcripts, errors, and keepalives.
 */
@Serializable
data class InboundMessage(
    val session_id: String,
    val message_type: String,  // "partial_transcript", "final_transcript", "error", "keepalive"
    val chunk_index: Int? = null,
    val partial_transcript: String? = null,
    val final_transcript: String? = null,
    val error_code: String? = null,
    val error_message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Translation session metadata.
 */
@Serializable
data class TranslationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val languagePair: LanguagePair,
    val startTime: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val totalChunks: Int = 0,
    val successfulTranslations: Int = 0,
    val failedTranslations: Int = 0
)

/**
 * Translation result from the cloud service.
 */
@Serializable
data class TranslationResult(
    val sessionId: String,
    val chunkIndex: Int,
    val sourceText: String,
    val translatedText: String,
    val isPartial: Boolean,
    val confidence: Float? = null,
    val latency: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * WebSocket server configuration.
 */
@Serializable
data class WebSocketConfig(
    val serverUrl: String,
    val port: Int = 8080,
    val endpoint: String = "/ws/translation",
    val reconnectAttempts: Int = 10,
    val baseReconnectDelay: Long = 1000L,  // 1 second
    val maxReconnectDelay: Long = 60000L, // 60 seconds
    val keepaliveInterval: Long = 30000L,  // 30 seconds
    val connectionTimeout: Long = 10000L   // 10 seconds
)

/**
 * Audio chunk metadata for transmission.
 */
@Serializable
data class AudioChunk(
    val data: String,        // Base64 encoded audio data
    val index: Int,
    val duration: Int,       // Duration in milliseconds
    val sampleRate: Int,
    val channels: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Keepalive message for connection maintenance.
 */
@Serializable
data class KeepaliveMessage(
    val session_id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val client_status: String = "active"
)

/**
 * Server error response structure.
 */
@Serializable
data class ServerError(
    val error_code: String,
    val error_message: String,
    val session_id: String,
    val retry_after: Long? = null,  // Suggested retry delay in milliseconds
    val timestamp: Long = System.currentTimeMillis()
)