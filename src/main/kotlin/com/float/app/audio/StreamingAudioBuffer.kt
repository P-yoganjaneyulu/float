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
    }
    
    private var audioTrack: AudioTrack? = null
    private var isInitialized = false
    private var isPlaying = false
    
    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    private val _latencyMs = MutableStateFlow(0f)
    val latencyMs: StateFlow<Float> = _latencyMs.asStateFlow()
    
    private val audioQueue = ConcurrentLinkedQueue<AudioChunk>()
    private var previousChunk: AudioChunk? = null
    private var playbackJob: Job? = null
    
    private val bufferSize: Int by lazy {
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR
    }
    
    data class AudioChunk(
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis(),
        val sampleRate: Int = SAMPLE_RATE,
        val channels: Int = 1
    )
    
    enum class PlaybackState {
        STOPPED,
        PLAYING,
        PAUSED,
        BUFFERING
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
    fun addAudioChunk(rawData: ByteArray) {
        if (!isInitialized) return
        
        val startTime = System.currentTimeMillis()
        
        // Apply ALL post-processing on client side
        val processedData = postProcessAudioChunk(rawData)
        
        val chunk = AudioChunk(
            data = processedData,
            timestamp = startTime
        )
        
        audioQueue.offer(chunk)
        
        // Update latency metric
        val processingTime = System.currentTimeMillis() - startTime
        _latencyMs.value = processingTime.toFloat()
        
        // Start playback if not already playing
        if (!isPlaying && audioQueue.isNotEmpty()) {
            startPlayback()
        }
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
            processedSamples = crossFadeWithPrevious(prevChunk, processedSamples)
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
        previousChunk = AudioChunk(
            data = processedSamples.toByteArray(),
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
    private fun crossFadeWithPrevious(prevChunk: AudioChunk, currentSamples: ShortArray): ShortArray {
        val prevSamples = prevChunk.data.chunked(2).map { bytes ->
            ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        }.toShortArray()
        
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
            
            audioTrack?.play()
            
            while (isActive && audioQueue.isNotEmpty()) {
                val chunk = audioQueue.poll() ?: break
                
                // Write to audio track
                val bytesWritten = audioTrack?.write(chunk.data, 0, chunk.data.size) ?: 0
                
                if (bytesWritten < 0) {
                    // Error occurred
                    break
                }
                
                // Small delay to prevent busy waiting
                delay(1)
            }
            
            isPlaying = false
            _playbackState.value = PlaybackState.STOPPED
        }
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
        previousChunk = null
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