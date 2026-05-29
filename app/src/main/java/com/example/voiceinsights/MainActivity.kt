package com.example.voiceinsights

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.voiceinsights.ui.theme.VoiceInsightsTheme

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    
    val isSignedIn = mutableStateOf(false)
    val signedInEmail = mutableStateOf<String?>(null)

    private lateinit var signInLauncher: ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register sign-in result handler
        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val account = GoogleDriveAuth.handleSignInResult(result.data)
            if (account != null) {
                isSignedIn.value = true
                signedInEmail.value = account.email
                Log.d(TAG, "Signed in as: ${account.email}")
            } else {
                Log.e(TAG, "Sign-in result: no account (resultCode=${result.resultCode})")
            }
        }

        // Check if already signed in
        isSignedIn.value = GoogleDriveAuth.isSignedIn(this)
        signedInEmail.value = GoogleDriveAuth.getSignedInEmail(this)
        Log.d(TAG, "Initial auth state: signedIn=${isSignedIn.value}, email=${signedInEmail.value}")

        // Schedule periodic call recording scan
        CallRecordingScanWorker.schedule(this)

        // Check if launched from boot notification to auto-start recording
        handleAutoStartIntent(intent)

        setContent {
            VoiceInsightsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        MainScreen(
                            isSignedIn = isSignedIn.value,
                            signedInEmail = signedInEmail.value,
                            onSignIn = { startGoogleSignIn() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoStartIntent(intent)
    }

    /**
     * If this activity was launched from the boot notification with auto_start_recording=true,
     * start the recording service immediately.
     */
    private fun handleAutoStartIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("auto_start_recording", false) == true) {
            Log.d(TAG, "Auto-start recording requested from boot notification")
            if (GoogleDriveAuth.isSignedIn(this)) {
                val serviceIntent = Intent(this, RecordingService::class.java).apply {
                    action = "START_RECORDING"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "Recording service started via boot auto-start")
            } else {
                Log.w(TAG, "Cannot auto-start: not signed into Google Drive")
            }
        }
    }

    private fun startGoogleSignIn() {
        Log.d(TAG, "Starting Google Sign-In...")
        val signInIntent = GoogleDriveAuth.getSignInIntent(this)
        signInLauncher.launch(signInIntent)
    }

    /**
     * Checks if overlay permission is granted and prompts the user if not.
     * Call this when enabling auto-start-on-boot feature.
     */
    fun promptOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted — prompting user")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}