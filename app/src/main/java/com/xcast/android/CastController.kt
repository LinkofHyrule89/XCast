package com.xcast.android

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

    fun load(
        session: CastSession,
        video: TweetVideo,
        videoTrack: VideoTrackOption,
        audioTrack: AudioTrackOption?,
        captionTrack: CaptionTrackOption?,
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

        Log.i(TAG, "Loading ${videoTrack.label} audio=${audioTrack?.label} cc=${captionTrack?.label} $url")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title)
            putString(MediaMetadata.KEY_SUBTITLE, video.subtitle)
            video.thumbnailUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val tracks = buildSidecarTracks(audioTrack, captionTrack, useHls)
        val activeIds = activeIdsFor(audioTrack, captionTrack, useHls)

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .apply {
                video.durationMs?.let { setStreamDuration(it) }
                if (tracks.isNotEmpty()) {
                    setMediaTracks(tracks)
                }
                if (captionTrack != null && captionTrack.id != TweetVideoResolver.CAPTIONS_OFF) {
                    setTextTrackStyle(TextTrackStyle())
                }
            }
            .build()

        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .apply {
                    if (activeIds.isNotEmpty()) {
                        setActiveTrackIds(activeIds)
                    }
                }
                .build(),
        ).setResultCallback { result ->
            if (result.status.isSuccess) {
                Log.i(TAG, "Load ok")
                applyActiveTracks(client, audioTrack, captionTrack)
                onSuccess()
            } else {
                val msg = result.status.statusMessage ?: "status ${result.status.statusCode}"
                Log.w(TAG, "Load failed: $msg")
                onFailure("TV rejected ${videoTrack.label}: $msg")
            }
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
}
