package com.float.app.network

import com.float.app.data.*
import com.float.app.di.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket connection state with additional metadata.
 */
data class WebSocketConnectionState(
    val state: ConnectionState,
    val sessionId: String? = null,
    val lastError: ErrorModel? = null,
    val reconnectAttempts: Int = 0,
    val lastConnectedTime: Long = 0L
)

/**
 * WebSocket message types for bi-directional communication.
 */
enum class MessageType {
    PARTIAL_TRANSCRIPT,
    FINAL_TRANSCRIPT,
    ERROR,
    KEEPALIVE,
    AUDIO_CHUNK,
    SESSION_INIT
}

/**
 * Robust WebSocket client for the FLOAT translation service.
 * Implements exponential backoff reconnection, proper JSON contract enforcement,
 * and resilient error handling for production use.
 */
@Singleton
class TranslatorWebSocket @Inject constructor(
    private val dispatchers: AppDispatchers
) {
    private val webSocketScope = CoroutineScope(dispatchers.io)
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private var keepaliveJob: Job? = null
    
    private val _connectionState = MutableStateFlow(
        WebSocketConnectionState(ConnectionState.DISCONNECTED)
    )
    val connectionState: StateFlow<WebSocketConnectionState> = _connectionState.asStateFlow()
    
    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult.asStateFlow()
    
    private val _serverError = MutableStateFlow<ErrorModel?>(null)
    val serverError: StateFlow<ErrorModel?> = _serverError.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val isConnecting = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    
    private var currentSession: TranslationSession? = null
    private var chunkIndex = 0
    private var reconnectDelay = 1000L // Start with 1 second
    private var reconnectAttempts = 0
    
    // Default configuration
    private var config = WebSocketConfig(
        serverUrl = "ws://localhost",
        port = 8080,
        endpoint = "/ws/translation"
    )
    
    // WebSocket listener implementation
    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocketScope.launch {
                handleConnectionOpen()
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocketScope.launch {
                handleTextMessage(text)
            }
        }
        
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocketScope.launch {
                handleBinaryMessage(bytes)
            }
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocketScope.launch {
                handleConnectionClosing(code, reason)
            }
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            webSocketScope.launch {
                handleConnectionClosed(code, reason)
            }
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            webSocketScope.launch {
                handleConnectionFailure(t, response)
            }
        }
    }
    
    /**
     * Initialize WebSocket client with configuration.
     */
    fun initialize(webSocketConfig: WebSocketConfig) {
        config = webSocketConfig
        
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectionTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(config.connectionTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(config.connectionTimeout, TimeUnit.MILLISECONDS)
            .pingInterval(30000, TimeUnit.MILLISECONDS) // 30 second ping interval
            .retryOnConnectionFailure(true)
            .build()
    }
    
    /**
     * Connect to WebSocket server with session initialization.
     */
    suspend fun connect(languagePair: LanguagePair): Boolean {
        if (isConnecting.compareAndSet(false, true)) {
            try {
                _connectionState.value = WebSocketConnectionState(
                    state = ConnectionState.CONNECTING,
                    reconnectAttempts = reconnectAttempts
                )
                
                // Create new session
                currentSession = TranslationSession(languagePair = languagePair)
                chunkIndex = 0
                
                val serverUrl = "${config.serverUrl}:${config.port}${config.endpoint}"
                val request = Request.Builder()
                    .url(serverUrl)
                    .addHeader("X-Session-ID", currentSession?.sessionId ?: "")
                    .addHeader("X-Language-Pair", "${languagePair.source}-${languagePair.target}")
                    .build()
                
                webSocket = okHttpClient?.newWebSocket(request, webSocketListener)
                
                // Wait for connection result
                var attempts = 0
                while (isConnecting.get() && attempts < 100) {
                    delay(100)
                    attempts++
                }
                
                return _isConnected.value
            } catch (e: Exception) {
                handleConnectionFailure(e, null)
                return false
            } finally {
                isConnecting.set(false)
            }
        }
        return false
    }
    
    /**
     * Disconnect from WebSocket server.
     */
    suspend fun disconnect() {
        shouldReconnect.set(false)
        keepaliveJob?.cancel()
        
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        
        _connectionState.value = WebSocketConnectionState(ConnectionState.DISCONNECTED)
        _isConnected.value = false
    }
    
    /**
     * Send audio chunk for translation.
     */
    suspend fun sendAudioChunk(audioData: ByteArray): Boolean {
        if (!_isConnected.value) {
            return false
        }
        
        return try {
            val session = currentSession ?: return false
            
            // Encode audio data to base64
            val base64Audio = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)
            
            val outboundMessage = OutboundMessage(
                session_id = session.sessionId,
                chunk_index = chunkIndex++,
                language_pair = session.languagePair,
                audio_chunk = base64Audio
            )
            
            val messageJson = json.encodeToString(outboundMessage)
            val success = webSocket?.send(messageJson) ?: false
            
            if (success) {
                // Update session stats
                currentSession = session.copy(totalChunks = chunkIndex)
            }
            
            success
        } catch (e: Exception) {
            handleConnectionFailure(e, null)
            false
        }
    }
    
    /**
     * Send keepalive message to maintain connection.
     */
    private suspend fun sendKeepalive() {
        if (!_isConnected.value) return
        
        try {
            val session = currentSession ?: return
            val keepalive = KeepaliveMessage(session_id = session.sessionId)
            val messageJson = json.encodeToString(keepalive)
            webSocket?.send(messageJson)
        } catch (e: Exception) {
            // Keepalive failures are handled by the WebSocket listener
        }
    }
    
    /**
     * Handle successful connection opening.
     */
    private suspend fun handleConnectionOpen() {
        isConnecting.set(false)
        _isConnected.value = true
        reconnectAttempts = 0
        reconnectDelay = config.baseReconnectDelay
        
        _connectionState.value = WebSocketConnectionState(
            state = ConnectionState.CONNECTED,
            sessionId = currentSession?.sessionId,
            reconnectAttempts = 0,
            lastConnectedTime = System.currentTimeMillis()
        )
        
        // Start keepalive job
        keepaliveJob?.cancel()
        keepaliveJob = webSocketScope.launch {
            while (isActive && _isConnected.value) {
                delay(config.keepaliveInterval)
                sendKeepalive()
            }
        }
    }
    
    /**
     * Handle incoming text messages from server.
     */
    private suspend fun handleTextMessage(text: String) {
        try {
            val inboundMessage = json.decodeFromString<InboundMessage>(text)
            
            when (inboundMessage.message_type) {
                "partial_transcript" -> {
                    inboundMessage.partial_transcript?.let { transcript ->
                        val result = TranslationResult(
                            sessionId = inboundMessage.session_id,
                            chunkIndex = inboundMessage.chunk_index ?: 0,
                            sourceText = "", // Server doesn't return source text in partial
                            translatedText = transcript,
                            isPartial = true,
                            timestamp = inboundMessage.timestamp
                        )
                        _translationResult.value = result
                    }
                }
                
                "final_transcript" -> {
                    inboundMessage.final_transcript?.let { transcript ->
                        val result = TranslationResult(
                            sessionId = inboundMessage.session_id,
                            chunkIndex = inboundMessage.chunk_index ?: 0,
                            sourceText = "", // Server doesn't return source text
                            translatedText = transcript,
                            isPartial = false,
                            timestamp = inboundMessage.timestamp
                        )
                        _translationResult.value = result
                        
                        // Update session stats
                        currentSession?.let { session ->
                            currentSession = session.copy(
                                successfulTranslations = session.successfulTranslations + 1
                            )
                        }
                    }
                }
                
                "error" -> {
                    val error = ErrorModel.fromServerError(
                        inboundMessage.error_code ?: "UNKNOWN_ERROR",
                        inboundMessage.error_message
                    )
                    _serverError.value = error
                    _connectionState.value = _connectionState.value.copy(lastError = error)
                }
                
                "keepalive" -> {
                    // Server keepalive, no action needed
                }
                
                else -> {
                    // Unknown message type, log but don't crash
                }
            }
        } catch (e: Exception) {
            // JSON parsing error, create appropriate error
            val error = ErrorModel.ConfigurationError("Failed to parse server message: ${e.message}")
            _serverError.value = error
        }
    }
    
    /**
     * Handle incoming binary messages (if any).
     */
    private suspend fun handleBinaryMessage(bytes: ByteString) {
        // Currently not used, but could handle binary audio data if needed
    }
    
    /**
     * Handle connection closing.
     */
    private suspend fun handleConnectionClosing(code: Int, reason: String) {
        _isConnected.value = false
        keepaliveJob?.cancel()
    }
    
    /**
     * Handle connection closed.
     */
    private suspend fun handleConnectionClosed(code: Int, reason: String) {
        _isConnected.value = false
        keepaliveJob?.cancel()
        
        if (shouldReconnect.get() && code != 1000) {
            // Initiate reconnection with exponential backoff
            scheduleReconnection()
        } else {
            _connectionState.value = WebSocketConnectionState(ConnectionState.DISCONNECTED)
        }
    }
    
    /**
     * Handle connection failure.
     */
    private suspend fun handleConnectionFailure(throwable: Throwable, response: Response?) {
        isConnecting.set(false)
        _isConnected.value = false
        keepaliveJob?.cancel()
        
        val error = when (throwable) {
            is java.net.SocketTimeoutException -> ErrorModel.NetworkTimeout("Connection timeout")
            is java.net.ConnectException -> ErrorModel.WebSocketConnectionFailure("Cannot connect to server")
            is java.io.IOException -> ErrorModel.WebSocketDisconnected("Network error: ${throwable.message}")
            else -> ErrorModel.fromException(throwable)
        }
        
        _serverError.value = error
        _connectionState.value = WebSocketConnectionState(
            state = ConnectionState.ERROR,
            lastError = error,
            reconnectAttempts = reconnectAttempts
        )
        
        if (shouldReconnect.get()) {
            scheduleReconnection()
        }
    }
    
    /**
     * Schedule reconnection with exponential backoff.
     */
    private suspend fun scheduleReconnection() {
        if (reconnectAttempts >= config.reconnectAttempts) {
            _connectionState.value = WebSocketConnectionState(
                state = ConnectionState.DISCONNECTED,
                reconnectAttempts = reconnectAttempts
            )
            return
        }
        
        _connectionState.value = WebSocketConnectionState(
            state = ConnectionState.RECONNECTING,
            reconnectAttempts = reconnectAttempts,
            lastError = _connectionState.value.lastError
        )
        
        // Calculate exponential backoff delay
        val delay = minOf(reconnectDelay, config.maxReconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(config.maxReconnectDelay)
        reconnectAttempts++
        
        delay(delay)
        
        if (shouldReconnect.get()) {
            currentSession?.let { session ->
                connect(session.languagePair)
            }
        }
    }
    
    /**
     * Get current connection statistics.
     */
    fun getConnectionStats(): ConnectionStats {
        return ConnectionStats(
            isConnected = _isConnected.value,
            sessionId = currentSession?.sessionId,
            reconnectAttempts = reconnectAttempts,
            lastConnectedTime = _connectionState.value.lastConnectedTime,
            totalChunks = currentSession?.totalChunks ?: 0,
            successfulTranslations = currentSession?.successfulTranslations ?: 0,
            failedTranslations = currentSession?.failedTranslations ?: 0
        )
    }
    
    /**
     * Reset reconnection state.
     */
    fun resetReconnectionState() {
        reconnectAttempts = 0
        reconnectDelay = config.baseReconnectDelay
        shouldReconnect.set(true)
    }
    
    /**
     * Force reconnection.
     */
    suspend fun forceReconnect(): Boolean {
        disconnect()
        resetReconnectionState()
        delay(1000)
        
        currentSession?.let { session ->
            return connect(session.languagePair)
        }
        return false
    }
    
    /**
     * Connection statistics data class.
     */
    data class ConnectionStats(
        val isConnected: Boolean,
        val sessionId: String?,
        val reconnectAttempts: Int,
        val lastConnectedTime: Long,
        val totalChunks: Int,
        val successfulTranslations: Int,
        val failedTranslations: Int
    )
    
    /**
     * Release WebSocket resources.
     */
    fun release() {
        webSocketScope.launch {
            disconnect()
            okHttpClient?.dispatcher?.executorService?.shutdown()
            okHttpClient?.connectionPool?.evictAll()
            webSocketScope.cancel()
        }
    }
}