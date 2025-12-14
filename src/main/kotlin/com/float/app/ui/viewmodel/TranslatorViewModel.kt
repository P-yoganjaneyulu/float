package com.float.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.float.app.data.ConnectionState
import com.float.app.data.ErrorModel
import com.float.app.data.LanguagePair
import com.float.app.service.AudioCaptureState
import com.float.app.service.FloatServiceState
import com.float.app.ui.SubtitleItem
import com.float.app.manager.LanguagePairManager
import com.float.app.network.TranslatorWebSocket
import com.float.app.service.FloatOverlayService
import com.float.app.service.AccessibilityDetectorManager
import com.float.app.audio.StreamingAudioBuffer
import com.float.app.audio.SeamlessM4T_Processor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the translator screen.
 */
data class TranslatorUiState(
    val isLoading: Boolean = false,
    val isServiceActive: Boolean = false,
    val currentLanguagePair: LanguagePair = LanguagePair("en", "hi"),
    val lastError: ErrorModel? = null
)

/**
 * ViewModel for the translator screen.
 * Manages UI state and interactions with the translation service.
 */
@HiltViewModel
class TranslatorViewModel @Inject constructor(
    private val languageManager: LanguagePairManager,
    private val webSocket: TranslatorWebSocket,
    private val accessibilityDetector: AccessibilityDetectorManager
) : ViewModel() {
    
    // UI state
    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()
    
    // Service states (these would be bound to actual service in real implementation)
    private val _serviceState = MutableStateFlow<FloatServiceState>(FloatServiceState.Stopped)
    val serviceState: StateFlow<FloatServiceState> = _serviceState.asStateFlow()
    
    private val _audioState = MutableStateFlow(AudioCaptureState())
    val audioState: StateFlow<AudioCaptureState> = _audioState.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _currentError = MutableStateFlow<ErrorModel?>(null)
    val currentError: StateFlow<ErrorModel?> = _currentError.asStateFlow()
    
    private val _subtitles = MutableStateFlow<List<SubtitleItem>>(emptyList())
    val subtitles: StateFlow<List<SubtitleItem>> = _subtitles.asStateFlow()
    
    // Audio visualization state
    private val _audioData = MutableStateFlow<ByteArray?>(null)
    val audioData: StateFlow<ByteArray?> = _audioData.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _isSpeechActive = MutableStateFlow(false)
    val isSpeechActive: StateFlow<Boolean> = _isSpeechActive.asStateFlow()
    
    private val _speechProbability = MutableStateFlow(0f)
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()
    
    private val _latencyMetrics = MutableStateFlow<com.float.app.audio.SeamlessM4T_Processor.LatencyMetrics?>(null)
    val latencyMetrics: StateFlow<com.float.app.audio.SeamlessM4T_Processor.LatencyMetrics?> = _latencyMetrics.asStateFlow()
    
    init {
        initializeViewModel()
    }
    
    /**
     * Initialize the ViewModel and set up observers.
     */
    private fun initializeViewModel() {
        viewModelScope.launch {
            // Observe language manager
            languageManager.currentLanguagePair.collect { pair ->
                _uiState.value = _uiState.value.copy(
                    currentLanguagePair = pair
                )
            }
            
            // Observe WebSocket connection state
            webSocket.connectionState.collect { state ->
                _connectionState.value = state.state
                _currentError.value = state.lastError
            }
            
            // Observe translation results
            webSocket.translationResult.collect { result ->
                result?.let { translation ->
                    addSubtitle(
                        text = translation.translatedText,
                        isSource = false,
                        isPartial = translation.isPartial
                    )
                }
            }
            
            // Observe accessibility detector
            accessibilityDetector.currentTargetApp.collect { targetApp ->
                targetApp?.let { app ->
                    // Auto-activate when target app is detected
                    if (app.autoActivate) {
                        startTranslation()
                    }
                }
            }
        }
    }
    
    /**
     * Change language pair.
     */
    fun changeLanguagePair(source: String, target: String) {
        viewModelScope.launch {
            try {
                languageManager.setLanguagePair(source, target)
                
                // Reconnect WebSocket with new language pair
                val newPair = LanguagePair(source, target)
                webSocket.disconnect()
                webSocket.connect(newPair)
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
            }
        }
    }
    
    /**
     * Start translation service.
     */
    fun startTranslation() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Start service (in real implementation, this would start the actual service)
                _serviceState.value = FloatServiceState.Starting
                
                // Connect WebSocket
                val languagePair = languageManager.currentLanguagePair.value
                webSocket.connect(languagePair)
                
                _serviceState.value = FloatServiceState.Active
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isServiceActive = true
                )
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
                _serviceState.value = FloatServiceState.Error
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
    
    /**
     * Stop translation service.
     */
    fun stopTranslation() {
        viewModelScope.launch {
            try {
                _serviceState.value = FloatServiceState.Stopped
                
                // Disconnect WebSocket
                webSocket.disconnect()
                
                // Clear subtitles
                _subtitles.value = emptyList()
                
                _uiState.value = _uiState.value.copy(isServiceActive = false)
                
            } catch (e: Exception) {
                _currentError.value = ErrorModel.fromException(e)
            }
        }
    }
    
    /**
     * Add subtitle to the display.
     */
    private fun addSubtitle(
        text: String,
        isSource: Boolean,
        isPartial: Boolean = false
    ) {
        val currentSubtitles = _subtitles.value.toMutableList()
        
        // Remove previous partial results from the same speaker
        if (!isPartial) {
            currentSubtitles.removeAll { it.isSource == isSource && it.isPartial }
        }
        
        val newSubtitle = SubtitleItem(
            id = "${System.currentTimeMillis()}_${if (isSource) "source" else "target"}",
            text = text,
            isSource = isSource,
            isPartial = isPartial
        )
        
        currentSubtitles.add(newSubtitle)
        
        // Keep only last 50 subtitles to prevent memory issues
        if (currentSubtitles.size > 50) {
            currentSubtitles.removeAt(0)
        }
        
        _subtitles.value = currentSubtitles
    }
    
    /**
     * Clear current error.
     */
    fun clearError() {
        _currentError.value = null
    }
    
    /**
     * Open settings.
     */
    fun openSettings() {
        // Implementation would navigate to settings screen
    }
    
    /**
     * Update audio state (called by service).
     */
    fun updateAudioState(state: AudioCaptureState) {
        _audioState.value = state
        
        // Update audio visualization state
        _isRecording.value = state.isCapturing
        _isProcessing.value = state.isProcessing
        
        // Extract audio data for visualization
        if (state.lastAudioChunk != null) {
            _audioData.value = state.lastAudioChunk
        }
        
        // Update speech activity from audio state
        _isSpeechActive.value = state.isSpeechActive
        _speechProbability.value = state.speechProbability
        
        // Add source subtitle when transcript is available
        if (state.lastTranscript.isNotBlank()) {
            addSubtitle(
                text = state.lastTranscript,
                isSource = true,
                isPartial = false
            )
        }
    }
    
    /**
     * Update service state (called by service).
     */
    fun updateServiceState(state: FloatServiceState) {
        _serviceState.value = state
        
        when (state) {
            is FloatServiceState.Error -> {
                _currentError.value = ErrorModel.UnknownError("Service error occurred")
            }
            else -> { /* Handle other states as needed */ }
        }
    }
    
    /**
     * Handle translation result from service.
     */
    fun handleTranslationResult(text: String, isSource: Boolean) {
        addSubtitle(text, isSource, false)
    }
    
    /**
     * Handle error from service.
     */
    fun handleError(error: ErrorModel) {
        _currentError.value = error
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cleanup resources
        webSocket.disconnect()
    }
}