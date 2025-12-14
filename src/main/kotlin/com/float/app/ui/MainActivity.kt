package com.float.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.float.app.service.FloatOverlayService
import com.float.app.service.AccessibilityDetectorManager
import com.float.app.ui.viewmodel.TranslatorViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Permission step for sequential permission flow.
 */
data class PermissionStep(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isRequired: Boolean = true,
    val isGranted: Boolean = false
)

/**
 * Main Activity with sequential permission flow and UI initialization.
 * Handles all critical permissions: SYSTEM_ALERT_WINDOW, RECORD_AUDIO, 
 * POST_NOTIFICATIONS, and AccessibilityService redirection.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var accessibilityDetectorManager: AccessibilityDetectorManager
    
    private val viewModel: TranslatorViewModel by lazy { hiltViewModel() }
    
    // Permission launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        checkOverlayPermission()
    }
    
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        checkMicrophonePermission(isGranted)
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        checkNotificationPermission(isGranted)
    }
    
    private val accessibilityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAccessibilityPermission()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            FLOATTheme {
                MainContent()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check permissions when returning from settings
        checkAllPermissions()
    }
    
    @Composable
    private fun MainContent() {
        val context = LocalContext.current
        var currentStep by remember { mutableStateOf(0) }
        var permissionSteps by remember { mutableStateOf(getPermissionSteps(context)) }
        
        // Check permissions on first composition
        LaunchedEffect(Unit) {
            checkAllPermissions()
        }
        
        // Update permission steps when they change
        LaunchedEffect(permissionSteps) {
            // Find first ungranted required permission
            val firstUngrantedIndex = permissionSteps.indexOfFirst { 
                !it.isGranted && it.isRequired 
            }
            if (firstUngrantedIndex != -1) {
                currentStep = firstUngrantedIndex
            }
        }
        
        val allRequiredGranted = permissionSteps
            .filter { it.isRequired }
            .all { it.isGranted }
        
        if (allRequiredGranted) {
            // Show main translator screen
            TranslatorScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Show permission flow
            PermissionFlowScreen(
                currentStep = currentStep,
                permissionSteps = permissionSteps,
                onStepCompleted = { stepId ->
                    handleStepCompleted(stepId)
                },
                onSkipStep = { stepId ->
                    handleStepSkipped(stepId)
                },
                onRetryPermissions = {
                    checkAllPermissions()
                }
            )
        }
    }
    
    /**
     * Get permission steps for the flow.
     */
    private fun getPermissionSteps(context: Context): List<PermissionStep> {
        return listOf(
            PermissionStep(
                id = "overlay",
                title = "System Alert Window",
                description = "Allow FLOAT to display overlay bubbles for real-time translation while using other apps.",
                icon = Icons.Default.PictureInPicture,
                isRequired = true,
                isGranted = Settings.canDrawOverlays(context)
            ),
            PermissionStep(
                id = "microphone",
                title = "Microphone Access",
                description = "Allow FLOAT to access your microphone for speech recognition and real-time translation.",
                icon = Icons.Default.Mic,
                isRequired = true,
                isGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ),
            PermissionStep(
                id = "notifications",
                title = "Notifications",
                description = "Allow FLOAT to show notifications for translation service status and important alerts.",
                icon = Icons.Default.Notifications,
                isRequired = true,
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true // Not required on older versions
                }
            ),
            PermissionStep(
                id = "accessibility",
                title = "Accessibility Service",
                description = "Enable FLOAT accessibility service to automatically detect when you're in supported apps and show translation overlay.",
                icon = Icons.Default.Accessibility,
                isRequired = true,
                isGranted = accessibilityDetectorManager.isServiceEnabled.value
            )
        )
    }
    
    /**
     * Handle permission step completion.
     */
    private fun handleStepCompleted(stepId: String) {
        when (stepId) {
            "overlay" -> requestOverlayPermission()
            "microphone" -> requestMicrophonePermission()
            "notifications" -> requestNotificationPermission()
            "accessibility" -> requestAccessibilityPermission()
        }
    }
    
    /**
     * Handle permission step skip.
     */
    private fun handleStepSkipped(stepId: String) {
        // Only allow skipping non-required permissions
        val steps = getPermissionSteps(this)
        val step = steps.find { it.id == stepId }
        if (step != null && !step.isRequired) {
            checkAllPermissions()
        }
    }
    
    /**
     * Check all permissions and update steps.
     */
    private fun checkAllPermissions() {
        permissionSteps = getPermissionSteps(this)
    }
    
    /**
     * Request overlay permission.
     */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }
    
    /**
     * Check overlay permission result.
     */
    private fun checkOverlayPermission() {
        checkAllPermissions()
    }
    
    /**
     * Request microphone permission.
     */
    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    
    /**
     * Check microphone permission result.
     */
    private fun checkMicrophonePermission(isGranted: Boolean) {
        checkAllPermissions()
    }
    
    /**
     * Request notification permission.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkAllPermissions()
        }
    }
    
    /**
     * Check notification permission result.
     */
    private fun checkNotificationPermission(isGranted: Boolean) {
        checkAllPermissions()
    }
    
    /**
     * Request accessibility permission.
     */
    private fun requestAccessibilityPermission() {
        val intent = accessibilityDetectorManager.getAccessibilitySettingsIntent()
        accessibilityPermissionLauncher.launch(intent)
    }
    
    /**
     * Check accessibility permission result.
     */
    private fun checkAccessibilityPermission() {
        accessibilityDetectorManager.checkAccessibilityServiceStatus()
        checkAllPermissions()
    }
}

/**
 * Permission flow screen with step-by-step permission requests.
 */
@Composable
private fun PermissionFlowScreen(
    currentStep: Int,
    permissionSteps: List<PermissionStep>,
    onStepCompleted: (String) -> Unit,
    onSkipStep: (String) -> Unit,
    onRetryPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // App logo and title
            AppHeader()
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Permission steps
            permissionSteps.forEachIndexed { index, step ->
                PermissionStepCard(
                    step = step,
                    isCurrent = index == currentStep,
                    isCompleted = step.isGranted,
                    isEnabled = index <= currentStep,
                    onClick = { 
                        if (!step.isGranted && index <= currentStep) {
                            onStepCompleted(step.id)
                        }
                    },
                    onSkip = if (!step.isRequired) {
                        { onSkipStep(step.id) }
                    } else null
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Action buttons
            ActionButtons(
                currentStep = currentStep,
                totalSteps = permissionSteps.size,
                allRequiredGranted = permissionSteps.filter { it.isRequired }.all { it.isGranted },
                onRetry = onRetryPermissions,
                onContinue = {
                    // Start the service if all permissions are granted
                    val intent = Intent(context, FloatOverlayService::class.java).apply {
                        action = FloatOverlayService.ACTION_START_SERVICE
                    }
                    context.startService(intent)
                }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * App header with logo and title.
 */
@Composable
private fun AppHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App icon placeholder
        Card(
            modifier = Modifier.size(80.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.1f)
            ),
            shape = CircleShape,
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "FLOAT Logo",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "FLOAT",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Real-time Translation for Everyone",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Individual permission step card.
 */
@Composable
private fun PermissionStepCard(
    step: PermissionStep,
    isCurrent: Boolean,
    isCompleted: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (isEnabled) it.clickable { onClick() } else it },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCompleted -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                isCurrent -> Color.White.copy(alpha = 0.15f)
                else -> Color.White.copy(alpha = 0.05f)
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrent) 8.dp else 2.dp
        ),
        border = if (isCurrent) {
            androidx.compose.foundation.BorderStroke(
                2.dp, Color.White.copy(alpha = 0.3f)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = when {
                            isCompleted -> Color(0xFF4CAF50)
                            isCurrent -> Color.White.copy(alpha = 0.2f)
                            else -> Color.White.copy(alpha = 0.1f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    tint = if (isCompleted) Color.White else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            
            // Status icon
            when {
                isCompleted -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                }
                isCurrent -> {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Current",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Circle,
                        contentDescription = "Pending",
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        // Skip button for optional permissions
        if (onSkip != null && !isCompleted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "Skip",
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Action buttons for permission flow.
 */
@Composable
private fun ActionButtons(
    currentStep: Int,
    totalSteps: Int,
    allRequiredGranted: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (allRequiredGranted) {
            // Continue to app button
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = "Start Using FLOAT",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Retry button
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    text = "Check Permissions Again",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
            
            // Progress indicator
            Text(
                text = "Step ${currentStep + 1} of $totalSteps",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Custom theme for the FLOAT application.
 */
@Composable
private fun FLOATTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4CAF50),
            secondary = Color(0xFF2196F3),
            tertiary = Color(0xFFFF9800),
            background = Color(0xFF1A1A2E),
            surface = Color(0xFF16213E),
            error = Color(0xFFF44336)
        ),
        content = content
    )
}