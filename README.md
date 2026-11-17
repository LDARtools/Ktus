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
    // tus requests must never be redirected; Ktus rejects any 3xx and any answer that came from another origin.
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

class PrefsTusUrlStore(private val prefs: MyKeyValueStore) : TusUrlStore {
    override suspend fun get(fingerprint: String): TusUpload? = prefs.read(fingerprint)
    override suspend fun set(fingerprint: String, upload: TusUpload) = prefs.write(fingerprint, upload)
    override suspend fun remove(fingerprint: String) = prefs.delete(fingerprint)
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
- If the server answers 404/410 for it, or reports a different `Upload-Length`, a new upload is created.
- A new upload is saved to the store right after creation, before any data is sent, and removed when the upload
  completes. Any other failure leaves it in place for the next attempt.

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
  progress, and at least every 5 seconds while it waits to retry. Use this one for a stall watchdog.
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

If the response to the PATCH carrying the last bytes is lost, the server may have stored them and finished the
upload; tusdotnet then removes it, so the follow-up HEAD answers 404/410. Ktus throws
`TusUploadPossiblyCompleteException` in that case (not `TusUploadExpiredException`), and
`resumeOrCreateAndUploadTus` removes the stored entry and rethrows it instead of uploading the file again.
**When you get this exception, check on your server whether the file arrived before uploading it again.**
A 404/410 after any earlier chunk is still `TusUploadExpiredException`.

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
