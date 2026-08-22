package com.xcast.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases updater, following the same idea as Neko:
 * check /releases/latest, compare versions, download the APK, then install.
 */
object AppUpdate {
    const val OWNER = "LinkofHyrule89"
    const val REPO = "XCast"
    const val SESSION_ACTION = "com.xcast.android.SESSION_API_PACKAGE_INSTALLED"
    private const val TAG = "XCastUpdate"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
        val sizeBytes: Long,
    )

    fun latest(): Release {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "XCast/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("GitHub returned HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            return parseRelease(JSONObject(body))
        }
    }

    fun isNewer(remoteTag: String, currentName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteTag)
        val current = parseVersion(currentName)
        val n = maxOf(remote.size, current.size)
        for (i in 0 until n) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        requireAllowed(url)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "XCast/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(request).execute().use { resp ->
            requireAllowed(resp.request.url.toString())
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) {
                throw IllegalStateException("Update file is too large")
            }
            var read = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > MAX_APK_BYTES) {
                        throw IllegalStateException("Update file is too large")
                    }
                    out.write(buf, 0, n)
                    if (total > 0) {
                        onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun apkFile(context: Context): File = File(File(context.cacheDir, "updates"), "XCast.apk")

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun install(context: Context, file: File) {
        if (!file.exists() || file.length() < 1_000L) {
            throw IllegalStateException("Downloaded update is missing")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canInstall(context)) {
            try {
                installSession(context, file)
                return
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller session failed, falling back", e)
            }
        }
        installPrompt(context, file)
    }

    private fun installSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.openWrite("package", 0, file.length()).use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        val intent = Intent(context, AppUpdateReceiver::class.java)
            .setAction(SESSION_ACTION)
            .putExtra("file", file.absolutePath)
        val pending = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        session.commit(pending.intentSender)
    }

    fun installPrompt(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun parseRelease(root: JSONObject): Release {
        val tag = root.optString("tag_name")
        if (tag.isBlank()) throw IllegalStateException("Latest GitHub release has no tag")
        val assets = root.optJSONArray("assets")
            ?: throw IllegalStateException("Latest GitHub release has no APK yet")
        var apkUrl = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                apkUrl = url
                size = asset.optLong("size")
                if (name.equals("XCast.apk", ignoreCase = true)) break
            }
        }
        if (apkUrl.isBlank()) {
            throw IllegalStateException("Latest GitHub release has no APK yet")
        }
        requireAllowed(apkUrl)
        return Release(
            tag = tag,
            version = tag.removePrefix("v"),
            notes = root.optString("body").ifBlank { "A new version of XCast is ready." },
            apkUrl = apkUrl,
            pageUrl = root.optString("html_url"),
            sizeBytes = size,
        )
    }

    private fun parseVersion(raw: String): List<Int> {
        return raw.trim().removePrefix("v").split('.', '-', '_')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
    }

    private fun requireAllowed(url: String) {
        val host = Uri.parse(url).host?.lowercase()
            ?: throw IllegalStateException("Invalid update URL")
        val ok = host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
        if (!ok) throw IllegalStateException("Blocked update host $host")
    }
}
