package com.example.voiceinsights

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val BOOT_NOTIFICATION_CHANNEL = "VoiceInsightsBootChannel"
        private const val BOOT_NOTIFICATION_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.d(TAG, "Device booted — checking if recording can auto-start...")

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasOverlayPermission = Settings.canDrawOverlays(context)
        val isSignedIn = GoogleDriveAuth.isSignedIn(context)

        Log.d(TAG, "Boot check: Mic=$hasMicPermission, Overlay=$hasOverlayPermission, SignedIn=$isSignedIn")

        if (!hasMicPermission || !isSignedIn) {
            Log.w(TAG, "Missing critical permissions — cannot auto-start. Mic: $hasMicPermission, SignedIn: $isSignedIn")
            return
        }

        if (hasOverlayPermission) {
            // Best path: overlay permission available, can use TrampolineActivity
            Log.d(TAG, "Overlay permission granted — launching TrampolineActivity...")
            try {
                val activityIntent = Intent(context, TrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(activityIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch TrampolineActivity: ${e.message}", e)
                // Fall through to notification fallback
                postResumeNotification(context)
            }
        } else {
            // Fallback: no overlay permission — post a notification the user can tap
            Log.d(TAG, "No overlay permission — posting 'Tap to resume' notification")
            postResumeNotification(context)
        }
    }

    /**
     * Posts a notification that, when tapped, opens MainActivity.
     * The user tap provides the user-interaction exemption needed
     * to start a foreground service on Android 14+.
     */
    private fun postResumeNotification(context: Context) {
        createBootNotificationChannel(context)

        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("auto_start_recording", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BOOT_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceInsights")
            .setContentText("Tap to resume recording after reboot")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BOOT_NOTIFICATION_ID, notification)
        Log.d(TAG, "Posted boot resume notification")
    }

    private fun createBootNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_NOTIFICATION_CHANNEL,
                "Boot Auto-Start",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification to resume recording after device reboot"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
