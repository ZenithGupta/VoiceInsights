package com.example.voiceinsights

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

class VadProcessor(context: Context) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    private val sampleRate = 16000L
    private val windowSizeSamples = 512 // 32ms window at 16kHz
    private val threshold = 0.5f

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            // Make sure you have silero_vad_v4.onnx in your assets folder!
            val modelBytes = context.assets.open("silero_vad_v4.onnx").readBytes()
            session = env?.createSession(modelBytes, OrtSession.SessionOptions())
            Log.d(TAG, "Silero VAD v4 model loaded. Inputs: ${session?.inputNames}, Outputs: ${session?.outputNames}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VAD model", e)
        }
    }

    /**
     * Processes a raw PCM file (16kHz, 16-bit mono), trims silence via Silero VAD v4,
     * and returns a ShortArray of only the speech frames.
     */
    fun processFile(pcmFile: File): ShortArray {
        if (session == null || env == null) {
            Log.e(TAG, "Session or env is null — model not loaded")
            return ShortArray(0)
        }
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            Log.w(TAG, "PCM file doesn't exist or is empty: ${pcmFile.name}")
            return ShortArray(0)
        }

        // Silero VAD v4 uses separate 'h' and 'c' state tensors of shape [2, 1, 64]
        var hData = FloatArray(2 * 1 * 64)
        var cData = FloatArray(2 * 1 * 64)

        val outputSamples = mutableListOf<Short>()
        var framesProcessed = 0
        var speechFrames = 0
        var maxProb = 0f
        var probSum = 0.0

        try {
            FileInputStream(pcmFile).use { fis ->
                val bufferBytes = ByteArray(windowSizeSamples * 2) // 2 bytes per 16-bit sample

                // Padding: keep N frames after speech ends to avoid cutting words
                var speechFramesRemaining = 0
                val paddingFrames = 10 // 10 * 32ms = 320ms padding

                while (true) {
                    val bytesRead = fis.read(bufferBytes)
                    if (bytesRead <= 0) break

                    // Convert bytes to float samples normalized to [-1.0, 1.0]
                    val byteBuffer = ByteBuffer.wrap(bufferBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                    val actualSamples = bytesRead / 2
                    val floatArray = FloatArray(windowSizeSamples)
                    val shortArray = ShortArray(windowSizeSamples)

                    for (i in 0 until windowSizeSamples) {
                        if (i < actualSamples && byteBuffer.hasRemaining()) {
                            val sample = byteBuffer.short
                            shortArray[i] = sample
                            floatArray[i] = sample / 32768.0f
                        } else {
                            // Zero-pad if we read fewer samples than window size (end of file)
                            shortArray[i] = 0
                            floatArray[i] = 0f
                        }
                    }

                    // Create input tensors matching Silero VAD v4 signature
                    val inputTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(floatArray),
                        longArrayOf(1, windowSizeSamples.toLong())
                    )
                    
                    // sr is int64 — pass as LongBuffer with shape [1] for ONNX Runtime compatibility
                    val srTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(longArrayOf(sampleRate)),
                        longArrayOf(1)
                    )
                    
                    val hTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(hData),
                        longArrayOf(2, 1, 64)
                    )
                    val cTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(cData),
                        longArrayOf(2, 1, 64)
                    )

                    val inputs = mapOf(
                        "input" to inputTensor,
                        "sr" to srTensor,
                        "h" to hTensor,
                        "c" to cTensor
                    )

                    val results = session!!.run(inputs)

                    // Output: "output" shape [1, 1] — speech probability
                    val outputValue = results.get("output").get().value
                    val outputProb = (outputValue as Array<FloatArray>)[0][0]

                    // Output: "hn" and "cn" shape [2, 1, 64] — updated LSTM states
                    val hnTensor = results.get("hn").get() as OnnxTensor
                    val cnTensor = results.get("cn").get() as OnnxTensor
                    
                    val hnBuffer = hnTensor.floatBuffer
                    val cnBuffer = cnTensor.floatBuffer
                    
                    hData = FloatArray(2 * 1 * 64)
                    cData = FloatArray(2 * 1 * 64)
                    
                    hnBuffer.get(hData)
                    cnBuffer.get(cData)

                    // Close all tensors to avoid memory leaks
                    inputTensor.close()
                    srTensor.close()
                    hTensor.close()
                    cTensor.close()
                    results.close()

                    framesProcessed++
                    probSum += outputProb
                    if (outputProb > maxProb) maxProb = outputProb

                    if (framesProcessed % 50 == 0) {
                        var sumSquares = 0.0
                        for (s in shortArray) { sumSquares += (s.toDouble() * s.toDouble()) }
                        val rms = Math.sqrt(sumSquares / shortArray.size)
                        Log.d(TAG, "Frame $framesProcessed: prob=${String.format("%.4f", outputProb)}, maxProb=${String.format("%.4f", maxProb)}, rms=${String.format("%.1f", rms)}")
                    }

                    if (outputProb >= threshold) {
                        speechFramesRemaining = paddingFrames
                    }

                    if (speechFramesRemaining > 0) {
                        // Only add the actual samples read (not zero-padding at EOF)
                        val samplesToAdd = if (actualSamples < windowSizeSamples) actualSamples else windowSizeSamples
                        for (i in 0 until samplesToAdd) {
                            outputSamples.add(shortArray[i])
                        }
                        speechFramesRemaining--
                        speechFrames++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PCM file ${pcmFile.name}: ${e.message}", e)
        }

        val totalDurationSec = (framesProcessed * windowSizeSamples) / sampleRate.toFloat()
        val speechDurationSec = (speechFrames * windowSizeSamples) / sampleRate.toFloat()
        val avgProb = if (framesProcessed > 0) probSum / framesProcessed else 0.0
        Log.d(TAG, "VAD complete: ${pcmFile.name} — " +
                "total=${String.format("%.1f", totalDurationSec)}s, " +
                "speech=${String.format("%.1f", speechDurationSec)}s, " +
                "output samples=${outputSamples.size}")
        Log.d(TAG, "VAD stats: maxProb=${String.format("%.4f", maxProb)}, avgProb=${String.format("%.4f", avgProb)}")

        return outputSamples.toShortArray()
    }

    fun release() {
        try {
            session?.close()
            env?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VAD resources", e)
        }
    }

    companion object {
        private const val TAG = "VadProcessor"
    }
}
