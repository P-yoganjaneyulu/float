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
import com.float.app.audio.AEC_VAD_Processor
import com.float.app.audio.TTS_Serial_Engine
import com.float.app.audio.VoskSTTProcessor
import com.float.app.data.ErrorModel
import com.float.app.data.LanguagePair
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
class FloatOverlayService : LifecycleService() {
    
    @Inject
    lateinit var aecVadProcessor: AEC_VAD_Processor
    
    @Inject
    lateinit var seamlessM4TProcessor: SeamlessM4T_Processor
    
    @Inject
    lateinit var ttsEngine: TTS_Serial_Engine
    
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
    
    // Service lifecycle management
    private var audioProcessingJob: Job? = null
    private var networkMonitoringJob: Job? = null
    private var ttsIntegrationJob: Job? = null
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
                // Initialize all components
                val audioInitialized = aecVadProcessor.initializeAudio()
                val sttInitialized = seamlessM4TProcessor.initialize(apiKey = "your_api_key_here") // This should come from secure storage
                val ttsInitialized = ttsEngine.initialize()
                val bufferInitialized = streamingBuffer.initialize()
                
                if (!audioInitialized || !sttInitialized || !ttsInitialized || !bufferInitialized) {
                    throw IllegalStateException("Failed to initialize core components")
                }
                
                // Setup component integrations
                setupComponentIntegrations()
                
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
    private fun setupComponentIntegrations() {
        serviceScope.launch {
            // Audio processing pipeline
            audioProcessingJob = launch {
                aecVadProcessor.audioChunkFlow.collect { audioChunk ->
                    audioChunk?.let { chunk ->
                        processAudioChunk(chunk)
                    }
                }
            
            // SeamlessM4T to streaming buffer integration
            seamlessM4TProcessor.translationResult.collect { result ->
                result?.let { translationResult ->
                    // Add target subtitle when translation is available
                    if (!translationResult.translatedText.isBlank()) {
                        addSubtitle(
                            text = translationResult.translatedText,
                            isSource = false,
                            isPartial = translationResult.isPartial
                        )
                    }
                }
            }
            
            // TTS audio ducking integration
            ttsIntegrationJob = launch {
                ttsEngine.audioDuckingSignal.collect { shouldDuck ->
                    aecVadProcessor.setAudioDucking(shouldDuck)
                }
            }
            
            // Network state monitoring
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
                }
            }
        }
    }
    private fun setupComponentIntegrations() {
        serviceScope.launch {
            // Audio processing pipeline
            audioProcessingJob = launch {
                aecVadProcessor.audioChunkFlow.collect { audioChunk ->
                    audioChunk?.let { chunk ->
                        processAudioChunk(chunk)
                    }
                }
            }
            
            // TTS audio ducking integration
            ttsIntegrationJob = launch {
                ttsEngine.audioDuckingSignal.collect { shouldDuck ->
                    aecVadProcessor.setAudioDucking(shouldDuck)
                }
            }
            
            // Network state monitoring
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
                }
            }
        }
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
            ttsEngine.release()
            seamlessM4TProcessor.release()
            streamingBuffer.release()
            aecVadProcessor.release()
            
            // Cancel all jobs
            audioProcessingJob?.cancel()
            networkMonitoringJob?.cancel()
            ttsIntegrationJob?.cancel()
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
                
                // Start audio processing
                aecVadProcessor.startAudioCapture()
                
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
                
                // Stop audio processing
                aecVadProcessor.stopAudioCapture()
                
                // Reset TTS
                ttsEngine.stop()
                
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
    private suspend fun processAudioChunk(audioChunk: ByteArray) {
        _audioCaptureState.value = _audioCaptureState.value.copy(isProcessing = true)
        
        try {
            // Get current VAD state from processor
            val isSpeechActive = aecVadProcessor.isSpeechActive.value
            val speechProbability = calculateSpeechProbability(audioChunk)
            
            // Update audio capture state with VAD information
            _audioCaptureState.value = _audioCaptureState.value.copy(
                isSpeechActive = isSpeechActive,
                speechProbability = speechProbability,
                lastAudioChunk = audioChunk
            )
            
            // Process with SeamlessM4T for direct S2S translation
            val languagePair = languageManager.currentLanguagePair.value
            val result = seamlessM4TProcessor.processAudioChunk(
                audioData = audioChunk,
                sourceLanguage = languagePair.source,
                targetLanguage = languagePair.target
            )
            
            result?.let { translationResult ->
                if (!translationResult.translatedText.isBlank()) {
                    _audioCaptureState.value = _audioCaptureState.value.copy(
                        lastTranslation = translationResult.translatedText
                    )
                }
            }
        } catch (e: Exception) {
            _currentError.value = ErrorModel.fromException(e)
        } finally {
            _audioCaptureState.value = _audioCaptureState.value.copy(isProcessing = false)
        }
    }
    
    /**
     * Calculate speech probability for VAD feedback.
     */
    private fun calculateSpeechProbability(audioChunk: ByteArray): Float {
        // Simple energy-based speech probability calculation
        if (audioChunk.size < 2) return 0f
        
        var energySum = 0.0
        for (i in audioChunk.indices step 2) {
            if (i + 1 < audioChunk.size) {
                val sample = ((audioChunk[i + 1].toInt() shl 8) or (audioChunk[i].toInt() and 0xFF)).toShort()
                energySum += (sample / 32768.0) * (sample / 32768.0)
            }
        }
        
        val rms = kotlin.math.sqrt(energySum / (audioChunk.size / 2))
        return (rms * 10).coerceIn(0f, 1f) // Scale to 0-1 range
    }
    
    /**
     * Process translation for final transcript.
     */
    private suspend fun processTranslation(text: String) {
        try {
            if (isOfflineMode) {
                // Offline fallback mode - use Vosk transcript directly
                _audioCaptureState.value = _audioCaptureState.value.copy(
                    lastTranslation = "[Offline] $text"
                )
                
                // Speak the original text in offline mode
                ttsEngine.speak(text)
                
            } else {
                // Online mode - send to WebSocket for translation
                // Note: In a real implementation, we'd convert text back to audio
                // For now, we'll simulate this by speaking the original
                ttsEngine.speak(text)
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
            
            // Check component health
            if (aecVadProcessor.processorState.value is com.float.app.audio.AudioProcessorState.Error) {
                _currentError.value = ErrorModel.AudioProcessingError("Audio processor in error state")
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
}