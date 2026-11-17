package com.ldartools.ktus.test

import com.ldartools.ktus.ITusFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * A MockEngine-backed tus server with scriptable faults, for commonTest.
 *
 * Every method has a queue of [Reply]s; when the queue is empty the server behaves like a correct tus
 * server. The PATCH handler always reads the whole body first (MockEngine cannot show partial writes —
 * the jvmTest real-socket tests cover those), and [Reply.apply] decides whether the bytes were stored
 * before the scripted answer, simulating "server applied the chunk, then the response was lost".
 */
class ScriptedTusServer(
    var location: String = "/files/abc",
    private val clock: () -> Long = { 0L },
) {
    sealed class Reply(val apply: Boolean) {
        /** Answer with [status] and [headers]. */
        class Status(
            val status: Int,
            val headers: Map<String, String> = emptyMap(),
            val body: String = "",
            apply: Boolean = false,
        ) : Reply(apply)

        /** Throw [error] from the engine, like a dropped connection. */
        class Throw(val error: Throwable, apply: Boolean = false) : Reply(apply)

        /** Fully custom answer. */
        class Custom(
            val handler: suspend MockRequestHandleScope.(request: HttpRequestData, body: ByteArray) -> HttpResponseData,
        ) : Reply(false)
    }

    data class Recorded(
        val method: String,
        val url: String,
        val uploadOffset: Long?,
        val bodySize: Int,
        val timeMillis: Long,
        val headers: Map<String, String>,
    )

    /** Bytes the server has stored for the upload. */
    var data: ByteArray = ByteArray(0)
    val offset: Long get() = data.size.toLong()

    /** `Upload-Length` reported by HEAD; defaults to the length sent by POST. Null = omit the header. */
    var uploadLength: Long? = null
    var reportUploadLength = true
    var maxSize: Long? = null
    var uploadExpires: String? = null
    var tusResumableOnOptions = true

    val requests = mutableListOf<Recorded>()
    private val scripts = mutableMapOf<String, ArrayDeque<Reply>>()

    /** Hook run at the start of every request, before scripted replies (for assertions mid-upload). */
    var onRequest: suspend (HttpRequestData) -> Unit = {}

    fun script(method: HttpMethod, vararg replies: Reply) {
        scripts.getOrPut(method.value) { ArrayDeque() }.addAll(replies)
    }

    fun count(method: HttpMethod): Int = requests.count { it.method == method.value }
    fun methods(): List<String> = requests.map { it.method }
    fun patchOffsets(): List<Long> = requests.filter { it.method == "PATCH" }.mapNotNull { it.uploadOffset }
    fun patchSizes(): List<Int> = requests.filter { it.method == "PATCH" }.map { it.bodySize }
    fun times(method: HttpMethod): List<Long> = requests.filter { it.method == method.value }.map { it.timeMillis }

    val engine: MockEngine = MockEngine { request -> handle(request) }

    fun client(expectSuccess: Boolean = false, followRedirects: Boolean = true): HttpClient =
        HttpClient(engine) {
            this.expectSuccess = expectSuccess
            this.followRedirects = followRedirects
        }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        onRequest(request)
        val body = if (request.method == HttpMethod.Patch) readBody(request) else ByteArray(0)
        requests += Recorded(
            method = request.method.value,
            url = request.url.toString(),
            uploadOffset = request.headers["Upload-Offset"]?.toLongOrNull(),
            bodySize = body.size,
            timeMillis = clock(),
            headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
        )

        val reply = scripts[request.method.value]?.removeFirstOrNull()
        if (reply != null) {
            if (reply.apply && request.method == HttpMethod.Patch) applyPatch(request, body)
            return when (reply) {
                is Reply.Status -> respond(
                    content = reply.body,
                    status = HttpStatusCode.fromValue(reply.status),
                    headers = headersOf(*reply.headers.map { it.key to listOf(it.value) }.toTypedArray()),
                )
                is Reply.Throw -> throw reply.error
                is Reply.Custom -> reply.handler(this, request, body)
            }
        }
        return defaultReply(request, body)
    }

    private fun applyPatch(request: HttpRequestData, body: ByteArray): Boolean {
        val requested = request.headers["Upload-Offset"]?.toLongOrNull()
        if (requested != offset) return false
        data += body
        return true
    }

    private fun MockRequestHandleScope.defaultReply(request: HttpRequestData, body: ByteArray): HttpResponseData =
        when (request.method) {
            HttpMethod.Options -> {
                val headers = buildList {
                    if (tusResumableOnOptions) add("Tus-Resumable" to listOf("1.0.0"))
                    add("Tus-Version" to listOf("1.0.0"))
                    maxSize?.let { add("Tus-Max-Size" to listOf(it.toString())) }
                }
                respond("", HttpStatusCode.NoContent, headersOf(*headers.toTypedArray()))
            }
            HttpMethod.Post -> {
                uploadLength = request.headers["Upload-Length"]?.toLongOrNull()
                val headers = buildList {
                    add("Location" to listOf(location))
                    add("Tus-Resumable" to listOf("1.0.0"))
                    uploadExpires?.let { add("Upload-Expires" to listOf(it)) }
                }
                respond("", HttpStatusCode.Created, headersOf(*headers.toTypedArray()))
            }
            HttpMethod.Head -> {
                val headers = buildList {
                    add("Upload-Offset" to listOf(offset.toString()))
                    if (reportUploadLength) uploadLength?.let { add("Upload-Length" to listOf(it.toString())) }
                    add("Tus-Resumable" to listOf("1.0.0"))
                }
                respond("", HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
            }
            HttpMethod.Patch -> {
                if (!applyPatch(request, body)) {
                    respond("offset mismatch", HttpStatusCode.Conflict)
                } else {
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", offset.toString()))
                }
            }
            HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }

    companion object {
        suspend fun readBody(request: HttpRequestData): ByteArray = when (val body = request.body) {
            is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readByteArray()
            is OutgoingContent.ByteArrayContent -> body.bytes()
            else -> ByteArray(0)
        }
    }
}

/** An [ITusFile] whose size can be changed mid-upload, and which records every section read. */
class MutableTusFile(private val nameValue: String, var data: ByteArray) : ITusFile {
    /** When set, [size] reports this instead of `data.size`. */
    var sizeOverride: Long? = null
    val reads = mutableListOf<Pair<Long, Long>>()

    override val size: Long get() = sizeOverride ?: data.size.toLong()
    override val name: String get() = nameValue

    override suspend fun readSection(offset: Long, length: Long): ByteReadChannel {
        reads += offset to length
        val start = offset.toInt().coerceIn(0, data.size)
        val end = (offset + length).toInt().coerceIn(start, data.size)
        return ByteReadChannel(data.copyOfRange(start, end))
    }

    override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
}

fun testBytes(size: Int): ByteArray = ByteArray(size) { ((it * 31) xor (it ushr 8)).toByte() }
