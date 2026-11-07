package com.xcast.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serves Twitter MP4s to the Cast receiver over HTTP/1.1 on the LAN.
 * Sony Bravia / Google TV often cannot fetch video.twimg.com (HTTP/2) itself.
 */
object CastMediaServer {
    const val PORT = 8765
    private const val TAG = "XCastProxy"
    private const val UA = "Mozilla/5.0 (CrKey armv7l 1.5.16041) AppleWebKit/537.36"
    private const val PREFIX_BYTES = 512 * 1024
    private const val SUFFIX_BYTES = 1024 * 1024
    private const val COPY_BUF = 64 * 1024

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            },
        )
        .build()

    @Volatile
    private var socket: ServerSocket? = null

    private val bufferLock = ReentrantLock()
    @Volatile
    private var pauseBuffer: Mp4PauseBuffer? = null
    @Volatile
    private var pauseListener: ((PauseBufferSnapshot) -> Unit)? = null

    private val hintsLock = Any()
    private val hints = object : LinkedHashMap<String, FileHint>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileHint>?) = size > 4
    }
    private val warming = HashSet<String>()

    private class FileHint(
        val contentLength: Long,
        val contentType: String,
        @Volatile var prefix: ByteArray = ByteArray(0),
        @Volatile var suffix: ByteArray = ByteArray(0),
        @Volatile var suffixStart: Long = -1L,
    )

    @Synchronized
    fun ensureStarted(context: Context? = null) {
        if (context != null) pauseBuffer(context)
        if (socket != null && !socket!!.isClosed) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(PORT))
        socket = server
        Thread({
            while (true) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ handle(s) }, "xcast-proxy").apply { isDaemon = true; start() }
            }
        }, "xcast-proxy-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "LAN proxy listening on $PORT")
    }

    fun lanUrl(context: Context, sourceHttps: String, warmup: Boolean = true): String? {
        if (!SafeUrls.isAllowedHttps(sourceHttps)) return null
        if (sourceHttps.contains(".m3u8")) return null
        val ip = ipv4(context) ?: return null
        ensureStarted()
        if (warmup) warmup(sourceHttps)
        val encoded = URLEncoder.encode(sourceHttps, StandardCharsets.UTF_8.name())
        val url = "http://$ip:$PORT/cast?u=$encoded"
        Log.i(TAG, "TV will fetch $url")
        return url
    }

    fun warmup(sourceHttps: String) {
        val source = stripQuery(sourceHttps)
        if (!SafeUrls.isAllowedHttps(source) || source.contains(".m3u8")) return
        synchronized(hintsLock) {
            if (source in warming) return
            val existing = hints[source]
            if (existing != null && existing.prefix.isNotEmpty() && existing.suffix.isNotEmpty()) return
            warming += source
        }
        Thread({
            try {
                fetchHint(source)
            } catch (e: Exception) {
                Log.w(TAG, "warmup failed", e)
            } finally {
                synchronized(hintsLock) { warming -= source }
            }
        }, "xcast-warmup").apply { isDaemon = true; start() }
    }

    fun setPauseBufferListener(listener: ((PauseBufferSnapshot) -> Unit)?) {
        pauseListener = listener
        pauseBuffer?.setListener(listener)
    }

    fun pauseBufferSnapshot(): PauseBufferSnapshot {
        return pauseBuffer?.snapshot() ?: PauseBufferSnapshot(PauseBufferReason.HIDDEN, 0L, 0L)
    }

    fun onCastPaused(context: Context, contentId: String?) {
        dispatchCast(context, contentId, paused = true)
    }

    fun onCastPlaying(context: Context, contentId: String?) {
        dispatchCast(context, contentId, paused = false)
    }

    fun onCastStopped() {
        pauseBuffer?.discard()
    }

    private fun dispatchCast(context: Context, contentId: String?, paused: Boolean) {
        if (contentId.isNullOrBlank()) return
        val source = proxiedMp4Source(contentId)
        if (source == null) {
            pauseBuffer?.discard()
            return
        }
        val buffer = pauseBuffer(context)
        if (paused) buffer.onPaused(source) else buffer.onPlaying(source)
    }

    internal fun proxiedMp4Source(contentId: String?): String? {
        if (contentId.isNullOrBlank() || !contentId.contains("/cast?u=")) return null
        val encoded = contentId.substringAfter("u=", "").substringBefore("&")
        if (encoded.isBlank()) return null
        val source = try {
            stripQuery(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))
        } catch (_: Exception) {
            return null
        }
        if (!SafeUrls.isAllowedHttps(source) || source.contains(".m3u8")) return null
        return source
    }

    private fun pauseBuffer(context: Context): Mp4PauseBuffer {
        pauseBuffer?.let { return it }
        return bufferLock.withLock {
            pauseBuffer?.let { return it }
            val created = Mp4PauseBuffer(
                directory = File(context.cacheDir, "pause-buffer"),
                upstream = OkHttpPauseUpstream(client, UA),
            )
            created.setListener { snap ->
                Log.i(TAG, "pause-buffer ${snap.reason} saved=${snap.savedBytes} total=${snap.totalBytes}")
                pauseListener?.invoke(snap)
            }
            pauseBuffer = created
            created
        }
    }

    private fun serveFromPauseCache(
        sock: Socket,
        source: String,
        rangeHeader: String?,
        headOnly: Boolean,
    ): Boolean {
        val opened = pauseBuffer?.openRange(source, rangeHeader) ?: return false
        opened.use { hit ->
            val out = sock.getOutputStream()
            out.write(cachedHttpHead(hit.status, hit.contentRange, hit.length).toByteArray(StandardCharsets.US_ASCII))
            if (!headOnly) hit.writeTo(out)
            out.flush()
            Log.i(TAG, "pause-cache hit status=${hit.status} bytes=${hit.length}")
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun ipv4(context: Context): String? {
        val wifi = wifiLegacy(context)
        if (wifi != null && isPrivateLan(wifi)) {
            Log.i(TAG, "proxy LAN IP from Wi-Fi $wifi")
            return wifi
        }
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun addrs(filter: (NetworkCapabilities) -> Boolean): String? {
            return cm.allNetworks.firstNotNullOfOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstNotNullOfOrNull null
                if (!filter(caps)) return@firstNotNullOfOrNull null
                cm.getLinkProperties(net)?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    ?.firstOrNull { isPrivateLan(it) }
            }
        }
        val fromWifi = addrs { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        if (fromWifi != null) {
            Log.i(TAG, "proxy LAN IP from Wi-Fi network $fromWifi")
            return fromWifi
        }
        val fromLan = addrs { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        Log.i(TAG, "proxy LAN IP $fromLan")
        return fromLan
    }

    private fun isPrivateLan(addr: String): Boolean {
        if (addr.startsWith("127.") || addr.startsWith("100.") || addr.startsWith("169.254.")) {
            return false
        }
        return addr.startsWith("192.168.") ||
            addr.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").containsMatchIn(addr)
    }

    @Suppress("DEPRECATION")
    private fun wifiLegacy(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
    }

    private fun handle(sock: Socket) {
        sock.tcpNoDelay = true
        try {
            sock.sendBufferSize = 256 * 1024
        } catch (_: Exception) {
        }
        sock.soTimeout = 20_000
        try {
            val input = sock.getInputStream()
            val headerBytes = readHeaders(input) ?: return
            val headers = headerBytes.decodeToString()
            val requestLine = headers.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeStatus(sock.getOutputStream(), 400, "Bad Request")
                return
            }
            val method = parts[0]
            if (method != "GET" && method != "HEAD") {
                writeStatus(sock.getOutputStream(), 405, "Method Not Allowed")
                return
            }
            val path = parts[1]
            val encoded = path.substringAfter("u=", "").substringBefore("&")
            val source = stripQuery(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))
            if (!SafeUrls.isAllowedHttps(source)) {
                writeStatus(sock.getOutputStream(), 403, "Forbidden")
                return
            }
            val rangeHeader = headers.lineSequence()
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
            sock.soTimeout = 0
            if (serveFromPauseCache(sock, source, rangeHeader, method == "HEAD")) return
            if (serveFromHint(sock, source, rangeHeader, method == "HEAD")) return
            proxyOrigin(sock, source, rangeHeader, method == "HEAD")
        } catch (e: Exception) {
            Log.w(TAG, "proxy error", e)
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serveFromHint(
        sock: Socket,
        source: String,
        rangeHeader: String?,
        headOnly: Boolean,
    ): Boolean {
        val hint = synchronized(hintsLock) { hints[source] } ?: return false
        val spec = HttpByteRanges.parse(rangeHeader, hint.contentLength) ?: return false
        val data = sliceHint(hint, spec) ?: return false
        val end = spec.start + data.size - 1
        val out = sock.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 206 Partial Content\r\n")
        sb.append("Content-Type: ${hint.contentType}\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Content-Length: ${data.size}\r\n")
        sb.append("Content-Range: bytes ${spec.start}-$end/${hint.contentLength}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            out.write(data)
        }
        out.flush()
        Log.i(TAG, "hint hit ${spec.start}-${end} of ${hint.contentLength}")
        return true
    }

    private fun proxyOrigin(sock: Socket, source: String, rangeHeader: String?, headOnly: Boolean) {
        val req = Request.Builder()
            .url(source)
            .header("User-Agent", UA)
            .apply { if (!rangeHeader.isNullOrBlank()) header("Range", rangeHeader) }
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: run {
                writeStatus(sock.getOutputStream(), 502, "Bad Gateway")
                return
            }
            val out = sock.getOutputStream()
            val code = resp.code
            val reason = if (code == 206) "Partial Content" else "OK"
            val type = resp.header("Content-Type") ?: "video/mp4"
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $code $reason\r\n")
            sb.append("Content-Type: $type\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Connection: close\r\n")
            resp.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
            resp.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
            if (!headOnly) {
                copyBuffered(body.byteStream(), out)
            }
            out.flush()
        }
    }

    private fun fetchHint(source: String) {
        val prefixReq = Request.Builder()
            .url(source)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-${PREFIX_BYTES - 1}")
            .build()
        client.newCall(prefixReq).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) return
            val length = contentLengthOf(resp) ?: return
            val type = resp.header("Content-Type") ?: "video/mp4"
            val prefix = readAtMost(resp.body?.byteStream() ?: return, PREFIX_BYTES)
            val hint = FileHint(length, type, prefix = prefix)
            synchronized(hintsLock) { hints[source] = hint }
            Log.i(TAG, "warmed prefix ${prefix.size} of $length $source")
        }
        val hint = synchronized(hintsLock) { hints[source] } ?: return
        if (hint.contentLength <= PREFIX_BYTES.toLong() + SUFFIX_BYTES) return
        val start = hint.contentLength - SUFFIX_BYTES
        val suffixReq = Request.Builder()
            .url(source)
            .header("User-Agent", UA)
            .header("Range", "bytes=$start-")
            .build()
        client.newCall(suffixReq).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) return
            val suffix = readAtMost(resp.body?.byteStream() ?: return, SUFFIX_BYTES)
            hint.suffix = suffix
            hint.suffixStart = start
            Log.i(TAG, "warmed suffix ${suffix.size} @ $start")
        }
    }

    private fun contentLengthOf(resp: okhttp3.Response): Long? {
        return responseTotalLength(
            resp.code,
            resp.header("Content-Range"),
            resp.header("Content-Length")?.toLongOrNull() ?: -1L,
        )
    }

    private fun sliceHint(hint: FileHint, spec: ByteRange): ByteArray? {
        val length = spec.endInclusive - spec.start + 1
        if (length <= 0L || length > Int.MAX_VALUE) return null
        val prefixEnd = hint.prefix.size.toLong()
        if (spec.start < prefixEnd && spec.endInclusive < prefixEnd) {
            return hint.prefix.copyOfRange(spec.start.toInt(), (spec.endInclusive + 1).toInt())
        }
        val suffixStart = hint.suffixStart
        if (suffixStart >= 0L && hint.suffix.isNotEmpty() &&
            spec.start >= suffixStart &&
            spec.endInclusive < suffixStart + hint.suffix.size
        ) {
            val from = (spec.start - suffixStart).toInt()
            val to = (spec.endInclusive - suffixStart + 1).toInt()
            return hint.suffix.copyOfRange(from, to)
        }
        return null
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(max.coerceAtMost(64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun copyBuffered(input: InputStream, output: OutputStream) {
        val buf = ByteArray(COPY_BUF)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

    private fun stripQuery(url: String): String {
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }

    private fun readHeaders(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            val bytes = buf.toByteArray()
            if (bytes.size >= 4 &&
                bytes[bytes.size - 4] == end[0] &&
                bytes[bytes.size - 3] == end[1] &&
                bytes[bytes.size - 2] == end[2] &&
                bytes[bytes.size - 1] == end[3]
            ) {
                return bytes
            }
        }
        return null
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        val msg = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(msg.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }
}
