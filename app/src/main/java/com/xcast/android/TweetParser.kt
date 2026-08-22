package com.xcast.android

object TweetParser {
    private val statusRegex = Regex(
        """https?://(?:www\.)?(?:mobile\.)?(?:x\.com|twitter\.com|vxtwitter\.com|fxtwitter\.com)/[^/\s]+/status(?:es)?/(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val shortStatusRegex = Regex(
        """https?://(?:www\.)?(?:x\.com|twitter\.com)/i/status(?:es)?/(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    fun extractStatusId(text: String): String? {
        statusRegex.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        shortStatusRegex.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        val digits = text.trim()
        if (digits.all { it.isDigit() } && digits.length >= 8) return digits
        return null
    }

    fun extractUrl(text: String): String? {
        val match = Regex("""https?://\S+""").find(text) ?: return null
        return match.value.trimEnd('.', ',', ')', ']')
    }
}
