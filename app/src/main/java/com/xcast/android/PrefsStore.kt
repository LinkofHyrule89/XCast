package com.xcast.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentItem(
    val statusId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val lastPositionMs: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

class PrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xcast_prefs", Context.MODE_PRIVATE)

    var castableOnly: Boolean
        get() = prefs.getBoolean(CASTABLE_ONLY, true)
        set(value) { prefs.edit().putBoolean(CASTABLE_ONLY, value).apply() }

    var lastHeightBand: Int
        get() = prefs.getInt(HEIGHT_BAND, 0)
        set(value) { prefs.edit().putInt(HEIGHT_BAND, value).apply() }

    fun recents(): List<RecentItem> {
        val raw = prefs.getString(RECENTS, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    RecentItem(
                        statusId = o.getString("statusId"),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle"),
                        thumbnailUrl = o.optString("thumbnailUrl").ifBlank { null },
                        durationMs = o.optLong("durationMs").takeIf { o.has("durationMs") && it > 0 },
                        lastPositionMs = o.optLong("lastPositionMs"),
                        updatedAt = o.optLong("updatedAt"),
                        pinned = o.optBoolean("pinned", false),
                    ),
                )
            }
        }.sortedWith(compareByDescending<RecentItem> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun upsertRecent(video: TweetVideo, positionMs: Long = 0L) {
        val now = System.currentTimeMillis()
        val existing = recents().firstOrNull { it.statusId == video.statusId }
        val item = RecentItem(
            statusId = video.statusId,
            title = video.title,
            subtitle = video.subtitle,
            thumbnailUrl = video.thumbnailUrl,
            durationMs = video.durationMs,
            lastPositionMs = positionMs,
            updatedAt = now,
            pinned = existing?.pinned == true,
        )
        val next = listOf(item) + recents().filterNot { it.statusId == video.statusId }
        saveRecents(trimRecents(next))
    }

    fun removeRecent(statusId: String) {
        saveRecents(recents().filterNot { it.statusId == statusId })
        clearResume(statusId)
    }

    fun togglePin(statusId: String) {
        saveRecents(
            recents().map { item ->
                if (item.statusId == statusId) item.copy(pinned = !item.pinned) else item
            },
        )
    }

    fun clearUnpinned() {
        recents().filter { !it.pinned }.forEach { clearResume(it.statusId) }
        saveRecents(recents().filter { it.pinned })
    }

    fun saveResume(statusId: String, positionMs: Long, durationMs: Long) {
        if (positionMs < 5_000L) {
            clearResume(statusId)
            return
        }
        if (durationMs > 0 && positionMs > durationMs - 10_000L) {
            clearResume(statusId)
            return
        }
        val root = resumeRoot()
        root.put(
            statusId,
            JSONObject().put("positionMs", positionMs).put("durationMs", durationMs),
        )
        prefs.edit().putString(RESUME, root.toString()).apply()
    }

    fun resumePosition(statusId: String): Long? {
        val o = resumeRoot().optJSONObject(statusId) ?: return null
        val pos = o.optLong("positionMs")
        return pos.takeIf { it > 0 }
    }

    fun clearResume(statusId: String) {
        val root = resumeRoot()
        root.remove(statusId)
        prefs.edit().putString(RESUME, root.toString()).apply()
    }

    private fun resumeRoot(): JSONObject =
        JSONObject(prefs.getString(RESUME, "{}") ?: "{}")

    private fun saveRecents(items: List<RecentItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("statusId", item.statusId)
                    .put("title", item.title)
                    .put("subtitle", item.subtitle)
                    .put("thumbnailUrl", item.thumbnailUrl ?: "")
                    .put("durationMs", item.durationMs ?: 0L)
                    .put("lastPositionMs", item.lastPositionMs)
                    .put("updatedAt", item.updatedAt)
                    .put("pinned", item.pinned),
            )
        }
        prefs.edit().putString(RECENTS, array.toString()).apply()
    }

    private fun trimRecents(items: List<RecentItem>): List<RecentItem> {
        val pinned = items.filter { it.pinned }
        val room = (MAX_RECENTS - pinned.size).coerceAtLeast(0)
        val unpinned = items.filter { !it.pinned }.take(room)
        return pinned + unpinned
    }

    companion object {
        private const val CASTABLE_ONLY = "castable_only"
        private const val HEIGHT_BAND = "height_band"
        private const val RECENTS = "recents"
        private const val RESUME = "resume"
        private const val MAX_RECENTS = 20
    }
}
