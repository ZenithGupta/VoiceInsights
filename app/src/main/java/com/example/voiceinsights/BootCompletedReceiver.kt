package com.example.voiceinsights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            Log.d("BootCompletedReceiver", "Device booted, checking permissions...")

            val hasMicPermission = ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                Log.d("BootCompletedReceiver", "Permissions granted, starting RecordingService...")
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    this.action = "START_RECORDING"
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.w("BootCompletedReceiver", "Microphone permission not granted, cannot start service.")
            }
        }
    }
}
