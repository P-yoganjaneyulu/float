package com.float.app.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

/**
 * Client-side Streaming Audio Buffer with Instagram-like smooth playback.
 * 
 * Consolidates ALL audio post-processing and emotion flattening on client side:
 * - Cross-fade between consecutive audio chunks
 * - Loudness normalization
 * - Click/pop reduction at chunk boundaries
 * - Soft fade-in & fade-out (5-20ms)
 * - Gentle compression
 * - Emotion flattening (Phase 1)
 */
class StreamingAudioBuffer {
    
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        private const val CROSS_FADE_MS = 15
        private const val FADE_IN_MS = 10
        private const val FADE_OUT_MS = 10
        
        // Constants for reliability enhancements
        private const val REORDERING_WINDOW_MS = 300 // 300ms reordering window
        private const val MIN_BUFFERED_AUDIO_MS = 200 // Minimum 200ms buffered before playback
        private const val MAX_BUFFER_SIZE = 100 // Maximum chunks in buffer
    }
    
    private var audioTrack: AudioTrack? = null
    private var isInitialized = false
    private var isPlaying = false
    
    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    private val _latencyMs = MutableStateFlow(0f)
    val latencyMs: StateFlow<Float> = _latencyMs.asStateFlow()
    
    // Enhanced audio queue with sequencing for out-of-order handling
    private val audioQueue = ConcurrentLinkedQueue<SequencedAudioChunk>()
    private val reorderingBuffer = mutableMapOf<Int, SequencedAudioChunk>() // For out-of-order chunks
    private var previousChunk: SequencedAudioChunk? = null
    private var expectedChunkIndex = 0 // Expected next chunk index
    private var playbackJob: Job? = null
    private var lastPlaybackTime = 0L
    
    // Adaptive jitter buffer
    private val bufferedAudioTimes = mutableListOf<Long>() // Track timing for jitter calculation
    private var jitterBufferThresholdMs = MIN_BUFFERED_AUDIO_MS.toFloat()
    
    private val bufferSize: Int by lazy {
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR
    }
    
    data class SequencedAudioChunk(
        val data: ByteArray,
        val index: Int, // Chunk sequence index
        val timestamp: Long = System.currentTimeMillis(),
        val sampleRate: Int = SAMPLE_RATE,
        val channels: Int = 1
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SequencedAudioChunk) return false
            return index == other.index
        }
        
        override fun hashCode(): Int {
            return index.hashCode()
        }
    }
    
    enum class PlaybackState {
        STOPPED,
        PLAYING,
        PAUSED,
        BUFFERING
    }
    
    interface LatencyCallback {
        fun onChunkProcessed(processingTimeMs: Float)
        fun onPlaybackStarted()
    }
    
    private var latencyCallback: LatencyCallback? = null
    
    /**
     * Set latency callback for internal monitoring.
     */
    fun setLatencyCallback(callback: LatencyCallback) {
        latencyCallback = callback
    }
    
    /**
     * Initialize audio buffer and playback system.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            
            isInitialized = true
            _playbackState.value = PlaybackState.STOPPED
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Add audio chunk to playback queue with ALL client-side post-processing.
     */
    fun addAudioChunk(rawData: ByteArray, chunkIndex: Int = expectedChunkIndex) {
        if (!isInitialized) return
        
        val startTime = System.currentTimeMillis()
        
        // Validate PCM integrity before processing
        if (!validatePCMIntegrity(rawData)) {
            // Replace corrupted chunk with silence
            val silenceData = createSilenceFrame(rawData.size)
            val chunk = SequencedAudioChunk(
                data = silenceData,
                index = chunkIndex,
                timestamp = startTime
            )
            audioQueue.offer(chunk)
        } else {
            // Apply ALL post-processing on client side
            val processedData = postProcessAudioChunk(rawData)
            
            val chunk = SequencedAudioChunk(
                data = processedData,
                index = chunkIndex,
                timestamp = startTime
            )
            
            // Handle out-of-order chunks
            if (chunkIndex == expectedChunkIndex) {
                // In-order chunk, add directly to queue
                audioQueue.offer(chunk)
                expectedChunkIndex++
                
                // Check if any buffered out-of-order chunks can now be processed
                processReorderedChunks()
            } else if (chunkIndex > expectedChunkIndex) {
                // Future chunk, buffer it temporarily if within reordering window
                val timeDiff = chunk.timestamp - System.currentTimeMillis()
                if (abs(timeDiff) <= REORDERING_WINDOW_MS) {
                    reorderingBuffer[chunkIndex] = chunk
                } else {
                    // Too far in future, drop it
                    // Could insert silence here if needed
                }
            } else {
                // Past chunk, likely duplicate, drop it
                // Could implement deduplication logic here if needed
            }
        }
        
        // Update latency metric and notify callback
        val processingTime = System.currentTimeMillis() - startTime
        _latencyMs.value = processingTime.toFloat()
        
        latencyCallback?.onChunkProcessed(processingTime.toFloat())
        
        // Start playback if not already playing and we have enough buffered audio
        if (!isPlaying && shouldStartPlayback()) {
            startPlayback()
        }
    }
    
    /**
     * Process any reordered chunks that can now be added to the main queue.
     */
    private fun processReorderedChunks() {
        while (reorderingBuffer.containsKey(expectedChunkIndex)) {
            val chunk = reorderingBuffer.remove(expectedChunkIndex)
            chunk?.let {
                audioQueue.offer(it)
                expectedChunkIndex++
            }
        }
    }
    
    /**
     * Check if we have enough buffered audio to start playback.
     */
    private fun shouldStartPlayback(): Boolean {
        if (audioQueue.isEmpty()) return false
        
        // Calculate total buffered audio time
        val bufferedTimeMs = calculateBufferedAudioTime()
        
        // Start playback when we have at least the minimum buffered time
        return bufferedTimeMs >= jitterBufferThresholdMs
    }
    
    /**
     * Calculate total buffered audio time in milliseconds.
     */
    private fun calculateBufferedAudioTime(): Float {
        if (audioQueue.isEmpty()) return 0f
        
        // Estimate based on chunk size (assuming ~200ms chunks)
        val chunkDurationMs = 200f // Approximate duration per chunk
        return audioQueue.size * chunkDurationMs
    }
    
    /**
     * Validate PCM integrity to prevent corrupted audio from reaching playback.
     */
    private fun validatePCMIntegrity(data: ByteArray): Boolean {
        // Check for empty data
        if (data.isEmpty()) return false
        
        // Check for minimum size (at least one sample)
        if (data.size < 2) return false
        
        // Convert to samples for validation
        val samples = data.chunked(2).map { bytes ->
            ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        }
        
        // Check for NaN or infinite values (though not applicable to integer PCM)
        // Check amplitude bounds
        for (sample in samples) {
            // PCM 16-bit should be within Short range
            if (sample < Short.MIN_VALUE || sample > Short.MAX_VALUE) {
                return false
            }
        }
        
        // Check for excessive zero samples (potential silence/corruption)
        val zeroSampleCount = samples.count { it == 0.toShort() }
        val zeroRatio = zeroSampleCount.toFloat() / samples.size
        
        // If more than 95% of samples are zero, consider it corrupted
        if (zeroRatio > 0.95f) {
            return false
        }
        
        return true
    }
    
    /**
     * Create a silence frame of specified size.
     */
    private fun createSilenceFrame(size: Int): ByteArray {
        return ByteArray(size) { 0 }
    }
    
    /**
     * Post-process audio chunk with ALL Instagram-like smoothness on client side.
     */
    private fun postProcessAudioChunk(rawData: ByteArray): ByteArray {
        if (rawData.isEmpty()) return rawData
        
        // Convert to Int16 array for processing
        val samples = rawData.chunked(2).map { bytes ->
            ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        }.toShortArray()
        
        var processedSamples = samples.clone()
        
        // 1. Click/pop reduction at boundaries
        processedSamples = reduceClicksAndPops(processedSamples)
        
        // 2. Cross-fade with previous chunk if available
        previousChunk?.let { prevChunk ->
            // Convert previous chunk data to samples
            val prevSamples = prevChunk.data.chunked(2).map { bytes ->
                ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
            }.toShortArray()
            processedSamples = crossFadeWithPrevious(prevSamples, processedSamples)
        }
        
        // 3. Soft fade-in and fade-out
        processedSamples = applySoftFades(processedSamples)
        
        // 4. Loudness normalization (client-side)
        processedSamples = normalizeLoudness(processedSamples)
        
        // 5. Gentle compression (client-side)
        processedSamples = applyGentleCompression(processedSamples)
        
        // 6. Emotion flattening (Phase 1 - client-side)
        processedSamples = applyEmotionFlattening(processedSamples)
        
        // Store current chunk for next cross-fade
        // We'll store the processed chunk for cross-fading
        previousChunk = SequencedAudioChunk(
            data = processedSamples.toByteArray(),
            index = 0, // Index not important for previous chunk storage
            timestamp = System.currentTimeMillis()
        )
        
        return processedSamples.toByteArray()
    }
    
    /**
     * Reduce clicks and pops at audio boundaries.
     */
    private fun reduceClicksAndPops(samples: ShortArray): ShortArray {
        if (samples.size < 100) return samples
        
        val fadeSamples = (0.005 * SAMPLE_RATE).toInt() // 5ms fade
        val result = samples.clone()
        
        // Fade in at start
        for (i in 0 until min(fadeSamples, result.size)) {
            val fade = i.toFloat() / fadeSamples
            result[i] = (result[i] * fade).toInt().toShort()
        }
        
        // Fade out at end
        for (i in max(0, result.size - fadeSamples) until result.size) {
            val fade = (result.size - 1 - i).toFloat() / fadeSamples
            result[i] = (result[i] * fade).toInt().toShort()
        }
        
        return result
    }
    
    /**
     * Cross-fade with previous audio chunk.
     */
    private fun crossFadeWithPrevious(prevSamples: ShortArray, currentSamples: ShortArray): ShortArray {
        val crossFadeSamples = (CROSS_FADE_MS * SAMPLE_RATE / 1000).coerceAtMost(
            min(prevSamples.size, currentSamples.size) / 4
        )
        
        if (crossFadeSamples < 10) return currentSamples
        
        val result = currentSamples.clone()
        
        // Apply cross-fade
        for (i in 0 until crossFadeSamples) {
            val fadeOut = 1.0f - (i.toFloat() / crossFadeSamples)
            val fadeIn = i.toFloat() / crossFadeSamples
            
            // Mix tail of previous with head of current
            val prevIndex = prevSamples.size - crossFadeSamples + i
            if (prevIndex >= 0 && prevIndex < prevSamples.size) {
                val mixed = (prevSamples[prevIndex] * fadeOut + result[i] * fadeIn).toInt()
                result[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        
        return result
    }
    
    /**
     * Apply soft fade-in and fade-out.
     */
    private fun applySoftFades(samples: ShortArray): ShortArray {
        if (samples.size < 200) return samples
        
        val fadeInSamples = (FADE_IN_MS * SAMPLE_RATE / 1000).coerceAtMost(samples.size / 10)
        val fadeOutSamples = (FADE_OUT_MS * SAMPLE_RATE / 1000).coerceAtMost(samples.size / 10)
        
        val result = samples.clone()
        
        // Quadratic fade-in
        for (i in 0 until fadeInSamples) {
            val fade = (i.toFloat() / fadeInSamples).pow(2)
            result[i] = (result[i] * fade).toInt().toShort()
        }
        
        // Quadratic fade-out
        for (i in max(0, result.size - fadeOutSamples) until result.size) {
            val fade = ((result.size - 1 - i).toFloat() / fadeOutSamples).pow(2)
            result[i] = (result[i] * fade).toInt().toShort()
        }
        
        return result
    }
    
    /**
     * Normalize loudness for consistent volume (client-side).
     */
    private fun normalizeLoudness(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        
        // Calculate RMS
        val rms = sqrt(samples.map { (it.toFloat() / 32768f).pow(2) }.average().toFloat())
        
        if (rms < 0.001f) return samples
        
        // Target RMS for Instagram-like consistency
        val targetRms = 0.2f
        val gain = (targetRms / rms).coerceIn(0.1f, 3.0f)
        
        val result = samples.map { (it * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        
        // Soft limiting to prevent clipping
        val maxAmplitude = 0.95f * 32768f
        val peak = result.maxOf { abs(it.toInt()) }
        
        return if (peak > maxAmplitude) {
            val limitGain = maxAmplitude / peak
            result.map { (it * limitGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
                .toShortArray()
        } else {
            result.toShortArray()
        }
    }
    
    /**
     * Apply gentle compression (client-side).
     */
    private fun applyGentleCompression(samples: ShortArray): ShortArray {
        if (samples.size < 100) return samples
        
        val result = samples.map { it.toFloat() / 32768f }.toMutableList()
        
        // Gentle compression with 2:1 ratio above threshold
        val threshold = 0.3f
        val ratio = 2.0f
        
        for (i in result.indices) {
            val abs = abs(result[i])
            if (abs > threshold) {
                val compressed = threshold + (abs - threshold) / ratio
                result[i] = if (result[i] >= 0) compressed else -compressed
            }
        }
        
        return result.map { (it * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
            .toShortArray()
    }
    
    /**
     * Apply Phase 1 emotion flattening (client-side).
     */
    private fun applyEmotionFlattening(samples: ShortArray): ShortArray {
        if (samples.size < 100) return samples
        
        val result = samples.map { it.toFloat() / 32768f }.toMutableList()
        
        // Energy smoothing to reduce extreme variations
        val windowSize = (0.05f * SAMPLE_RATE).toInt().coerceAtMost(result.size / 10)
        if (windowSize > 10) {
            for (i in windowSize until result.size - windowSize) {
                val window = result.subList(i - windowSize, i + windowSize)
                val energy = sqrt(window.map { it.pow(2) }.average().toFloat())
                
                if (energy > 0.1f) {
                    val energyGain = min(0.15f / sqrt(energy), 1.5f)
                    result[i] *= energyGain
                }
            }
        }
        
        // Final amplitude leveling for stability
        val targetLevel = 0.7f
        val currentLevel = sqrt(result.map { it.pow(2) }.average().toFloat())
        
        if (currentLevel > 0) {
            val levelGain = (targetLevel / currentLevel).coerceIn(0.5f, 2.0f)
            for (i in result.indices) {
                result[i] *= levelGain
            }
        }
        
        return result.map { (it * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
            .toShortArray()
    }
    
    /**
     * Start audio playback from the queue.
     */
    private fun startPlayback() {
        if (isPlaying || !isInitialized) return
        
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            isPlaying = true
            _playbackState.value = PlaybackState.PLAYING
            
            // Notify callback when playback starts
            latencyCallback?.onPlaybackStarted()
            
            audioTrack?.play()
            
            while (isActive) {
                // Check if we need to buffer more audio
                if (audioQueue.isEmpty()) {
                    // Handle buffer underrun
                    handleBufferUnderrun()
                    delay(10) // Small delay to prevent busy waiting
                    continue
                }
                
                val chunk = audioQueue.poll() ?: break
                
                // Write to audio track
                val bytesWritten = audioTrack?.write(chunk.data, 0, chunk.data.size) ?: 0
                
                if (bytesWritten < 0) {
                    // Error occurred
                    break
                }
                
                // Update last playback time
                lastPlaybackTime = System.currentTimeMillis()
                
                // Small delay to prevent busy waiting
                delay(1)
            }
            
            isPlaying = false
            _playbackState.value = PlaybackState.STOPPED
        }
    }
    
    /**
     * Handle buffer underrun by filling with silence.
     */
    private fun handleBufferUnderrun() {
        // Create a small silence frame to prevent abrupt stops
        val silenceFrame = createSilenceFrame(1024) // 1KB of silence
        audioTrack?.write(silenceFrame, 0, silenceFrame.size)
    }
    
    /**
     * Stop audio playback.
     */
    fun stop() {
        playbackJob?.cancel()
        audioTrack?.pause()
        audioTrack?.flush()
        isPlaying = false
        _playbackState.value = PlaybackState.STOPPED
        audioQueue.clear()
        reorderingBuffer.clear()
        previousChunk = null
        expectedChunkIndex = 0
    }
    
    /**
     * Pause audio playback.
     */
    fun pause() {
        if (!isPlaying) return
        
        playbackJob?.cancel()
        audioTrack?.pause()
        isPlaying = false
        _playbackState.value = PlaybackState.PAUSED
    }
    
    /**
     * Resume audio playback.
     */
    fun resume() {
        if (isPlaying || audioQueue.isEmpty()) return
        
        startPlayback()
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        isInitialized = false
    }
    
    /**
     * Get current queue size for monitoring.
     */
    fun getQueueSize(): Int = audioQueue.size
    
    /**
     * Check if playback is active.
     */
    fun isPlaying(): Boolean = isPlaying
}