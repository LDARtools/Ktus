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
    // tus requests must never be redirected. Ktus rejects any 3xx, and any answer that came from a different URL
    // than the one it asked for (extra query parameters that client plugins add are fine), but
    // only after the client has followed it (Ktor follows HEAD redirects by default), so turn it off here.
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
  never dropped or re-created; calling again simply resumes it. See
  [When the outcome is unknown](#when-the-outcome-is-unknown). Persist the whole `TusUpload`, including that field.

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

- `onProgress` reports bytes handed to the HTTP engine while a PATCH body is written. Some engines take the body
  before any connection exists (on iOS the Darwin engine can report the whole chunk and then fail to connect),
  so it is not proof that bytes reached the server. It can go backwards when a chunk has to be sent again, and it is silent while Ktus waits to retry, which can legitimately last minutes
  (a server answering `503` with `Retry-After`, or tusdotnet's `423` lock). Do not use it for a stall watchdog.
- `onActivity` fires whenever Ktus is doing something: a request starts or gets its response, the body makes
  progress, every `TusUploadOptions.activityHeartbeatMillis` (default 5 s) while a request is in flight, and at
  least that often while it waits to retry. Use this one for a stall watchdog. With
  `activityHeartbeatMillis = null` it is silent while a single request is in flight, so the watchdog window
  must then exceed the engine's socket/request timeout plus about 10 s. The in-flight ticker runs in a side job
  that is cancelled with the request.
- Because the in-flight ticker keeps `onActivity` alive, a watchdog fed by it cannot catch a request that never
  completes. Bound that with the engine's timeouts (Ktor `HttpTimeout`: socket/request timeout). The budget
  (`maxElapsedMillis`) measures a run of consecutive failures: it starts at the first failure, and any request that succeeds at its job ends
  it (OPTIONS answered, POST 201, the first HEAD answered, a PATCH 204, a recovery HEAD showing more data). A
  recovery HEAD that shows no new data does not end it, so a server that answers but never accepts data still
  runs out of budget. When `resumeOrCreateAndUploadTus` gets a definitive answer about a stored upload (gone,
  wrong length, a permanent refusal) and creates a new one, that also ends the run: the new upload starts with a
  full budget. Once it is spent, no request is sent except the single last resolution HEAD when Ktus gives up
  retrying a PATCH while a final PATCH is unresolved. So counted from when the run's first failing request was sent,
  Ktus gives up at worst after: that request + `maxElapsedMillis` + up to two request timeouts (a request still
  in flight when the budget ran out, then that last HEAD; both can happen when the request in flight is the
  final PATCH). Without a request timeout a request can run forever, so
  set one. Size `maxElapsedMillis` to what you can really wait, or put a finite outer limit (`withTimeout`)
  around the call.
- Callbacks can run on different threads: the in-flight ticker on the caller's dispatcher, and
  `onProgress`/`onActivity` during the body write on the engine's thread. Ktus never runs `onActivity`
  concurrently with itself (an overlapping call is skipped), but make callbacks thread-safe anyway (for
  example, only write an atomic timestamp).
- Do not call Ktor's `onUpload` in the request block: Ktor keeps one upload listener per request, and Ktus
  installs its own after your block. Use `onProgress` instead.
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
    targetChunkMillis = 10_000,            // size each PATCH to ~10 s on the measured link (default off)
    sendStallMillis = 30_000,              // abandon a request whose body stops moving (default off)
    replyTimeoutMillis = 30_000,           // ... or that got no answer this long after it was sent (default off)
    finalReplyTimeoutMillis = 300_000,     // the same for the PATCH carrying the last bytes (default off)
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
`204`) Ktus backs off, then sends HEAD, and continues from the offset the server reports. When it gives up
retrying a PATCH while a final PATCH is unresolved (out of attempts or budget, or the required wait would not
fit the budget; the PATCH given up on can be a later, smaller one after the chunk was halved), it sends one last
HEAD without waiting: if that shows the whole file, the upload succeeded. If a recovery HEAD is what fails (its
own retries run out, or the budget is spent without progress), no further HEAD is sent and the call ends with
`TusUploadOutcomeUnknownException`. It never re-sends a chunk at the
old offset blindly. If that HEAD reports less than the server has already confirmed, Ktus asks again (up to 3
times, with backoff, within the budget) and then fails with `TusOffsetMismatchException` rather than rewinding.

## When the outcome is unknown

The one failure a retry cannot fix: Ktus sent the PATCH carrying the **last** bytes, never learned whether the
server stored them (the connection dropped, the request timed out, a gateway answered 5xx, the response was
lost), and could not find out afterwards. The server may hold the complete file. tusdotnet even removes an
upload once it is complete, so every later HEAD or PATCH answers 404, which looks exactly like an expired
upload. That includes the final PATCH itself, when another request (a replayed PATCH, a second worker, an earlier
call whose mark was lost) completed the upload first. Uploading again from scratch can make the server receive
and process the file twice, so a 404/410 answered to any final PATCH is never treated as "expired": it is
`TusUploadOutcomeUnknownException` with `kind = UPLOAD_GONE`.

Ktus follows one rule. Once a PATCH carrying the last bytes has been sent in a call, that final chunk stays
**unresolved** until either
- a HEAD or the PATCH itself shows `offset == total`: the upload succeeded; or
- a later PATCH is answered `204` with an offset below the total, which proves the upload still exists and is
  incomplete.

A HEAD showing `offset < total` lets the upload continue, but does **not** resolve it: behind a load balancer
without affinity, the HEAD may be answered by a stale server instance. While the final chunk is unresolved,
**every** failure that ends the call (404/410, retries or budget exhausted, a network error, an HTTP error, a
protocol or validation error, a closed client) is reported as **`TusUploadOutcomeUnknownException`**, with the
original failure as its `cause`. The one exception is a **definitive refusal**: the PATCH carrying the last
bytes was answered with a 4xx that Ktus does not retry, other than 404/410 (408, 409, 423, 429 and, by default,
401 are retried and are never definitive; 404/410 are never definitive, see above), or a 3xx, or failed on a short local read, and no final PATCH earlier in the same call
(or in an earlier call, for a marked entry) ended without a definitive answer. Any 5xx is never definitive (a
server may answer 5xx after it stored the bytes), and neither is an answer from a different URL than the one
requested (the client followed a redirect, so the body may have reached the original server first).

Once any final PATCH has ended without a definitive answer, that stays true: a later PATCH answered `204` below
the total lets the upload continue, but every failure for the rest of the call is still
`TusUploadOutcomeUnknownException` (the earlier PATCH may yet complete on another server instance), and the
store mark is kept, so the next call starts in the same state. Only the upload completing, or you removing
the entry, clears it.
On a definitive refusal the original error is thrown. Cancelling the caller still throws
`CancellationException`.

`TusUploadOutcomeUnknownException` deliberately does **not** extend `IOException` or `TusProtocolException`, so a
generic "network error, retry later" or "expired, re-create" handler does not swallow it.

With `resumeOrCreateAndUploadTus`, the store entry is marked before the final PATCH is sent (`finalSentAt`). A
marked entry is never dropped or re-created: a later call that gets 404/410 for it (or finds it expired) throws
`TusUploadOutcomeUnknownException` again, and so does any other failure of a marked entry (including a 403):
it is never dropped or re-created. If the caller's coroutine is cancelled while the final PATCH is in flight,
the `CancellationException` propagates as coroutines require; the marked entry is how the next call knows.
The mark is cleared only when the upload completes, or when a PATCH is answered `204` below the total while no
final PATCH has ever ended uncertain (an entry carried over from an earlier call keeps its mark until it
completes), or by a definitive refusal of the final PATCH (see above). A HEAD never clears it (it may be
answered by a stale server instance). A malformed answer to the final PATCH (a `2xx` with a missing or impossible
`Upload-Offset`) is an unknown outcome too.

**What to do with `TusUploadOutcomeUnknownException`:** every one has exactly one `e.kind`
(`TusUploadOutcomeUnknownException.Kind`, also exposed as `isUploadGone`, `isNetworkFailure`, `isServerRefusal`),
computed from the cause and the retry policy in use:

| `e.kind` | Meaning | What to do |
|---|---|---|
| `UPLOAD_GONE` | 404/410 or `TusUploadExpiredException` (including a 404/410 answered to the final PATCH itself), or no cause because the marked entry could not be resumed at all (expired, length no longer matches the file, host no longer allowed). Typically the server finished and removed the upload. | Ask your server whether the file arrived, then remove the entry. Never re-create the upload without that check: the file may already be on the server. |
| `SERVER_REFUSAL` | A 4xx your retry policy does not retry (such as 403 or 400, whether Ktor threw it or not), or a different `Upload-Length`. Retrying will not help. | Ask your server whether the file arrived, then remove the entry. |
| `NETWORK_FAILURE` | Offline, a timeout, a connection lost. | Call `resumeOrCreateAndUploadTus` again later. Do not call your server-check API while offline. |
| `UNKNOWN` | Anything else: an exhausted 5xx or 423, an offset mismatch (a stale server instance can cause it), a protocol error, a closed client. | Call again later. |

Calling again is always safe: it sends HEAD to the same upload URL, finds the upload complete or sends what the
server is missing (a server that disagrees about the offset answers 409, which protects the data), and never
re-creates a marked entry.

**When you check the server**, treat "received" and "still processing" as present. The PATCH whose outcome is
unknown may still be running on some server instance, so do not check right after it unless the upload URL is
gone; and remove the entry (`store.remove(fingerprint)`) only once the server has answered: it has the file
(report success), or it does not and the upload URL is gone (start a fresh upload).

Ktus is conservative here: a 423 or 409 answered to the final PATCH also leaves its outcome unknown (the server
was busy with, or disagreed about, that very upload), which can cost one extra call or server check.

If removing the entry fails after an upload completed, the call still succeeds and the entry stays marked; the
next call finds the upload complete or gone, and the same steps resolve it.

**If your server can answer 5xx after it stored a body** (for example "stored, but processing failed"), a later
success (a HEAD showing the full offset) can follow such a 5xx. Verify the stored content on the server after
every success, not only after failures.

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
writer), or set `RetryOptions.minDelayMillis` above the origin's own request timeout. It applies to retries of a
PATCH the origin may still be processing: one that timed out, got 502/503/504 or 423, or failed with a network
error that does not prove the request never reached the origin. It does not apply to failures that prove it
never got there (connection refused or unreachable, DNS failure, no network, connect timeout, TLS handshake
failure; on iOS NSURLErrorDomain -1003, -1004, -1006, -1022 and -1201 to -1206), or to HEAD/OPTIONS/POST
retries. Other iOS errors, such as -1009 "not connected to the internet", get the floor, because they can occur
after bytes were sent. It is a floor applied after the jitter; raising `initialDelayMillis` alone does not
guarantee it, because full jitter can draw any delay down to 0. When the remaining `maxElapsedMillis` budget is
shorter than the wait a retry requires, Ktus gives up instead of retrying early; `minDelayMillis` must be below
`maxElapsedMillis`.

### Weak links: adaptive chunks and silence limits

On a weak mobile link you want a dead request to fail fast and be retried, without cutting one that is slow but
still moving. **No timer on the client can tell those apart once the bytes are buffered**, and they always are:

- Ktus sees a body's progress only as bytes handed to Ktor. Ktor buffers about 1 MiB of a request body before
  the socket, on every engine: a chunk of up to ~1 MiB is handed over at once (on Darwin before the connection
  even exists), and a larger one moves in ~1 MiB steps.
- Below Ktor, the kernel's send and receive buffers hold more. An engine's own timeout cannot see past them
  either. Ktus pins what the engines do in tests (`OkHttpSocketTimeoutBehaviourTest`,
  `DarwinSocketTimeoutBehaviourTest`, `CioStallRealSocketTest`): with a 2.5 s `socketTimeoutMillis` and a
  server reading a 2 MiB body steadily over ~13 s,
  - **CIO** cuts it at about the timeout (2.5 s): its socket timeout runs from the connection, so it caps the
    whole request.
  - **OkHttp** (read/write timeouts per socket operation) and **Darwin** (NSURLSession's idle timeout) cut it
    before the server has read it all (after 7-13 s on a Mac, earlier on CI runners with larger socket
    buffers): once the engine has written the last byte, the time the server needs to read what is still
    buffered counts as silence.
  - All three cut a server that stops reading at ~2.5 s, and wait for a slow answer only as long as the limit.

So any silence limit, the engine's or Ktus's, holds only if a request's bytes in flight are small next to the
link speed: **limit > bytes in flight ÷ slowest speed + the server's answer time.** Ktus keeps the bytes in
flight small with adaptive chunk sizing, and then applies its own engine-independent limits.

**Adaptive chunk sizing** (`targetChunkMillis`, off by default). The first PATCH of a call uses `minChunkSize`.
After each PATCH the server confirmed, Ktus measures the end-to-end goodput (bytes confirmed ÷ time from the
PATCH's start to its `204`; the server's answer is the only measure of bytes really delivered), smooths it
(exponential average, weight 0.5), and sizes the next PATCH to take `targetChunkMillis`: within
`minChunkSize`..`chunkSize`, and at most twice the previous size, so one fast sample cannot jump to the cap. A
timeout or a silence-limit cut drops the next PATCH straight to `minChunkSize` (a cut gives no goodput sample,
and halving from 2 MiB on a link that just fell to ~3 KB/s would cost one more cut per halving); it grows back
at most 2× per confirmed PATCH. It needs `adaptiveChunkSize` (the default). A resume within the same call (after a HEAD) keeps the
estimate; a new call starts again from `minChunkSize`. With a target, `minChunkSize` may be as low as 16 KiB.
More, smaller PATCHes cost the server more requests: a store that keeps each PATCH as a separate piece (for
example one Azure block-blob block per PATCH, at most 50,000 blocks) limits the file to about
50,000 × `minChunkSize` in the worst case (1.5 GiB at 32 KiB).

**Silence limits** (all off by default; they work the same on every engine):

- `sendStallMillis`: abandon a request whose body makes no progress for this long while it is not fully sent.
  The clock starts when the request is handed to the engine (so a stalled connect or TLS handshake counts) and
  restarts on every body progress event, the same events behind `onProgress`. A request without a body
  (OPTIONS, HEAD, POST, DELETE) is fully sent as soon as it is handed to the engine, so only the reply limit
  applies to it.
- `replyTimeoutMillis`: once a request is fully sent (every body byte handed to the engine), abandon it if no
  response arrives within this long. Every request except the PATCH carrying the last bytes.
- `finalReplyTimeoutMillis`: the same for the PATCH carrying the last bytes, which a server may answer only
  after slow completion work (storing or processing the whole file). Setting only `replyTimeoutMillis` leaves
  the final PATCH with no Ktus limit.

An abandoned request is a timed-out network failure (`TusStallTimeoutException`, an `IOException` whose `kind`
says which limit was reached): it is retried within the retry budget (it counts as a failure of the run, like
any other), the chunk shrinks as after an engine timeout, and an abandoned final PATCH leaves its outcome unknown
exactly as an engine timeout does. Cancelling the caller is still a plain cancellation.

**An abandoned request is abandoned only on the client.** Ktus stops the request and closes or releases the
connection, but bytes already handed to the operating system can still be delivered (an engine usually closes
the socket gracefully), so the link stays busy for a while after a cut and the server may still receive the
whole body and apply the abandoned PATCH. That is safe: tus offsets keep the data correct. The next PATCH is
sent at the offset the HEAD reported; if the abandoned one lands first, the server answers that next PATCH
with 409, Ktus asks with HEAD again and continues from the new offset, and no byte is applied twice
(`AbandonedPatchRealSocketTest`). Ktor offers no engine-neutral way to make a cancelled request reset the
connection instead.

**Recommended together**, for a mobile client behind Azure Front Door (240 s origin timeout):

```kotlin
TusUploadOptions(
    chunkSize = 2 * 1024 * 1024,        // the cap
    minChunkSize = 32 * 1024,           // the floor, and the first PATCH of each call
    targetChunkMillis = 10_000,         // each PATCH ~10 s of data on the measured link
    sendStallMillis = 30_000,           // 3x the target
    replyTimeoutMillis = 30_000,
    finalReplyTimeoutMillis = 300_000,  // the gateway's 240 s + ~3x the target (see below)
)
```

The final reply clock starts when the last byte is handed to Ktor, not when it reaches the server, so it also
covers sending what is still buffered: set `finalReplyTimeoutMillis` to at least the proxy's timeout plus about
3 × `targetChunkMillis` (280-300 s behind a 240 s proxy). The proxy then answers first with its own 504 when the
origin is slow.

The 3× margin covers the kernel buffers and a link that slows down between two PATCHes. The floor still has to
fit: at ~3 KB/s a 32 KiB PATCH takes ~11 s, inside 30 s; on a link slower than about `minChunkSize` ÷ limit,
every attempt is cut until the retry budget ends.

**Engine timeouts stay an outer cap, well above these, or off.** CIO's `socketTimeoutMillis` counts the whole
request from the connection, and OkHttp's and Darwin's cut a moving body once only the buffered tail is left
(above). Use at least 4 × (`sendStallMillis` + `replyTimeoutMillis`) for the PATCHes before the last one, and at
least `finalReplyTimeoutMillis` plus the time to send the final chunk for the final PATCH; an engine timeout is
per client, so in practice that means the larger value, or leaving it off and relying on Ktus's limits.

**In tests under `runTest`**, pass the test scheduler's clock (`RetryOptions(timeSource = testScheduler.timeSource)`):
the silence limits, the adaptive sizing and the retry budget all measure time with `RetryOptions.timeSource`, and
with the default real clock the watchdog would mix virtual delays with real time.

### Things to avoid

- **Ktor's `HttpRequestRetry` plugin** on the client used for tus. Ktus retries itself and decides after every
  failure whether to ask the server with HEAD first; a plugin that silently re-sends a PATCH defeats that and can
  append the same bytes twice.
- **Two calls with the same fingerprint at the same time** (for example a foreground and a background worker).
  They share one store entry and one server upload; their PATCHes conflict, and their marks overwrite each
  other. Run at most one upload per fingerprint.

### Stored entries that keep failing

- A stored upload whose server keeps reporting an impossible offset (`TusOffsetMismatchException`) is kept, since
  a stale HEAD can cause that too. It will keep failing until it expires (`expiresAt`), or forever if the server
  sent no `Upload-Expires`. Resolve it like an outcome-unknown entry: check the server, then
  `store.remove(fingerprint)`.
- `expiresAt` is read once, from the creation response, and never refreshed from later responses. Ktus assumes
  the server's expiry is absolute (set at creation). If your server extends the expiry on every PATCH, a stored
  entry may be treated as expired early, and a new upload created; set a far expiry or omit `Upload-Expires`
  if that matters.

An example of the steps above:

```kotlin
try {
    httpClient.resumeOrCreateAndUploadTus(createUrl = url, file = file, fingerprint = fp, store = store) { auth() }
    markUploaded(file)
} catch (e: TusUploadOutcomeUnknownException) {
    when (e.kind) {
        // Offline, a flaky network, a transient server error or a stale answer: resuming later is safe.
        TusUploadOutcomeUnknownException.Kind.NETWORK_FAILURE,
        TusUploadOutcomeUnknownException.Kind.UNKNOWN -> scheduleRetry(file)
        // The upload URL is gone, or the server keeps refusing it: only the server can say whether the file
        // arrived ("received" or "still processing" both count as arrived).
        TusUploadOutcomeUnknownException.Kind.UPLOAD_GONE,
        TusUploadOutcomeUnknownException.Kind.SERVER_REFUSAL -> when (api.serverHasOrIsProcessing(fileId)) {
            true -> { store.remove(fp); markUploaded(file) }
            false -> { store.remove(fp); scheduleRetry(file) } // the next call creates a fresh upload
            null -> scheduleRetry(file) // could not reach the server: ask again later
        }
    }
} catch (e: IOException) {
    scheduleRetry(file) // offline, timeouts, retryable statuses: the store entry is kept for the next attempt
}
```

Catch `TusUploadOutcomeUnknownException` before any catch-all `catch (e: Exception)`, and make the
server-check call itself handle being offline (treat a failure there as "try again later").

**Your own callback exceptions are not wrapped.** If `onProgress`, `onActivity` or `onConfirmed` throws during or
after the final PATCH (with `createAndUploadTus`, `uploadTus` or `resumeOrCreateAndUploadTus`), the call ends with
that exception exactly as thrown, not `TusUploadOutcomeUnknownException`, even though the final PATCH may have
landed. Treat it like a cancellation after `onFinalPatch`: check the server before uploading again.

Backoff uses full jitter: each delay is random in `[0, min(maxDelayMillis, initialDelayMillis * factor^n)]`.
Retrying stops at whichever comes first:
- `maxElapsedMillis` (default 10 minutes), measured over a run of consecutive failures: it starts at the first failure, and any request that succeeds at its job ends
  it (OPTIONS answered, POST 201, the first HEAD answered, a PATCH 204, a recovery HEAD showing more data). A
  recovery HEAD that shows no new data does not end it, so a server that answers but never accepts data still
  runs out of budget. A long upload over a
  flaky network is not cut short while it keeps moving, and a blip early in a call does not shorten later retries.
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
  from any URL other than the one requested is rejected. The one tolerance: an answer for the same path whose
  query has every requested parameter value plus extra parameters (or extra values) is accepted, because client
  plugins add parameters that way after Ktus built the request; such an answer is for the same tus resource.
  On iOS, NSURLSession does not follow redirects on its own; Ktor's redirect handling does, and is caught the same
  way.

## Installation

Add the following libraries to your `.toml` file.

```toml
[versions]
ktus = "1.2.0"

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
