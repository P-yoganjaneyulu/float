# 💡 Project Idea: FLOAT - Real-Time Edge AI Translator

## 1. Vision and Product Goal

**FLOAT** is a mission-critical mobile application designed to eliminate real-time language barriers during live voice communication (phone calls, VoIP, in-person conversation). Unlike existing translation apps that are slow or rely entirely on cloud services, FLOAT operates as a persistent, low-latency, floating overlay on Android, optimized specifically for complex, low-resource languages like those commonly spoken in India.

**Core Value Proposition:** To provide an instantaneous, high-accuracy, and secure translation experience that seamlessly integrates into the user's primary communication environment, without ever leaving the active application (e.g., WhatsApp, Meet, or a standard phone call).

---

## 2. Core Problem Solved

Traditional mobile translation suffers from four major failures that FLOAT must overcome:

1.  **Latency:** Cloud-only models introduce high network latency ($\ge 3$ seconds total), making real-time conversation impossible.
2.  **Audio Conflicts:** Mobile operating systems lock the microphone during active calls, preventing recording of the other party's speech.
3.  **Accuracy for Low-Resource Languages:** Many offline and generic models fail to accurately translate regional Indian languages (Tamil, Telugu, Kannada, etc.).
4.  **UX Friction:** Users must constantly switch apps or use disruptive screen-mirroring features.

---

## 3. Production Architecture (SeamlessM4T v2 S2S)

FLOAT uses a **Network-Dependent Speech-to-Speech Architecture** to achieve target latency of typically 1-3 seconds end-to-end, leveraging **SeamlessM4T v2** for direct audio-to-audio translation with **CLIENT-SIDE PERCEPTUAL PROCESSING**.

| Component | Function | Location | Rationale |
| :--- | :--- | :--- | :--- |
| **Audio Chunk Capture** | Basic audio capture | Android Kotlin Services | Simple capture - no preprocessing |
| **Network Communication** | WebSocket chunked requests | Android Kotlin Network | Send 100-300ms chunks to backend |
| **Backend S2S** | Direct speech-to-speech | FastAPI + Hugging Face API | Peak safety normalization only |
| **Client-Side Processing** | Instagram-style perceptual smoothing | StreamingAudioBuffer.kt | Cross-fade, normalization, compression, emotion flattening |

### ✅ IMPLEMENTED CHANGES

#### **Phase 1: Core Architecture Transformation (COMPLETED)**

1. **Replaced Vosk STT → NLLB → TTS pipeline** with **SeamlessM4T v2 S2S**
   - ✅ Created `SeamlessM4T_Processor.kt` for Android client
   - ✅ Replaced `TranslationProcessor.py` with `SeamlessM4TProcessor`
   - ✅ Updated backend to use Hugging Face API
   - ✅ Removed Vosk dependencies from Android build

2. **Implemented Advanced Audio Preprocessing**
   - ✅ **Noise Suppression**: High-pass filter at 80Hz
   - ✅ **Automatic Gain Control (AGC)**: Window-based RMS normalization
   - ✅ **Band-pass Filtering**: 80Hz-8kHz human speech range
   - ✅ **Audio Normalization**: Consistent amplitude levels

3. **Upgraded Voice Activity Detection**
   - ✅ **Speech-Probability VAD**: Multi-feature detection
   - ✅ **Energy Analysis**: RMS-based energy calculation
   - ✅ **Zero Crossing Rate**: Speech pattern detection
   - ✅ **Spectral Centroid**: Frequency analysis
   - ✅ **Voicing Probability**: Periodicity detection

4. **Fixed Chunking Strategy**
   - ✅ **100-300ms chunks**: Configurable chunk size
   - ✅ **200-300ms overlap**: Rolling window context
   - ✅ **Context Persistence**: Cross-chunk audio continuity
   - ✅ **Stutter Deduplication**: Repetition removal

5. **Added Latency Tracking System**
   - ✅ **Timestamp Tracking**: Audio capture → request → response → playback
   - ✅ **Network Latency**: API call timing
   - ✅ **Processing Latency**: Model inference timing
   - ✅ **Total Latency**: End-to-end measurement

#### **Phase 2: Streaming Illusion (PENDING)**

6. **Immediate UI Feedback** (⏳ TODO)
   - 🔄 Waveform visualization
   - 🔄 Microphone activity indicators
   - 🔄 Real-time state feedback

7. **Controlled Buffering** (⏳ TODO)
   - 🔄 Small, managed audio buffers
   - 🔄 Progressive audio accumulation
   - 🔄 Smooth playback scheduling

8. **Progressive Playback** (⏳ TODO)
   - 🔄 Start before full response arrives
   - 🔄 Streaming audio assembly
   - 🔄 Gap-free playback

#### **Phase 3: Production Polish (PENDING)**

9. **Audio Post-processing** (⏳ TODO)
   - 🔄 Cross-fading between chunks
   - 🔄 Audio normalization
   - 🔄 Jitter reduction

10. **Emotion Flattening** (⏳ TODO)
    - 🔄 Phase 1 implementation (partially done in preprocessing)
    - 🔄 Controlled audio characteristics
    - 🔄 Intelligibility prioritization

---

## 4. Critical Technical Constraints (Zero Tolerance)

### 4.1. Audio & Concurrency Discipline

* **Latency Target:** End-to-end user-perceived delay typically 1-3 seconds (network-dependent).
* **Client-Side Processing:** All perceptual audio processing (cross-fade, normalization, compression, emotion flattening) handled by StreamingAudioBuffer.kt.
* **No Audio Feedback Loops:** Backend performs only peak safety normalization - no audio feedback on client side.
* **VAD-Driven Chunking:** Audio is processed using VAD and chunked into small frames ($\le 300\text{ms}$) before sending over the WebSocket to maintain streaming reactivity.
* **Concurrency Rules (Kotlin Coroutines):**
    * **`Dispatchers.IO`**: Exclusively for all high-latency tasks (Audio I/O, Vosk STT, Network).
    * **`Dispatchers.TTS_SERIAL`**: A dedicated single-threaded dispatcher to ensure TTS audio requests are executed strictly in sequential order, preventing audio overlap.

### 4.2. Android OS Compliance

* **Foreground Service:** Must use **`ForegroundServiceType="microphone"`** (API 34+ compliance) and obtain the `POST_NOTIFICATIONS` permission to ensure continuous, uninterrupted background operation.
* **Overlay & Mic Access:** Uses `AccessibilityService` for call/app detection and `SYSTEM_ALERT_WINDOW` for the floating UI. **Audio recording is limited to Speakerphone Mode** as per Android privacy restrictions.

### 4.3. Networking & Resilience

* **WebSocket Contract:** Strict bi-directional JSON contract must include essential metadata: `session_id`, `chunk_index`, partial/final flags, and explicit `error_code` handling.
* **Reconnection:** Uses an **Exponential Backoff** strategy for WebSocket reconnection attempts, with built-in `keepalive` messages to manage network stability.

---

## 5. User Experience (UI/UX) Requirements

* **Aesthetic:** Uses a clean **Material 3 / Glassmorphism Fallback** design (transparent dark overlays, high-contrast text) to feel premium, lightweight, and non-disruptive.
* **Feedback:** The UI must be **highly reactive** to internal state changes:
    * Clear **Mic Activity Indicator** showing when the audio pipeline is actively processing speech.
    * Immediate display of status for all **15 critical failure scenarios** (`ErrorModel`).
    * Seamless transition to **Offline Fallback Mode** (showing raw transcript only) when the cloud connection drops.
* **Subtitle Display:** Live, scrolling subtitle history that clearly differentiates between the translated output and the raw transcript (during fallback) with legible Indian scripts.