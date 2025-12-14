package com.float.app.manager

import com.float.app.data.LanguagePair
import com.float.app.di.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supported language information for translation.
 */
data class SupportedLanguage(
    val code: String,           // ISO language code (e.g., "en", "hi", "ta")
    val name: String,            // Display name (e.g., "English", "हिन्दी")
    val nativeName: String,      // Native name (e.g., "English", "हिन्दी")
    val isRTL: Boolean = false,  // Right-to-left writing system
    val isSTTSupported: Boolean = true,  // Speech-to-text support
    val isTTSSupported: Boolean = true,  // Text-to-speech support
    val isCloudSupported: Boolean = true  // Cloud translation support
)

/**
 * Language pair with metadata.
 */
data class LanguagePairInfo(
    val pair: LanguagePair,
    val displayName: String,
    val isOfflineSupported: Boolean = false,
    val isCloudSupported: Boolean = true,
    val confidence: Float = 1.0f
)

/**
 * Singleton manager for language pair configuration and Vosk model management.
 * Manages current source/target languages and provides model path resolution.
 */
@Singleton
class LanguagePairManager @Inject constructor(
    private val dispatchers: AppDispatchers
) {
    private val managerScope = CoroutineScope(dispatchers.main)
    
    private val _currentLanguagePair = MutableStateFlow(
        LanguagePair("en", "hi") // Default: English to Hindi
    )
    val currentLanguagePair: StateFlow<LanguagePair> = _currentLanguagePair.asStateFlow()
    
    private val _availableLanguages = MutableStateFlow<List<SupportedLanguage>>(emptyList())
    val availableLanguages: StateFlow<List<SupportedLanguage>> = _availableLanguages.asStateFlow()
    
    private val _supportedPairs = MutableStateFlow<List<LanguagePairInfo>>(emptyList())
    val supportedPairs: StateFlow<List<LanguagePairInfo>> = _supportedPairs.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // Predefined supported languages for Indian context
    private val supportedLanguages = listOf(
        SupportedLanguage("en", "English", "English", false, true, true, true),
        SupportedLanguage("hi", "Hindi", "हिन्दी", false, true, true, true),
        SupportedLanguage("bn", "Bengali", "বাংলা", false, true, true, true),
        SupportedLanguage("te", "Telugu", "తెలుగు", false, true, true, true),
        SupportedLanguage("ta", "Tamil", "தமிழ்", false, true, true, true),
        SupportedLanguage("mr", "Marathi", "मराठी", false, true, true, true),
        SupportedLanguage("gu", "Gujarati", "ગુજરાતી", false, true, true, true),
        SupportedLanguage("kn", "Kannada", "ಕನ್ನಡ", false, true, true, true),
        SupportedLanguage("ml", "Malayalam", "മലയാളം", false, true, true, true),
        SupportedLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", false, true, true, true),
        SupportedLanguage("ur", "Urdu", "اردو", true, true, true, true),
        SupportedLanguage("as", "Assamese", "অসমীয়া", false, true, false, true),
        SupportedLanguage("or", "Odia", "ଓଡ଼ିଆ", false, true, false, true),
        SupportedLanguage("sd", "Sindhi", "سنڌي", true, false, false, true)
    )
    
    // Supported language pairs with metadata
    private val languagePairs = listOf(
        // English to Indian languages
        LanguagePairInfo(LanguagePair("en", "hi"), "English → Hindi", false, true, 0.95f),
        LanguagePairInfo(LanguagePair("en", "bn"), "English → Bengali", false, true, 0.92f),
        LanguagePairInfo(LanguagePair("en", "te"), "English → Telugu", false, true, 0.91f),
        LanguagePairInfo(LanguagePair("en", "ta"), "English → Tamil", false, true, 0.93f),
        LanguagePairInfo(LanguagePair("en", "mr"), "English → Marathi", false, true, 0.90f),
        LanguagePairInfo(LanguagePair("en", "gu"), "English → Gujarati", false, true, 0.89f),
        LanguagePairInfo(LanguagePair("en", "kn"), "English → Kannada", false, true, 0.88f),
        LanguagePairInfo(LanguagePair("en", "ml"), "English → Malayalam", false, true, 0.90f),
        LanguagePairInfo(LanguagePair("en", "pa"), "English → Punjabi", false, true, 0.87f),
        LanguagePairInfo(LanguagePair("en", "ur"), "English → Urdu", false, true, 0.86f),
        
        // Indian languages to English
        LanguagePairInfo(LanguagePair("hi", "en"), "Hindi → English", false, true, 0.94f),
        LanguagePairInfo(LanguagePair("bn", "en"), "Bengali → English", false, true, 0.91f),
        LanguagePairInfo(LanguagePair("te", "en"), "Telugu → English", false, true, 0.90f),
        LanguagePairInfo(LanguagePair("ta", "en"), "Tamil → English", false, true, 0.92f),
        LanguagePairInfo(LanguagePair("mr", "en"), "Marathi → English", false, true, 0.89f),
        LanguagePairInfo(LanguagePair("gu", "en"), "Gujarati → English", false, true, 0.88f),
        LanguagePairInfo(LanguagePair("kn", "en"), "Kannada → English", false, true, 0.87f),
        LanguagePairInfo(LanguagePair("ml", "en"), "Malayalam → English", false, true, 0.89f),
        LanguagePairInfo(LanguagePair("pa", "en"), "Punjabi → English", false, true, 0.86f),
        LanguagePairInfo(LanguagePair("ur", "en"), "Urdu → English", false, true, 0.85f),
        
        // Between Indian languages (limited support)
        LanguagePairInfo(LanguagePair("hi", "bn"), "Hindi → Bengali", false, true, 0.82f),
        LanguagePairInfo(LanguagePair("hi", "te"), "Hindi → Telugu", false, true, 0.81f),
        LanguagePairInfo(LanguagePair("hi", "ta"), "Hindi → Tamil", false, true, 0.83f),
        LanguagePairInfo(LanguagePair("bn", "hi"), "Bengali → Hindi", false, true, 0.82f),
        LanguagePairInfo(LanguagePair("te", "hi"), "Telugu → Hindi", false, true, 0.80f),
        LanguagePairInfo(LanguagePair("ta", "hi"), "Tamil → Hindi", false, true, 0.82f)
    )
    
    init {
        initialize()
    }
    
    /**
     * Initialize the language manager with supported languages and pairs.
     */
    private fun initialize() {
        managerScope.launch {
            _availableLanguages.value = supportedLanguages
            _supportedPairs.value = languagePairs
            _isInitialized.value = true
        }
    }
    
    /**
     * Set the current language pair for translation.
     */
    fun setLanguagePair(source: String, target: String) {
        val newPair = LanguagePair(source, target)
        
        // Validate that the pair is supported
        if (isLanguagePairSupported(newPair)) {
            _currentLanguagePair.value = newPair
        } else {
            throw IllegalArgumentException("Language pair $source -> $target is not supported")
        }
    }
    
    /**
     * Set language pair from LanguagePair object.
     */
    fun setLanguagePair(pair: LanguagePair) {
        setLanguagePair(pair.source, pair.target)
    }
    
    /**
     * Get the current source language.
     */
    fun getCurrentSourceLanguage(): String {
        return _currentLanguagePair.value.source
    }
    
    /**
     * Get the current target language.
     */
    fun getCurrentTargetLanguage(): String {
        return _currentLanguagePair.value.target
    }
    
    /**
     * Get Vosk model path for the specified language.
     */
    fun getVoskModelPath(languageCode: String): String {
        return "/data/data/com.float.app/files/vosk-models/$languageCode"
    }
    
    /**
     * Get Vosk model path for current source language.
     */
    fun getCurrentVoskModelPath(): String {
        return getVoskModelPath(getCurrentSourceLanguage())
    }
    
    /**
     * Check if a language is supported for STT.
     */
    fun isLanguageSupportedForSTT(languageCode: String): Boolean {
        return supportedLanguages.find { it.code == languageCode }?.isSTTSupported ?: false
    }
    
    /**
     * Check if a language is supported for TTS.
     */
    fun isLanguageSupportedForTTS(languageCode: String): Boolean {
        return supportedLanguages.find { it.code == languageCode }?.isTTSSupported ?: false
    }
    
    /**
     * Check if a language pair is supported.
     */
    fun isLanguagePairSupported(pair: LanguagePair): Boolean {
        return languagePairs.any { it.pair.source == pair.source && it.pair.target == pair.target }
    }
    
    /**
     * Get language information by code.
     */
    fun getLanguageInfo(languageCode: String): SupportedLanguage? {
        return supportedLanguages.find { it.code == languageCode }
    }
    
    /**
     * Get language pair information.
     */
    fun getLanguagePairInfo(pair: LanguagePair): LanguagePairInfo? {
        return languagePairs.find { it.pair.source == pair.source && it.pair.target == pair.target }
    }
    
    /**
     * Get supported language pairs for a specific source language.
     */
    fun getSupportedTargetsForSource(sourceCode: String): List<LanguagePairInfo> {
        return languagePairs.filter { it.pair.source == sourceCode }
    }
    
    /**
     * Get supported source languages for a specific target language.
     */
    fun getSupportedSourcesForTarget(targetCode: String): List<LanguagePairInfo> {
        return languagePairs.filter { it.pair.target == targetCode }
    }
    
    /**
     * Get the best available language pair for offline fallback.
     */
    fun getBestOfflinePair(): LanguagePairInfo? {
        return languagePairs.filter { it.isOfflineSupported }
            .maxByOrNull { it.confidence }
    }
    
    /**
     * Get language pairs sorted by confidence.
     */
    fun getLanguagePairsByConfidence(): List<LanguagePairInfo> {
        return languagePairs.sortedByDescending { it.confidence }
    }
    
    /**
     * Get commonly used language pairs for quick access.
     */
    fun getCommonLanguagePairs(): List<LanguagePairInfo> {
        return listOf(
            LanguagePairInfo(LanguagePair("en", "hi"), "English → Hindi", false, true, 0.95f),
            LanguagePairInfo(LanguagePair("hi", "en"), "Hindi → English", false, true, 0.94f),
            LanguagePairInfo(LanguagePair("en", "ta"), "English → Tamil", false, true, 0.93f),
            LanguagePairInfo(LanguagePair("ta", "en"), "Tamil → English", false, true, 0.92f),
            LanguagePairInfo(LanguagePair("en", "bn"), "English → Bengali", false, true, 0.92f),
            LanguagePairInfo(LanguagePair("bn", "en"), "Bengali → English", false, true, 0.91f)
        )
    }
    
    /**
     * Check if current language pair supports offline translation.
     */
    fun currentPairSupportsOffline(): Boolean {
        val pair = _currentLanguagePair.value
        return getLanguagePairInfo(pair)?.isOfflineSupported ?: false
    }
    
    /**
     * Check if current language pair supports cloud translation.
     */
    fun currentPairSupportsCloud(): Boolean {
        val pair = _currentLanguagePair.value
        return getLanguagePairInfo(pair)?.isCloudSupported ?: true
    }
    
    /**
     * Get display name for current language pair.
     */
    fun getCurrentPairDisplayName(): String {
        val pair = _currentLanguagePair.value
        return getLanguagePairInfo(pair)?.displayName ?: "${pair.source} → ${pair.target}"
    }
    
    /**
     * Swap source and target languages.
     */
    fun swapLanguages() {
        val current = _currentLanguagePair.value
        val swapped = LanguagePair(current.target, current.source)
        
        if (isLanguagePairSupported(swapped)) {
            _currentLanguagePair.value = swapped
        }
    }
    
    /**
     * Reset to default language pair.
     */
    fun resetToDefault() {
        _currentLanguagePair.value = LanguagePair("en", "hi")
    }
}