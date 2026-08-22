package com.xcast.android

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

object CastController {
    private const val TAG = "XCastCast"
    const val AUDIO_TRACK_ID = 1L
    const val TEXT_TRACK_ID = 100L
    @Volatile
    private var loadSeq = 0

    fun load(
        session: CastSession,
        video: TweetVideo,
        videoTrack: VideoTrackOption,
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
        startPositionMs: Long = 0L,
        context: Context? = null,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val client = session.remoteMediaClient
        if (client == null) {
            onFailure("Cast session has no media client yet.")
            return
        }

        val useHls = videoTrack.id == TweetVideoResolver.VIDEO_HLS ||
            (audioTrack != null && !audioTrack.muxed)
        val url = SafeUrls.requireAllowed(
            if (useHls) {
                video.hlsMasterUrl ?: videoTrack.url
            } else {
                videoTrack.url
            },
        )
        val contentType = if (useHls || url.contains(".m3u8")) {
            "application/x-mpegurl"
        } else {
            videoTrack.contentType
        }

        val seq = ++loadSeq
        val queue = playbackQueue(video, videoTrack, url, contentType, context)
        loadAttempt(
            client = client,
            video = video,
            audioTrack = audioTrack,
            captionTrack = captionTrack,
            startPositionMs = startPositionMs,
            queue = queue,
            index = 0,
            seq = seq,
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }

    private data class PlayUrl(
        val url: String,
        val contentType: String,
        val label: String,
        val useHls: Boolean,
    )

    private fun playbackQueue(
        video: TweetVideo,
        selected: VideoTrackOption,
        selectedUrl: String,
        selectedType: String,
        context: Context?,
    ): List<PlayUrl> {
        val out = mutableListOf<PlayUrl>()
        fun add(url: String, type: String, label: String, hls: Boolean, warmup: Boolean) {
            val playUrl = if (!hls && context != null) {
                CastMediaServer.lanUrl(context, url, warmup = warmup) ?: url
            } else {
                url
            }
            if (playUrl.startsWith("https://") && !SafeUrls.isAllowedHttps(playUrl)) return
            if (out.any { it.url == playUrl }) return
            out += PlayUrl(playUrl, type, label, hls)
        }
        val selectedIsHls = selectedType.contains("mpegurl")
        add(stripQuery(selectedUrl), selectedType, selected.label, selectedIsHls, warmup = !selectedIsHls)
        val fallbacks = video.videoTracks
            .filter { it.contentType.contains("mp4") && it.fit != CastFit.UNSUPPORTED }
            .filter { it.id != selected.id }
            .sortedWith(compareByDescending<VideoTrackOption> { it.height }.thenByDescending { it.bitrate })
        for (track in fallbacks) {
            add(stripQuery(track.url), "video/mp4", track.label, false, warmup = false)
        }
        return out
    }

    private fun loadAttempt(
        client: RemoteMediaClient,
        video: TweetVideo,
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
        startPositionMs: Long,
        queue: List<PlayUrl>,
        index: Int,
        seq: Int,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (seq != loadSeq) return
        if (index >= queue.size) {
            onFailure("TV could not play any stream for this post (tried ${queue.joinToString { it.label }}).")
            return
        }
        val item = queue[index]
        Log.i(TAG, "Loading ${item.label} ${item.contentType} ${item.url} at ${startPositionMs}ms")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title.take(80))
            video.subtitle.takeIf { it.isNotBlank() }?.let {
                putString(MediaMetadata.KEY_SUBTITLE, it)
            }
            video.thumbnailUrl?.takeIf { SafeUrls.isAllowedHttps(it) }?.let { thumb ->
                addImage(WebImage(Uri.parse(thumb)))
            }
        }

        // Sidecar audio/VTT on the Default Media Receiver often yields
        // "Invalid Request" and IDLE_REASON_ERROR. Load a bare media item first.
        val mediaInfo = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.contentType)
            .setMetadata(metadata)
            .apply {
                val duration = video.durationMs
                if (duration != null && duration > 0L) setStreamDuration(duration)
            }
            .build()

        val durationHint = video.durationMs ?: 0L
        val startOnLoad = startPositionMs in 1_000L until (durationHint - 8_000L).coerceAtLeast(1_000L)
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .apply { if (startOnLoad) setCurrentTime(startPositionMs) }
            .build()

        val playbackWatch = object : RemoteMediaClient.Callback() {
            private var finished = false
            var armed = false
            override fun onStatusUpdated() {
                if (finished || !armed || seq != loadSeq) return
                val status = client.mediaStatus ?: return
                when (status.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING,
                    MediaStatus.PLAYER_STATE_PAUSED,
                    MediaStatus.PLAYER_STATE_BUFFERING,
                    -> {
                        finished = true
                        client.unregisterCallback(this)
                        Log.i(TAG, "Playing ${item.label}")
                        maybeSeekToResume(client, video, startPositionMs)
                        applyActiveTracks(client, audioTrack, captionTrack)
                        onSuccess()
                    }
                    MediaStatus.PLAYER_STATE_IDLE -> {
                        val reason = status.idleReason
                        if (reason == MediaStatus.IDLE_REASON_ERROR ||
                            reason == MediaStatus.IDLE_REASON_CANCELED
                        ) {
                            finished = true
                            client.unregisterCallback(this)
                            Log.w(TAG, "Idle error on ${item.label} reason=$reason")
                            loadAttempt(
                                client, video, audioTrack, captionTrack, startPositionMs,
                                queue, index + 1, seq, onSuccess, onFailure,
                            )
                        }
                    }
                }
            }
        }
        client.registerCallback(playbackWatch)

        client.load(request).setResultCallback { result ->
            if (seq != loadSeq) {
                client.unregisterCallback(playbackWatch)
                return@setResultCallback
            }
            if (!result.status.isSuccess) {
                client.unregisterCallback(playbackWatch)
                val msg = result.status.statusMessage ?: "status ${result.status.statusCode}"
                Log.w(TAG, "Load failed for ${item.label}: $msg")
                loadAttempt(
                    client, video, audioTrack, captionTrack, startPositionMs,
                    queue, index + 1, seq, onSuccess, onFailure,
                )
            } else {
                Log.i(TAG, "Load command accepted for ${item.label}")
                playbackWatch.armed = true
                playbackWatch.onStatusUpdated()
            }
        }
    }

    private fun maybeSeekToResume(
        client: RemoteMediaClient,
        video: TweetVideo,
        startPositionMs: Long,
    ) {
        if (startPositionMs < 1_000L) return
        val duration = client.streamDuration.takeIf { it > 0 } ?: video.durationMs ?: 0L
        if (startPositionMs >= (duration - 8_000L).coerceAtLeast(1_000L)) return
        val pos = client.approximateStreamPosition
        if (kotlin.math.abs(pos - startPositionMs) > 2_500L) {
            seekTo(client, startPositionMs)
        }
    }

    fun applyActiveTracks(
        client: RemoteMediaClient,
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
    ) {
        val ids = mutableListOf<Long>()
        val discovered = client.mediaInfo?.mediaTracks.orEmpty()

        if (audioTrack != null && !audioTrack.muxed) {
            val match = discovered.firstOrNull { track ->
                track.type == MediaTrack.TYPE_AUDIO &&
                    (
                        track.name?.contains(audioTrack.label.substringAfter("· ").trim(), ignoreCase = true) == true ||
                            (audioTrack.bitrate > 0 && track.name?.contains((audioTrack.bitrate / 1000).toString()) == true)
                        )
            } ?: discovered.firstOrNull { it.type == MediaTrack.TYPE_AUDIO }
            ids += match?.id ?: AUDIO_TRACK_ID
        }

        val captionsOn = captionTrack != null &&
            captionTrack.id != TweetVideoResolver.CAPTIONS_OFF &&
            !captionTrack.url.isNullOrBlank()
        if (captionsOn) {
            val match = discovered.firstOrNull { track ->
                track.type == MediaTrack.TYPE_TEXT &&
                    (
                        track.language.equals(captionTrack.language, ignoreCase = true) ||
                            track.name?.contains(captionTrack.label.take(8), ignoreCase = true) == true ||
                            track.id == TEXT_TRACK_ID
                        )
            } ?: discovered.firstOrNull { it.type == MediaTrack.TYPE_TEXT }
            ids += match?.id ?: TEXT_TRACK_ID
        }

        if (ids.isEmpty()) return
        Log.i(TAG, "Active tracks $ids captionsOn=$captionsOn")
        client.setActiveMediaTracks(ids.toLongArray())
    }

    private fun buildSidecarTracks(
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
        useHls: Boolean,
    ): List<MediaTrack> {
        val tracks = mutableListOf<MediaTrack>()
        if (useHls && audioTrack != null && !audioTrack.muxed && !audioTrack.url.isNullOrBlank()) {
            tracks += MediaTrack.Builder(AUDIO_TRACK_ID, MediaTrack.TYPE_AUDIO)
                .setName(audioTrack.label)
                .setContentId(audioTrack.url)
                .setLanguage("en")
                .build()
        }
        if (captionTrack != null &&
            captionTrack.id != TweetVideoResolver.CAPTIONS_OFF &&
            !captionTrack.url.isNullOrBlank()
        ) {
            val subtype = if (captionTrack.closedCaptions) {
                MediaTrack.SUBTYPE_CAPTIONS
            } else {
                MediaTrack.SUBTYPE_SUBTITLES
            }
            tracks += MediaTrack.Builder(TEXT_TRACK_ID, MediaTrack.TYPE_TEXT)
                .setSubtype(subtype)
                .setName(captionTrack.label)
                .setContentId(captionTrack.url)
                .setContentType(captionTrack.contentType.ifBlank { "text/vtt" })
                .setLanguage(captionTrack.language ?: "en")
                .build()
        }
        return tracks
    }

    private fun activeIdsFor(
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
        useHls: Boolean,
    ): LongArray {
        val ids = mutableListOf<Long>()
        if (useHls && audioTrack != null && !audioTrack.muxed) {
            ids += AUDIO_TRACK_ID
        }
        if (captionTrack != null && captionTrack.id != TweetVideoResolver.CAPTIONS_OFF) {
            ids += TEXT_TRACK_ID
        }
        return ids.toLongArray()
    }

    private fun stripQuery(url: String): String {
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }

    fun togglePlayPause(client: RemoteMediaClient) {
        if (client.isPlaying) client.pause() else client.play()
    }

    fun seekTo(client: RemoteMediaClient, positionMs: Long) {
        val duration = client.streamDuration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, duration)
        client.seek(
            MediaSeekOptions.Builder()
                .setPosition(clamped)
                .setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED)
                .build(),
        )
    }

    fun skip(client: RemoteMediaClient, deltaMs: Long) {
        seekTo(client, client.approximateStreamPosition + deltaMs)
    }

    fun isPlaying(client: RemoteMediaClient): Boolean {
        val state = client.playerState
        return state == MediaStatus.PLAYER_STATE_PLAYING || state == MediaStatus.PLAYER_STATE_BUFFERING
    }

    fun setVolume(session: CastSession, volume: Double) {
        session.volume = volume.coerceIn(0.0, 1.0)
    }

    fun setMuted(session: CastSession, muted: Boolean) {
        session.isMute = muted
    }

    fun setSpeed(client: RemoteMediaClient, rate: Double) {
        client.setPlaybackRate(rate)
    }

    fun prevChapterPosition(chapters: List<VideoChapter>, positionMs: Long): Long {
        if (chapters.isEmpty()) return 0L
        val current = chapters.lastOrNull { it.positionMs <= positionMs } ?: chapters.first()
        val into = positionMs - current.positionMs
        if (into > 2_000L) return current.positionMs
        val idx = chapters.indexOf(current)
        return if (idx > 0) chapters[idx - 1].positionMs else 0L
    }

    fun nextChapterPosition(chapters: List<VideoChapter>, positionMs: Long, durationMs: Long): Long {
        if (chapters.isEmpty()) return durationMs
        val next = chapters.firstOrNull { it.positionMs > positionMs + 500 }
        return next?.positionMs ?: durationMs.coerceAtLeast(positionMs)
    }
}
