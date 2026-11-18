package com.ldartools.ktus.test

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal HTTP/1.1 tus server on a real socket, with scriptable faults that MockEngine cannot express:
 * dropping the connection after storing the body, answering before the body has been read, stalling
 * forever, and so on. It speaks just enough HTTP for the Ktor CIO engine (keep-alive, Content-Length bodies).
 */
class RealSocketTusServer : AutoCloseable {

    /** What the server does with one request. */
    sealed class Fault {
        /** Behave like a correct tus server. */
        data object None : Fault()

        /** Read and store the PATCH body, then close the connection without answering. */
        data object ApplyThenDrop : Fault()

        /** Answer 409 and close as soon as the PATCH headers arrive, without reading the body. */
        data object EarlyConflict : Fault()

        /** Read (and discard) the body, then answer [status] with [headers]. */
        data class Status(val status: Int, val headers: Map<String, String> = emptyMap()) : Fault()

        /** Read the headers, then never answer (until the client gives up or the server closes). */
        data object Stall : Fault()

        /** Read the headers, then neither read the body nor answer. */
        data object StallWithoutReading : Fault()

        /** Read the PATCH body [bytesPerTick] at a time, sleeping [tickMillis] between reads, then behave normally. */
        data class SlowRead(val bytesPerTick: Int, val tickMillis: Long) : Fault()

        /** Read the PATCH body, wait [millis], then behave normally. */
        data class AnswerAfter(val millis: Long) : Fault()
    }

    data class Logged(val method: String, val uploadOffset: Long?, val contentLength: Long?, val atMillis: Long, val result: String)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val lock = Object()
    private val startNanos = System.nanoTime()

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val stored = ByteArrayOutputStream()
    var uploadLength: Long? = null

    /** Answer 423 to HEAD and PATCH until this time (ms since start), like tusdotnet's per-file lock. */
    @Volatile var lockedUntilMillis: Long = 0

    /** Like tusdotnet: once the upload is complete it is processed and removed, so HEAD/PATCH answer 404. */
    @Volatile var removeWhenComplete = false

    /** Answer `Connection: close` and close after every response, so clients never reuse a connection. */
    @Volatile var oneRequestPerConnection = false
    @Volatile var removed = false
    val completions = AtomicInteger()

    /** Chooses the fault for each request: (method, 1-based count of requests with that method). */
    @Volatile var faults: (method: String, n: Int) -> Fault = { _, _ -> Fault.None }

    val log: MutableList<Logged> = Collections.synchronizedList(mutableListOf())
    private val counts = mutableMapOf<String, AtomicInteger>()
    val openConnections = AtomicInteger()

    val data: ByteArray get() = synchronized(lock) { stored.toByteArray() }
    val offset: Long get() = synchronized(lock) { stored.size().toLong() }

    fun nowMillis(): Long = (System.nanoTime() - startNanos) / 1_000_000

    private val acceptor = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: SocketException) { break }
            sockets += socket
            Thread { serve(socket) }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        openConnections.incrementAndGet()
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (!socket.isClosed) {
                val head = readHead(input) ?: break
                val keepOpen = handle(head, input, output, socket)
                if (!keepOpen || oneRequestPerConnection) break
            }
        } catch (e: Exception) {
            // Client went away.
        } finally {
            openConnections.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    private class Head(val method: String, val path: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.clear()
                if (text.isEmpty()) break
                lines += text
            } else {
                line.append(b.toChar())
            }
        }
        val (method, path) = lines.first().split(" ")
        val headers = lines.drop(1).associate { header ->
            val i = header.indexOf(':')
            header.substring(0, i).trim().lowercase() to header.substring(i + 1).trim()
        }
        return Head(method, path, headers)
    }

    private fun readBody(input: InputStream, length: Long, perRead: Int = 64 * 1024, sleepMillis: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(perRead)
        var remaining = length
        while (remaining > 0) {
            if (sleepMillis > 0) Thread.sleep(sleepMillis)
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** Returns whether to keep the connection open for another request. */
    private fun handle(head: Head, input: InputStream, output: OutputStream, socket: Socket): Boolean {
        val n = synchronized(counts) { counts.getOrPut(head.method) { AtomicInteger() }.incrementAndGet() }
        val fault = faults(head.method, n)
        val contentLength = head.headers["content-length"]?.toLongOrNull()
        val requestOffset = head.headers["upload-offset"]?.toLongOrNull()
        fun log(result: String) {
            log += Logged(head.method, requestOffset, contentLength, nowMillis(), result)
        }

        when (fault) {
            Fault.Stall, Fault.StallWithoutReading -> {
                log("stall")
                if (fault == Fault.Stall && contentLength != null) readBody(input, contentLength)
                while (!socket.isClosed && !server.isClosed) Thread.sleep(20)
                return false
            }
            Fault.EarlyConflict -> {
                log("409-early")
                respond(output, 409, mapOf("Connection" to "close"))
                socket.shutdownOutput()
                Thread.sleep(200)
                return false
            }
            else -> Unit
        }

        val locked = (head.method == "HEAD" || head.method == "PATCH") && nowMillis() < lockedUntilMillis
        if (removed && (head.method == "HEAD" || head.method == "PATCH")) {
            if (head.method == "PATCH") readBody(input, contentLength ?: 0)
            log("404")
            respond(output, 404, emptyMap())
            return true
        }
        when (head.method) {
            "OPTIONS" -> {
                log("204")
                respond(output, 204, mapOf("Tus-Resumable" to "1.0.0", "Tus-Version" to "1.0.0"))
            }
            "POST" -> {
                uploadLength = head.headers["upload-length"]?.toLongOrNull()
                log("201")
                respond(output, 201, mapOf("Location" to "/files/abc", "Tus-Resumable" to "1.0.0"))
            }
            "HEAD" -> {
                if (locked) { log("423"); respond(output, 423, emptyMap()); return true }
                if (fault is Fault.Status) { log("${fault.status}"); respond(output, fault.status, fault.headers); return true }
                log("200 offset=$offset")
                respond(output, 200, buildMap {
                    put("Upload-Offset", offset.toString())
                    uploadLength?.let { put("Upload-Length", it.toString()) }
                    put("Tus-Resumable", "1.0.0")
                    put("Cache-Control", "no-store")
                }, isHead = true)
            }
            "PATCH" -> {
                val body = if (fault is Fault.SlowRead) readBody(input, contentLength ?: 0, fault.bytesPerTick, fault.tickMillis)
                    else readBody(input, contentLength ?: 0)
                if (fault is Fault.AnswerAfter) Thread.sleep(fault.millis)
                if (locked) { log("423"); respond(output, 423, emptyMap()); return true }
                when (fault) {
                    is Fault.Status -> { log("${fault.status}"); respond(output, fault.status, fault.headers); return true }
                    Fault.ApplyThenDrop -> {
                        synchronized(lock) { if (requestOffset == offset) stored.write(body) }
                        checkComplete()
                        log("applied+dropped")
                        socket.close()
                        return false
                    }
                    else -> Unit
                }
                synchronized(lock) {
                    if (requestOffset != offset || body.size.toLong() != contentLength) {
                        log("409")
                        respond(output, 409, emptyMap())
                        return true
                    }
                    stored.write(body)
                }
                checkComplete()
                log("204 offset=$offset")
                respond(output, 204, mapOf("Upload-Offset" to offset.toString(), "Tus-Resumable" to "1.0.0"))
            }
            "DELETE" -> { log("204"); respond(output, 204, emptyMap()) }
            else -> { log("405"); respond(output, 405, emptyMap()) }
        }
        return true
    }

    private fun checkComplete() {
        if (offset == uploadLength) {
            completions.incrementAndGet()
            if (removeWhenComplete) removed = true
        }
    }

    private fun respond(output: OutputStream, status: Int, headers: Map<String, String>, isHead: Boolean = false) {
        val text = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            if (!isHead && status != 204) append("Content-Length: 0\r\n")
            if (oneRequestPerConnection) append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(text.toByteArray())
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 409 -> "Conflict"; 423 -> "Locked"
        503 -> "Service Unavailable"; else -> "Status"
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        acceptor.join(2_000)
    }
}
