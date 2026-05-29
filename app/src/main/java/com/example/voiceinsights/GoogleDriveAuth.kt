package com.example.voiceinsights

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

object GoogleDriveAuth {

    private const val TAG = "GoogleDriveAuth"
    private const val APP_NAME = "VoiceInsights"

    private val driveFileScope = Scope(DriveScopes.DRIVE_FILE)

    /**
     * Build a GoogleSignInClient that requests Drive scope + email.
     */
    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveFileScope)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Get the sign-in intent to launch.
     */
    fun getSignInIntent(context: Context): Intent {
        return getSignInClient(context).signInIntent
    }

    /**
     * Check if user has previously signed in with Drive scope.
     */
    fun isSignedIn(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, driveFileScope)
    }

    /**
     * Get the signed-in account's email.
     */
    fun getSignedInEmail(context: Context): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    /**
     * Build a Google Drive service from the currently signed-in account.
     * Returns null if not signed in.
     */
    fun getDriveService(context: Context): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        if (!GoogleSignIn.hasPermissions(account, driveFileScope)) return null

        val email = account.email ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account ?: android.accounts.Account(email, "com.google")
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    /**
     * Handle the result from the sign-in intent.
     */
    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(Exception::class.java)
            Log.d(TAG, "Sign-in success: ${account.email}")
            account
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed: ${e.message}", e)
            null
        }
    }

    fun signOut(context: Context) {
        getSignInClient(context).signOut()
    }
}
