package com.example.voiceinsights

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class RecordingService : Service() {

    companion object {
        var isServiceRunning = false
    }

    private val audioCaptureManager by lazy { AudioCaptureManager(this) }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We use a started service, not a bound service
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> {
                isServiceRunning = true
                startRecording()
            }
            "STOP_RECORDING" -> {
                isServiceRunning = false
                stopRecording()
            }
            "PAUSE_FOR_CALL" -> pauseRecording()
            "RESUME_AFTER_CALL" -> resumeRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (!GoogleDriveAuth.isSignedIn(this)) {
            Log.e("RecordingService", "Drive not connected. Cannot start recording to prevent PCM bloat.")
            stopSelf()
            return
        }

        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "VoiceInsightsChannel")
            .setContentTitle("VoiceInsights Active")
            .setContentText("Recording audio in the background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Default accessible icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
        Log.d("RecordingService", "Foreground service started. Microphone is active.")
        
        audioCaptureManager.startCapture()
    }

    private fun stopRecording() {
        Log.d("RecordingService", "Stopping Foreground service.")
        audioCaptureManager.stopCapture()
        stopForeground(true)
        stopSelf()
    }

    private fun pauseRecording() {
        if (!isServiceRunning) return
        Log.d("RecordingService", "Pausing for phone call...")
        audioCaptureManager.stopCapture()
        updateNotification("Paused during phone call")
    }

    private fun resumeRecording() {
        if (!isServiceRunning) return
        Log.d("RecordingService", "Resuming after phone call...")
        audioCaptureManager.startCapture()
        updateNotification("Recording audio in the background...")
    }

    private fun updateNotification(text: String) {
        val notification: Notification = NotificationCompat.Builder(this, "VoiceInsightsChannel")
            .setContentTitle("VoiceInsights Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "VoiceInsightsChannel",
                "VoiceInsights Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
