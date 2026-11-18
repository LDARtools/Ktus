@file:OptIn(ExperimentalForeignApi::class)

package com.ldartools.ktus.test

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import platform.posix.*
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * A minimal HTTP/1.1 tus server on a real POSIX socket, for the Darwin (NSURLSession) engine on the iOS
 * simulator. One request per connection (`Connection: close`), Content-Length or chunked bodies.
 */
class PosixTusServer : AutoCloseable {

    enum class Fault {
        None, ApplyThenDrop, Stall, StallWithoutReading,
        /** Read the PATCH body 64 KiB at a time with 50 ms pauses, then behave normally. */
        SlowRead,
    }

    data class Logged(val method: String, val uploadOffset: Long?, val contentLength: Long?, val atMillis: Long, val result: String)

    private val start = TimeSource.Monotonic.markNow()
    fun nowMillis(): Long = start.elapsedNow().inWholeMilliseconds

    private val lock = NSLock()
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private var stored = ByteArray(0)
    var uploadLength: Long? = null
    var removeWhenComplete = false
    private var removed = false
    val completions = AtomicInt(0)
    private val counts = mutableMapOf<String, Int>()
    private val logList = mutableListOf<Logged>()

    /** (method, 1-based count of that method) -> fault, or a status with headers via [statuses]. */
    var faults: (String, Int) -> Fault = { _, _ -> Fault.None }
    var statuses: (String, Int) -> Pair<Int, Map<String, String>>? = { _, _ -> null }

    val data: ByteArray get() = locked { stored.copyOf() }
    val log: List<Logged> get() = locked { logList.toList() }

    @Volatile private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenFd: Int = socket(AF_INET, SOCK_STREAM, 0)
    val port: Int
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        check(listenFd >= 0) { "socket() failed: $errno" }
        port = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1 in network byte order (little-endian host)
            check(bind(listenFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed: $errno" }
            check(listen(listenFd, 32) == 0) { "listen failed: $errno" }
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(listenFd, addr.ptr.reinterpret(), len.ptr)
            val p = addr.sin_port.toInt()
            ((p and 0xff) shl 8) or ((p shr 8) and 0xff)
        }
        scope.launch { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val ready = memScoped {
                val pfd = alloc<pollfd>().apply { fd = listenFd; events = POLLIN.convert() }
                poll(pfd.ptr, 1u, 100)
            }
            if (ready <= 0) continue
            val fd = accept(listenFd, null, null)
            if (fd < 0) continue
            memScoped {
                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
            }
            scope.launch {
                try { serve(Conn(fd)) } catch (e: Throwable) { } finally { platform.posix.close(fd) }
            }
        }
    }

    private inner class Conn(val fd: Int) {
        private val buf = ByteArray(64 * 1024)
        private var pos = 0
        private var lim = 0

        fun readByte(): Int {
            if (pos == lim) {
                val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), 0).toInt() }
                if (n <= 0) return -1
                pos = 0; lim = n
            }
            return buf[pos++].toInt() and 0xff
        }

        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = readByte()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        fun readExactly(n: Long, slow: Boolean = false): ByteArray {
            val out = ByteArray(n.toInt())
            var i = 0
            while (i < n) {
                if (slow && i % (64 * 1024) == 0) usleep(50_000u)
                val b = readByte()
                if (b == -1) break
                out[i++] = b.toByte()
            }
            return if (i == out.size) out else out.copyOf(i)
        }

        fun write(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) {
                val n = bytes.usePinned { send(fd, it.addressOf(off), (bytes.size - off).convert(), 0).toInt() }
                if (n <= 0) return
                off += n
            }
        }
    }

    private fun serve(conn: Conn) {
        val requestLine = conn.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = conn.readLine() ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val method = requestLine.substringBefore(' ')
        val n = locked { (counts[method] ?: 0).plus(1).also { counts[method] = it } }
        val contentLength = headers["content-length"]?.toLongOrNull()
        val requestOffset = headers["upload-offset"]?.toLongOrNull()
        fun log(result: String) = locked { logList += Logged(method, requestOffset, contentLength, nowMillis(), result) }
        fun body(): ByteArray = when {
            contentLength != null -> conn.readExactly(contentLength, slow = faults(method, n) == Fault.SlowRead)
            headers["transfer-encoding"]?.contains("chunked") == true -> readChunked(conn)
            else -> ByteArray(0)
        }

        when (faults(method, n)) {
            Fault.Stall, Fault.StallWithoutReading -> {
                log("stall")
                if (faults(method, n) == Fault.Stall) body()
                while (!closed) usleep(20_000u)
                return
            }
            else -> Unit
        }
        statuses(method, n)?.let { (status, extra) ->
            if (method == "PATCH") body()
            log("$status")
            respond(conn, status, extra)
            return
        }
        if (locked { removed } && (method == "HEAD" || method == "PATCH")) {
            if (method == "PATCH") body()
            log("404")
            respond(conn, 404, emptyMap())
            return
        }
        when (method) {
            "OPTIONS" -> { log("204"); respond(conn, 204, mapOf("Tus-Resumable" to "1.0.0")) }
            "POST" -> {
                uploadLength = headers["upload-length"]?.toLongOrNull()
                log("201")
                respond(conn, 201, mapOf("Location" to "/files/abc", "Tus-Resumable" to "1.0.0"))
            }
            "HEAD" -> {
                val offset = locked { stored.size }
                log("200 offset=$offset")
                respond(conn, 200, buildMap {
                    put("Upload-Offset", offset.toString())
                    uploadLength?.let { put("Upload-Length", it.toString()) }
                    put("Cache-Control", "no-store")
                })
            }
            "PATCH" -> {
                val bytes = body()
                // A body shorter than its Content-Length means the client aborted the request: like a server that
                // only applies complete requests, store nothing (and answer nothing).
                if (contentLength != null && bytes.size.toLong() != contentLength) { log("aborted"); return }
                val accepted = locked {
                    if (requestOffset == stored.size.toLong()) { stored += bytes; true } else false
                }
                if (accepted) checkComplete()
                if (faults(method, n) == Fault.ApplyThenDrop) { log("applied+dropped"); return }
                if (!accepted) { log("409"); respond(conn, 409, emptyMap()); return }
                val offset = locked { stored.size }
                log("204 offset=$offset")
                respond(conn, 204, mapOf("Upload-Offset" to offset.toString(), "Tus-Resumable" to "1.0.0"))
            }
            else -> { log("405"); respond(conn, 405, emptyMap()) }
        }
    }

    private fun checkComplete() {
        locked {
            if (stored.size.toLong() == uploadLength) {
                completions.incrementAndGet()
                if (removeWhenComplete) removed = true
            }
        }
    }

    private fun readChunked(conn: Conn): ByteArray {
        var out = ByteArray(0)
        while (true) {
            val size = conn.readLine()?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: break
            if (size == 0L) { conn.readLine(); break }
            out += conn.readExactly(size)
            conn.readLine()
        }
        return out
    }

    private fun respond(conn: Conn, status: Int, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $status X\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        conn.write(text.encodeToByteArray())
    }

    override fun close() {
        closed = true
        platform.posix.close(listenFd)
        scope.cancel()
    }
}
