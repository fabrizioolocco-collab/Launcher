package app.olauncher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.helper.showToast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OneDrive (personal Microsoft account) search through Microsoft Graph.
 *
 * Sign-in: OAuth 2.0 authorization code with PKCE in the browser; Microsoft redirects to
 * [REDIRECT_URI], handled by [OneDriveAuthActivity], which calls [completeSignIn]. Tokens are kept
 * in a private SharedPreferences file; the refresh token renews the access token silently.
 */
class OneDriveSearch(private val context: Context) {

    class Item(val name: String, val path: String?, val webUrl: String, val isFolder: Boolean, val parentId: String?)

    private val prefs = context.getSharedPreferences("app.minimalauncher.onedrive", Context.MODE_PRIVATE)

    fun isConnected(): Boolean = prefs.getString(KEY_REFRESH, null) != null

    fun disconnect() = prefs.edit { clear() }

    /** Starts the Microsoft sign-in in the browser; true when it opened it. */
    fun connect(): Boolean {
        if (CLIENT_ID.isBlank()) {
            context.showToast(context.getString(R.string.ml_onedrive_soon))
            return false
        }
        val verifier = randomString()
        val state = randomString()
        prefs.edit { putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state) }
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = Uri.parse("$AUTHORITY/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.ml_search_cannot_open))
            false
        }
    }

    /** Microsoft's reason for the last failed token request, shown to the user. */
    private var lastError: String? = null

    /**
     * Redirect back from Microsoft: exchanges the code for tokens. Returns null when signed in,
     * otherwise the reason. Runs network I/O: call off the main thread.
     */
    fun completeSignIn(redirect: Uri): String? {
        redirect.getQueryParameter("error")?.let { error ->
            return listOfNotNull(error, redirect.getQueryParameter("error_description")).joinToString(": ")
        }
        val code = redirect.getQueryParameter("code") ?: return "no code in $redirect"
        if (redirect.getQueryParameter("state") != prefs.getString(KEY_STATE, null)) return "state mismatch"
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return "missing verifier"
        val ok = requestToken(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
        )
        return if (ok) null else lastError ?: "token request failed"
    }

    /** Files and folders matching [query], or null on errors (e.g. expired sign-in). Network I/O. */
    fun search(query: String): List<Item>? {
        val token = accessToken() ?: return null
        val q = URLEncoder.encode(query.replace("'", "''"), "UTF-8").replace("+", "%20")
        val url = "https://graph.microsoft.com/v1.0/me/drive/root/search(q='$q')" +
                "?\$top=$MAX_RESULTS&\$select=name,webUrl,parentReference,folder"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            if (conn.responseCode == 401) {
                prefs.edit { remove(KEY_ACCESS) }
                return null
            }
            if (conn.responseCode !in 200..299) return null
            val items = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("value") ?: return emptyList()
            List(items.length()) { i ->
                val o = items.getJSONObject(i)
                val parent = o.optJSONObject("parentReference")
                val path = parent?.optString("path")?.substringAfter("root:", "")?.trimStart('/')?.ifBlank { null }
                Item(
                    name = o.optString("name"),
                    path = path?.let { Uri.decode(it) },
                    webUrl = o.optString("webUrl"),
                    isFolder = o.has("folder"),
                    parentId = parent?.optString("id")?.ifBlank { null },
                )
            }.filter { it.webUrl.isNotBlank() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Web address of a OneDrive item (e.g. the folder holding a file), or null. Network I/O. */
    fun webUrlOf(itemId: String): String? {
        val token = accessToken() ?: return null
        return try {
            val conn = (URL("https://graph.microsoft.com/v1.0/me/drive/items/${Uri.encode(itemId)}?\$select=webUrl")
                .openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().readText()).optString("webUrl").ifBlank { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun accessToken(): String? {
        val access = prefs.getString(KEY_ACCESS, null)
        if (access != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0)) return access
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        if (!requestToken("grant_type" to "refresh_token", "refresh_token" to refresh)) return null
        return prefs.getString(KEY_ACCESS, null)
    }

    private fun requestToken(vararg params: Pair<String, String>): Boolean = try {
        val body = (listOf("client_id" to CLIENT_ID, "scope" to SCOPES) + params)
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = (URL("$AUTHORITY/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode !in 200..299) {
            lastError = "HTTP ${conn.responseCode}: " + try {
                val err = JSONObject(conn.errorStream.bufferedReader().readText())
                listOf(err.optString("error"), err.optString("error_description").substringBefore("\r").substringBefore("\n"))
                    .filter { it.isNotBlank() }.joinToString(": ")
            } catch (e: Exception) {
                ""
            }
            // A rejected refresh token means the user must sign in again
            if (params.any { it.first == "grant_type" && it.second == "refresh_token" }) prefs.edit { remove(KEY_REFRESH) }
            false
        } else {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            prefs.edit {
                putString(KEY_ACCESS, json.getString("access_token"))
                putLong(KEY_EXPIRES, System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000)
                json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString(KEY_REFRESH, it) }
                remove(KEY_VERIFIER)
                remove(KEY_STATE)
            }
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        lastError = e.toString()
        false
    }

    private fun randomString(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        /** "Application (client) ID" of the app registered on Microsoft Entra (not a secret). */
        const val CLIENT_ID = "583f7ca6-fa44-4333-a927-eb8f8580d3ab"
        const val REDIRECT_URI = "minimalauncher://auth"
        private const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        private const val SCOPES = "Files.Read offline_access"
        private const val MAX_RESULTS = 15

        private const val KEY_ACCESS = "ACCESS"
        private const val KEY_REFRESH = "REFRESH"
        private const val KEY_EXPIRES = "EXPIRES"
        private const val KEY_VERIFIER = "VERIFIER"
        private const val KEY_STATE = "STATE"
    }
}

/** Receives Microsoft's redirect after sign-in, stores the tokens and goes back to home. */
class OneDriveAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val ctx = applicationContext
        Thread {
            val error = if (data == null) "no data" else OneDriveSearch(ctx).completeSignIn(data)
            runOnUiThread {
                if (error == null) {
                    ctx.showToast(ctx.getString(R.string.ml_onedrive_connected))
                    goHome()
                } else {
                    // Show Microsoft's reason so it can be fixed (e.g. a wrong redirect URI)
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(R.string.ml_onedrive_failed)
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { goHome() }
                        .show()
                }
            }
        }.start()
    }

    private fun goHome() {
        startActivity(
            Intent(applicationContext, app.olauncher.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
