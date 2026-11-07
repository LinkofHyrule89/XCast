package com.xcast.android

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Mp4PauseBufferTest {
    private val dir = Files.createTempDirectory("pause-buffer").toFile()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()
    private val origins = mutableListOf<Origin>()
    private val buffers = mutableListOf<Mp4PauseBuffer>()

    @After
    fun tearDown() {
        buffers.forEach { runCatching { it.shutdown() } }
        origins.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun cachedRangesMatchOriginAndFullFileRequestWaitsUntilComplete() {
        val origin = origin(bytes(80_000))
        val buffer = buffer(origin)
        buffer.onPaused(origin.url)
        val done = waitUntil(buffer) { it.reason == PauseBufferReason.SAVED }
        assertEquals(origin.data.size.toLong(), done.savedBytes)

        assertRange(buffer, origin, "bytes=0-3")
        assertRange(buffer, origin, "bytes=1000-2500")
        assertRange(buffer, origin, "bytes=-7")
        assertRange(buffer, origin, "bytes=40-")
        buffer.openRange(origin.url, null).use { hit ->
            checkCached(hit)
            assertEquals(200, hit.status)
            assertNull(hit.contentRange)
            assertArrayEquals(origin.data, hit.readBytes())
            val head = cachedHttpHead(hit.status, hit.contentRange, hit.length)
            assertTrue(head.contains("HTTP/1.1 200 OK"))
            assertTrue(head.contains("Content-Length: ${origin.data.size}"))
        }
    }

    @Test
    fun uncachedRangeFallsThroughAndInProgressPrefixStaysExact() {
        val origin = origin(bytes(200_000))
        origin.stallAfter = 24_000
        val buffer = buffer(origin)
        buffer.onPaused(origin.url)
        assertTrue(origin.stalled.await(5, TimeUnit.SECONDS))
        val snap = waitUntil(buffer) { it.savedBytes >= 8_000 && it.reason == PauseBufferReason.SAVING }
        val insideEnd = snap.savedBytes - 1
        buffer.openRange(origin.url, "bytes=0-$insideEnd").use { hit ->
            checkCached(hit)
            assertEquals(206, hit.status)
            assertEquals("bytes 0-$insideEnd/${origin.data.size}", hit.contentRange)
            val body = hit.readBytes()
            assertEquals(snap.savedBytes, body.size.toLong())
            assertArrayEquals(origin.data.copyOf(body.size), body)
            val head = cachedHttpHead(hit.status, hit.contentRange, hit.length)
            assertTrue(head.startsWith("HTTP/1.1 206 Partial Content"))
            assertTrue(head.contains("Content-Range: bytes 0-$insideEnd/${origin.data.size}"))
            assertTrue(head.contains("Content-Length: ${body.size}"))
        }
        assertNull(buffer.openRange(origin.url, "bytes=0-$snap.savedBytes"))
        assertNull(buffer.openRange(origin.url, "bytes=${snap.savedBytes}-"))
        assertNull(buffer.openRange(origin.url, null))

        val missed = origin.readRange(snap.savedBytes, snap.savedBytes + 20)
        assertArrayEquals(
            origin.data.copyOfRange(snap.savedBytes.toInt(), (snap.savedBytes + 21).toInt()),
            missed,
        )
    }

    @Test
    fun playingAgainStopsTheDownloadAndKeepsSavedBytes() {
        val origin = origin(bytes(300_000))
        origin.stallAfter = 20_000
        val buffer = buffer(origin)
        buffer.onPaused(origin.url)
        assertTrue(origin.stalled.await(5, TimeUnit.SECONDS))
        val paused = waitUntil(buffer) { it.savedBytes > 0L }
        buffer.onPlaying(origin.url)
        origin.release()
        val after = waitUntil(buffer) {
            it.reason == PauseBufferReason.PARTIAL || it.reason == PauseBufferReason.SAVED
        }
        Thread.sleep(250)
        val settled = buffer.snapshot()
        assertEquals(after.savedBytes, settled.savedBytes)
        assertTrue(settled.savedBytes < origin.data.size)
        assertTrue(settled.savedBytes >= paused.savedBytes)
        assertEquals(PauseBufferReason.PARTIAL, settled.reason)
        buffer.openRange(origin.url, "bytes=0-${settled.savedBytes - 1}").use { hit ->
            val body = checkCached(hit).readBytes()
            assertArrayEquals(origin.data.copyOf(body.size), body)
        }
    }

    @Test
    fun discardAndSourceSwitchRemoveThePreviousFile() {
        val first = bytes(60_000)
        val second = bytes(40_000, seed = 9)
        val origin = origin(first, second)
        val buffer = buffer(origin)
        origin.stallAfter = 8_000
        buffer.onPaused(origin.url)
        waitUntil(buffer) { it.savedBytes > 0L }
        buffer.discard()
        origin.release()
        val cleared = waitUntil(buffer) { it.reason == PauseBufferReason.HIDDEN && it.savedBytes == 0L }
        assertEquals(0L, cleared.savedBytes)
        assertNull(buffer.openRange(origin.url, "bytes=0-10"))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("current.") })

        origin.stallAfter = -1
        buffer.onPaused(origin.url)
        waitUntil(buffer) { it.reason == PauseBufferReason.SAVED }
        buffer.onPaused(origin.otherUrl)
        val switched = waitUntil(buffer) { it.reason == PauseBufferReason.SAVED && it.totalBytes == second.size.toLong() }
        assertEquals(second.size.toLong(), switched.savedBytes)
        assertNull(buffer.openRange(origin.url, "bytes=0-10"))
        buffer.openRange(origin.otherUrl, null).use { hit ->
            assertArrayEquals(second, checkCached(hit).readBytes())
        }
    }

    @Test
    fun storageCapAndLowDiskStopWithoutServingMissingBytes() {
        val origin = origin(bytes(50_000))
        val capped = track(
            Mp4PauseBuffer(
                directory = File(dir, "cap"),
                upstream = OkHttpPauseUpstream(client, "test"),
                maxBytes = 1_500,
                minFreeBytes = 1,
            ),
        )
        capped.onPaused(origin.url)
        val cappedSnap = waitUntil(capped) { it.reason == PauseBufferReason.CAPPED }
        assertEquals(1_500L, cappedSnap.savedBytes)
        capped.openRange(origin.url, "bytes=0-1499").use { hit ->
            assertArrayEquals(origin.data.copyOf(1_500), checkCached(hit).readBytes())
        }
        assertNull(capped.openRange(origin.url, "bytes=1400-1600"))

        val low = track(
            Mp4PauseBuffer(
                directory = File(dir, "low"),
                upstream = OkHttpPauseUpstream(client, "test"),
                maxBytes = Mp4PauseBuffer.DEFAULT_MAX_BYTES,
                minFreeBytes = 100,
                freeSpace = { 0L },
            ),
        )
        low.onPaused(origin.url)
        val lowSnap = waitUntil(low) { it.reason == PauseBufferReason.LOW_SPACE }
        assertEquals(0L, lowSnap.savedBytes)
        assertNull(low.openRange(origin.url, "bytes=0-10"))

        val mid = track(
            Mp4PauseBuffer(
                directory = File(dir, "mid"),
                upstream = OkHttpPauseUpstream(client, "test"),
                maxBytes = Mp4PauseBuffer.DEFAULT_MAX_BYTES,
                minFreeBytes = 50,
                freeSpace = {
                    val saved = File(dir, "mid/current.part").length()
                    if (saved >= 12_000) 0L else 1_000_000L
                },
            ),
        )
        mid.onPaused(origin.url)
        val midSnap = waitUntil(mid) { it.reason == PauseBufferReason.LOW_SPACE }
        assertTrue(midSnap.savedBytes >= 12_000)
        assertTrue(midSnap.savedBytes < origin.data.size)
        mid.openRange(origin.url, "bytes=0-${midSnap.savedBytes - 1}").use { hit ->
            val body = checkCached(hit).readBytes()
            assertArrayEquals(origin.data.copyOf(body.size), body)
        }
        assertNull(mid.openRange(origin.url, "bytes=0-${midSnap.savedBytes}"))
    }

    @Test
    fun retriesThenSavesAndRejectedLinkDoesNotCorruptAPrefix() {
        val origin = origin(bytes(30_000))
        origin.fullReadFailures = 2
        val buffer = buffer(origin)
        buffer.onPaused(origin.url)
        val saved = waitUntil(buffer) { it.reason == PauseBufferReason.SAVED }
        assertEquals(origin.data.size.toLong(), saved.savedBytes)

        val shifted = ScriptedUpstream(bytes(20_000, seed = 3))
        val guarded = track(
            Mp4PauseBuffer(File(dir, "shift"), shifted, maxBytes = 100_000, minFreeBytes = 1),
        )
        guarded.onPaused("https://video.twimg.com/shift.mp4")
        val stopped = waitUntil(guarded) { it.reason == PauseBufferReason.STOPPED }
        assertEquals(8_000L, stopped.savedBytes)
        guarded.openRange("https://video.twimg.com/shift.mp4", "bytes=0-7999").use { hit ->
            assertArrayEquals(shifted.data.copyOf(8_000), checkCached(hit).readBytes())
        }
        assertEquals(8_000L, File(dir, "shift/current.part").length())

        origin.reject = 403
        val rejected = buffer(origin, File(dir, "reject"))
        rejected.onPaused(origin.url)
        val denied = waitUntil(rejected) { it.reason == PauseBufferReason.REJECTED }
        assertEquals(0L, denied.savedBytes)
    }

    @Test
    fun reloadedCacheServesWithoutAnotherDownloadAndOpenFileSurvivesDelete() {
        val origin = origin(bytes(16_000))
        val first = buffer(origin, File(dir, "reload"))
        first.onPaused(origin.url)
        waitUntil(first) { it.reason == PauseBufferReason.SAVED }
        val hits = origin.hits.get()
        first.shutdown()
        buffers.remove(first)

        val second = track(
            Mp4PauseBuffer(
                directory = File(dir, "reload"),
                upstream = OkHttpPauseUpstream(client, "test"),
                maxBytes = 100_000,
                minFreeBytes = 1,
            ),
        )
        val restored = second.snapshot()
        assertEquals(PauseBufferReason.SAVED, restored.reason)
        second.openRange(origin.url, "bytes=4-9").use { hit ->
            assertArrayEquals(origin.data.copyOfRange(4, 10), checkCached(hit).readBytes())
        }
        assertEquals(hits, origin.hits.get())

        second.openRange(origin.url, "bytes=0-31").use { hit ->
            second.discard()
            assertArrayEquals(origin.data.copyOf(32), checkCached(hit).readBytes())
        }
        assertNull(second.openRange(origin.url, "bytes=0-31"))
    }

    @Test
    fun proxySourceParsingIgnoresHlsAndPlainCdnUrls() {
        val mp4 = "https://video.twimg.com/ext_tw_video/123/pu/vid/720x720/ab.mp4"
        val encoded = java.net.URLEncoder.encode(mp4, "UTF-8")
        assertEquals(mp4, CastMediaServer.proxiedMp4Source("http://192.168.1.9:8765/cast?u=$encoded"))
        assertNull(CastMediaServer.proxiedMp4Source("https://video.twimg.com/playlist.m3u8"))
        assertNull(CastMediaServer.proxiedMp4Source("http://192.168.1.9:8765/cast?u=" + java.net.URLEncoder.encode("https://evil.example/a.mp4", "UTF-8")))
        assertNull(CastMediaServer.proxiedMp4Source(null))
    }

    private fun buffer(origin: Origin, directory: java.io.File = dir): Mp4PauseBuffer {
        return track(
            Mp4PauseBuffer(
                directory = directory,
                upstream = OkHttpPauseUpstream(client, "test"),
                maxBytes = Mp4PauseBuffer.DEFAULT_MAX_BYTES,
                minFreeBytes = 1,
                freeSpace = { 1_000_000_000L },
            ),
        )
    }

    private fun track(buffer: Mp4PauseBuffer): Mp4PauseBuffer {
        buffers += buffer
        return buffer
    }

    private fun origin(primary: ByteArray, other: ByteArray = ByteArray(0)): Origin {
        val origin = Origin(primary, other)
        origins += origin
        origin.start()
        return origin
    }

    private fun assertRange(buffer: Mp4PauseBuffer, origin: Origin, header: String) {
        val spec = HttpByteRanges.parse(header, origin.data.size.toLong())
        checkCached(spec)
        buffer.openRange(origin.url, header).use { hit ->
            val body = checkCached(hit).readBytes()
            assertEquals(spec.endInclusive - spec.start + 1, body.size.toLong())
            assertArrayEquals(origin.data.copyOfRange(spec.start.toInt(), spec.endInclusive.toInt() + 1), body)
        }
    }

    private fun waitUntil(
        buffer: Mp4PauseBuffer,
        timeoutMs: Long = 8_000,
        ready: (PauseBufferSnapshot) -> Boolean,
    ): PauseBufferSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = buffer.snapshot()
        while (System.currentTimeMillis() < deadline) {
            last = buffer.snapshot()
            if (ready(last)) return last
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for buffer state $last")
    }

    private fun bytes(size: Int, seed: Int = 1): ByteArray {
        val out = ByteArray(size)
        var value = seed
        for (i in out.indices) {
            value = value * 1103515245 + 12345
            out[i] = value.toByte()
        }
        return out
    }

    private class ScriptedUpstream(val data: ByteArray) : PauseBufferUpstream {
        private var opens = 0

        override fun totalLength(url: String) = data.size.toLong()

        override fun open(url: String, start: Long): UpstreamBody {
            opens++
            if (opens == 1) {
                val slice = data.copyOfRange(0, 8_000)
                return UpstreamBody(ByteArrayInputStream(slice), 206, data.size.toLong())
            }
            return UpstreamBody(ByteArrayInputStream(data), 200, data.size.toLong())
        }
    }

    private class Origin(val data: ByteArray, val other: ByteArray) {
        val hits = AtomicInteger()
        @Volatile var stallAfter = -1
        @Volatile var fullReadFailures = 0
        @Volatile var reject = 0
        val stalled = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val server = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool()
        private val acceptThread = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                executor.execute { handle(socket) }
            }
        }
        val url: String
        val otherUrl: String

        init {
            val port = server.localPort
            url = "http://127.0.0.1:$port/a.mp4"
            otherUrl = "http://127.0.0.1:$port/b.mp4"
        }

        fun start() {
            acceptThread.isDaemon = true
            acceptThread.start()
        }

        fun release() {
            release.countDown()
        }

        fun readRange(start: Long, endInclusive: Long): ByteArray {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
            connection.inputStream.use { return it.readBytes() }
        }

        fun close() {
            release.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
        }

        private fun handle(socket: Socket) {
            try {
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val headerBytes = readHeaders(input) ?: return
                val headers = headerBytes.toString(StandardCharsets.US_ASCII)
                val requestLine = headers.lineSequence().firstOrNull().orEmpty()
                val path = requestLine.split(" ").getOrNull(1).orEmpty().substringBefore("?")
                val payload = if (path.startsWith("/b.mp4")) other else data
                val range = headers.lineSequence()
                    .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                hits.incrementAndGet()
                if (reject != 0) {
                    writeStatus(socket, reject, "no")
                    return
                }
                val probe = range == "bytes=0-0"
                if (!probe && fullReadFailures > 0) {
                    fullReadFailures--
                    writeStatus(socket, 500, "later")
                    return
                }
                val spec = if (range == null) {
                    if (payload.isEmpty()) null else ByteRange(0, payload.size.toLong() - 1)
                } else {
                    HttpByteRanges.parse(range, payload.size.toLong())
                }
                if (spec == null) {
                    writeStatus(socket, 416, "")
                    return
                }
                val length = spec.endInclusive - spec.start + 1
                val head = StringBuilder()
                if (range != null) {
                    head.append("HTTP/1.1 206 Partial Content\r\n")
                    head.append("Content-Range: bytes ${spec.start}-${spec.endInclusive}/${payload.size}\r\n")
                } else {
                    head.append("HTTP/1.1 200 OK\r\n")
                }
                head.append("Content-Length: $length\r\n")
                head.append("Accept-Ranges: bytes\r\n")
                head.append("Connection: close\r\n\r\n")
                val out = socket.getOutputStream()
                out.write(head.toString().toByteArray(StandardCharsets.US_ASCII))
                var sent = 0
                var offset = spec.start.toInt()
                while (sent < length) {
                    if (stallAfter >= 0 && sent >= stallAfter) {
                        stalled.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    val n = minOf(4_096, (length - sent).toInt())
                    out.write(payload, offset, n)
                    offset += n
                    sent += n
                }
                out.flush()
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun writeStatus(socket: Socket, code: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.US_ASCII)
            val text = "HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(text.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }
}

@OptIn(ExperimentalContracts::class)
private fun <T> checkCached(value: T?): T {
    contract { returns() implies (value != null) }
    if (value == null) throw AssertionError("expected a cached range")
    return value
}

private fun readHeaders(input: InputStream): ByteArray? {
    val buf = ByteArrayOutputStream()
    val end = byteArrayOf(13, 10, 13, 10)
    while (buf.size() < 16_384) {
        val b = input.read()
        if (b < 0) return null
        buf.write(b)
        val bytes = buf.toByteArray()
        if (bytes.size >= 4 &&
            bytes[bytes.size - 4] == end[0] &&
            bytes[bytes.size - 3] == end[1] &&
            bytes[bytes.size - 2] == end[2] &&
            bytes[bytes.size - 1] == end[3]
        ) {
            return bytes
        }
    }
    return null
}
