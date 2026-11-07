package com.xcast.android

import okhttp3.OkHttpClient
import okhttp3.Request

internal class OkHttpPauseUpstream(
    private val client: OkHttpClient,
    private val userAgent: String,
) : PauseBufferUpstream {
    override fun totalLength(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403 || resp.code == 410) {
                throw UpstreamRejectedException(resp.code)
            }
            if (resp.code != 200 && resp.code != 206) {
                throw java.io.IOException("length probe HTTP ${resp.code}")
            }
            val total = responseTotalLength(
                resp.code,
                resp.header("Content-Range"),
                resp.header("Content-Length")?.toLongOrNull() ?: -1L,
            ) ?: throw java.io.IOException("length missing")
            return total
        }
    }

    override fun open(url: String, start: Long): UpstreamBody {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$start-")
            .build()
        val call = client.newCall(request)
        try {
            val resp = call.execute()
            if (resp.code == 401 || resp.code == 403 || resp.code == 410) {
                resp.close()
                throw UpstreamRejectedException(resp.code)
            }
            val body = resp.body ?: run {
                resp.close()
                throw java.io.IOException("empty upstream")
            }
            val total = responseTotalLength(
                resp.code,
                resp.header("Content-Range"),
                if (resp.code == 200) body.contentLength() else -1L,
            )
            return UpstreamBody(body.byteStream(), resp.code, total ?: -1L) {
                call.cancel()
                resp.close()
            }
        } catch (e: UpstreamRejectedException) {
            throw e
        } catch (e: Exception) {
            call.cancel()
            throw e
        }
    }
}
