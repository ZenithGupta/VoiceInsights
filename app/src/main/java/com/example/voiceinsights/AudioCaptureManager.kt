package com.example.voiceinsights

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class AudioCaptureManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Silero VAD / Whisper expects 16kHz, 16-bit, Mono audio
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // Chunk duration: 10 minutes = 600,000 ms
    private val chunkDurationMs = 10 * 60 * 1000L 

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRecording) return
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed!")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = scope.launch {
                writeAudioDataToChunks()
            }
            Log.d(TAG, "Started continuous audio capture with PCM")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture: ${e.message}", e)
        }
    }

    private suspend fun writeAudioDataToChunks() {
        val audioData = ByteArray(bufferSize)
        var currentFile: File? = null
        var outputStream: FileOutputStream? = null
        var currentChunkStartTime = System.currentTimeMillis()
        val audioDir = File(context.filesDir, "audio_chunks").apply { mkdirs() }

        // Process any leftover .pcm files from a previous crash asynchronously
        audioDir.listFiles { f -> f.name.endsWith(".pcm") }?.forEach { leftover ->
            Log.d(TAG, "Processing leftover PCM from crash: ${leftover.name}")
            scope.launch { processAndEncodeChunk(leftover) }
        }

        try {
            while (isRecording) {
                // Check if coroutine is still active (handles cancellation)
                yield()

                if (outputStream == null || (System.currentTimeMillis() - currentChunkStartTime) >= chunkDurationMs) {
                    // Close and process the completed chunk
                    outputStream?.apply {
                        flush()
                        close()
                    }
                    
                    if (currentFile != null && currentFile.exists() && currentFile.length() > 0) {
                        Log.d(TAG, "10-min chunk completed: ${currentFile.name} (${currentFile.length()} bytes)")
                        val chunkToProcess = currentFile
                        scope.launch { processAndEncodeChunk(chunkToProcess) }
                    }

                    val timestamp = System.currentTimeMillis()
                    currentFile = File(audioDir, "chunk_$timestamp.pcm")
                    outputStream = FileOutputStream(currentFile)
                    currentChunkStartTime = timestamp
                    Log.d(TAG, "Started new PCM chunk: ${currentFile.name}")
                }

                val readSize = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    outputStream?.write(audioData, 0, readSize)
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Recording coroutine cancelled")
            throw e // Re-throw so finally block runs under cancellation context
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data: ${e.message}", e)
        } finally {
            // Close the current output stream
            try {
                outputStream?.apply {
                    flush()
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream: ${e.message}")
            }

            // Process the final partial chunk asynchronously so stopCapture returns quickly
            if (currentFile != null && currentFile.exists() && currentFile.length() > 0) {
                Log.d(TAG, "Processing final partial chunk: ${currentFile.name} (${currentFile.length()} bytes)")
                val chunkToProcess = currentFile
                scope.launch { processAndEncodeChunk(chunkToProcess) }
            }
            Log.d(TAG, "Finalized last audio chunk.")
        }
    }

    /**
     * Processes a PCM file through VAD → AAC encoding → Drive upload.
     * This is a suspend function that runs INLINE (not fire-and-forget).
     * The PCM file is only deleted after a successful m4a is created.
     */
    private suspend fun processAndEncodeChunk(pcmFile: File) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting VAD processing for ${pcmFile.name} (${pcmFile.length()} bytes)")
            val m4aFile = File(pcmFile.parent, pcmFile.nameWithoutExtension + ".m4a")
            val tmpM4aFile = File(pcmFile.parent, pcmFile.nameWithoutExtension + ".m4a.tmp")

            try {
                val vadProcessor = VadProcessor(context)
                val cleanSpeech = vadProcessor.processFile(pcmFile)
                vadProcessor.release()

                if (cleanSpeech.isNotEmpty()) {
                    Log.d(TAG, "Encoding ${cleanSpeech.size} speech samples to m4a...")
                    val encoder = AacEncoder(tmpM4aFile.absolutePath, sampleRate, 1)
                    encoder.encode(cleanSpeech)
                    encoder.stop()
                    
                    // Rename tmp to final m4a to prevent DriveUploadWorker from grabbing it early
                    tmpM4aFile.renameTo(m4aFile)
                    Log.d(TAG, "Saved trimmed audio: ${m4aFile.name} (${m4aFile.length()} bytes)")

                    // Trigger Drive upload
                    DriveUploadWorker.enqueue(context)

                    // Only delete PCM after m4a is confirmed
                    if (m4aFile.exists() && m4aFile.length() > 0) {
                        pcmFile.delete()
                        Log.d(TAG, "Deleted PCM: ${pcmFile.name}")
                    } else {
                        Log.w(TAG, "m4a file missing or empty after encoding — keeping PCM for retry")
                    }
                } else {
                    Log.d(TAG, "No speech detected in ${pcmFile.name} — deleting silent PCM")
                    pcmFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VAD/encoding pipeline for ${pcmFile.name}: ${e.message}", e)
                // Keep the PCM file on error so it can be retried on next app start
                Log.w(TAG, "Keeping PCM file for retry: ${pcmFile.name}")
                // Clean up partial m4a if it exists
                if (tmpM4aFile.exists()) {
                    tmpM4aFile.delete()
                    Log.d(TAG, "Deleted partial m4a: ${tmpM4aFile.name}")
                }
                if (m4aFile.exists()) {
                    m4aFile.delete()
                }
            }
        }
    }

    /**
     * Stops recording gracefully. Sets isRecording = false to let the recording loop
     * exit naturally, then waits for the final chunk to be processed through VAD/encode
     * before releasing the AudioRecord.
     */
    fun stopCapture() {
        if (!isRecording) return
        Log.d(TAG, "Stopping capture — waiting for final chunk processing...")
        isRecording = false

        // Let the recording loop exit naturally via isRecording = false.
        // The finally block will process the last partial chunk inline.
        // We wait for that to complete before releasing AudioRecord.
        scope.launch {
            try {
                recordingJob?.join() // Wait for recording + final chunk processing to finish
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for recording job: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) {
                    audioRecord?.apply {
                        try {
                            stop()
                            release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error releasing AudioRecord: ${e.message}", e)
                        }
                    }
                    audioRecord = null
                    Log.d(TAG, "AudioRecord released. Capture fully stopped.")
                }
                recordingJob = null
            }
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
