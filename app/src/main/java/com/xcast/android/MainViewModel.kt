package com.xcast.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LookupError {
    NONE,
    EMPTY,
    NO_ID,
    NO_VIDEO,
    LOOKUP_FAILED,
    REJECTED,
}

data class UiState(
    val loading: Boolean = false,
    val message: String = "Share an X post that contains a video, or paste the post URL.",
    val video: TweetVideo? = null,
    val selectedVideoId: String? = null,
    val selectedAudioId: String? = null,
    val selectedCaptionId: String? = null,
    val castableOnly: Boolean = true,
    val recents: List<RecentItem> = emptyList(),
    val error: LookupError = LookupError.NONE,
    val resumeAtMs: Long = 0L,
    val lastUrl: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = TweetVideoResolver()
    private val store = PrefsStore(application)
    private val _uiState = MutableStateFlow(
        UiState(
            recents = store.recents(),
            castableOnly = store.castableOnly,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState

    private var pendingCast = false
    private var silentRecast = false

    fun resolve(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    video = null,
                    error = LookupError.EMPTY,
                    message = "Paste an X post URL first.",
                )
            }
            return
        }
        if (TweetParser.extractStatusId(text) == null) {
            _uiState.update {
                it.copy(
                    loading = false,
                    video = null,
                    error = LookupError.NO_ID,
                    message = "No X/Twitter post URL found.",
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, message = "Looking up video…", video = null, error = LookupError.NONE, lastUrl = text)
            }
            try {
                val video = resolver.resolve(text)
                val defaultVideo = defaultVideoTrack(video)
                defaultVideo?.url?.let { CastMediaServer.warmup(it) }
                store.upsertRecent(video)
                val resume = store.resumePosition(video.statusId) ?: 0L
                _uiState.value = UiState(
                    loading = false,
                    message = "Ready — ${defaultVideo?.label ?: "video"} selected. Tap Cast to TV.",
                    video = video,
                    selectedVideoId = defaultVideo?.id,
                    selectedAudioId = TweetVideoResolver.AUDIO_MUXED,
                    selectedCaptionId = TweetVideoResolver.CAPTIONS_OFF,
                    castableOnly = store.castableOnly,
                    recents = store.recents(),
                    error = LookupError.NONE,
                    resumeAtMs = resume,
                    lastUrl = text,
                )
            } catch (e: Exception) {
                pendingCast = false
                val msg = e.message.orEmpty()
                val err = when {
                    msg.contains("no video", true) || msg.contains("no media", true) -> LookupError.NO_VIDEO
                    else -> LookupError.LOOKUP_FAILED
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        message = e.message ?: "Failed to resolve video.",
                        video = null,
                        error = err,
                        recents = store.recents(),
                    )
                }
            }
        }
    }

    fun retryLookup() {
        val url = _uiState.value.lastUrl
        if (url.isNotBlank()) resolve(url)
    }

    fun markRejected(message: String) {
        _uiState.update { it.copy(error = LookupError.REJECTED, message = message) }
    }

    fun selectVideo(id: String): Boolean {
        if (_uiState.value.selectedVideoId == id) return false
        _uiState.update { state ->
            val track = state.video?.videoTracks?.firstOrNull { it.id == id }
            val audioId = if (track?.muxedAudio == false) {
                state.video?.audioTracks?.lastOrNull { !it.muxed }?.id ?: state.selectedAudioId
            } else {
                TweetVideoResolver.AUDIO_MUXED
            }
            if (track != null) {
                store.lastHeightBand = when {
                    track.id == TweetVideoResolver.VIDEO_HLS -> 0
                    else -> track.height
                }
            }
            state.copy(selectedVideoId = id, selectedAudioId = audioId)
        }
        selectedVideo()?.url?.let { CastMediaServer.warmup(it) }
        return true
    }

    fun selectCaption(id: String) {
        _uiState.update { it.copy(selectedCaptionId = id) }
    }

    fun selectAudio(id: String) {
        _uiState.update { state ->
            val audio = state.video?.audioTracks?.firstOrNull { it.id == id }
            val videoId = if (audio != null && !audio.muxed) {
                TweetVideoResolver.VIDEO_HLS
            } else {
                state.selectedVideoId
            }
            state.copy(selectedAudioId = id, selectedVideoId = videoId)
        }
    }

    fun setCastableOnly(enabled: Boolean) {
        store.castableOnly = enabled
        _uiState.update { state ->
            val next = state.copy(castableOnly = enabled)
            val visible = next.video?.videoTracks.orEmpty().filter {
                !enabled || it.fit != CastFit.UNSUPPORTED
            }
            val selectedStillVisible = visible.any { it.id == next.selectedVideoId }
            if (selectedStillVisible) next
            else next.copy(selectedVideoId = visible.maxByOrNull { it.height }?.id ?: next.selectedVideoId)
        }
    }

    fun selectedVideo(): VideoTrackOption? {
        val state = _uiState.value
        return state.video?.videoTracks?.firstOrNull { it.id == state.selectedVideoId }
    }

    fun selectedAudio(): AudioTrackOption? {
        val state = _uiState.value
        return state.video?.audioTracks?.firstOrNull { it.id == state.selectedAudioId }
    }

    fun selectedCaption(): CaptionTrackOption? {
        val state = _uiState.value
        return state.video?.captionTracks?.firstOrNull { it.id == state.selectedCaptionId }
    }

    fun visibleVideoTracks(): List<VideoTrackOption> {
        val state = _uiState.value
        val all = state.video?.videoTracks.orEmpty()
        if (!state.castableOnly) return all
        return all.filter { it.fit != CastFit.UNSUPPORTED }
    }

    fun openRecent(item: RecentItem) {
        resolve("https://x.com/i/status/${item.statusId}")
    }

    fun removeRecent(statusId: String) {
        store.removeRecent(statusId)
        _uiState.update { it.copy(recents = store.recents()) }
    }

    fun togglePin(statusId: String) {
        store.togglePin(statusId)
        _uiState.update { it.copy(recents = store.recents()) }
    }

    fun clearUnpinnedRecents() {
        store.clearUnpinned()
        _uiState.update { it.copy(recents = store.recents()) }
    }

    fun saveResume(positionMs: Long, durationMs: Long) {
        val id = _uiState.value.video?.statusId ?: return
        store.saveResume(id, positionMs, durationMs)
        val video = _uiState.value.video ?: return
        store.upsertRecent(video, positionMs)
        _uiState.update { it.copy(recents = store.recents()) }
    }

    fun consumeResume(): Long {
        val id = _uiState.value.video?.statusId
        val stored = id?.let { store.resumePosition(it) } ?: 0L
        val pos = maxOf(_uiState.value.resumeAtMs, stored)
        _uiState.update { it.copy(resumeAtMs = 0L) }
        return pos
    }

    fun clearResumeAndRestart() {
        val id = _uiState.value.video?.statusId ?: return
        store.clearResume(id)
        _uiState.update { it.copy(resumeAtMs = 0L) }
    }

    fun markPendingCast(silent: Boolean = false) {
        pendingCast = true
        silentRecast = silent
    }

    fun consumePendingCast(): Boolean {
        if (!pendingCast) return false
        pendingCast = false
        return true
    }

    fun consumeSilentRecast(): Boolean {
        val silent = silentRecast
        silentRecast = false
        return silent
    }

    private fun defaultVideoTrack(video: TweetVideo): VideoTrackOption? {
        val preferred = store.lastHeightBand
        val visible = if (store.castableOnly) {
            video.videoTracks.filter { it.fit != CastFit.UNSUPPORTED }
        } else {
            video.videoTracks
        }
        val mp4 = visible.filter { it.contentType.contains("mp4") }
        val quality = compareBy<VideoTrackOption> { it.height }.thenBy { it.bitrate }
        val bestSafe = mp4.filter { it.fit == CastFit.SAFE }.maxWithOrNull(quality)
        val preferredTrack = mp4.filter { it.height == preferred }.maxByOrNull { it.bitrate }
        // Honor a saved pick only when it is at least as sharp as the
        // best Cast-safe encode (e.g. user chose ~3K). Never keep an old
        // 720p default when 1080p is available.
        if (preferred > 0 && preferredTrack != null &&
            preferredTrack.height >= (bestSafe?.height ?: 0)
        ) {
            return preferredTrack
        }
        return bestSafe
            ?: mp4.filter { it.fit != CastFit.UNSUPPORTED }.maxWithOrNull(quality)
            ?: mp4.maxWithOrNull(quality)
            ?: visible.firstOrNull()
    }

    private fun defaultCaptionId(video: TweetVideo): String {
        return video.captionTracks.firstOrNull { it.id != TweetVideoResolver.CAPTIONS_OFF }?.id
            ?: TweetVideoResolver.CAPTIONS_OFF
    }
}
