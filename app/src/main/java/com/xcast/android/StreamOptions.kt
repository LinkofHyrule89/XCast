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
        return if (bitrate in 1..16_000_000) CastFit.SAFE else CastFit.RISKY
    }
    // LAN proxy + Google TV: 1080p H.264 is reliable. Twitter's 1080p
    // encodes are often ~2008x1080 at ~10 Mbps. ~3K/25 Mbps can play on
    // a Bravia but may buffer through a VPN, so keep it as a fallback pick.
    return when {
        height <= 1080 && width <= 2048 && bitrate <= 16_000_000 -> CastFit.SAFE
        height <= 1080 && bitrate <= 16_000_000 -> CastFit.SAFE
        height <= 1600 && width <= 3200 && bitrate <= 30_000_000 -> CastFit.RISKY
        else -> CastFit.UNSUPPORTED
    }
}

fun CastFit.badge(): String = when (this) {
    CastFit.SAFE -> "Castable"
    CastFit.RISKY -> "Highest · may buffer"
    CastFit.UNSUPPORTED -> "Too heavy for Cast"
}
