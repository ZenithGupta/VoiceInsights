package com.example.voiceinsights

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun MainScreen(
    isSignedIn: Boolean = false,
    signedInEmail: String? = null,
    onSignIn: () -> Unit = {}
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(RecordingService.isServiceRunning) }
    var permissionsGranted by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.entries.all { it.value }
    }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, permissionsGranted, hasOverlayPermission, isSignedIn) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (permissionsGranted && hasOverlayPermission && isSignedIn && !RecordingService.isServiceRunning) {
                    isRecording = true
                    val intent = android.content.Intent(context, RecordingService::class.java).apply {
                        action = "START_RECORDING"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasOverlayPermission) {
            Text(
                "VoiceInsights needs 'Display over other apps' permission to auto-start after a device reboot.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { 
                val intent = android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                    android.net.Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) {
                Text("Grant Overlay Permission")
            }
        } else if (!permissionsGranted) {
            Text(
                "VoiceInsights needs Microphone, Notification, and Storage permissions to run.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                Text("Grant Permissions")
            }
        } else {
            // Google Drive status card
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSignedIn)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Google Drive",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isSignedIn) {
                        Text(
                            text = "✓ Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = signedInEmail ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Chunks will auto-upload when connected to network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            text = "Not connected — recordings saved locally only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onSignIn) {
                            Text("Sign in with Google")
                        }
                    }
                }
            }

            // Recording status
            Text(
                text = if (isRecording) "Recording into chunks..." else "Ready to Record",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(48.dp))

            // Record button
            Button(
                onClick = { 
                    isRecording = !isRecording 
                    val intent = android.content.Intent(context, RecordingService::class.java).apply {
                        action = if (isRecording) "START_RECORDING" else "STOP_RECORDING"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                },
                enabled = isSignedIn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = CircleShape,
                modifier = Modifier.size(160.dp)
            ) {
                Text(
                    text = if (isRecording) "STOP" else "START",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isSignedIn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions (shown when signed in and not recording)
            if (isSignedIn && !isRecording) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { CallRecordingScanWorker.scanNow(context) }
                ) {
                    Text("Scan Calls")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Chunks auto-upload to Drive • Calls scanned every 5 min + after each call",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
