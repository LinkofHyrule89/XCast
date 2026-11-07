package com.xcast.android

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PauseBufferReason {
    HIDDEN,
    SAVING,
    PARTIAL,
    SAVED,
    CAPPED,
    LOW_SPACE,
    REJECTED,
    STOPPED,
}

data class PauseBufferSnapshot(
    val reason: PauseBufferReason,
    val savedBytes: Long,
    val totalBytes: Long,
)

class UpstreamRejectedException(val code: Int) : java.io.IOException("HTTP $code")

interface PauseBufferUpstream {
    fun totalLength(url: String): Long

    fun open(url: String, start: Long): UpstreamBody
}

class UpstreamBody(
    val stream: InputStream,
    val statusCode: Int,
    val totalLength: Long,
    private val closeExtra: () -> Unit = {},
) : AutoCloseable {
    private val once = AtomicBoolean(false)

    override fun close() {
        if (!once.compareAndSet(false, true)) return
        try {
            stream.close()
        } catch (_: Exception) {
        }
        try {
            closeExtra()
        } catch (_: Exception) {
        }
    }
}

class CachedRange(
    val status: Int,
    val contentRange: String?,
    val length: Long,
    private val file: RandomAccessFile,
) : AutoCloseable {
    fun writeTo(out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0L) {
            val n = file.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("saved video ended early")
            out.write(buf, 0, n)
            left -= n
        }
    }

    fun readBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        writeTo(out)
        return out.toByteArray()
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * One temporary MP4 for the Cast session. Bytes already published stay readable
 * while a later chunk is written. A range is served only when every requested
 * byte is already saved, so a short or torn response is never returned.
 */
class Mp4PauseBuffer(
    private val directory: File,
    private val upstream: PauseBufferUpstream,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    private val freeSpace: () -> Long = { directory.usableSpace },
    private val maxAttempts: Int = 3,
) {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private var listener: ((PauseBufferSnapshot) -> Unit)? = null
    private var url: String? = null
    private var published = 0L
    private var total = -1L
    private var reason = PauseBufferReason.HIDDEN
    private var generation = 0
    private var downloadWanted = false
    private var closed = false
    private var active: UpstreamBody? = null

    private val worker = Thread(::loop, "xcast-pause-buffer").apply {
        isDaemon = true
        start()
    }

    init {
        directory.mkdirs()
        lock.withLock { loadMetaLocked() }
    }

    fun setListener(listener: ((PauseBufferSnapshot) -> Unit)?) {
        this.listener = listener
    }

    fun snapshot(): PauseBufferSnapshot = lock.withLock { snapshotLocked() }

    fun onPaused(sourceUrl: String) {
        val snap = lock.withLock {
            if (closed) return
            if (sourceUrl != url) switchLocked(sourceUrl)
            if (total > 0L && published >= total) {
                downloadWanted = false
                reason = PauseBufferReason.SAVED
            } else {
                downloadWanted = true
                reason = PauseBufferReason.SAVING
                ready.signalAll()
            }
            snapshotLocked()
        }
        emit(snap)
    }

    fun onPlaying(sourceUrl: String) {
        val snap = lock.withLock {
            if (closed) return
            if (sourceUrl != url) {
                switchLocked(null)
                downloadWanted = false
                closeActiveLocked()
                reason = PauseBufferReason.HIDDEN
            } else {
                downloadWanted = false
                closeActiveLocked()
                reason = retainedReasonLocked()
            }
            ready.signalAll()
            snapshotLocked()
        }
        emit(snap)
    }

    fun discard() {
        val snap = lock.withLock {
            if (closed) return@withLock snapshotLocked()
            switchLocked(null)
            downloadWanted = false
            closeActiveLocked()
            reason = PauseBufferReason.HIDDEN
            ready.signalAll()
            snapshotLocked()
        }
        emit(snap)
    }

    fun shutdown() {
        lock.withLock {
            closed = true
            downloadWanted = false
            closeActiveLocked()
            ready.signalAll()
        }
        worker.join(5_000)
    }

    /**
     * Opens a range only when it is fully inside the flushed prefix, or the
     * whole file when the save is complete and [rangeHeader] is null.
     * Returns null when the TV should use the normal upstream path.
     */
    fun openRange(sourceUrl: String, rangeHeader: String?): CachedRange? {
        return lock.withLock {
            if (sourceUrl != url || published <= 0L || total <= 0L) return@withLock null
            val part = partFile()
            if (!part.exists() || part.length() < published) return@withLock null
            if (rangeHeader.isNullOrBlank()) {
                if (published < total) return@withLock null
                val raf = RandomAccessFile(part, "r")
                return@withLock CachedRange(200, null, total, raf)
            }
            val spec = HttpByteRanges.parse(rangeHeader, total) ?: return@withLock null
            if (spec.endInclusive >= published) return@withLock null
            val length = spec.endInclusive - spec.start + 1
            val raf = RandomAccessFile(part, "r")
            raf.seek(spec.start)
            val contentRange = "bytes ${spec.start}-${spec.endInclusive}/$total"
            CachedRange(206, contentRange, length, raf)
        }
    }

    private fun loop() {
        while (true) {
            val job = lock.withLock {
                while (!closed && !downloadWanted) {
                    try {
                        ready.await()
                    } catch (_: InterruptedException) {
                        if (closed) return
                    }
                }
                if (closed) return
                val current = url ?: return@withLock null
                Job(current, generation)
            }
            if (job != null) {
                try {
                    download(job)
                } catch (_: Exception) {
                    if (stillCurrent(job) && downloadWantedLocked()) {
                        mark(job, PauseBufferReason.STOPPED)
                    }
                }
            }
        }
    }

    private data class Job(val url: String, val generation: Int)

    private fun download(job: Job) {
        var known = lock.withLock { if (!same(job)) -1L else total }
        if (known < 0L) return
        if (known == 0L) {
            known = resolveTotal(job) ?: return
        }
        var attempts = 0
        while (attempts < maxAttempts) {
            val start = lock.withLock {
                if (!same(job) || !downloadWanted) return
                published
            }
            if (start >= known) {
                mark(job, PauseBufferReason.SAVED)
                return
            }
            if (start >= maxBytes) {
                mark(job, PauseBufferReason.CAPPED)
                return
            }
            if (freeSpace() < minFreeBytes) {
                mark(job, PauseBufferReason.LOW_SPACE)
                return
            }
            var progressed = false
            try {
                upstream.open(job.url, start).use { body ->
                    lock.withLock {
                        if (!same(job) || !downloadWanted) {
                            body.close()
                            return
                        }
                        active = body
                    }
                    try {
                        when {
                            body.statusCode == 401 || body.statusCode == 403 || body.statusCode == 410 -> {
                                mark(job, PauseBufferReason.REJECTED)
                                return
                            }
                            body.statusCode == 206 || (body.statusCode == 200 && start == 0L) -> Unit
                            else -> throw java.io.IOException("unexpected status ${body.statusCode}")
                        }
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (!stillCurrent(job) || !downloadWantedLocked()) return
                            val n = body.stream.read(buf)
                            if (n < 0) break
                            val wrote = commit(job, buf, n, known)
                            if (wrote > 0) progressed = true
                            if (wrote < n) return
                        }
                    } finally {
                        lock.withLock {
                            if (active === body) active = null
                        }
                    }
                }
            } catch (_: UpstreamRejectedException) {
                mark(job, PauseBufferReason.REJECTED)
                return
            } catch (_: Exception) {
                if (!stillCurrent(job) || !downloadWantedLocked()) return
            }
            val state = lock.withLock {
                if (!same(job) || !downloadWanted) return
                published
            }
            if (state >= known) {
                mark(job, PauseBufferReason.SAVED)
                return
            }
            if (state >= maxBytes) {
                mark(job, PauseBufferReason.CAPPED)
                return
            }
            if (!progressed) attempts++ else attempts = 0
            if (attempts >= maxAttempts) {
                mark(job, PauseBufferReason.STOPPED)
                return
            }
            try {
                Thread.sleep(150L * attempts.coerceAtLeast(1))
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun resolveTotal(job: Job): Long? {
        var attempts = 0
        while (attempts < maxAttempts) {
            if (!stillCurrent(job) || !downloadWantedLocked()) return null
            try {
                val length = upstream.totalLength(job.url)
                if (length <= 0L) throw java.io.IOException("unknown length")
                val snap = lock.withLock {
                    if (!same(job)) return null
                    total = length
                    persistLocked()
                    snapshotLocked()
                }
                emit(snap)
                return length
            } catch (_: UpstreamRejectedException) {
                mark(job, PauseBufferReason.REJECTED)
                return null
            } catch (_: Exception) {
                attempts++
                if (attempts >= maxAttempts) {
                    mark(job, PauseBufferReason.STOPPED)
                    return null
                }
                try {
                    Thread.sleep(150L * attempts)
                } catch (_: InterruptedException) {
                }
            }
        }
        return null
    }

    /** @return bytes accepted from this buffer, which can be less than [n] when the save stops. */
    private fun commit(job: Job, buf: ByteArray, n: Int, knownTotal: Long): Int {
        var emitSnap: PauseBufferSnapshot? = null
        val accepted = lock.withLock {
            if (!same(job) || !downloadWanted) return 0
            if (freeSpace() < minFreeBytes) {
                downloadWanted = false
                reason = PauseBufferReason.LOW_SPACE
                closeActiveLocked()
                emitSnap = snapshotLocked()
                return@withLock 0
            }
            val room = minOf(maxBytes - published, knownTotal - published, n.toLong())
            if (room <= 0L) {
                downloadWanted = false
                reason = if (published >= knownTotal) PauseBufferReason.SAVED else PauseBufferReason.CAPPED
                closeActiveLocked()
                emitSnap = snapshotLocked()
                return@withLock 0
            }
            val previous = reason
            RandomAccessFile(partFile(), "rw").use { raf ->
                raf.seek(published)
                raf.write(buf, 0, room.toInt())
                raf.fd.sync()
            }
            published += room
            if (published >= knownTotal) {
                downloadWanted = false
                reason = PauseBufferReason.SAVED
                closeActiveLocked()
            } else if (published >= maxBytes) {
                downloadWanted = false
                reason = PauseBufferReason.CAPPED
                closeActiveLocked()
            } else {
                reason = PauseBufferReason.SAVING
            }
            persistLocked()
            if (reason != previous) emitSnap = snapshotLocked()
            room.toInt()
        }
        emitSnap?.let { emit(it) }
        return accepted
    }

    private fun mark(job: Job, next: PauseBufferReason) {
        val snap = lock.withLock {
            if (!same(job)) return
            downloadWanted = false
            closeActiveLocked()
            reason = when {
                next == PauseBufferReason.SAVED -> PauseBufferReason.SAVED
                next == PauseBufferReason.CAPPED && published > 0L -> PauseBufferReason.CAPPED
                next == PauseBufferReason.LOW_SPACE -> PauseBufferReason.LOW_SPACE
                next == PauseBufferReason.REJECTED -> PauseBufferReason.REJECTED
                next == PauseBufferReason.STOPPED && published > 0L -> PauseBufferReason.STOPPED
                next == PauseBufferReason.STOPPED -> PauseBufferReason.STOPPED
                published >= total && total > 0L -> PauseBufferReason.SAVED
                else -> next
            }
            ready.signalAll()
            snapshotLocked()
        }
        emit(snap)
    }

    private fun retainedReasonLocked(): PauseBufferReason {
        return when {
            published <= 0L -> PauseBufferReason.HIDDEN
            reason == PauseBufferReason.CAPPED ||
                reason == PauseBufferReason.LOW_SPACE ||
                reason == PauseBufferReason.REJECTED ||
                reason == PauseBufferReason.STOPPED -> reason
            total > 0L && published >= total -> PauseBufferReason.SAVED
            else -> PauseBufferReason.PARTIAL
        }
    }

    private fun stillCurrent(job: Job): Boolean = lock.withLock { same(job) && !closed }

    private fun downloadWantedLocked(): Boolean = lock.withLock { downloadWanted && !closed }

    private fun same(job: Job): Boolean = job.generation == generation && job.url == url

    private fun switchLocked(newUrl: String?) {
        generation++
        closeActiveLocked()
        partFile().delete()
        metaFile().delete()
        url = newUrl
        published = 0L
        total = if (newUrl == null) -1L else 0L
        reason = PauseBufferReason.HIDDEN
    }

    private fun closeActiveLocked() {
        val body = active
        active = null
        body?.close()
    }

    private fun snapshotLocked() = PauseBufferSnapshot(reason, published, total.coerceAtLeast(0L))

    private fun loadMetaLocked() {
        val meta = metaFile()
        if (!meta.exists()) return
        val lines = meta.readLines()
        if (lines.size < 3) {
            switchLocked(null)
            return
        }
        val saved = lines[0].toLongOrNull()
        val size = lines[1].toLongOrNull()
        val source = lines[2]
        val part = partFile()
        if (saved == null || size == null || saved < 0L || size < saved || source.isBlank() ||
            !part.isFile || part.length() < saved
        ) {
            switchLocked(null)
            return
        }
        url = source
        published = saved
        total = size
        reason = if (saved >= size && size > 0L) PauseBufferReason.SAVED else if (saved > 0L) {
            PauseBufferReason.PARTIAL
        } else {
            PauseBufferReason.HIDDEN
        }
    }

    private fun persistLocked() {
        val source = url ?: return
        val tmp = File(directory, "current.meta.tmp")
        tmp.writeText("$published\n$total\n$source\n")
        val dest = metaFile()
        if (!tmp.renameTo(dest)) {
            dest.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun partFile() = File(directory, "current.part")

    private fun metaFile() = File(directory, "current.meta")

    private fun emit(snap: PauseBufferSnapshot) {
        listener?.invoke(snap)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MIN_FREE_BYTES = 256L * 1024 * 1024
    }
}
