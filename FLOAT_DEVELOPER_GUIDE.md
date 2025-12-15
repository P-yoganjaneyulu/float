# FLOAT Developer Guide

A comprehensive guide for developers to understand, install, run, and test the FLOAT speech-to-speech translation system.

## 1. Tech Stack Summary

### Frontend Stack
| Component | Technology | Version |
|-----------|------------|---------|
| Framework | Next.js | 15.3.5 |
| Language | TypeScript/React | 19.0.0 |
| Styling | Tailwind CSS | 4.x |
| Build System | Next.js | Built-in |
| Package Manager | npm | Latest |

### Android Stack
| Component | Technology | Version |
|-----------|------------|---------|
| Language | Kotlin | 1.9.0 |
| Framework | Android SDK | API 34 (Android 14) |
| UI | Jetpack Compose | Material 3 |
| Architecture | MVVM | With Hilt DI |
| Audio APIs | Android Media APIs | Built-in |
| Networking | OkHttp | 4.12.0 |
| Min SDK | Android 7.0 | API 24 |

### Backend Stack
| Component | Technology | Version |
|-----------|------------|---------|
| Language | Python | 3.11+ |
| Framework | FastAPI | 0.104.1 |
| ASGI Server | Uvicorn | 0.24.0 |
| WebSocket | websockets | 12.0 |
| ML Model | SeamlessM4T v2 | Hugging Face API |
| HTTP Client | aiohttp | 3.9.1 |
| Scientific Computing | NumPy/SciPy | 1.24.3/1.11.4 |

### Networking & Infrastructure
| Component | Technology |
|-----------|------------|
| Protocol | WebSocket (chunked audio) |
| Message Format | JSON |
| External Services | Hugging Face API (SeamlessM4T v2) |
| Containerization | Docker |
| Orchestration | Docker Compose |
| Monitoring | Prometheus/Grafana |

## 2. Installation Requirements

### OS Requirements
- **Windows**: Windows 10 or later
- **macOS**: macOS 10.15 (Catalina) or later
- **Linux**: Ubuntu 20.04 LTS or equivalent

### Required Software

| Software | Version | Purpose | Type |
|----------|---------|---------|------|
| **Node.js** | 18+ | Frontend development | Required |
| **Python** | 3.11+ | Backend development | Required |
| **Android Studio** | Arctic Fox or later | Android app development | Required |
| **Java JDK** | 11 or later | Android build system | Required |
| **Git** | Latest | Version control | Required |
| **Docker** | Latest | Containerization | Required |
| **Docker Compose** | Latest | Multi-container orchestration | Required |

### Package Managers
| Manager | Purpose | Installation Command |
|---------|---------|---------------------|
| **npm** | Frontend dependencies | Comes with Node.js |
| **pip** | Python dependencies | Comes with Python |
| **Gradle** | Android build system | Bundled with Android Studio |

### Environment Variables
| Variable | Purpose | Required |
|----------|---------|----------|
| **HUGGINGFACE_API_KEY** | Access to SeamlessM4T v2 model | Yes (for backend) |

## 3. How to Run the Project (Step-by-Step)

### Running Backend Server

1. **Navigate to backend directory**
   ```bash
   cd backend
   ```

2. **Create virtual environment**
   ```bash
   python -m venv venv
   # On Windows:
   venv\Scripts\activate
   # On macOS/Linux:
   source venv/bin/activate
   ```

3. **Set up Hugging Face API key**
   ```bash
   # On Windows (PowerShell):
   $env:HUGGINGFACE_API_KEY="your_huggingface_api_key_here"
   # On macOS/Linux:
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

6. **Alternative: Using Docker**
   ```bash
   # Using Docker Compose (Recommended)
   docker-compose up -d
   
   # Or using Docker directly
   docker build -t float-backend .
   docker run -p 8080:8080 float-backend
   ```

7. **Verify deployment**
   ```bash
   curl http://localhost:8080/health
   ```

### Running Android App

1. **Open in Android Studio**
   - Open the project root directory in Android Studio
   - Wait for Gradle sync to complete

2. **Set up an Android device or emulator**
   - Create an AVD (Android Virtual Device) with API 34 or higher
   - Or connect a physical Android device with USB debugging enabled

3. **Build and run**
   ```bash
   # From command line (in project root)
   ./gradlew assembleDebug
   ./gradlew installDebug
   
   # Or use Android Studio's Run button
   ```

### Running Frontend (Web Interface)

1. **Install dependencies**
   ```bash
   npm install
   ```

2. **Run development server**
   ```bash
   npm run dev
   ```

3. **Access in browser**
   - Open `http://localhost:3000`

## 4. Mobile Testing Guide

### Android Emulator Testing

1. **Set up Android Emulator**
   - Open Android Studio
   - Go to AVD Manager
   - Create a new virtual device with:
     - API Level: 34 (Android 14) or higher
     - RAM: 4GB or more recommended
     - Storage: 6GB or more

2. **Configure emulator settings**
   - Enable Microphone in emulator settings
   - Set up network to use host machine's network

3. **Deploy and test**
   - Run the app on emulator
   - Grant all required permissions when prompted
   - Test audio recording and translation functionality

### Physical Android Phone Testing

1. **Enable Developer Options**
   - Go to Settings > About Phone
   - Tap "Build Number" 7 times

2. **Enable USB Debugging**
   - Go to Settings > Developer Options
   - Enable "USB Debugging"

3. **Connect Device**
   - Connect phone to computer via USB
   - Allow USB debugging when prompted on phone

4. **Verify Connection**
   ```bash
   adb devices
   ```
   Should show your device

5. **Deploy and Test**
   - Run the app from Android Studio or command line
   - Grant all required permissions
   - Test functionality

### Different Android Devices (OS Versions)

1. **Minimum Supported Version**
   - Android 7.0 (API 24) and above

2. **Testing Strategy**
   - Test on minimum supported version (API 24)
   - Test on latest stable version (API 34+)
   - Test on popular versions in between

3. **Common Compatibility Issues**
   - Foreground service limitations in Android 8.0+
   - Notification channel requirements in Android 8.0+
   - Background execution limits in Android 9.0+

### USB Debugging Steps

1. **Enable Developer Options**
   - Settings > About Phone > Build Number (tap 7 times)

2. **Enable USB Debugging**
   - Settings > Developer Options > USB Debugging

3. **Connect and Authorize**
   - Connect via USB cable
   - Allow debugging when prompted
   - Install necessary drivers if on Windows

### Network Setup

1. **Same Wi-Fi Network**
   - Both backend and mobile device on same network
   - Find backend machine's IP address:
     ```bash
     # On Windows:
     ipconfig
     # On macOS/Linux:
     ifconfig
     ```

2. **Localhost vs IP Address**
   - **Development**: Use localhost/127.0.0.1
   - **Device Testing**: Use machine's IP address
   - **Production**: Use domain name

3. **WebSocket Connection**
   - Default: `ws://localhost:8080/ws/translation`
   - For device: `ws://YOUR_IP:8080/ws/translation`

## 5. Cross-Device & Network Testing

### Multiple Devices Connected to Same Backend

1. **Setup**
   - Ensure all devices are on same network
   - Backend accessible via network IP
   - Each device connects with unique session ID

2. **Testing Process**
   - Launch app on multiple devices
   - Verify each gets unique session
   - Monitor backend connection stats at `http://YOUR_IP:8080/stats`

### Network Switching Testing

1. **Wi-Fi ↔ Mobile Data**
   - Start translation on Wi-Fi
   - Turn off Wi-Fi, enable mobile data
   - Observe reconnection behavior
   - Check for data loss during transition

2. **Expected Behavior**
   - Automatic reconnection with exponential backoff
   - Audio chunks buffered during disconnect
   - Resend of unacknowledged chunks after reconnect

### Temporary Network Loss Testing

1. **Simulation**
   - Disable network entirely for 10-30 seconds
   - Re-enable network
   - Observe recovery behavior

2. **Verification Points**
   - Connection reestablishment
   - Audio continuity
   - No crashes or hangs

### Long-Run Stability Testing

1. **Extended Usage Test**
   - Run continuous translation for 1+ hours
   - Monitor:
     - Memory usage
     - Battery consumption
     - Audio quality consistency
     - Connection stability

2. **Stress Testing**
   - Multiple concurrent connections
   - High network latency simulation
   - Packet loss simulation

## Known Pitfalls & Troubleshooting Tips

### Common Issues and Fixes

#### Backend Issues
1. **Hugging Face API Key Missing**
   - **Error**: "HUGGINGFACE_API_KEY environment variable not set"
   - **Fix**: Set the environment variable with a valid API key

2. **Port Already in Use**
   - **Error**: "Address already in use"
   - **Fix**: Kill existing process or change port in main.py

3. **Dependency Installation Failures**
   - **Error**: Compilation errors during pip install
   - **Fix**: Ensure build tools are installed (build-essential on Linux)

#### Android Issues
1. **Microphone Permission Denied**
   - **Fix**: Manually grant microphone permission in app settings

2. **Overlay Permission Not Granted**
   - **Fix**: Enable "Draw over other apps" in system settings

3. **Accessibility Service Not Working**
   - **Fix**: Enable accessibility service in system settings

4. **App Crashes on Startup**
   - **Fix**: Check logs with `adb logcat` for specific errors

#### Network Issues
1. **WebSocket Connection Failed**
   - **Check**: Backend server is running
   - **Check**: Correct IP address and port
   - **Check**: Firewall settings

2. **High Latency**
   - **Check**: Network connectivity
   - **Check**: Hugging Face API response times
   - **Check**: Backend server load

### Debugging Commands

#### Android Debugging
```bash
# View app logs
adb logcat -s FloatApp

# Install debug version
./gradlew installDebug

# Run tests
./gradlew test
```

#### Backend Debugging
```bash
# Run with debug logging
export LOG_LEVEL=DEBUG
python main.py

# Check health endpoint
curl http://localhost:8080/health

# Check stats
curl http://localhost:8080/stats
```

### Performance Monitoring

1. **Backend Metrics**
   - Access Grafana dashboard at `http://localhost:3000`
   - Access Prometheus at `http://localhost:9090`

2. **Android Performance**
   - Use Android Studio Profiler
   - Monitor memory, CPU, and network usage

This guide should enable any new developer to understand, set up, run, and test the FLOAT system successfully.