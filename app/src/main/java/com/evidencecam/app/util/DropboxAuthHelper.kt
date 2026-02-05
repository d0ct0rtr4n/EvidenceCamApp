package com.evidencecam.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evidencecam.app.BuildConfig
import com.evidencecam.app.model.DropboxConfig
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dropboxAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "dropbox_auth")

@Singleton
class DropboxAuthHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DropboxAuthHelper"

        // App Key loaded from BuildConfig (set via secrets.properties)
        val APP_KEY = BuildConfig.DROPBOX_APP_KEY

        private const val REDIRECT_URI = "evidencecam://dropbox/oauth"
        private const val AUTH_URL = "https://www.dropbox.com/oauth2/authorize"
        private const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"

        private val KEY_CODE_VERIFIER = stringPreferencesKey("code_verifier")
    }

    /**
     * Get an Intent to open the authorization URL in a browser
     */
    suspend fun getAuthIntent(): Intent {
        // Generate PKCE code verifier and challenge
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // Store code verifier for token exchange using DataStore
        context.dropboxAuthDataStore.edit { prefs ->
            prefs[KEY_CODE_VERIFIER] = codeVerifier
        }

        val scopes = listOf(
            "files.content.write",
            "sharing.write",
            "sharing.read",
            "account_info.read"
        ).joinToString(" ")

        val authUrl = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", APP_KEY)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")
            .build()

        return Intent(Intent.ACTION_VIEW, authUrl)
    }

    /**
     * Handle the OAuth callback and exchange the authorization code for tokens.
     */
    suspend fun handleAuthCallback(uri: Uri): Result<DropboxConfig> = withContext(Dispatchers.IO) {
        try {
            val code = uri.getQueryParameter("code")
                ?: return@withContext Result.failure(Exception("No authorization code received"))

            val codeVerifier = context.dropboxAuthDataStore.data.map { prefs ->
                prefs[KEY_CODE_VERIFIER]
            }.first() ?: return@withContext Result.failure(Exception("Code verifier not found"))

            // Clear stored verifier
            context.dropboxAuthDataStore.edit { prefs ->
                prefs.remove(KEY_CODE_VERIFIER)
            }

            // Exchange code for token
            exchangeCodeForToken(code, codeVerifier)
        } catch (e: Exception) {
            Log.e(TAG, "Auth callback failed", e)
            Result.failure(e)
        }
    }

    private fun exchangeCodeForToken(code: String, codeVerifier: String): Result<DropboxConfig> {
        val url = URL(TOKEN_URL)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = buildString {
                append("code=$code")
                append("&grant_type=authorization_code")
                append("&client_id=$APP_KEY")
                append("&redirect_uri=$REDIRECT_URI")
                append("&code_verifier=$codeVerifier")
            }

            connection.outputStream.use { os ->
                os.write(postData.toByteArray())
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val accessToken = json.getString("access_token")
                // Note: optString returns "" instead of null, so use takeIf to convert empty to null
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() }
                val expiresIn = json.optLong("expires_in", 14400L)
                val accountId = json.optString("account_id").takeIf { it.isNotEmpty() }

                // Get account email if we have an access token
                val accountEmail = try {
                    getAccountEmail(accessToken)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch account email", e)
                    null
                }

                val tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                val config = DropboxConfig(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = accountId,
                    accountEmail = accountEmail,
                    folderPath = "",
                    tokenExpiresAt = tokenExpiresAt
                )
                Result.success(config)
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Token exchange failed: $error")
                Result.failure(Exception("Token exchange failed: ${connection.responseCode}"))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getAccountEmail(accessToken: String): String? {
        val url = URL("https://api.dropboxapi.com/2/users/get_current_account")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.write("null".toByteArray())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                json.optString("email").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Create a Dropbox client from saved config.
     * Handles token refresh if needed.
     */
    suspend fun getClient(config: DropboxConfig): DbxClientV2 = withContext(Dispatchers.IO) {
        val requestConfig = DbxRequestConfig.newBuilder("EvidenceCam/1.0").build()

        // Use credential with refresh token support
        val credential = if (config.refreshToken != null) {
            DbxCredential(config.accessToken, config.tokenExpiresAt, config.refreshToken, APP_KEY)
        } else {
            DbxCredential(config.accessToken)
        }

        DbxClientV2(requestConfig, credential)
    }

    /**
     * Create the upload folder if it doesn't exist.
     * If folderPath is empty, files are stored in the app root folder (no creation needed).
     */
    suspend fun ensureFolderExists(config: DropboxConfig): Boolean = withContext(Dispatchers.IO) {
        // Skip folder creation if using root app folder
        if (config.folderPath.isEmpty()) {
            Log.d(TAG, "Using root app folder, no folder creation needed")
            return@withContext true
        }

        runCatching {
            val client = getClient(config)
            try {
                client.files().createFolderV2(config.folderPath)
                Log.d(TAG, "Created folder: ${config.folderPath}")
            } catch (e: Exception) {
                // Folder might already exist, which is fine
                if (!e.message.orEmpty().contains("path/conflict")) {
                    throw e
                }
                Log.d(TAG, "Folder already exists: ${config.folderPath}")
            }
            true
        }.getOrDefault(false)
    }
}
