package com.xcast.android

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SafeUrls {
    fun isAllowedHttps(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") return false
        return allowedHost(host)
    }

    fun requireAllowed(url: String): String {
        if (!isAllowedHttps(url)) {
            throw IllegalArgumentException("Blocked non-HTTPS or disallowed host")
        }
        return url
    }

    private fun allowedHost(host: String): Boolean {
        val suffixes = listOf(
            "fxtwitter.com",
            "vxtwitter.com",
            "fixupx.com",
            "twimg.com",
            "pscp.tv",
            "periscope.tv",
            "twitter.com",
            "x.com",
            "video.pscp.tv",
        )
        return suffixes.any { host == it || host.endsWith(".$it") }
    }
}
