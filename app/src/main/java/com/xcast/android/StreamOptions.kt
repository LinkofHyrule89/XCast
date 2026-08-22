package com.xcast.android

enum class CastFit {
    SAFE,
    RISKY,
    UNSUPPORTED,
}

data class VideoTrackOption(
    val id: String,
    val label: String,
    val detail: String,
    val url: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fit: CastFit,
    val muxedAudio: Boolean,
)

data class AudioTrackOption(
    val id: String,
    val label: String,
    val detail: String,
    val url: String?,
    val bitrate: Int,
    val muxed: Boolean,
)

data class CaptionTrackOption(
    val id: String,
    val label: String,
    val detail: String,
    val url: String?,
    val contentType: String,
    val language: String?,
    val closedCaptions: Boolean,
)

fun castFit(width: Int, height: Int, bitrate: Int): CastFit {
    if (height <= 0 || width <= 0) {
        return if (bitrate in 1..8_000_000) CastFit.SAFE else CastFit.RISKY
    }
    // Default Media Receiver: H.264 up to 1080p is typical; 3K/high-bitrate fails immediately.
    return when {
        height <= 720 && width <= 1920 && bitrate <= 8_000_000 -> CastFit.SAFE
        height <= 1080 && width <= 2048 && bitrate <= 12_000_000 -> CastFit.RISKY
        height <= 1080 && bitrate <= 8_000_000 -> CastFit.RISKY
        else -> CastFit.UNSUPPORTED
    }
}

fun CastFit.badge(): String = when (this) {
    CastFit.SAFE -> "Castable"
    CastFit.RISKY -> "May fail on some TVs"
    CastFit.UNSUPPORTED -> "Too heavy for Cast"
}
