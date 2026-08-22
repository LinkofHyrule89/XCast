package com.xcast.android

data class TweetVideo(
    val statusId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val hlsMasterUrl: String?,
    val videoTracks: List<VideoTrackOption>,
    val audioTracks: List<AudioTrackOption>,
    val captionTracks: List<CaptionTrackOption> = emptyList(),
    val chapters: List<VideoChapter> = emptyList(),
)

data class VideoChapter(
    val positionMs: Long,
    val title: String,
    val stamp: String,
)
