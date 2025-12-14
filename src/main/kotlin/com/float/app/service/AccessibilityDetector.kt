package com.float.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Target application information for accessibility monitoring.
 */
data class TargetApp(
    val packageName: String,
    val appName: String,
    val isSupported: Boolean = true,
    val autoActivate: Boolean = true
)

/**
 * Accessibility event information.
 */
data class AccessibilityEventInfo(
    val packageName: String,
    val eventType: Int,
    val eventTime: Long,
    val contentDescription: String? = null,
    val text: CharSequence? = null,
    val className: String? = null
)

/**
 * Accessibility detector state.
 */
sealed class AccessibilityDetectorState {
    object Disabled : AccessibilityDetectorState()
    object Enabled : AccessibilityDetectorState()
    object Monitoring : AccessibilityDetectorState()
    data class Error(val message: String) : AccessibilityDetectorState()
    data class TargetDetected(val packageName: String, val appName: String) : AccessibilityDetectorState()
}

/**
 * Minimal and robust AccessibilityService for monitoring target applications.
 * Detects when user enters supported apps (WhatsApp, Phone, etc.) and triggers
 * the FLOAT overlay service accordingly.
 */
class FloatAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "FloatAccessibilityService"
        
        // Supported target applications
        val SUPPORTED_APPS = listOf(
            TargetApp("com.whatsapp", "WhatsApp"),
            TargetApp("com.whatsapp.w4b", "WhatsApp Business"),
            TargetApp("com.android.phone", "Phone"),
            TargetApp("com.android.dialer", "Dialer"),
            TargetApp("com.google.android.dialer", "Google Phone"),
            TargetApp("com.facebook.orca", "Messenger"),
            TargetApp("com.facebook.katana", "Facebook"),
            TargetApp("com.instagram.android", "Instagram"),
            TargetApp("com.twitter.android", "Twitter"),
            TargetApp("com.zhiliaoapp.musically", "TikTok"),
            TargetApp("com.snapchat.android", "Snapchat"),
            TargetApp("com.linkedin.android", "LinkedIn"),
            TargetApp("com.google.android.apps.messaging", "Messages"),
            TargetApp("com.samsung.android.messaging", "Samsung Messages")
        )
        
        // Actions for communication with FloatOverlayService
        const val ACTION_TARGET_APP_DETECTED = "com.float.app.TARGET_APP_DETECTED"
        const val ACTION_TARGET_APP_LEFT = "com.float.app.TARGET_APP_LEFT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
    
    private var isMonitoring = false
    private var currentTargetApp: TargetApp? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        configureService()
    }
    
    /**
     * Configure the accessibility service with appropriate settings.
     */
    private fun configureService() {
        val serviceInfo = AccessibilityServiceInfo().apply {
            // Event types to monitor
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            
            // Feedback types - we don't need any feedback
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Flags
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            
            // Event retrieval mode
            eventRetrievalMode = AccessibilityServiceInfo.RETRIEVE_INTERACTIVE_WINDOWS
            
            // Don't timeout
            notificationTimeout = 0
        }
        
        serviceInfo = serviceInfo
        isMonitoring = true
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { handleAccessibilityEvent(it) }
    }
    
    /**
     * Handle accessibility events for target app detection.
     */
    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val packageName = event.packageName?.toString() ?: return
            
            // Check if this is a target app
            val targetApp = SUPPORTED_APPS.find { it.packageName == packageName }
            
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(packageName, targetApp, event)
                }
                
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    handleViewFocused(packageName, targetApp, event)
                }
                
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Only process if we're already monitoring a target app
                    currentTargetApp?.let { current ->
                        if (current.packageName == packageName) {
                            handleContentChanged(packageName, event)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but don't crash the service
        }
    }
    
    /**
     * Handle window state changed events.
     */
    private fun handleWindowStateChanged(
        packageName: String,
        targetApp: TargetApp?,
        event: AccessibilityEvent
    ) {
        if (targetApp != null && targetApp.isSupported) {
            // User entered a target app
            if (currentTargetApp?.packageName != packageName) {
                currentTargetApp = targetApp
                notifyTargetAppDetected(targetApp)
            }
        } else {
            // User left a target app
            currentTargetApp?.let { current ->
                if (current.packageName != packageName) {
                    currentTargetApp = null
                    notifyTargetAppLeft(current)
                }
            }
        }
    }
    
    /**
     * Handle view focused events.
     */
    private fun handleViewFocused(
        packageName: String,
        targetApp: TargetApp?,
        event: AccessibilityEvent
    ) {
        // Similar logic to window state changed
        if (targetApp != null && targetApp.isSupported) {
            if (currentTargetApp?.packageName != packageName) {
                currentTargetApp = targetApp
                notifyTargetAppDetected(targetApp)
            }
        }
    }
    
    /**
     * Handle content changed events for active target apps.
     */
    private fun handleContentChanged(packageName: String, event: AccessibilityEvent) {
        // This could be used to detect specific UI interactions
        // For now, we just ensure the service stays active
    }
    
    /**
     * Notify FloatOverlayService that a target app was detected.
     */
    private fun notifyTargetAppDetected(targetApp: TargetApp) {
        if (targetApp.autoActivate) {
            val intent = Intent(ACTION_TARGET_APP_DETECTED).apply {
                putExtra(EXTRA_PACKAGE_NAME, targetApp.packageName)
                putExtra(EXTRA_APP_NAME, targetApp.appName)
                setPackage(packageName)
            }
            
            // Send broadcast to FloatOverlayService
            sendBroadcast(intent)
        }
    }
    
    /**
     * Notify FloatOverlayService that user left a target app.
     */
    private fun notifyTargetAppLeft(targetApp: TargetApp) {
        val intent = Intent(ACTION_TARGET_APP_LEFT).apply {
            putExtra(EXTRA_PACKAGE_NAME, targetApp.packageName)
            putExtra(EXTRA_APP_NAME, targetApp.appName)
            setPackage(packageName)
        }
        
        // Send broadcast to FloatOverlayService
        sendBroadcast(intent)
    }
    
    override fun onInterrupt() {
        // Service interrupted - cleanup if needed
        isMonitoring = false
        currentTargetApp = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        currentTargetApp = null
    }
    
    /**
     * Check if service is currently monitoring.
     */
    fun isMonitoring(): Boolean = isMonitoring
    
    /**
     * Get current target app being monitored.
     */
    fun getCurrentTargetApp(): TargetApp? = currentTargetApp
}

/**
 * Manager class for accessibility service operations.
 * Provides a clean interface for other components to interact with the accessibility detector.
 */
@Singleton
class AccessibilityDetectorManager @Inject constructor(
    private val context: Context
) {
    private val managerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    
    private val _detectorState = MutableStateFlow<AccessibilityDetectorState>(AccessibilityDetectorState.Disabled)
    val detectorState: StateFlow<AccessibilityDetectorState> = _detectorState.asStateFlow()
    
    private val _currentTargetApp = MutableStateFlow<TargetApp?>(null)
    val currentTargetApp: StateFlow<TargetApp?> = _currentTargetApp.asStateFlow()
    
    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()
    
    init {
        checkAccessibilityServiceStatus()
    }
    
    /**
     * Check if accessibility service is enabled.
     */
    fun checkAccessibilityServiceStatus() {
        managerScope.launch {
            val enabled = isAccessibilityServiceEnabled()
            _isServiceEnabled.value = enabled
            
            if (enabled) {
                _detectorState.value = AccessibilityDetectorState.Enabled
            } else {
                _detectorState.value = AccessibilityDetectorState.Disabled
            }
        }
    }
    
    /**
     * Check if the accessibility service is enabled in system settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceId = "${context.packageName}/.service.FloatAccessibilityService"
        
        try {
            val settingsString = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            return settingsString?.contains(serviceId) == true
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Get intent to redirect user to accessibility settings.
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    
    /**
     * Handle target app detected broadcast.
     */
    fun handleTargetAppDetected(packageName: String, appName: String) {
        managerScope.launch {
            val targetApp = FloatAccessibilityService.SUPPORTED_APPS
                .find { it.packageName == packageName }
            
            targetApp?.let { app ->
                _currentTargetApp.value = app
                _detectorState.value = AccessibilityDetectorState.TargetDetected(
                    packageName = packageName,
                    appName = appName
                )
            }
        }
    }
    
    /**
     * Handle target app left broadcast.
     */
    fun handleTargetAppLeft(packageName: String, appName: String) {
        managerScope.launch {
            if (_currentTargetApp.value?.packageName == packageName) {
                _currentTargetApp.value = null
                _detectorState.value = AccessibilityDetectorState.Monitoring
            }
        }
    }
    
    /**
     * Get all supported target apps.
     */
    fun getSupportedApps(): List<TargetApp> {
        return FloatAccessibilityService.SUPPORTED_APPS
    }
    
    /**
     * Check if a specific app is supported.
     */
    fun isAppSupported(packageName: String): Boolean {
        return FloatAccessibilityService.SUPPORTED_APPS
            .any { it.packageName == packageName && it.isSupported }
    }
    
    /**
     * Get supported app by package name.
     */
    fun getSupportedApp(packageName: String): TargetApp? {
        return FloatAccessibilityService.SUPPORTED_APPS
            .find { it.packageName == packageName }
    }
    
    /**
     * Update auto-activation setting for a target app.
     */
    fun updateAppAutoActivation(packageName: String, autoActivate: Boolean) {
        // In a real implementation, this would persist the setting
        // For now, we'll just update the in-memory list
    }
    
    /**
     * Reset detector state.
     */
    fun reset() {
        managerScope.launch {
            _currentTargetApp.value = null
            _detectorState.value = if (_isServiceEnabled.value) {
                AccessibilityDetectorState.Enabled
            } else {
                AccessibilityDetectorState.Disabled
            }
        }
    }
}