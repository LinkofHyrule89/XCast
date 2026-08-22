package com.xcast.android

/**
 * X video "chapters" are timestamp links in the post text
 * (0:00, 00:00, 1:02:03, 01:02:03) plus the rest of the line as a title.
 */
object ChapterParser {
    private val lineRegex = Regex(
        """(?m)^[\t \u00A0]*[•\-\*]?\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\b\s*[-–—:|]?\s*(.*)$""",
    )

    fun parse(text: String, durationMs: Long?): List<VideoChapter> {
        val duration = durationMs ?: Long.MAX_VALUE
        val found = mutableListOf<VideoChapter>()
        for (match in lineRegex.findAll(text.replace("\r\n", "\n"))) {
            val stamp = match.groupValues[1]
            val title = match.groupValues[2].trim().ifBlank { "Chapter" }
            if (isClockLike(title)) continue
            val ms = parseTimestamp(stamp) ?: continue
            if (ms > duration + 1_000) continue
            found += VideoChapter(positionMs = ms, title = title.take(120), stamp = stamp)
        }
        return found
            .distinctBy { it.positionMs }
            .sortedBy { it.positionMs }
    }

    fun parseTimestamp(stamp: String): Long? {
        val parts = stamp.split(":").mapNotNull { it.toIntOrNull() }
        val seconds = when (parts.size) {
            2 -> {
                val (m, s) = parts
                if (s > 59) return null
                m * 60 + s
            }
            3 -> {
                val (h, m, s) = parts
                if (m > 59 || s > 59) return null
                h * 3600 + m * 60 + s
            }
            else -> return null
        }
        return seconds * 1000L
    }

    private fun isClockLike(title: String): Boolean {
        val t = title.trim()
        return t.equals("am", true) || t.equals("pm", true) ||
            t.equals("a.m.", true) || t.equals("p.m.", true)
    }
}
