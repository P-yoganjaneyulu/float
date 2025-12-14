package com.float.app.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides coroutine dispatchers for different execution contexts in the FLOAT application.
 * Ensures proper thread isolation for audio processing, UI updates, and TTS operations.
 */
@Singleton
class AppDispatchers @Inject constructor() {
    
    /**
     * IO dispatcher for audio recording, network operations, and file I/O.
     * Uses Dispatchers.IO for optimal thread pool management.
     */
    val io: CoroutineDispatcher = Dispatchers.IO
    
    /**
     * Main dispatcher for UI updates and state management.
     * Uses Dispatchers.Main for Android UI thread operations.
     */
    val main: CoroutineDispatcher = Dispatchers.Main
    
    /**
     * Dedicated single-thread dispatcher for TTS operations.
     * Ensures serial execution of speech synthesis requests to prevent
     * audio conflicts and maintain proper sequencing.
     */
    val ttsSerial: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TTS-Serial-Dispatcher").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()
    
    /**
     * Dedicated dispatcher for audio processing to ensure real-time performance.
     * Separates audio operations from general I/O to prevent blocking.
     */
    val audio: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "Audio-Processing-Dispatcher").apply {
            isDaemon = false
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()
}