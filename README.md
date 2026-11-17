# Ktus ([Kotlin Multiplatform](https://kotlinlang.org/multiplatform/) + [Ktor](https://ktor.io/) + [Tus](https://tus.io))
[![CI](https://github.com/LDARtools/Ktus/actions/workflows/ci.yml/badge.svg)](https://github.com/LDARtools/Ktus/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.ldartools/ktus?filter=!*_*)](https://central.sonatype.com/artifact/com.ldartools/ktus)
[![Release](https://img.shields.io/github/v/release/LDARtools/Ktus?include_prereleases)](https://github.com/LDARtools/Ktus/releases/latest)
![GitHub License](https://img.shields.io/github/license/LDARtools/Ktus)

<img alt="Ktus Logo" height="100" src="./Ktus.svg" title="Logo"/>

> tus is a protocol based on HTTP for resumable file uploads. Resumable means that an upload can be interrupted at any moment and can be resumed without re-uploading the previous data again. An interruption may happen willingly, if the user wants to pause, or by accident in case of a network issue or server outage.

**Ktus** is a multiplatform client library for uploading files using the [tus resumable upload protocol](https://tus.io) to any remote server supporting it. It is build on top of the [Ktor](https://ktor.io/) client library and supports all platforms supported by Ktor.

## Usage

Below are several usage examples showing basic and advanced flows.

### Configure the HttpClient

Ktus uses your Ktor `HttpClient`. Two settings matter:

```kotlin
val httpClient = HttpClient(engine) {
    // tus requests must never be redirected. Ktus rejects any 3xx and any answer that came from another origin,
    // but only after the engine has followed it (Ktor follows HEAD redirects by default), so turn it off here.
    followRedirects = false
    // Ktus classifies responses by status itself, so expectSuccess can be either value.
    install(HttpTimeout) {
        socketTimeoutMillis = 60_000 // a stalled PATCH fails, is retried, and shrinks the chunk size
    }
}
```

### Basic

Ktus has a convenience function that combines both the create and upload phases of a Tus upload into a single call.

```kotlin
// import com.ldartools.ktus.createAndUploadTus
// import com.ldartools.ktus.okio.OkioTusFile

val file = OkioTusFile(filePath)

httpClient.createAndUploadTus(createUrl = url, file = file, metadata = mapOf("filename" to file.name)) { 
    // optional per-request configuration; runs again for every attempt, so it can refresh a token
    setAuthorizationHeader(anonymous = false)
}
```

### Resuming across attempts and app restarts

`resumeOrCreateAndUploadTus` remembers the upload URL in a `TusUrlStore` under a fingerprint you choose for the
file. The next call with the same fingerprint resumes where the server is instead of starting over.

```kotlin
// import com.ldartools.ktus.resumeOrCreateAndUploadTus
// import com.ldartools.ktus.TusUrlStore

// Writes must be on disk when set/remove return (SharedPreferences.commit(), DataStore edit, a database
// transaction), never fire-and-forget like SharedPreferences.apply(). See below.
class PrefsTusUrlStore(private val prefs: MyKeyValueStore) : TusUrlStore {
    override suspend fun get(fingerprint: String): TusUpload? = prefs.read(fingerprint)
    override suspend fun set(fingerprint: String, upload: TusUpload) = prefs.writeDurably(fingerprint, upload)
    override suspend fun remove(fingerprint: String) = prefs.deleteDurably(fingerprint)
}

httpClient.resumeOrCreateAndUploadTus(
    createUrl = url,
    file = file,
    fingerprint = "$path:${file.size}:$lastModified", // must change when the content changes
    store = PrefsTusUrlStore(prefs),
    metadata = mapOf("filename" to file.name),
)
```

What it does:
- A stored upload is resumed when its length equals the file size, its `expiresAt` (from the server's
  `Upload-Expires`) has not passed, and its URL is on an allowed origin (see [Security](#security)).
- If the server answers 404/410 for it, or reports a different `Upload-Length`, a new upload is created, and so
  it is (once) when the server refuses it permanently (a protocol error, or a status such as 403).
- A new upload is saved to the store right after creation, before any data is sent, and removed when the upload
  completes. Network failures, retryable statuses, 409 and problems with the local file leave it in place.
- Just before the PATCH carrying the last bytes, the entry is marked (`TusUpload.finalSentAt`). A marked entry is
  never dropped or re-created; see [When the outcome is unknown](#when-the-outcome-is-unknown). Persist the
  whole `TusUpload`, including that field.

`InMemoryTusUrlStore` is a ready-made store for tests and for resuming within one process.

### Create and upload separately

You can also split the create and upload phases. `createTusUpload` returns a `TusUpload` with the URL, the
length and the server's expiry; `createTus` returns just the URL.

```kotlin
// import com.ldartools.ktus.createTusUpload
// import com.ldartools.ktus.uploadTus

val upload = httpClient.createTusUpload(createUrl = url, file = file, metadata = mapOf("filename" to file.name))
saveSomewhere(upload)

// Later, possibly after a restart. Starts with HEAD and continues from the server's offset.
httpClient.uploadTus(uploadUrl = upload.uploadUrl, file = file) {
    setAuthorizationHeader(anonymous = false)
}
```

`uploadTus` throws `TusUploadExpiredException` when the server no longer has the upload (404/410), and
`TusUploadLengthMismatchException` when the URL belongs to a file of a different size; create a new upload in
both cases (or let `resumeOrCreateAndUploadTus` do it).

`createAndUploadTus` still accepts an `onCreate` callback that receives the upload URL right after creation.

### Progress

```kotlin
httpClient.createAndUploadTus(
    createUrl = url,
    file = file,
    onProgress = { sent, total -> println("Handed to the network: $sent / $total") },
    onConfirmed = { offset, total -> println("Stored by the server: $offset / $total") },
    onActivity = { watchdog.pet() },
)
```

- `onProgress` reports bytes handed to the HTTP engine while a PATCH body is written. It can go backwards when a
  chunk has to be sent again, and it is silent while Ktus waits to retry, which can legitimately last minutes
  (a server answering `503` with `Retry-After`, or tusdotnet's `423` lock). Do not use it for a stall watchdog.
- `onActivity` fires whenever Ktus is doing something: a request starts or gets its response, the body makes
  progress, every `TusUploadOptions.activityHeartbeatMillis` (default 5 s) while a request is in flight, and at
  least that often while it waits to retry. Use this one for a stall watchdog. With
  `activityHeartbeatMillis = null` it is silent while a single request is in flight, so the watchdog window
  must then exceed the engine's socket/request timeout plus about 10 s. The in-flight ticker runs in a side job
  that is cancelled with the request.
- Because the in-flight ticker keeps `onActivity` alive, a watchdog fed by it cannot catch a request that never
  completes. Bound that with the engine's timeouts (Ktor `HttpTimeout`: socket/request timeout): Ktus then
  gives up, at worst, about `maxElapsedMillis` + one request timeout after the failures start. Size
  `maxElapsedMillis` to what you can really wait, or put a finite outer limit (`withTimeout`) around the call.
- Callbacks can run on different threads: the in-flight ticker on the caller's dispatcher, and
  `onProgress`/`onActivity` during the body write on the engine's thread. Ktus never runs `onActivity`
  concurrently with itself (an overlapping call is skipped), but make callbacks thread-safe anyway (for
  example, only write an atomic timestamp).
- Exceptions thrown by `onProgress`, `onActivity`, `onConfirmed` or the `TusUrlStore` end the upload and reach
  you exactly as thrown (including a `CancellationException`); they are never retried.
- `onConfirmed` reports offsets the server has acknowledged: after each PATCH answered `204`, after a recovery
  HEAD that shows more data than before, and exactly once with `offset == total` when the upload is complete
  (including when the first HEAD shows it was already complete).

### Terminating an upload

```kotlin
httpClient.terminateTus(uploadUrl = upload.uploadUrl) { setAuthorizationHeader(anonymous = false) }
```

Sends `DELETE` ([Termination](https://tus.io/protocols/resumable-upload#termination) extension). `204`, and
`404`/`410` (already gone), count as success.

### Passing options

```kotlin
// import com.ldartools.ktus.TusUploadOptions
// import com.ldartools.ktus.RetryOptions

val options = TusUploadOptions(
    checkServerCapabilities = true,        // OPTIONS first: Tus-Resumable and Tus-Max-Size
    chunkSize = 4 * 1024 * 1024,           // bytes per PATCH
    useFileLock = false,
    allowEmptyUpload = false,              // a zero-byte file is rejected unless you allow it
    allowedUploadHosts = setOf("uploads.example.com"), // other hosts the server may send uploads to
    omitEmptyMetadataValues = true,        // drop metadata entries whose value is ""
    adaptiveChunkSize = true,              // halve the chunk after a PATCH timeout / 408 / 504 ...
    minChunkSize = 256 * 1024,             // ... down to this floor
    retryOptions = RetryOptions(
        maxRetries = 10,                   // attempts per operation, including the first
        initialDelayMillis = 500,
        maxDelayMillis = 30_000,
        factor = 2.0,
        maxElapsedMillis = 10 * 60 * 1000, // total budget for one run of consecutive failures
    )
)
```

### Pause and resume uploads

Cancel the coroutine to pause. Cancellation stops the request and closes the file section being read. Resume
with `uploadTus` (or call `resumeOrCreateAndUploadTus` again with the same fingerprint).

```kotlin
val job = scope.launch {
    httpClient.resumeOrCreateAndUploadTus(createUrl = url, file = file, fingerprint = fp, store = store)
}

job.cancelAndJoin() // pause
// ...
scope.launch {        // resume
    httpClient.resumeOrCreateAndUploadTus(createUrl = url, file = file, fingerprint = fp, store = store)
}
```

## Retries

Every tus request (OPTIONS, POST, HEAD, PATCH, DELETE) is classified by its HTTP status, whether or not the
client has `expectSuccess` set:

| Response | What Ktus does |
|---|---|
| Network error or timeout | Retry |
| 408, 423, 429, 500, 502, 503, 504 (`RetryOptions.retryableStatusCodes`) | Retry, waiting at least as long as a `Retry-After` header asks (delta-seconds or HTTP-date), up to max(`maxDelayMillis`, 60 s) per wait |
| 401 | Retry, so your request block can supply a fresh token (`retryOnUnauthorized`) |
| 409 on PATCH | HEAD, then continue from the server's offset |
| 404 / 410 on HEAD or PATCH | `TusUploadExpiredException` |
| 404 / 410 on POST or OPTIONS | `TusHttpException` (the endpoint is wrong; nothing expired) |
| 404 / 410 on DELETE | Success |
| Any 3xx | `TusProtocolException`; redirects are never followed |
| 423 | Retried like the others, but not counted against `maxRetries`: only `maxElapsedMillis` bounds a lock |
| Any other 4xx or 5xx | `TusHttpException` with the status code, method and start of the body (with `expectSuccess = true`, Ktor's own `ClientRequestException`/`ServerResponseException` is rethrown for these non-retried statuses, as in 1.0) |
| A closed `HttpClient` | `IllegalStateException` at once, no retries |

After any PATCH whose outcome is unknown (network error, timeout, a retryable status, 409, or any 2xx other than
`204`) Ktus backs off, sends HEAD, and continues from the offset the server reports. It never re-sends a chunk
at the old offset blindly. If that HEAD reports less than the server has already confirmed, the upload fails
with `TusOffsetMismatchException` rather than rewinding.

## When the outcome is unknown

The one failure a retry cannot fix: Ktus sent the PATCH carrying the **last** bytes, never learned whether the
server stored them (the connection dropped, the request timed out, a gateway answered 5xx, the response was
lost), and could not find out afterwards. The server may hold the complete file. tusdotnet even removes an
upload once it is complete, so every later HEAD or PATCH answers 404, which looks exactly like an expired
upload. Uploading again from scratch can make the server receive and process the file twice.

Ktus follows one rule. Once a PATCH carrying the last bytes has been sent in a call, that final chunk stays
**unresolved** until either
- a HEAD or the PATCH itself shows `offset == total`: the upload succeeded; or
- a later PATCH is answered `204` with an offset below the total, which proves the upload still exists and is
  incomplete.

A HEAD showing `offset < total` lets the upload continue, but does **not** resolve it: behind a load balancer
without affinity, the HEAD may be answered by a stale server instance. While the final chunk is unresolved,
**every** failure that ends the call (404/410, retries or budget exhausted, a network error, an HTTP error, a
protocol or validation error, a closed client) is reported as **`TusUploadOutcomeUnknownException`**, with the
original failure as its `cause`. The one exception is a **definitive refusal**: the PATCH carrying the last bytes was answered with a 4xx, a redirect, or failed on a
short local read, and no final PATCH earlier in the same call (or in an earlier call, for a marked entry) ended
without a definitive answer. Any 5xx is never definitive: a server may answer 5xx after it stored the bytes.
On a definitive refusal the original error is thrown. Cancelling the caller still throws
`CancellationException`.

`TusUploadOutcomeUnknownException` deliberately does **not** extend `IOException` or `TusProtocolException`, so a
generic "network error, retry later" or "expired, re-create" handler does not swallow it.

With `resumeOrCreateAndUploadTus`, the store entry is marked before the final PATCH is sent (`finalSentAt`). A
marked entry is never dropped or re-created: a later call that gets 404/410 for it (or finds it expired) throws
`TusUploadOutcomeUnknownException` again, and so does any other failure of a marked entry (including a 403):
it is never dropped or re-created. If the caller's coroutine is cancelled while the final PATCH is in flight,
the `CancellationException` propagates as coroutines require; the marked entry is how the next call knows.
A HEAD never clears the mark (it may be answered by a stale server instance); only a PATCH answered `204` with
an offset below the total does, because that proves the upload still exists and is incomplete, or a definitive
refusal of the final PATCH (see above). A malformed answer to the final PATCH (a `2xx` with a missing or impossible
`Upload-Offset`) is an unknown outcome too.

**A marked entry stays in the store until you remove it**, even when a later call gets a definitive answer from
the server (a 404, 403 or 423 that was not a direct answer to a first, fresh final PATCH): Ktus cannot tell a
completed-and-removed upload from one that never completed. To resolve it, ask your server whether the file
arrived (by id, name or checksum); then `store.remove(fingerprint)` and either mark the file uploaded or upload
it again. If removing the entry fails after an upload completed, the call still succeeds and the entry stays
marked: the next call reports `TusUploadOutcomeUnknownException`, and the same check resolves it.

The store must write durably: `set` and `remove` must have reached disk when they return (for example
`SharedPreferences.commit()` or DataStore, not `apply()`). A mark lost in a crash is a duplicate upload waiting
to happen.

`createAndUploadTus` and `uploadTus` have no store. If you use them, pass `onFinalPatch`: it is called right
before every attempt to send the PATCH carrying the last bytes, so you can persist "the final chunk may have
been sent". Cancelling such a call during the final PATCH surfaces as a plain `CancellationException` with no
other signal, so after any cancellation or failure of a call that may have reached the final chunk, check
the server's state before uploading again.

A catch-all `catch (e: Exception)` also catches `TusUploadOutcomeUnknownException`: catch it first.

### Gateways, timeouts and overlapping writes

Behind a gateway (for example Azure Front Door) with no session affinity, a PATCH that timed out at the gateway
(504) or on the client may still be running on the origin. Ktus backs off and asks with HEAD before sending more,
but the next PATCH can reach a different server instance while the first one is still appending. tusdotnet locks
an upload per process, not across instances, so two instances can write to the same upload at once if they
share its storage. Check that your server is safe for that (a storage-level lock, affinity, or a single
writer), or set `RetryOptions.minDelayMillis` above the origin's own request timeout, so every retry waits at
least that long (it is a floor applied after the jitter; raising `initialDelayMillis` alone does not guarantee
it, because full jitter can draw any delay down to 0).

**When you get this exception, check on your server whether the file arrived before uploading it again**, then
resolve the store entry yourself:

```kotlin
try {
    httpClient.resumeOrCreateAndUploadTus(createUrl = url, file = file, fingerprint = fp, store = store) { auth() }
    markUploaded(file)
} catch (e: TusUploadOutcomeUnknownException) {
    // Ask the server whether it has this file (for example by its id or checksum).
    if (api.serverHasFile(fileId)) {
        store.remove(fp)
        markUploaded(file)
    } else {
        // The server has nothing usable: start a fresh upload next time.
        store.remove(fp)
        scheduleRetry(file)
    }
} catch (e: IOException) {
    scheduleRetry(file) // offline, timeouts, retryable statuses: the store entry is kept for the next attempt
}
```

Backoff uses full jitter: each delay is random in `[0, min(maxDelayMillis, initialDelayMillis * factor^n)]`.
Retrying stops at whichever comes first:
- `maxElapsedMillis` (default 10 minutes), counted from the first failure of a run of consecutive failures. Any
  progress (a PATCH the server accepted, or a HEAD showing more data) starts a fresh run, so a long upload over a
  flaky network is not cut short while it keeps moving.
- `maxRetries` (default 10): the maximum number of attempts for one operation, including the first. Despite the
  name it counts attempts, not retries.

When retries run out, a network failure is rethrown as the original `IOException`; a status failure becomes a
`TusHttpException` on every method, with Ktor's `ResponseException` as its `cause` when the client has
`expectSuccess = true`.

`resumeOrCreateAndUploadTus` drops a stored upload and creates a new one (once) when resuming it fails
permanently: a protocol error or a status that is not retried, such as 403.

## Validation

- The file size is read once when the upload starts. The upload fails with `TusProtocolException` if the size
  changes before the server has every byte, or if the file turns out shorter than its size while a chunk is
  read. Only the size is checked: an edit that keeps the size the same is not detected. Use a fingerprint that
  includes the modification time or a hash if that matters.
- Empty files are rejected with `IllegalArgumentException` unless `allowEmptyUpload = true`.
- HEAD must report an `Upload-Offset` in `[0, size]`, and, when it sends `Upload-Length`, the file's size.
- A PATCH answered `204` must report an `Upload-Offset` that moved forward by at least one byte and at most the
  bytes sent.
- The upload succeeds only when the server's offset equals the file size.
- With `checkServerCapabilities`, a file larger than the server's `Tus-Max-Size` fails before anything is created.
- Metadata keys must be non-empty printable ASCII without spaces or commas (`IllegalArgumentException`
  otherwise, before any request is sent).

## Security

- **Upload URL origin.** The `Location` of a creation response is resolved per RFC 3986. The result must be on the
  creation URL's scheme, host and port, unless its host is in `TusUploadOptions.allowedUploadHosts`. Anything else
  is rejected, as is any URL with user information (`user@host`), so a compromised or misconfigured server cannot send your file and your request headers (for
  example an `Authorization` header) to another host. The same check applies to URLs loaded from a `TusUrlStore`.
- **No downgrade.** An `https` creation URL never accepts an `http` upload URL, whatever the host.
- **No raw fallback.** An unparseable `Location` is an error; it is never used as-is.
- **No redirects.** Any 3xx answer to a tus request is an error. Configure the client with
  `followRedirects = false`; if the client follows a redirect anyway (Ktor does for HEAD by default), an answer
  from another origin is rejected.

## Installation

Add the following libraries to your `.toml` file.

```toml
[versions]
ktus = "1.1.0"

[libraries]
ktus = { module = "com.ldartools:ktus", version.ref = "ktus" }
ktus-okio = { module = "com.ldartools:ktus-okio", version.ref = "ktus" }
```

Add the dependencies to your common code.

```kotlin
commonMain.dependencies {
    //...
    implementation(libs.ktus)
    implementation(libs.ktus.okio)
}
```

It is recommended that you used `OkioTusFile` to get started as this is the easiest approach.
Just understand that this take a dependency on [Okio](https://github.com/square/okio).
If you do not wish to use `OkioTusFile`, remove the references above and provide an `ITusFile` implementation.

## Features

* [Core Tus Protocol](https://tus.io/protocols/resumable-upload#core-protocol)
* [Creation](https://tus.io/protocols/resumable-upload#creation)
* [Termination](https://tus.io/protocols/resumable-upload#termination)
* [Expiration](https://tus.io/protocols/resumable-upload#expiration) (client side: `Upload-Expires` is read and used to skip expired stored uploads)
* [Metadata](https://tus.io/protocols/resumable-upload#upload-metadata)
* `Tus-Max-Size` check
* Resume across attempts and restarts (`TusUrlStore`, `resumeOrCreateAndUploadTus`)
* Status-based retries with full-jitter exponential backoff, `Retry-After` and a total time budget
* HEAD-before-resume after every uncertain PATCH, with offset and length validation
* Adaptive chunk size after timeouts
* Upload URL origin checks and redirect rejection
* Progress (bytes sent) and confirmed progress (bytes stored by the server)
* Full support for Ktor DSL
* Cross platform
* File read locks (optional)

## Limitations

The following optional [Tus protocol extensions](https://tus.io/protocols/resumable-upload#protocol-extensions) **have not** been implemented.
* [Creation With Upload](https://tus.io/protocols/resumable-upload#creation-with-upload)
* [Creation-Defer-Length](https://tus.io/protocols/resumable-upload#upload-defer-length)
* [Checksum](https://tus.io/protocols/resumable-upload#checksum)
* [Concatenation](https://tus.io/protocols/resumable-upload#concatenation)
* File read locks (optional) are not supported by `OkioTusFile` because file read locks are not supported by Okio. If you want this feature, please upvote this issue https://github.com/square/okio/issues/1464.
* File locks (optional) are volatile. They will not survive an application restart.

## Documentation

### ITusFile

Because Ktus is cross platform it must be told how to retrieve the bytes that are being uploaded. This is done via the `ITusFile` interface.
You must provide an `ITusFile` implementation(s) that will work for your platform(s).

```kotlin
interface ITusFile {
    /** The total size of the file in bytes. Read once at the start of an upload. */
    val size: Long

    /** The name of the file, which may be used for metadata. */
    val name: String

    /** Reads a range of the file into a ByteReadChannel (the 1.0 API; used by the default writeSection). */
    suspend fun readSection(offset: Long, length: Long): ByteReadChannel

    /** Creates an optional read lock that is closed when the upload ends. */
    suspend fun fileReadLock(): AutoCloseable

    /**
     * Writes exactly [length] bytes starting at [offset] to [channel]. Called inside the PATCH request, so a file
     * opened here and closed in a finally block cannot leak when the request fails or is cancelled. Must throw
     * TusProtocolException if the file is shorter than expected.
     */
    suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) { /* default: copies readSection */ }
}
```

Implementations written for 1.0 keep working through the default `writeSection`. New implementations should
override `writeSection` and open the file inside it.

Whatever the source of the `ITusFile`, it MUST be re-readable. By its nature, Tus will re-request bytes to be read if a chunk fails.

#### OkioTusFile

`OkioTusFile` is an `ITusFile` built on [Okio](https://github.com/square/okio), in a separate module to keep the
dependencies of Ktus at a minimum. It reads each chunk with a positional read (no reading and discarding from the
start of the file), opens the file inside the request, and closes it when the chunk is sent, fails or is
cancelled. Pass a `FileSystem` (for example okio's `FakeFileSystem` in tests) with `OkioTusFile(path, fileSystem)`.

### File locks

The `useFileLock` option takes the `ITusFile`'s read lock for the duration of the upload, provided the implementation supports it, in order to ensure that bytes are not mutated while an upload is in progress.

## Comparison with Official TUS Clients

Ktus was built and validated against the [TUS 1.0.0 protocol specification](https://tus.io/protocols/resumable-upload) and compared with the official TUS client implementations to ensure correctness and feature parity.

| Feature | [tus-js-client](https://github.com/tus/tus-js-client) | [tus-java-client](https://github.com/tus/tus-java-client) | [TUSKit (Swift)](https://github.com/tus/TUSKit) | **Ktus** |
|---|:---:|:---:|:---:|:---:|
| Core protocol (HEAD/PATCH) | Yes | Yes | Yes | Yes |
| Creation (POST) | Yes | Yes | Yes | Yes |
| OPTIONS capability check | Yes | No | Yes | Yes (incl. Tus-Max-Size) |
| Chunked uploads | Yes | Yes | Yes | Yes (adaptive after timeouts) |
| Metadata encoding | Yes | Yes | Yes | Yes |
| Metadata key validation | No | No | Yes | Yes |
| Retry with backoff | Fixed delays | No (caller) | Counter only | Full-jitter exponential + Retry-After + time budget |
| 5xx retry | Yes | No (caller) | Yes (system) | Yes (408/423/429/500/502/503/504) |
| 409 Conflict recovery | No | No | HEAD + retry | HEAD + retry |
| Offset advancement check | No | No | No | Yes |
| 404/410 expired handling | Re-creates upload | Exception | Re-creates upload | Exception, or re-create with `resumeOrCreateAndUploadTus` |
| Upload URL persistence | Built-in | Built-in | Built-in | `TusUrlStore` (bring your own storage) |
| Creation-with-upload | Yes | No | No | No |
| Upload-Defer-Length | Yes | No | No | No |
| Concatenation / parallel | Yes | No | No | No |
| Relative URL resolution | RFC 3986 | RFC 2396 | RFC 3986 | RFC 3986 |
| Cross-platform | Browser + Node | JVM | Apple | JVM, Android, iOS, Linux, + all Ktor targets |

### Where Ktus differs

**Strengths:**
- **Status-based retries** that behave the same whether or not the client throws on errors, with full jitter, `Retry-After` and a total time budget.
- **Never a blind re-send.** Every uncertain PATCH is followed by HEAD, and offsets and lengths reported by the server are validated.
- **Offset advancement validation** prevents infinite loops and catches servers that report impossible offsets.
- **Origin checks** stop a `Location` header or a stored URL from sending your file and credentials to another host.
- **Ktor DSL integration** gives full control over every HTTP request (auth headers, timeouts, logging, etc.) through a familiar Kotlin builder.
- **True multiplatform** — a single library covers JVM, Android, iOS, Linux, and all other Ktor-supported targets.

**Trade-offs:**
- **Persistence is yours.** `TusUrlStore` is an interface; Ktus ships only an in-memory implementation.
- **`uploadTus` does not re-create** an expired upload on its own; use `resumeOrCreateAndUploadTus` for that.

## Future Work

- [ ] Add `ITusFile` implementations for common platforms (e.g., Android, iOS, JVM)
- [ ] Add support for more Tus protocol extensions
    - [ ] Checksum
    - [ ] Creation With Upload
    - [ ] Concatenation

PRs are welcome! :)
