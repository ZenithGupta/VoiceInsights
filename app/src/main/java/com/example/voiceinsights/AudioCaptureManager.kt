package com.example.voiceinsights

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class AudioCaptureManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Chunk duration: 10 minutes (balances file size and data-loss risk)
    private val chunkDurationMs = 10 * 60 * 1000L 

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    fun startCapture() {
        if (isRecording) return
        isRecording = true
        
        recordingJob = scope.launch {
            try {
                while (isRecording && isActive) {
                    val timestamp = System.currentTimeMillis()
                    val audioDir = File(context.filesDir, "audio_chunks").apply { mkdirs() }
                    val currentFile = File(audioDir, "chunk_$timestamp.m4a")

                    mediaRecorder = createMediaRecorder().apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioChannels(1)
                        setAudioEncodingBitRate(64000) // 64kbps AAC compresses extremely well for voice
                        setAudioSamplingRate(44100)
                        setOutputFile(currentFile.absolutePath)
                        prepare()
                        start()
                    }

                    Log.d("AudioCapture", "Started new compressed .m4a chunk: ${currentFile.name}")
                    
                    // Maintain the recording for precisely the chunk duration
                    delay(chunkDurationMs)
                    
                    stopCurrentRecorder()
                    Log.d("AudioCapture", "Finalized chunk: ${currentFile.name}")
                    // TODO: Trigger Drive Upload Worker for this file
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("AudioCapture", "Error in capture loop: ${e.message}")
                }
            } finally {
                stopCurrentRecorder()
            }
        }
    }

    private fun stopCurrentRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
        }
    }

    fun stopCapture() {
        isRecording = false
        recordingJob?.cancel()
        Log.d("AudioCapture", "Completely stopped ambient audio capture")
    }
}
