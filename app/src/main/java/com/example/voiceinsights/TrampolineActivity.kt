package com.example.voiceinsights

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, RecordingService::class.java).apply {
            action = "START_RECORDING"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
