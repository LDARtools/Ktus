# Changelog

All notable changes to Ktus are documented here. The project follows [Semantic Versioning](https://semver.org/).

## 1.2.0

Adaptive chunk sizing and silence limits that do not depend on the HTTP engine. Everything is off by default, so
1.1 behaviour is unchanged; 1.1.x callers keep linking (hidden 1.1 `TusUploadOptions` constructor and `copy`).

### Added
- `TusUploadOptions.targetChunkMillis`: size each PATCH to take about this long. The first PATCH of a call uses
  `minChunkSize`; after each confirmed PATCH the next size is the smoothed goodput (bytes confirmed / time to its
  204, exponential average with weight 0.5) times the target, within `minChunkSize`..`chunkSize` and at most
  twice the previous size. A timeout halves it and lowers the estimate; a resume within the call keeps it. With
  a target, `minChunkSize` must be at least 16 KiB.
- `TusUploadOptions.sendStallMillis`: abandon a request whose body makes no progress for this long while it is
  not fully sent. The clock starts when the request is handed to the engine and restarts on every body progress
  event. Requests without a body are fully sent once handed to the engine.
- `TusUploadOptions.replyTimeoutMillis`: abandon a fully sent request that gets no answer within this long; every
  request except the PATCH carrying the last bytes.
- `TusUploadOptions.finalReplyTimeoutMillis`: the same for the PATCH carrying the last bytes.
- `TusStallTimeoutException` (an `IOException`) with `kind` (`SEND_STALL`, `REPLY_TIMEOUT`,
  `FINAL_REPLY_TIMEOUT`) and `limitMillis`. An abandoned request is classified exactly like an engine timeout:
  retried within the run budget, the chunk halved, and an abandoned final PATCH leaves its outcome unknown.
  Caller cancellation stays a cancellation.
- Each limit must be positive when set (`IllegalArgumentException` otherwise).

Why: no client-side timer can tell a slow request from a dead one once its bytes are buffered (Ktor holds ~1 MiB
of a body on every engine, the kernel more). CIO's socket timeout caps a whole PATCH; OkHttp's and Darwin's cut a
slow but moving body once only the buffered tail is left (pinned in `OkHttpSocketTimeoutBehaviourTest` and
`DarwinSocketTimeoutBehaviourTest`). Adaptive sizing keeps each PATCH's bytes in flight small next to the link
speed, which is what makes the silence limits valid; see "Weak links" in the README for the rule and the
recommended values.

## 1.1.0

A reliability and security release. The 1.0.x function and constructor signatures remain in the binary as
hidden overloads, so code compiled against 1.0.x links against 1.1.0, and calls that use named arguments or a
trailing `block` lambda keep compiling. Two source breaks: a call to `uploadTus` with five positional arguments
or to `createAndUploadTus` with seven (passing `block` positionally rather than as a trailing lambda) no longer
compiles, because new callback parameters now sit before `block`. Use a trailing lambda or `block = …`.

Two new failure types are not `IOException`s, so a "network error, retry later" handler does not catch them:
`TusUploadOutcomeUnknownException` and the `IllegalStateException` thrown for a closed `HttpClient`.

### Added
- `onActivity` callback on `uploadTus`, `createAndUploadTus` and `resumeOrCreateAndUploadTus`: fires on every
  request, on body progress and at least every 5 s while waiting to retry. Use it for stall watchdogs;
  `onProgress` is silent during retry waits.
- `TusUploadOutcomeUnknownException` (extends `Exception`, not `IOException`): the outcome of the PATCH
  carrying the last bytes is unknown and could not be established; verify on the server before uploading
  again. See "When the outcome is unknown" in the README.
- `TusUpload.finalSentAt`: set by `resumeOrCreateAndUploadTus` before the final PATCH; a marked entry is never
  dropped or re-created.
- `TusLocalFileException` (a `TusProtocolException`) for problems with the local file (size changed, short read).
- `RetryOptions.minDelayMillis` (default 0, must be below `maxElapsedMillis` when set): the shortest wait before retrying a
  PATCH the origin may still be processing (a request/socket timeout, 502/503/504, 423, or any network error
  that does not prove the request never reached the origin), applied after the jitter. If the remaining budget
  is shorter than that wait, the call gives up instead of retrying early. It does not apply to failures that
  prove nothing reached the origin (connection refused or unreachable, DNS failure, no network, connect
  timeout, TLS handshake failure, and NSURLErrorDomain -1003, -1004, -1006, -1022 and -1201 to -1206 on iOS), or to HEAD/OPTIONS/POST.
  The decision comes from the failure, not from upload progress, which some engines report before connecting.
- `TusUploadOutcomeUnknownException.kind` (`UPLOAD_GONE`, `SERVER_REFUSAL`, `NETWORK_FAILURE`, `UNKNOWN`, exactly one
  per exception, computed from the cause and the retry policy in use) and the matching `isUploadGone`,
  `isServerRefusal` and `isNetworkFailure`, to decide between "check the server" and "call again later" without
  hand-rolled cause checks. An offset mismatch is `UNKNOWN` (a stale server instance can cause it).
- `TusUploadOptions.activityHeartbeatMillis` (default 5 s; null turns it off): `onActivity` ticks while a
  request is in flight.
- `onFinalPatch` callback on `uploadTus`, `createAndUploadTus` and `resumeOrCreateAndUploadTus`: called right
  before every attempt to send the PATCH carrying the last bytes, so callers without a store can persist that
  the final chunk may have been sent.
- `createTusUpload(...)`: creates an upload and returns a `TusUpload` (URL, length, `expiresAt` parsed from
  `Upload-Expires`).
- `TusUrlStore`, `InMemoryTusUrlStore` and `resumeOrCreateAndUploadTus(...)`: resume a stored upload when it is
  still valid, otherwise create a new one; the URL is saved before any data is sent and removed on success.
- `terminateTus(...)`: the Termination extension (`DELETE`; 204, 404 and 410 count as success).
- `onConfirmed(offset, total)` on `uploadTus`, `createAndUploadTus` and `resumeOrCreateAndUploadTus`: the
  offset the server has acknowledged. `onProgress` keeps its meaning (bytes handed to the engine).
- `TusHttpException(statusCode, method, body)` and `TusUploadLengthMismatchException`, both subclasses of
  `TusProtocolException`.
- `TusUploadOptions`: `allowEmptyUpload`, `allowedUploadHosts`, `omitEmptyMetadataValues`,
  `adaptiveChunkSize`, `minChunkSize`.
- `RetryOptions`: `maxElapsedMillis`, `retryableStatusCodes`, `retryOnUnauthorized`, `honorRetryAfter`,
  `jitter`, `random`, `timeSource`.
- `ITusFile.writeSection(offset, length, channel)` with a default implementation built on `readSection`.
- `OkioTusFile(path, fileSystem)` and `Path.toTusFile(fileSystem)`.

### Changed
- Responses are classified by HTTP status for every tus request, whether or not the client has
  `expectSuccess` set. In 1.0.x, with the default `expectSuccess = false`, a 5xx on OPTIONS, POST or HEAD
  failed at once, and a 5xx on PATCH was retried with no delay.
- Retryable statuses are 408, 423, 429, 500, 502, 503 and 504, plus 401 (so the request block can refresh a
  token). `Retry-After` (seconds or HTTP-date) is honoured, up to max(`maxDelayMillis`, 60 s) per wait and
  never beyond the remaining budget.
- A `CancellationException` thrown by the HTTP engine while the caller is still active is treated as a network
  failure and retried; cancelling the caller still stops the upload at once.
- 404/410 from POST or OPTIONS is now a `TusHttpException`, not a `TusUploadExpiredException`; 404/410 from
  HEAD or PATCH is still `TusUploadExpiredException`.
- Any other 4xx/5xx is a `TusHttpException` carrying the status. With `expectSuccess = true` Ktor's own
  `ClientRequestException`/`ServerResponseException` is still rethrown for these non-retried statuses, as in
  1.0.x. When retries of a retryable status run out, every method throws `TusHttpException`, with Ktor's
  exception as its `cause` when the client threw one.
- 423 Locked is not counted against `maxRetries`; only the elapsed budget bounds a lock.
- A closed `HttpClient` fails at once with `IllegalStateException` instead of being retried.
- `resumeOrCreateAndUploadTus` drops a stored upload whose resume fails permanently (a protocol error or a
  non-retried status such as 403) and creates a new one, once.
- `ktus-okio` exposes `ktus` as an `api` dependency, since `ITusFile` is part of its public API.
- After every PATCH whose outcome is unknown (network error, timeout, retryable status, 409, any 2xx other than
  204) Ktus backs off, sends HEAD, and continues from the server's offset. 1.0.x re-sent the same chunk at the
  old offset after a network error (and, with `expectSuccess = true`, after a 5xx).
- Backoff uses full jitter and a time budget (`maxElapsedMillis`, default 10 minutes) for each run of
  consecutive failures; any request that succeeds at its job ends the run. The non-204 PATCH path now backs off too.
- `RetryOptions.maxRetries` is documented as what it always was: attempts per operation, including the first.
  Its default rose from 3 to 10, and `maxDelayMillis` from 10 s to 30 s, so the time budget is the main limit.
- Validation: the file size is read once and must not change; HEAD `Upload-Offset` must be within the file and
  `Upload-Length`, when present, must equal the file size; a 204 PATCH must advance the offset by 1 to the
  number of bytes sent; a file larger than `Tus-Max-Size` fails before creation.
- After a PATCH timeout, 408 or 504 the chunk size is halved for later PATCHes, down to `minChunkSize`
  (`adaptiveChunkSize`, on by default).
- Metadata entries with an empty value are left out by default (tusdotnet rejects a key without a value).
  Metadata keys must be printable ASCII without spaces or commas.
- PATCH bodies are written by `ITusFile.writeSection` inside the request. If the server answers before reading
  the whole body, the body write is stopped, so the file is closed.
- `OkioTusFile` reads chunks positionally (`openReadOnly` + `source(offset)`) inside the request instead of
  skipping from byte 0 in a detached coroutine, and throws `TusProtocolException` when the file is shorter
  than expected instead of sending a truncated chunk.

### Security
- The creation `Location` is resolved per RFC 3986 (1.0.x mangled a relative Location with a query string).
  The upload URL must stay on the creation URL's scheme, host and port unless its host is listed in
  `allowedUploadHosts`; an `https` creation URL never accepts an `http` upload URL; an unparseable Location is
  an error instead of being used as-is. Stored URLs are checked the same way.
- Any 3xx answer to a tus request is an error, and an answer that came from another origin (because the client
  followed a redirect) is rejected. Configure clients with `followRedirects = false`.

### Behaviour changes (source compatible, but observable)
Code that compiled against 1.0.x compiles against 1.1.0, but these differences can be seen at run time:
- **Empty files are rejected.** Uploading a zero-byte file throws `IllegalArgumentException` before any request,
  unless `allowEmptyUpload = true`.
- **Metadata keys are validated.** A key that is empty or contains anything but printable ASCII without spaces
  or commas throws `IllegalArgumentException` (1.0.x rejected only spaces, commas and empty keys).
- **Empty metadata values are dropped** from `Upload-Metadata` unless `omitEmptyMetadataValues = false`.
- **A `Location` on another origin needs the allow-list.** A different host, port or scheme is rejected unless
  the host is in `allowedUploadHosts`; https to http is always rejected, and so is a `Location` with user
  information (`user@host`).
- **Any 3xx answer is an error** (`TusProtocolException`), and an answer the client got by following a
  redirect (to any other URL, the same origin included; the same path with every requested query value plus
  extra parameters or values, as client plugins add them, is fine)
  is rejected; on a final PATCH that is an unknown outcome.
- **No request is sent once the retry budget is spent, except the single last resolution HEAD** (without waiting
  or retrying) when Ktus gives up retrying a PATCH while a final PATCH is unresolved (also a later, smaller PATCH
  after the chunk was halved); if it shows the whole file, the upload succeeds. (When a recovery HEAD is what
  fails, no further HEAD is sent.) The budget measures a run of
  consecutive failures across the whole call: any request that succeeds at its job ends the run (a recovery HEAD
  that shows no new data does not), and so does the definitive answer that makes `resumeOrCreateAndUploadTus`
  drop a stored upload and create a new one. Counted from when the run's first failing request
  was sent, a call gives up at worst after that request + `maxElapsedMillis` + up to two request timeouts (a
  request still in flight when the budget ran out, then that last HEAD).
- **`minDelayMillis` must be below `maxElapsedMillis`** (when it is set), otherwise no retry could be sent.
- **404/410 from POST or OPTIONS** is a `TusHttpException`, no longer a `TusUploadExpiredException`.
- **Retry defaults are longer.** `maxRetries` 3 → 10, `maxDelayMillis` 10 s → 30 s, plus a new 10-minute
  `maxElapsedMillis` budget. Worst case for one operation that keeps failing: about 10 attempts with up to
  30 s (random, full jitter) between them, so typically 1–2 minutes. Counted from when a run's first failing
  request was sent, a call gives up at worst after that request + `maxElapsedMillis` + up to two request
  timeouts (a request still in flight when the budget ran out, then the last resolution HEAD for an unresolved
  final PATCH). A `Retry-After` can stretch a single wait up to max(`maxDelayMillis`, 60 s). Any request that
  succeeds at its job ends the run of failures; a recovery HEAD that shows no new data does not. Pass
  `RetryOptions(maxRetries = 3, maxDelayMillis = 10_000)` for the old limits.
- **HEAD before every re-PATCH.** After any PATCH whose outcome is unknown, Ktus waits, sends HEAD and
  continues from the server's offset, so a retry costs one extra request. If that HEAD reports an offset below
  one the server already confirmed, the upload fails with `TusOffsetMismatchException` instead of rewinding.
- **An unknown final outcome is reported separately.** Once the outcome of the PATCH carrying the last bytes is
  unknown and a HEAD cannot show `offset == total` (success) or `offset < total` (continue), every failure of
  the call becomes `TusUploadOutcomeUnknownException`, including the 404/410 that tusdotnet answers after it
  has completed and removed an upload, exhausted retries and HTTP errors. A plain retry of such an upload
  raised `IOException` or `TusUploadExpiredException` before. `resumeOrCreateAndUploadTus` keeps the entry
  marked and never re-creates it; calling it again is safe and resumes the same upload. **Never start a fresh
  upload of the file without checking the server first**; see "When the outcome is unknown" in the README.
- **A 404/410 answered to a final PATCH is never "expired".** tusdotnet answers exactly that once another
  request (a replayed PATCH, a second worker, a lost mark) completed the upload and removed it, so it is
  `TusUploadOutcomeUnknownException` with `kind = UPLOAD_GONE` (cause: the `TusUploadExpiredException`), even on
  the first final PATCH. `resumeOrCreateAndUploadTus` keeps the entry marked instead of re-creating the upload,
  and `createAndUploadTus` no longer throws a plain `TusUploadExpiredException` that a caller would answer by
  re-creating. On `UPLOAD_GONE`, check the server; never re-create blindly. A 404/410 on the first HEAD or on a
  PATCH that does not carry the last bytes (with no final PATCH sent) is still `TusUploadExpiredException`.
- **Once a final PATCH has been sent in a call, it stays unresolved** until the server shows `offset == total` or
  a later PATCH is answered 204 below the total; a HEAD never resolves it. While unresolved, every failure of
  the call is `TusUploadOutcomeUnknownException`. The only exception is a definitive refusal of a final PATCH:
  a non-retried 4xx other than 404/410 (408, 409, 423, 429 and 401 are retried and never definitive; 404/410
  never are), a 3xx, or a short local
  read, when no final PATCH earlier in the call (or in an earlier call, for a marked entry) ended without a
  definitive answer. A 5xx, or an answer from another origin (the client followed a redirect), is never
  definitive.
- **Once any final PATCH ended without a definitive answer, that uncertainty is permanent.** For the rest of the
  call every failure is `TusUploadOutcomeUnknownException`, even after a later 204 below the total let the
  upload continue, and the store mark is kept, so the next call starts uncertain too. Only completing the
  upload, or the app removing the entry, clears it.
- **The `finalSentAt` mark is only cleared by a PATCH answered 204 below the total when no final PATCH ended
  uncertain** (or by a definitive refusal of the final PATCH, a short read included), never by a HEAD (which can
  be stale). The closed-client check runs before the mark is
  written, so a PATCH that was never sent leaves no mark. If removing the entry fails after the upload
  completed, the call still succeeds.
- **`TusUrlStore` writes must be durable when they return.**
- **A HEAD below an offset the server already confirmed is asked again** (up to 3 times, with backoff, within the
  budget) before the upload fails with `TusOffsetMismatchException`; confirmed bytes are never re-sent.
- **`RetryOptions` validation.** Compared with 1.0.3 (which validated nothing):
  - negative `initialDelayMillis`, `maxDelayMillis`, `maxElapsedMillis` or `minDelayMillis`, a negative or NaN
    `factor`, and a `minDelayMillis` equal to or above `maxElapsedMillis` now throw `IllegalArgumentException`;
  - `maxRetries` of 0 or less still means a single attempt, as in 1.0.3;
  - `initialDelayMillis` above `maxDelayMillis` is accepted, and is now capped at `maxDelayMillis` (1.0.3 used it
    uncapped for the first wait);
  - a `factor` between 0 and 1 is accepted (delays shrink), as in 1.0.3.
- **Ktus installs its upload-progress listener after the request block.** Ktor keeps one `onUpload` listener per
  request, so an `onUpload` set in the block is replaced; use `onProgress`.
- **`TusOffsetMismatchException` no longer makes `resumeOrCreateAndUploadTus` drop a stored upload** (it can be
  caused by a stale HEAD); the entry is kept.
- **A short read is reported as `TusLocalFileException`** and is not retried, whatever the engine reports for the
  aborted request (the writer's exception, a cancellation, an `IOException`, or the server's answer to the
  truncated body, depending on timing); verified with real sockets on the Darwin engine as well as with
  MockEngine and okio's FakeFileSystem. A 2xx answered to a body Ktus knows was short is an unknown outcome on
  the final PATCH.
- **Any failure of a marked entry, including a permanent 4xx, is `TusUploadOutcomeUnknownException`**; a marked
  entry is never dropped or re-created.
- **A malformed answer to the final PATCH is an unknown outcome**: a 2xx with a missing or impossible
  `Upload-Offset`, and also a client closed while the final PATCH is in flight.
- **A retry that the budget cannot cover is not sent.** When the remaining `maxElapsedMillis` is shorter than the
  wait a retry requires (`minDelayMillis`, the 250 ms floor after 423, or an honoured `Retry-After`), Ktus gives up
  with the last failure instead of retrying early.
- **`OkioTusFile` reports a missing or deleted file as `TusLocalFileException`** (not retried); it used to report
  size 0 or a raw okio `IOException` that was retried.
- **`activityHeartbeatMillis` must be positive or null**; it also sets the backoff heartbeat interval.
- **Callbacks may run on different threads** and must be thread-safe. `onActivity` is never run concurrently
  with itself: an overlapping call (heartbeat vs. body-write progress) is skipped.
- **The size check stops once the server has every byte**, and an edit that keeps the file's size is never
  detected.
- **Exceptions thrown by `onProgress`** are rethrown as they are and end the upload; they are never treated as
  network errors and retried.

### Build and CI
- Every GitHub Action is pinned to a full commit SHA; workflows default to `permissions: contents: read`.
- Publishing runs only for full releases, runs the JVM tests first, checks that the release tag equals the
  Gradle version, and runs in the `maven-central` GitHub environment. The maintainer must create that
  environment with required reviewers and move the Maven Central and signing secrets into it; secrets left at
  repository level would bypass the protection.
- Gradle wrapper validation in every workflow, and `distributionSha256Sum` in `gradle-wrapper.properties`.
- A macOS CI job compiles the iOS targets and runs the iOS simulator tests (including Darwin-engine
  real-socket tests); the publish job runs the JVM and iOS simulator tests before publishing.
- Build outputs are no longer committed.

## 1.0.3
- Retry on 401 Unauthorized so the request block can refresh an expired token.

## 1.0.2
- `ktus-okio` version bump.

## 1.0.1 and earlier
- Initial releases.
