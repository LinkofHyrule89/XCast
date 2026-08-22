package com.xcast.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val message: String = "Share an X post that contains a video, or paste the post URL.",
    val video: TweetVideo? = null,
    val selectedVideoId: String? = null,
    val selectedAudioId: String? = null,
    val selectedCaptionId: String? = null,
    val castableOnly: Boolean = true,
)

class MainViewModel : ViewModel() {
    private val resolver = TweetVideoResolver()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var pendingCast = false

    fun resolve(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            _uiState.value = UiState(message = "Paste an X post URL first.")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = "Looking up video…", video = null) }
            try {
                val video = resolver.resolve(text)
                val defaultVideo = defaultVideoTrack(video)
                _uiState.value = UiState(
                    loading = false,
                    message = "Pick a Castable resolution, then tap Cast to TV.",
                    video = video,
                    selectedVideoId = defaultVideo?.id,
                    selectedAudioId = TweetVideoResolver.AUDIO_MUXED,
                    selectedCaptionId = defaultCaptionId(video),
                    castableOnly = true,
                )
            } catch (e: Exception) {
                pendingCast = false
                _uiState.value = UiState(
                    loading = false,
                    message = e.message ?: "Failed to resolve video.",
                    video = null,
                )
            }
        }
    }

    fun selectVideo(id: String) {
        _uiState.update { state ->
            val track = state.video?.videoTracks?.firstOrNull { it.id == id }
            val audioId = if (track?.muxedAudio == false) {
                state.video?.audioTracks?.lastOrNull { !it.muxed }?.id ?: state.selectedAudioId
            } else {
                TweetVideoResolver.AUDIO_MUXED
            }
            state.copy(selectedVideoId = id, selectedAudioId = audioId)
        }
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

    fun markPendingCast() {
        pendingCast = true
    }

    fun consumePendingCast(): Boolean {
        if (!pendingCast) return false
        pendingCast = false
        return true
    }

    private fun defaultVideoTrack(video: TweetVideo): VideoTrackOption? {
        val mp4 = video.videoTracks.filter { it.contentType.contains("mp4") }
        return mp4.filter { it.fit == CastFit.SAFE }.maxByOrNull { it.height }
            ?: mp4.filter { it.fit != CastFit.UNSUPPORTED }.maxByOrNull { it.height }
            ?: mp4.maxByOrNull { it.height }
            ?: video.videoTracks.firstOrNull()
    }

    private fun defaultCaptionId(video: TweetVideo): String {
        return video.captionTracks.firstOrNull { it.id != TweetVideoResolver.CAPTIONS_OFF }?.id
            ?: TweetVideoResolver.CAPTIONS_OFF
    }
}
