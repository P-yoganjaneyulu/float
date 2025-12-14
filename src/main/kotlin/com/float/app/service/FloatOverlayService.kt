package com.float.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.float.app.audio.StreamingAudioBuffer
import com.float.app.audio.StreamingAudioBuffer.LatencyCallback
import com.float.app.di.AppDispatchers
import com.float.app.manager.LanguagePairManager
import com.float.app.network.TranslatorWebSocket
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Service state for tracking the FLOAT overlay service status.
 */
sealed class FloatServiceState {
    object Stopped : FloatServiceState()
    object Starting : FloatServiceState()
    object Active : FloatServiceState()
    object Recording : FloatServiceState()
    object Processing : FloatServiceState()
    object Error : FloatServiceState()
    data class OfflineMode(val reason: String) : FloatServiceState()
}

/**
 * Audio capture state for the service.
 */
data class AudioCaptureState(
    val isCapturing: Boolean = false,
    val isProcessing: Boolean = false,
    val isSpeaking: Boolean = false,
    val isOnline: Boolean = false,
    val lastTranscript: String = "",
    val lastTranslation: String = "",
    val lastAudioChunk: ByteArray? = null,
    val isSpeechActive: Boolean = false,
    val speechProbability: Float = 0f
)

/**
 * Main FLOAT Overlay Service with Android 13/14 compliance.
 * Integrates all audio, network, and manager components into a cohesive service.
 */
@AndroidEntryPoint
class FloatOverlayService : LifecycleService(), LatencyCallback {
    
    @Inject
    lateinit var webSocket: TranslatorWebSocket
    
    @Inject
    lateinit var languageManager: LanguagePairManager
    
    @Inject
    lateinit var streamingBuffer: StreamingAudioBuffer
    
    @Inject
    lateinit var dispatchers: AppDispatchers
    
    private val serviceScope = CoroutineScope(dispatchers.main)
    
    // Service state management
    private val _serviceState = MutableStateFlow<FloatServiceState>(FloatServiceState.Stopped)
    val serviceState: StateFlow<FloatServiceState> = _serviceState.asStateFlow()
    
    private val _audioCaptureState = MutableStateFlow(AudioCaptureState())
    val audioCaptureState: StateFlow<AudioCaptureState> = _audioCaptureState.asStateFlow()
    
    private val _currentError = MutableStateFlow<ErrorModel?>(null)
    val currentError: StateFlow<ErrorModel?> = _currentError.asStateFlow()
    
    // Internal latency tracking (for monitoring only)
    private val _internalLatencyMetrics = MutableStateFlow<InternalLatencyMetrics?>(null)
    val internalLatencyMetrics: StateFlow<InternalLatencyMetrics?> = _internalLatencyMetrics.asStateFlow()
    
    data class InternalLatencyMetrics(
        val micChunkReady: Long = 0,
        val networkSend: Long = 0,
        val networkReceive: Long = 0,
        val playbackStart: Long = 0,
        val totalLatencyMs: Float = 0f,
        val networkLatencyMs: Float = 0f,
        val processingLatencyMs: Float = 0f
    )
    
    // Service lifecycle management
    private var networkMonitoringJob: Job? = null
    private var powerResilienceJob: Job? = null
    
    // Notification management
    private lateinit var notificationManager: NotificationManager
    private val NOTIFICATION_CHANNEL_ID = "float_service_channel"
    private val NOTIFICATION_ID = 1001
    
    // Service configuration
    private var isAudioCaptureActive = false
    private var isOverlayExpanded = false
    private var isOfflineMode = false
    
    companion object {
        const val ACTION_START_SERVICE = "com.float.app.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.float.app.STOP_SERVICE"
        const val ACTION_START_CAPTURE = "com.float.app.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.float.app.STOP_CAPTURE"
        const val ACTION_TOGGLE_OVERLAY = "com.float.app.TOGGLE_OVERLAY"
        const val EXTRA_LANGUAGE_SOURCE = "extra_language_source"
        const val EXTRA_LANGUAGE_TARGET = "extra_language_target"
    }
    
    override fun onCreate() {
        super.onCreate()
        initializeNotificationChannel()
        initializeService()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_STOP_SERVICE -> handleStopService()
            ACTION_START_CAPTURE -> handleStartCapture()
            ACTION_STOP_CAPTURE -> handleStopCapture()
            ACTION_TOGGLE_OVERLAY -> handleToggleOverlay()
        }
        
        return START_STICKY // Ensure service restarts if killed by system
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // No binding needed for this service
    }
    
    /**
     * Initialize notification channel for Android 8+ compatibility.
     */
    private fun initializeNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FLOAT Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time translation service"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Initialize the service and all components.
     */
    private fun initializeService() {
        serviceScope.launch {
            _serviceState.value = FloatServiceState.Starting
            
            try {
                // Initialize core components only
                val bufferInitialized = streamingBuffer.initialize()
                
                // Set latency callback for internal tracking
                streamingBuffer.setLatencyCallback(this)
                
                if (!bufferInitialized) {
                    throw IllegalStateException("Failed to initialize core components")
                }
                
                // Start monitoring jobs
                startMonitoringJobs()
                
                _serviceState.value = FloatServiceState.Active
                
                // Start foreground service with notification
                startForeground(NOTIFICATION_ID, createServiceNotification())
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
                _serviceState.value = FloatServiceState.Error
            }
        }
    }
    
    /**
     * Setup integrations between components.
     */
    /**
     * Setup minimal component integrations.
     */
    private fun setupComponentIntegrations() {
        serviceScope.launch {
            // Network state monitoring with latency tracking
            networkMonitoringJob = launch {
                webSocket.connectionState.collect { connectionState ->
                    val isOnline = connectionState.state.name == "CONNECTED"
                    _audioCaptureState.value = _audioCaptureState.value.copy(isOnline = isOnline)
                    
                    // Handle offline fallback mode
                    if (!isOnline && isAudioCaptureActive) {
                        enterOfflineMode("Network disconnected")
                    } else if (isOnline && isOfflineMode) {
                        exitOfflineMode()
                    }
                    
                    // Update latency metrics when connection changes
                    updateLatencyMetrics()
                }
                
                // Test latency tracking
                simulateMicChunkReady()
            }
        }
    }
    
    /**
     * Update internal latency metrics for monitoring.
     */
    private fun updateLatencyMetrics() {
        val currentMetrics = _internalLatencyMetrics.value
        if (currentMetrics != null && currentMetrics.playbackStart > 0) {
            val now = System.currentTimeMillis()
            val totalLatency = now - currentMetrics.micChunkReady
            
            _internalLatencyMetrics.value = currentMetrics.copy(
                totalLatencyMs = totalLatency.toFloat(),
                networkLatencyMs = currentMetrics.networkLatencyMs,
                processingLatencyMs = currentMetrics.processingLatencyMs
            )
        }
    }
    
    /**
     * Simulate mic chunk ready for testing.
     */
    private fun simulateMicChunkReady() {
        val now = System.currentTimeMillis()
        _internalLatencyMetrics.value = InternalLatencyMetrics(
            micChunkReady = now,
            networkSend = now,
            networkReceive = now,
            playbackStart = 0,
            totalLatencyMs = 0f,
            networkLatencyMs = 0f,
            processingLatencyMs = 0f
        )
    }
    
    /**
     * Start monitoring jobs for power resilience and system health.
     */
    private fun startMonitoringJobs() {
        serviceScope.launch {
            powerResilienceJob = launch {
                while (isActive) {
                    checkSystemHealth()
                    delay(30000) // Check every 30 seconds
                }
            }
        }
    }
    
    /**
     * Handle service start with language configuration.
     */
    private fun handleStartService(intent: Intent) {
        serviceScope.launch {
            try {
                // Extract language pair from intent
                val sourceLang = intent.getStringExtra(EXTRA_LANGUAGE_SOURCE) ?: "en"
                val targetLang = intent.getStringExtra(EXTRA_LANGUAGE_TARGET) ?: "hi"
                
                // Configure language manager
                languageManager.setLanguagePair(sourceLang, targetLang)
                
                // Initialize WebSocket connection
                val languagePair = languageManager.currentLanguagePair.value
                webSocket.initialize(
                    com.float.app.data.WebSocketConfig(
                        serverUrl = "ws://localhost",
                        port = 8080,
                        endpoint = "/ws/translation"
                    )
                )
                
                // Connect to translation service
                webSocket.connect(languagePair)
                
                _serviceState.value = FloatServiceState.Active
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
                _serviceState.value = FloatServiceState.Error
            }
        }
    }
    
    /**
     * Handle service stop.
     */
    private fun handleStopService() {
        serviceScope.launch {
            stopAudioCapture()
            delay(1000) // Allow cleanup
            
            // Stop all components
            webSocket.disconnect()
            streamingBuffer.release()
            
            // Cancel all jobs
            networkMonitoringJob?.cancel()
            powerResilienceJob?.cancel()
            
            _serviceState.value = FloatServiceState.Stopped
            stopSelf()
        }
    }
    
    /**
     * Handle start audio capture.
     */
    private fun handleStartCapture() {
        if (isOverlayExpanded && !isAudioCaptureActive) {
            startAudioCapture()
        }
    }
    
    /**
     * Handle stop audio capture.
     */
    private fun handleStopCapture() {
        stopAudioCapture()
    }
    
    /**
     * Handle overlay toggle.
     */
    private fun handleToggleOverlay() {
        isOverlayExpanded = !isOverlayExpanded
        
        if (!isOverlayExpanded && isAudioCaptureActive) {
            stopAudioCapture()
        }
        
        updateNotification()
    }
    
    /**
     * Start audio capture pipeline.
     */
    private fun startAudioCapture() {
        serviceScope.launch {
            try {
                isAudioCaptureActive = true
                _serviceState.value = FloatServiceState.Recording
                
                _audioCaptureState.value = _audioCaptureState.value.copy(isCapturing = true)
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
                isAudioCaptureActive = false
                _serviceState.value = FloatServiceState.Error
            }
        }
    }
    
    /**
     * Stop audio capture pipeline.
     */
    private fun stopAudioCapture() {
        serviceScope.launch {
            try {
                isAudioCaptureActive = false
                
                _audioCaptureState.value = _audioCaptureState.value.copy(
                    isCapturing = false,
                    isProcessing = false,
                    isSpeaking = false
                )
                
                _serviceState.value = FloatServiceState.Active
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
            }
        }
    }
    
    /**
     * Process audio chunk through the pipeline.
     */
    /**
     * Process translation for final transcript.
     */
    private suspend fun processTranslation(text: String) {
        try {
            if (isOfflineMode) {
                // Offline fallback mode - use transcript directly
                _audioCaptureState.value = _audioCaptureState.value.copy(
                    lastTranslation = "[Offline] $text"
                )
            } else {
                // Online mode - send to WebSocket for translation
                // Note: In S2S architecture, this would be handled by backend
            }
        } catch (e: Exception) {
            _currentError.value = ErrorModel.fromException(e)
        }
    }
    
    /**
     * Enter offline fallback mode.
     */
    private fun enterOfflineMode(reason: String) {
        isOfflineMode = true
        _serviceState.value = FloatServiceState.OfflineMode(reason)
        updateNotification()
    }
    
    /**
     * Exit offline fallback mode.
     */
    private fun exitOfflineMode() {
        isOfflineMode = false
        _serviceState.value = FloatServiceState.Active
        updateNotification()
    }
    
    /**
     * Check system health and handle power resilience.
     */
    private suspend fun checkSystemHealth() {
        try {
            // Check if we have necessary permissions
            if (!hasSystemAlertWindowPermission()) {
                _currentError.value = ErrorModel.PermissionDenied("System alert window permission missing")
            }
            
            // Check if service is being throttled by OEM policies
            if (isServiceThrottled()) {
                updateNotification("Service may be throttled by battery optimization")
            }
            
        } catch (e: Exception) {
            _currentError.value = ErrorModel.fromException(e)
        }
    }
    
    /**
     * Check if system alert window permission is granted.
     */
    private fun hasSystemAlertWindowPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }
    
    /**
     * Check if service is being throttled by OEM policies.
     */
    private fun isServiceThrottled(): Boolean {
        // In a real implementation, this would check various indicators
        // For now, we'll use a simple heuristic
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
               !isIgnoringBatteryOptimizations()
    }
    
    /**
     * Check if app is ignoring battery optimizations.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        // Implementation would check battery optimization settings
        return true // Simplified for this example
    }
    
    /**
     * Create service notification.
     */
    private fun createServiceNotification(message: String = "FLOAT Translation Service Active"): Notification {
        val intent = Intent(this, FloatOverlayService::class.java).apply {
            action = ACTION_TOGGLE_OVERLAY
        }
        
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FLOAT Translation")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_manage) // Replace with app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
        
        if (isOfflineMode) {
            builder.setContentText("Offline Mode: $message")
                .setColor(android.graphics.Color.parseColor("#FF9800")) // Orange for offline
        }
        
        return builder.build()
    }
    
    /**
     * Update service notification.
     */
    private fun updateNotification(message: String = "FLOAT Translation Service Active") {
        try {
            notificationManager.notify(NOTIFICATION_ID, createServiceNotification(message))
        } catch (e: Exception) {
            // Notification update failed
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handleStopService()
    }
    
    // LatencyCallback implementation
    override fun onChunkProcessed(processingTimeMs: Float) {
        val currentMetrics = _internalLatencyMetrics.value
        if (currentMetrics != null) {
            val updatedMetrics = currentMetrics.copy(
                processingLatencyMs = processingTimeMs
            )
            _internalLatencyMetrics.value = updatedMetrics
        }
    }
    
    override fun onPlaybackStarted() {
        val currentMetrics = _internalLatencyMetrics.value
        if (currentMetrics != null) {
            val updatedMetrics = currentMetrics.copy(
                playbackStart = System.currentTimeMillis()
            )
            _internalLatencyMetrics.value = updatedMetrics
        }
    }
}