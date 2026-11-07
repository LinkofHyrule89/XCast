package com.xcast.android

internal data class ByteRange(val start: Long, val endInclusive: Long)

internal object HttpByteRanges {
    fun parse(header: String?, contentLength: Long): ByteRange? {
        if (header.isNullOrBlank() || contentLength <= 0L) return null
        val spec = header.removePrefix("bytes=").substringBefore(",").trim()
        if (spec.startsWith("-")) {
            val suffix = spec.drop(1).toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            val start = (contentLength - suffix).coerceAtLeast(0L)
            return ByteRange(start, contentLength - 1)
        }
        val start = spec.substringBefore("-").toLongOrNull() ?: return null
        val endPart = spec.substringAfter("-", "")
        val end = endPart.toLongOrNull() ?: (contentLength - 1)
        if (start < 0L || start >= contentLength || end < start) return null
        return ByteRange(start, end.coerceAtMost(contentLength - 1))
    }
}

internal fun responseTotalLength(code: Int, contentRange: String?, contentLength: Long): Long? {
    contentRange?.let { range ->
        val total = range.substringAfter("/", missingDelimiterValue = "").toLongOrNull()
        if (total != null && total > 0L) return total
    }
    if (code == 200 && contentLength > 0L) return contentLength
    return null
}

internal fun cachedHttpHead(status: Int, contentRange: String?, length: Long): String {
    val sb = StringBuilder()
    if (status == 206) {
        sb.append("HTTP/1.1 206 Partial Content\r\n")
        if (!contentRange.isNullOrBlank()) {
            sb.append("Content-Range: $contentRange\r\n")
        }
    } else {
        sb.append("HTTP/1.1 200 OK\r\n")
    }
    sb.append("Content-Type: video/mp4\r\n")
    sb.append("Accept-Ranges: bytes\r\n")
    sb.append("Access-Control-Allow-Origin: *\r\n")
    sb.append("Content-Length: $length\r\n")
    sb.append("Connection: close\r\n\r\n")
    return sb.toString()
}
