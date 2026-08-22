package com.xcast.android

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TweetVideoResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun resolve(sharedText: String): TweetVideo = withContext(Dispatchers.IO) {
        val statusId = TweetParser.extractStatusId(sharedText)
            ?: throw IllegalArgumentException("No X/Twitter post URL found in the shared text.")

        for (endpoint in endpoints(statusId)) {
            try {
                return@withContext fetchFxStyle(endpoint, statusId)
            } catch (e: Exception) {
                Log.w(TAG, "Lookup failed", e)
            }
        }
        throw IllegalStateException(
            "Could not load a video for this post. It may have no video, be private, or the lookup service is down.",
        )
    }

    private fun endpoints(statusId: String): List<String> = listOf(
        "https://api.fxtwitter.com/status/$statusId",
        "https://api.vxtwitter.com/i/status/$statusId",
    )

    private fun fetchFxStyle(url: String, statusId: String): TweetVideo {
        val body = httpGet(url)
        val root = JSONObject(body)
        val tweet = root.optJSONObject("tweet") ?: root
        val media = tweet.optJSONObject("media")
            ?: throw IllegalStateException("This post has no media.")

        val videos = media.optJSONArray("videos")
            ?: throw IllegalStateException("This post has no video.")
        if (videos.length() == 0) {
            throw IllegalStateException("This post has no video.")
        }

        val video = videos.getJSONObject(0)
        val variants = mergeVariantSources(video)
        val mp4s = variants.filter { it.isMp4 && !it.isHls }
            .distinctBy { "${it.width}x${it.height}-${it.bitrate}" }
            .sortedBy { it.height }

        if (mp4s.isEmpty() && variants.none { it.isHls }) {
            throw IllegalStateException("No playable video URL found.")
        }

        val videoTracks = mp4s.map { v ->
            val heightLabel = if (v.height > 0) "${v.height}p" else "MP4"
            val mbps = if (v.bitrate > 0) "%.1f Mbps".format(v.bitrate / 1_000_000.0) else "unknown bitrate"
            val fit = castFit(v.width, v.height, v.bitrate)
            VideoTrackOption(
                id = "mp4-${v.width}x${v.height}-${v.bitrate}",
                label = "$heightLabel · ${v.width}×${v.height}",
                detail = "$mbps · H.264 + AAC · ${fit.badge()}",
                url = v.url,
                contentType = "video/mp4",
                width = v.width,
                height = v.height,
                bitrate = v.bitrate,
                fit = fit,
                muxedAudio = true,
            )
        }

        val hlsUrl = variants.firstOrNull { it.isHls }?.url
        val hls = hlsUrl?.let { parseHlsQuietly(it) }

        val audioTracks = mutableListOf<AudioTrackOption>()
        audioTracks += AudioTrackOption(
            id = AUDIO_MUXED,
            label = "AAC (included in MP4)",
            detail = "Picture and sound in one file — most reliable on Chromecast",
            url = null,
            bitrate = 0,
            muxed = true,
        )
        hls?.audios?.sortedBy { it.bitrateHint }?.forEach { a ->
            val kbps = if (a.bitrateHint > 0) "${a.bitrateHint / 1000} kbps" else "HLS"
            audioTracks += AudioTrackOption(
                id = "hls-audio-${a.groupId}",
                label = "HLS ${a.name} · $kbps",
                detail = "Separate audio rendition from the X playlist",
                url = a.uri,
                bitrate = a.bitrateHint,
                muxed = false,
            )
        }

        // Adaptive HLS as a video option so the TV can pick a rendition.
        val tracks = videoTracks.toMutableList()
        if (hlsUrl != null) {
            val maxHls = hls?.videos?.maxByOrNull { it.height }
            tracks += VideoTrackOption(
                id = VIDEO_HLS,
                label = "Adaptive HLS" + (maxHls?.let { " (up to ${it.height}p)" } ?: ""),
                detail = "Lets the TV pick a rendition. Use with an HLS audio track.",
                url = hlsUrl,
                contentType = "application/x-mpegurl",
                width = maxHls?.width ?: 0,
                height = maxHls?.height ?: 0,
                bitrate = maxHls?.bandwidth ?: 0,
                fit = CastFit.RISKY,
                muxedAudio = false,
            )
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException("No playable video URL found.")
        }

        val author = tweet.optJSONObject("author")
        val handle = author?.optString("screen_name").orEmpty().ifBlank {
            author?.optString("username").orEmpty()
        }
        val name = author?.optString("name").orEmpty()
        val text = tweet.optString("text").ifBlank { tweet.optString("full_text") }
        val rawText = tweet.optString("raw_text")
        val duration = when {
            video.has("duration") && !video.isNull("duration") ->
                (video.optDouble("duration") * 1000).toLong()
            video.has("duration_millis") -> video.optLong("duration_millis")
            else -> null
        }

        return TweetVideo(
            statusId = statusId,
            title = text.ifBlank { "X video" }.take(120),
            subtitle = if (handle.isNotBlank()) "@$handle" else name.ifBlank { "X" },
            thumbnailUrl = video.optString("thumbnail_url").ifBlank {
                video.optString("thumbnail").ifBlank { null }
            },
            durationMs = duration,
            hlsMasterUrl = hlsUrl,
            videoTracks = tracks,
            audioTracks = audioTracks,
            captionTracks = buildCaptionTracks(hls),
            chapters = parseChapters(text, rawText, root, duration),
        )
    }

    private fun parseChapters(
        text: String,
        rawText: String,
        root: JSONObject,
        durationMs: Long?,
    ): List<VideoChapter> {
        val fromJson = chaptersFromJson(root, durationMs)
        if (fromJson.size >= 2) return fromJson
        val fromText = ChapterParser.parse(listOf(text, rawText).filter { it.isNotBlank() }.joinToString("\n"), durationMs)
        return if (fromJson.size > fromText.size) fromJson else fromText
    }

    private fun chaptersFromJson(root: JSONObject, durationMs: Long?): List<VideoChapter> {
        val found = mutableListOf<VideoChapter>()
        collectTimestampArrays(root, found)
        val duration = durationMs ?: Long.MAX_VALUE
        return found.filter { it.positionMs <= duration + 1_000 }
            .distinctBy { it.positionMs }
            .sortedBy { it.positionMs }
    }

    private fun collectTimestampArrays(value: Any?, out: MutableList<VideoChapter>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.contains("timestamp", ignoreCase = true) ||
                        key.equals("chapters", ignoreCase = true)
                    ) {
                        if (child is org.json.JSONArray) {
                            for (i in 0 until child.length()) {
                                val item = child.optJSONObject(i) ?: continue
                                parseTimestampObject(item)?.let { out += it }
                            }
                        }
                    } else {
                        collectTimestampArrays(child, out)
                    }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) collectTimestampArrays(value.opt(i), out)
            }
        }
    }

    private fun parseTimestampObject(obj: JSONObject): VideoChapter? {
        val start = when {
            obj.has("start_millis") -> obj.optLong("start_millis")
            obj.has("start_ms") -> obj.optLong("start_ms")
            obj.has("start_offset_ms") -> obj.optLong("start_offset_ms")
            obj.has("seconds") -> (obj.optDouble("seconds") * 1000).toLong()
            else -> ChapterParser.parseTimestamp(obj.optString("timestamp").ifBlank { obj.optString("time") }) ?: return null
        }
        val title = obj.optString("title").ifBlank {
            obj.optString("text").ifBlank { obj.optString("label") }
        }.ifBlank { "Chapter" }
        return VideoChapter(start, title.take(120), formatPosition(start))
    }

    private fun buildCaptionTracks(hls: HlsMaster?): List<CaptionTrackOption> {
        val out = mutableListOf(
            CaptionTrackOption(
                id = CAPTIONS_OFF,
                label = "Off",
                detail = "No captions on the TV",
                url = null,
                contentType = "text/vtt",
                language = null,
                closedCaptions = false,
            ),
        )
        hls?.subtitles.orEmpty().forEachIndexed { index, sub ->
            if (sub.uri.isBlank()) return@forEachIndexed
            val sidecar = resolveCaptionSidecar(sub.uri)
            val kind = if (sub.closedCaptions) "Closed captions" else "Subtitles"
            val auto = if (sub.autoGenerated) " · auto-generated" else ""
            val lang = sub.language?.uppercase() ?: "und"
            out += CaptionTrackOption(
                id = "cap-$index-${sub.groupId}-$lang",
                label = "$kind · ${sub.name}",
                detail = "$lang$auto · WebVTT for Chromecast",
                url = sidecar ?: sub.uri,
                contentType = if ((sidecar ?: sub.uri).contains(".vtt")) "text/vtt" else "text/vtt",
                language = sub.language,
                closedCaptions = sub.closedCaptions,
            )
        }
        return out
    }

    private fun resolveCaptionSidecar(playlistOrVtt: String): String? {
        if (playlistOrVtt.contains(".vtt")) return playlistOrVtt
        return try {
            val body = httpGet(playlistOrVtt)
            HlsMasterParser.firstMediaUri(playlistOrVtt, body)
        } catch (e: Exception) {
            Log.w(TAG, "Caption playlist fetch failed", e)
            null
        }
    }

    private fun parseHlsQuietly(url: String): HlsMaster? = try {
        HlsMasterParser.parse(url, httpGet(url))
    } catch (e: Exception) {
        Log.w(TAG, "HLS master parse failed", e)
        null
    }

    private fun mergeVariantSources(video: JSONObject): List<RawVariant> {
        val out = mutableListOf<RawVariant>()
        collectVariants(video.optJSONArray("variants"), out)
        collectFormats(video.optJSONArray("formats"), out)
        video.optString("url").takeIf { it.isNotBlank() && SafeUrls.isAllowedHttps(it) }?.let {
            out += RawVariant(it, 0, guessContentType(it), parseSize(it))
        }
        return out.distinctBy { it.url }
    }

    private fun collectVariants(array: JSONArray?, out: MutableList<RawVariant>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val url = v.optString("url")
            if (url.isBlank() || !SafeUrls.isAllowedHttps(url)) continue
            val type = v.optString("content_type").ifBlank {
                v.optString("type", guessContentType(url))
            }
            out += RawVariant(url, v.optInt("bitrate", 0), type, parseSize(url))
        }
    }

    private fun collectFormats(array: JSONArray?, out: MutableList<RawVariant>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            val url = v.optString("url")
            if (url.isBlank() || !SafeUrls.isAllowedHttps(url)) continue
            val container = v.optString("container")
            val type = when {
                container.contains("m3u8") || url.contains(".m3u8") -> "application/x-mpegurl"
                container.contains("mp4") || url.contains(".mp4") -> "video/mp4"
                else -> guessContentType(url)
            }
            out += RawVariant(url, v.optInt("bitrate", 0), type, parseSize(url))
        }
    }

    private fun parseSize(url: String): Pair<Int, Int> {
        val match = SIZE_IN_URL.find(url) ?: return 0 to 0
        return (match.groupValues[1].toIntOrNull() ?: 0) to (match.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun guessContentType(url: String): String = when {
        url.contains(".m3u8") -> "application/x-mpegurl"
        url.contains(".mp4") -> "video/mp4"
        url.contains(".webm") -> "video/webm"
        else -> "video/mp4"
    }

    private fun httpGet(url: String): String {
        SafeUrls.requireAllowed(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) XCast/1.0")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            SafeUrls.requireAllowed(finalUrl)
            if (!response.isSuccessful) {
                throw IllegalStateException("Lookup failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw IllegalStateException("Empty response")
            val length = body.contentLength()
            if (length > MAX_BODY_BYTES) {
                throw IllegalStateException("Response too large")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_BODY_BYTES) {
                throw IllegalStateException("Response too large")
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private data class RawVariant(
        val url: String,
        val bitrate: Int,
        val contentType: String,
        val size: Pair<Int, Int>,
    ) {
        val width: Int get() = size.first
        val height: Int get() = size.second
        val isHls: Boolean
            get() = contentType.contains("mpeg", ignoreCase = true) || url.contains(".m3u8")
        val isMp4: Boolean
            get() = contentType.contains("mp4", ignoreCase = true) || url.contains(".mp4")
    }

    companion object {
        private const val TAG = "XCastResolver"
        const val AUDIO_MUXED = "audio-muxed"
        const val VIDEO_HLS = "video-hls"
        const val CAPTIONS_OFF = "captions-off"
        private val SIZE_IN_URL = Regex("""(\d{2,5})x(\d{2,5})""")
        private const val MAX_BODY_BYTES = 2_000_000
    }
}
