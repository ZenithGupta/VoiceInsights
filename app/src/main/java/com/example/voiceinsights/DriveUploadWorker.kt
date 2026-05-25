package com.example.voiceinsights

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DriveUploadWorker"
        private const val WORK_NAME = "drive_upload"
        private const val DRIVE_FOLDER_NAME = "VoiceInsights"
        private const val PREF_NAME = "drive_upload_prefs"
        private const val KEY_FOLDER_ID = "drive_folder_id"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.d(TAG, "Upload work enqueued")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val driveService = GoogleDriveAuth.getDriveService(applicationContext)
            if (driveService == null) {
                Log.w(TAG, "Not authenticated — skipping upload")
                return@withContext Result.retry()
            }

            val audioDir = File(applicationContext.filesDir, "audio_chunks")
            if (!audioDir.exists() || audioDir.listFiles().isNullOrEmpty()) {
                Log.d(TAG, "No files to upload")
                return@withContext Result.success()
            }

            val folderId = getOrCreateDriveFolder(driveService)
            // Only upload completed .m4a files — skip .pcm and .recording files
            val files = audioDir.listFiles { f -> f.name.endsWith(".m4a") } ?: emptyArray()
            var uploadedCount = 0
            var failedCount = 0

            for (file in files) {
                if (file.length() == 0L) {
                    Log.w(TAG, "Empty file ${file.name} detected — deleting to prevent upload errors.")
                    file.delete()
                    continue
                }

                try {
                    uploadFile(driveService, folderId, file)
                    file.delete()
                    uploadedCount++
                    Log.d(TAG, "Uploaded and deleted: ${file.name}")
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Failed to upload ${file.name}: ${e.message}")
                }
            }

            Log.d(TAG, "Upload complete: $uploadedCount success, $failedCount failed")

            // Always return success so the WorkManager queue NEVER gets permanently stuck on a bad file.
            // Any leftover files that failed to upload will simply be picked up on the next run.
            return@withContext Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload worker error", e)
            Result.retry()
        }
    }

    private fun getOrCreateDriveFolder(driveService: Drive): String {
        val cachedId = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_ID, null)

        if (cachedId != null) {
            try {
                val folder = driveService.files().get(cachedId)
                    .setFields("id, trashed")
                    .execute()
                if (folder != null && folder.trashed != true) {
                    return cachedId
                }
            } catch (_: Exception) { }
        }

        val query = "name='$DRIVE_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        val existingFolder = result.files?.firstOrNull()
        if (existingFolder != null) {
            saveFolderId(existingFolder.id)
            return existingFolder.id
        }

        val folderMetadata = DriveFile().apply {
            name = DRIVE_FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }

        val folder = driveService.files().create(folderMetadata)
            .setFields("id")
            .execute()

        saveFolderId(folder.id)
        Log.d(TAG, "Created Drive folder: ${folder.id}")
        return folder.id
    }

    private fun uploadFile(driveService: Drive, folderId: String, localFile: File) {
        val mimeType = when {
            localFile.name.endsWith(".m4a") -> "audio/mp4"
            localFile.name.endsWith(".mp3") -> "audio/mpeg"
            localFile.name.endsWith(".amr") -> "audio/amr"
            else -> "application/octet-stream"
        }

        val fileMetadata = DriveFile().apply {
            name = localFile.name
            parents = listOf(folderId)
        }

        val mediaContent = FileContent(mimeType, localFile)
        driveService.files().create(fileMetadata, mediaContent)
            .setFields("id, name")
            .execute()
    }

    private fun saveFolderId(id: String) {
        applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_ID, id)
            .apply()
    }
}
