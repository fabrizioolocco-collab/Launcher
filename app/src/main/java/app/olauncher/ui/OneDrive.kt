package app.olauncher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.helper.showToast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * OneDrive (personal Microsoft account) through Microsoft Graph, for the file explorer.
 *
 * Sign-in: OAuth 2.0 authorization code with PKCE in the browser; Microsoft redirects to
 * [REDIRECT_URI], handled by [OneDriveAuthActivity], which calls [completeSignIn]. Tokens are kept
 * in a private SharedPreferences file; the refresh token renews the access token silently.
 * Everything but [connect] and [isConnected] does network I/O: call it off the main thread.
 */
class OneDrive(private val context: Context) {

    class Item(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val mimeType: String?,
        val size: Long,
        val modified: Long,
    )

    private val prefs = context.getSharedPreferences("app.minimalauncher.onedrive", Context.MODE_PRIVATE)

    fun isConnected(): Boolean = prefs.getString(KEY_REFRESH, null) != null

    fun disconnect() = prefs.edit { clear() }

    /** Starts the Microsoft sign-in in the browser; true when it opened it. */
    fun connect(): Boolean {
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

    /** Redirect back from Microsoft: exchanges the code for tokens. Returns null when signed in, otherwise the reason. */
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

    private fun accessToken(): String? {
        val access = prefs.getString(KEY_ACCESS, null)
        if (access != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0)) return access
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        if (!requestToken("grant_type" to "refresh_token", "refresh_token" to refresh)) return null
        return prefs.getString(KEY_ACCESS, null)
    }

    private fun requestToken(vararg params: Pair<String, String>): Boolean = try {
        // A refresh keeps the scopes granted at sign-in (older sign-ins were read-only)
        val refreshing = params.any { it.first == "grant_type" && it.second == "refresh_token" }
        val scope = if (refreshing) prefs.getString(KEY_SCOPE, null) ?: OLD_SCOPES else SCOPES
        val body = (listOf("client_id" to CLIENT_ID, "scope" to scope) + params)
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val request = okhttp3.Request.Builder().url("$AUTHORITY/token")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                lastError = "HTTP ${response.code}: " + try {
                    val err = JSONObject(text)
                    listOf(err.optString("error"), err.optString("error_description").substringBefore("\r").substringBefore("\n"))
                        .filter { it.isNotBlank() }.joinToString(": ")
                } catch (e: Exception) {
                    ""
                }
                // A rejected refresh token means the user must sign in again
                if (refreshing && response.code in 400..499) prefs.edit { remove(KEY_REFRESH) }
                false
            } else {
                val json = JSONObject(text)
                prefs.edit {
                    putString(KEY_ACCESS, json.getString("access_token"))
                    putLong(KEY_EXPIRES, System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000)
                    json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString(KEY_REFRESH, it) }
                    if (!refreshing) putString(KEY_SCOPE, scope)
                    remove(KEY_VERIFIER)
                    remove(KEY_STATE)
                }
                true
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        lastError = e.toString()
        false
    }

    /** False for sign-ins made before editing was supported: the user must connect again. */
    fun canWrite(): Boolean = prefs.getString(KEY_SCOPE, null)?.contains("ReadWrite") == true

    private val http by lazy { okhttp3.OkHttpClient() }

    private fun call(method: String, url: String, json: JSONObject? = null): JSONObject? {
        val token = accessToken() ?: return null
        val body = json?.toString()?.jsonBody()
        val request = okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $token")
            .method(method, body ?: if (method == "POST" || method == "PATCH") "".jsonBody() else null)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) prefs.edit { remove(KEY_ACCESS) }
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code}"
                    null
                } else response.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = e.toString()
            null
        }
    }

    private fun String.jsonBody() = toRequestBody("application/json".toMediaType())

    private fun itemUrl(id: String?) = if (id == null) "$GRAPH/root" else "$GRAPH/items/${Uri.encode(id)}"

    private fun parseItem(o: JSONObject) = Item(
        id = o.optString("id"),
        name = o.optString("name"),
        isFolder = o.has("folder"),
        mimeType = o.optJSONObject("file")?.optString("mimeType")?.ifBlank { null },
        size = o.optLong("size"),
        modified = parseDate(o.optString("lastModifiedDateTime")),
    )

    private fun parseDate(iso: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(iso.take(19))?.time ?: 0
    } catch (e: Exception) {
        0
    }

    /** Content of a folder ([folderId] null for the root), or null on errors (e.g. expired sign-in). */
    fun children(folderId: String?): List<Item>? {
        val list = mutableListOf<Item>()
        var url: String? = itemUrl(folderId) + "/children?\$top=200&\$select=$ITEM_FIELDS"
        while (url != null && list.size < 2000) {
            val page = call("GET", url) ?: return null
            val values = page.optJSONArray("value") ?: break
            for (i in 0 until values.length()) list += parseItem(values.getJSONObject(i))
            url = page.optString("@odata.nextLink").ifBlank { null }
        }
        return list
    }

    fun rename(id: String, name: String) = call("PATCH", itemUrl(id), JSONObject().put("name", name)) != null

    fun delete(id: String): Boolean {
        val token = accessToken() ?: return false
        val request = okhttp3.Request.Builder().url(itemUrl(id)).header("Authorization", "Bearer $token").delete().build()
        return try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun folderRef(folderId: String?): JSONObject {
        val id = folderId ?: call("GET", itemUrl(null) + "?\$select=id")?.optString("id")
        return JSONObject().put("id", id)
    }

    fun move(id: String, targetFolderId: String?) =
        call("PATCH", itemUrl(id) + "?@microsoft.graph.conflictBehavior=rename", JSONObject().put("parentReference", folderRef(targetFolderId))) != null

    /** Server-side copy: runs in the background on OneDrive, the result shows up shortly after. */
    fun copy(id: String, targetFolderId: String?): Boolean {
        val drive = call("GET", "$GRAPH?\$select=id")?.optString("id")
        val ref = folderRef(targetFolderId).apply { if (!drive.isNullOrBlank()) put("driveId", drive) }
        return call("POST", itemUrl(id) + "/copy?@microsoft.graph.conflictBehavior=rename", JSONObject().put("parentReference", ref)) != null
    }

    fun createFolder(parentId: String?, name: String): Item? = call(
        "POST", itemUrl(parentId) + "/children",
        JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "rename"),
    )?.let { parseItem(it) }

    /** Uploads a local file into [parentId] (null = root); big files go in chunks. */
    fun upload(file: File, parentId: String?): Boolean {
        val token = accessToken() ?: return false
        val target = itemUrl(parentId) + ":/" + Uri.encode(file.name) + ":"
        return try {
            if (file.length() <= SMALL_UPLOAD) {
                val request = okhttp3.Request.Builder().url("$target/content?@microsoft.graph.conflictBehavior=rename")
                    .header("Authorization", "Bearer $token")
                    .put(file.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                http.newCall(request).execute().use { it.isSuccessful }
            } else {
                val session = call(
                    "POST", "$target/createUploadSession",
                    JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "rename")),
                ) ?: return false
                val uploadUrl = session.optString("uploadUrl").ifBlank { return false }
                val total = file.length()
                val buffer = ByteArray(CHUNK)
                file.inputStream().use { input ->
                    var offset = 0L
                    while (offset < total) {
                        var read = 0
                        while (read < CHUNK) {
                            val n = input.read(buffer, read, CHUNK - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read == 0) return false
                        val chunk = buffer.toRequestBody("application/octet-stream".toMediaType(), 0, read)
                        val request = okhttp3.Request.Builder().url(uploadUrl)
                            .header("Content-Range", "bytes $offset-${offset + read - 1}/$total")
                            .put(chunk).build()
                        val ok = http.newCall(request).execute().use { it.isSuccessful }
                        if (!ok) return false
                        offset += read
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Downloads [item] to [target]. */
    fun downloadTo(item: Item, target: File): Boolean {
        val token = accessToken() ?: return false
        val request = okhttp3.Request.Builder().url(itemUrl(item.id) + "/content").header("Authorization", "Bearer $token").build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                response.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return false
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            target.delete()
            false
        }
    }

    /** Downloads [item] into the app cache so a local app (or the built-in viewers) can open it; null on errors. */
    fun downloadToCache(item: Item): File? {
        val dir = File(cacheDir, item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, item.name.replace('/', '_'))
        return if (downloadTo(item, target)) target else null
    }

    /** Removes the files downloaded to open or share them. */
    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    private val cacheDir get() = File(context.cacheDir, "onedrive")

    private fun randomString(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        /** "Application (client) ID" of the app registered on Microsoft Entra (not a secret). */
        const val CLIENT_ID = "583f7ca6-fa44-4333-a927-eb8f8580d3ab"
        const val REDIRECT_URI = "minimalauncher://auth"
        private const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        private const val SCOPES = "Files.ReadWrite offline_access"
        private const val OLD_SCOPES = "Files.Read offline_access"
        private const val GRAPH = "https://graph.microsoft.com/v1.0/me/drive"
        private const val ITEM_FIELDS = "id,name,folder,file,size,lastModifiedDateTime"
        private const val SMALL_UPLOAD = 4L * 1024 * 1024
        private const val CHUNK = 320 * 1024 * 16

        private const val KEY_SCOPE = "SCOPE"
        private const val KEY_ACCESS = "ACCESS"
        private const val KEY_REFRESH = "REFRESH"
        private const val KEY_EXPIRES = "EXPIRES"
        private const val KEY_VERIFIER = "VERIFIER"
        private const val KEY_STATE = "STATE"
    }
}

/** Receives Microsoft's redirect after sign-in, stores the tokens and goes back to OneDrive in the explorer. */
class OneDriveAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val ctx = applicationContext
        Thread {
            val error = if (data == null) "no data" else OneDrive(ctx).completeSignIn(data)
            runOnUiThread {
                if (error == null) {
                    ctx.showToast(ctx.getString(R.string.ml_onedrive_connected))
                    backToExplorer()
                } else {
                    // Show Microsoft's reason so it can be fixed (e.g. a wrong redirect URI)
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(R.string.ml_onedrive_failed)
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { backToExplorer() }
                        .show()
                }
            }
        }.start()
    }

    private fun backToExplorer() {
        FileExplorerActivity.openCloud(applicationContext)
        finish()
    }
}
