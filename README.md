# FLOAT - Real-time Speech-to-Speech Translation System

[![Android](https://img.shields.io/badge/Android-13%2B-green.svg)](https://developer.android.com/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.104.1-red.svg)](https://fastapi.tiangolo.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A production-grade, real-time **speech-to-speech** translation system optimized for Indian languages and mobile environments. FLOAT provides seamless, low-latency translation using **SeamlessM4T v2** direct audio-to-audio translation with advanced audio preprocessing and streaming illusion.

## 🌟 Features

### Core Capabilities
- **Real-time S2S Translation**: Sub-1.5 second end-to-end latency using SeamlessM4T v2
- **14 Indian Languages**: Hindi, Bengali, Telugu, Tamil, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Urdu, Assamese, Odia, Sindhi, and more
- **Direct Audio Translation**: No intermediate text processing - pure speech-to-speech pipeline
- **Advanced Audio Preprocessing**: WebRTC-style noise suppression, AGC, band-pass filtering
- **Speech-Probability VAD**: Advanced voice activity detection with multiple features
- **Emotion Flattening**: Phase 1 implementation for optimal translation accuracy
- **Intelligent Chunking**: 100-300ms chunks with 200-300ms overlap for context
- **Streaming Illusion**: Progressive playback with controlled buffering

### Technical Excellence
- **Android 13/14 Compliant**: Proper foreground service implementation
- **Material 3 Design**: Glassmorphism UI with dark theme optimization
- **Concurrent Processing**: Hundreds of simultaneous WebSocket connections
- **Robust Error Handling**: 15+ specific error scenarios with recovery
- **Power Resilient**: Handles OEM background restrictions and battery optimization
- **Production Ready**: Docker deployment with monitoring and observability

## 🏗️ Architecture

### SeamlessM4T v2 Speech-to-Speech Architecture
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Android App   │    │  WebSocket API  │    │  SeamlessM4T   │
│                 │    │                 │    │     v2 API      │
│ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │ Audio Pre-  │ │◄──►│ │ FastAPI     │ │◄──►│ │ Hugging Face │ │
│ │ processing   │ │    │ │ Server      │ │    │ │ S2S Model   │ │
│ │ (Noise/AGC) │ │    │ └─────────────┘ │    │ └─────────────┘ │
│ └─────────────┘ │    │                 │    │                 │
│                 │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ ┌─────────────┐ │    │ │ Rate Limit  │ │    │ │ Latency     │ │
│ │ Advanced VAD │ │    │ │ & Monitor   │ │    │ │ Tracking     │ │
│ │ (Speech Prob)│ │    │ └─────────────┘ │    │ └─────────────┘ │
│ └─────────────┘ │    │                 │    │                 │
│                 │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ ┌─────────────┐ │    │ │ Prometheus  │ │    │ │ Streaming    │ │
│ │ Chunking     │ │    │ │ Metrics     │ │    │ │ Illusion     │ │
│ │ (100-300ms) │ │    │ └─────────────┘ │    │ └─────────────┘ │
│ └─────────────┘ │    │                 │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Technology Stack

#### Android Client
- **Framework**: Android 15 with App Router
- **Language**: Kotlin with Coroutines
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with Hilt DI
- **Audio**: Advanced preprocessing (WebRTC-style)
- **Networking**: OkHttp WebSocket with Exponential Backoff
- **Translation**: SeamlessM4T v2 via Hugging Face API

#### Backend Server
- **Framework**: FastAPI with asyncio
- **ML**: Hugging Face SeamlessM4T v2 API
- **Audio Processing**: scipy-based preprocessing
- **Infrastructure**: Docker, aiohttp, Prometheus
- **Deployment**: uvicorn with health monitoring

## 🚀 Quick Start

### Prerequisites
- **Android Studio**: Arctic Fox or later
- **Python 3.11+**: For backend development
- **Docker**: For containerized deployment
- **Node.js 18+**: For development tools

### Android App Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-org/float-translation.git
   cd float-translation
   ```

2. **Open in Android Studio**
   - Open the `android/` directory as a project
   - Sync Gradle dependencies
   - Set up an Android device or emulator

3. **Configure permissions**
   - The app will guide you through required permissions
   - Grant: Microphone, Overlay, Notifications, Accessibility

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

### Backend Server Setup

1. **Navigate to backend directory**
   ```bash
   cd backend
   ```

2. **Create virtual environment**
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

3. **Set up Hugging Face API key**
   ```bash
   export HUGGINGFACE_API_KEY="your_huggingface_api_key_here"
   ```

4. **Install dependencies**
   ```bash
   pip install -r requirements.txt
   ```

5. **Run the server**
   ```bash
   python main.py
   ```

6. **Verify deployment**
   ```bash
   curl http://localhost:8080/health
   ```

### Docker Deployment

1. **Using Docker Compose (Recommended)**
   ```bash
   cd backend
   docker-compose up -d
   ```

2. **Using Docker directly**
   ```bash
   cd backend
   docker build -t float-backend .
   docker run -p 8080:8080 float-backend
   ```

## 📱 Usage Guide

### First-Time Setup

1. **Launch the app** and complete the permission flow
2. **Select languages** using the swap-enabled selector
3. **Enable accessibility** for automatic app detection
4. **Start translation** using the floating action button

### Using the Service

1. **Automatic Activation**: FLOAT automatically activates in supported apps
2. **Manual Control**: Tap the floating bubble to start/stop translation
3. **Visual Feedback**: 
   - Red dot: Microphone active
   - Green status: Connected to cloud
   - Orange banner: Offline mode

### Supported Apps

- **WhatsApp & WhatsApp Business**
- **Phone & Dialer apps**
- **Facebook & Messenger**
- **Instagram & Twitter**
- **LinkedIn & Snapchat**
- **And many more...**

## 🔧 Development

### Project Structure

```
float/
├── android/                          # Android client application
│   ├── src/main/kotlin/com/float/app/
│   │   ├── audio/                    # Advanced audio processing
│   │   │   ├── AEC_VAD_Processor.kt        # WebRTC-style preprocessing
│   │   │   ├── SeamlessM4T_Processor.kt     # S2S translation client
│   │   │   └── TTS_Serial_Engine.kt        # Audio playback engine
│   │   ├── data/                     # Data models and contracts
│   │   │   ├── SessionModel.kt
│   │   │   └── ErrorModel.kt
│   │   ├── di/                       # Dependency injection
│   │   │   └── AppDispatchers.kt
│   │   ├── manager/                  # Business logic managers
│   │   │   └── LanguagePairManager.kt
│   │   ├── network/                  # Networking components
│   │   │   └── TranslatorWebSocket.kt
│   │   ├── service/                  # Android services
│   │   │   ├── FloatOverlayService.kt
│   │   │   └── AccessibilityDetector.kt
│   │   └── ui/                      # UI components
│   │       ├── MainActivity.kt
│   │       ├── TranslatorScreen.kt
│   │       └── components/
│   │           └── ErrorBanner.kt
│   └── build.gradle                  # Android build configuration
├── backend/                          # FastAPI backend server
│   ├── main.py                      # FastAPI application entry point
│   ├── translation_processor.py       # SeamlessM4T v2 S2S processor
│   ├── models.py                    # Pydantic data models
│   ├── requirements.txt              # Python dependencies
│   ├── Dockerfile                   # Container configuration
│   └── docker-compose.yml           # Development environment
├── src/                            # Next.js frontend
│   ├── app/                        # Web application
│   ├── components/                  # UI components
│   └── lib/                        # Utilities
├── docs/                           # Documentation
├── scripts/                         # Build and deployment scripts
└── README.md                       # This file
```

### Android Development

#### Key Components

**Audio Processing Pipeline**
```kotlin
// Audio capture with echo cancellation
val audioProcessor = AEC_VAD_Processor(appDispatchers)
audioProcessor.initializeAudio()
audioProcessor.startAudioCapture()

// Speech-to-text processing
val sttProcessor = VoskSTTProcessor(context, appDispatchers)
sttProcessor.initialize("hi") // Hindi
val result = sttProcessor.processAudioChunk(audioData)

// Text-to-speech synthesis
val ttsEngine = TTS_Serial_Engine(context, appDispatchers)
ttsEngine.initialize(Locale("hi", "IN"))
ttsEngine.speak("नमस्ते दुनिया!")
```

**WebSocket Communication**
```kotlin
// Connect to translation service
val webSocket = TranslatorWebSocket(appDispatchers)
webSocket.connect(LanguagePair("en", "hi"))

// Send audio for translation
webSocket.sendAudioChunk(audioData)

// Receive translation results
webSocket.translationResult.collect { result ->
    showTranslation(result.translatedText)
}
```

#### Error Handling

All errors are handled through the comprehensive `ErrorModel` system:

```kotlin
when (error) {
    is ErrorModel.MicrophoneBusy -> showMicBusyDialog()
    is ErrorModel.WebSocketDisconnected -> enableOfflineMode()
    is ErrorModel.VoskModelCorruption -> reinstallModels()
    // ... 12+ other error types
}
```

### Backend Development

#### WebSocket Endpoint

```python
@app.websocket("/ws/translation/{session_id}")
async def websocket_endpoint(websocket: WebSocket, session_id: str):
    await websocket_manager.connect(websocket, session_id, client_info)
    
    while True:
        data = await websocket.receive_text()
        await process_websocket_message(session_id, data)
```

#### Translation Processing

```python
# Initialize translation processor
processor = TranslationProcessor()
await processor.initialize()

# Translate audio chunk
result = await processor.translate_audio(
    audio_data=base64_audio,
    source_language="en",
    target_language="hi",
    session_id=session_id
)
```

#### Performance Monitoring

```python
# Health check endpoint
@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "active_connections": connection_stats.active_connections,
        "total_translations": connection_stats.total_translations,
        "processor_status": translation_processor.get_status()
    }
```

## 🧪 Testing

### Android Tests

```bash
# Run unit tests
./gradlew test

# Run integration tests
./gradlew connectedAndroidTest

# Run lint checks
./gradlew lint
```

### Backend Tests

```bash
# Run Python tests
cd backend
python -m pytest tests/

# Run with coverage
python -m pytest --cov=. tests/

# Run performance benchmarks
python -m pytest benchmarks/
```

### Load Testing

```bash
# WebSocket load testing
cd backend
python load_test.py --connections=100 --duration=60s

# API performance testing
python benchmark.py --concurrent=50 --requests=1000
```

## 📊 Monitoring & Observability

### Production Metrics

**Key Performance Indicators**
- **End-to-end Latency**: Target ≤ 1.5 seconds
- **Connection Success Rate**: Target ≥ 99.5%
- **Translation Accuracy**: Target ≥ 92% for major Indian languages
- **Memory Usage**: Target ≤ 200MB on mid-range devices
- **Battery Impact**: Target ≤ 5% per hour of active use

### Grafana Dashboard

Access at `http://localhost:3000` (admin/admin)

- **Connection Metrics**: Active connections, success rates
- **Performance Metrics**: Translation latency, throughput
- **Resource Metrics**: CPU, memory, GPU utilization
- **Error Metrics**: Error rates, types, recovery times

### Prometheus Metrics

Access at `http://localhost:9090`

- **float_connections_total**: Total WebSocket connections
- **float_translations_total**: Total translation requests
- **float_translation_duration_seconds**: Translation processing time
- **float_errors_total**: Error count by type

## 🔒 Security Considerations

### Android Security
- **Microphone Access**: Only when user explicitly starts translation
- **Privacy Indication**: Clear visual indicator when recording
- **Data Encryption**: All network traffic uses TLS 1.3
- **Local Processing**: Speech recognition happens on-device when possible

### Backend Security
- **Rate Limiting**: 100 requests/minute per IP
- **Input Validation**: Strict message format validation
- **CORS Configuration**: Configurable for production
- **Session Management**: Automatic cleanup of inactive sessions

## 🌍 Deployment

### Production Environment

#### Android App

1. **Configure signing**
   ```bash
   ./gradlew assembleRelease
   ```

2. **Upload to Play Store**
   - Target Android 13+ (API 33+)
   - Request microphone, overlay, accessibility permissions
   - Provide privacy policy and data usage details

#### Backend Server

1. **Production Docker**
   ```bash
   docker build -t float-backend:prod .
   docker run -d --name float-prod -p 8080:8080 float-backend:prod
   ```

2. **Kubernetes Deployment**
   ```yaml
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: float-backend
   spec:
     replicas: 3
     selector:
       matchLabels:
         app: float-backend
   ```

3. **Load Balancer Configuration**
   - Configure health checks on `/health`
   - Enable WebSocket proxying
   - Set up SSL termination

### Environment Variables

```bash
# Backend Configuration
export LOG_LEVEL=INFO
export MAX_CONNECTIONS=1000
export MODEL_CACHE_DIR=/app/models
export REDIS_URL=redis://redis:6379

# Android Configuration
export FLOAT_SERVER_URL=wss://api.float.com
export FLOAT_SERVER_PORT=443
```

## 🤝 Contributing

We welcome contributions! Please follow our guidelines:

### Development Workflow

1. **Fork the repository**
2. **Create a feature branch**
   ```bash
   git checkout -b feature/amazing-feature
   ```
3. **Make your changes**
4. **Add tests**
5. **Ensure all tests pass**
   ```bash
   ./gradlew test && cd backend && python -m pytest
   ```
6. **Submit a pull request**

### Code Style

#### Kotlin (Android)
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use [ktlint](https://ktlint.github.io/) for formatting
- Include KDoc comments for public APIs

#### Python (Backend)
- Follow [PEP 8](https://pep8.org/) style guide
- Use [Black](https://black.readthedocs.io/) for formatting
- Include type hints for all functions

### Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
feat: add support for Punjabi language
fix: resolve microphone permission issue on Android 13
docs: update API documentation
test: add integration tests for WebSocket
```

## 📝 API Documentation

### WebSocket API

#### Connection
```
ws://localhost:8080/ws/translation/{session_id}
```

#### Message Format (Client → Server)
```json
{
  "session_id": "uuid-string",
  "chunk_index": 0,
  "language_pair": {
    "source": "en",
    "target": "hi"
  },
  "audio_chunk": "base64-encoded-audio-data",
  "timestamp": 1634567890123
}
```

#### Message Format (Server → Client)
```json
{
  "session_id": "uuid-string",
  "message_type": "final_transcript",
  "chunk_index": 0,
  "final_transcript": "नमस्ते दुनिया!",
  "confidence": 0.95,
  "timestamp": 1634567890123
}
```

### HTTP API

#### Health Check
```
GET /health
```

#### Statistics
```
GET /stats
```

#### Active Sessions
```
GET /sessions
```

## 🐛 Troubleshooting

### Common Issues

#### Android App
- **Microphone not working**: Check if another app is using it
- **Overlay not showing**: Verify system alert window permission
- **Service stops**: Check battery optimization settings
- **Translation inaccurate**: Ensure correct language pair selected

#### Backend Server
- **High memory usage**: Reduce model size or enable quantization
- **Slow translations**: Check GPU availability and model loading
- **Connection drops**: Verify WebSocket configuration and timeouts
- **Rate limiting**: Adjust limits in production configuration

### Debug Mode

#### Android
```bash
./gradlew installDebug -Pdebug=true
adb logcat -s FloatApp
```

#### Backend
```bash
export LOG_LEVEL=DEBUG
python main.py
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **Vosk**: For offline speech recognition
- **HuggingFace**: For NLLB-200 translation model
- **FastAPI**: For high-performance WebSocket server
- **Jetpack Compose**: For modern Android UI
- **Material Design**: For design guidelines and components

## 📞 Support

- **Documentation**: [docs.float.com](https://docs.float.com)
- **Issues**: [GitHub Issues](https://github.com/your-org/float-translation/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/float-translation/discussions)
- **Email**: support@float.com

---

**FLOAT** - Breaking language barriers, one conversation at a time. 🌍✨