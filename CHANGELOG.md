# Changelog

All notable changes to Ktus are documented here. The project follows [Semantic Versioning](https://semver.org/).

## 1.1.0

A reliability and security release. Existing calls keep compiling and keep their meaning; the 1.0.x function
and constructor signatures remain in the binary as hidden overloads, so code compiled against 1.0.x links
against 1.1.0.

### Added
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
  `ClientRequestException`/`ServerResponseException` is still rethrown, as in 1.0.x.
- After every PATCH whose outcome is unknown (network error, timeout, retryable status, 409, any 2xx other than
  204) Ktus backs off, sends HEAD, and continues from the server's offset. 1.0.x re-sent the same chunk at the
  old offset after a network error (and, with `expectSuccess = true`, after a 5xx).
- Backoff uses full jitter and a total time budget (`maxElapsedMillis`, default 10 minutes, counted from the
  first failure of a run and reset by progress). The non-204 PATCH path now backs off too.
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
  redirect to another origin is rejected.
- **404/410 from POST or OPTIONS** is a `TusHttpException`, no longer a `TusUploadExpiredException`.
- **Retry defaults are longer.** `maxRetries` 3 → 10, `maxDelayMillis` 10 s → 30 s, plus a new 10-minute
  `maxElapsedMillis` budget. Worst case for one operation that keeps failing: about 10 attempts with up to
  30 s (random, full jitter) between them, so typically 1–2 minutes and never more than the 10-minute budget
  plus one request. A `Retry-After` can stretch a single wait up to max(`maxDelayMillis`, 60 s). Any upload
  progress restarts the budget. Pass `RetryOptions(maxRetries = 3, maxDelayMillis = 10_000)` for the old limits.
- **HEAD before every re-PATCH.** After any PATCH whose outcome is unknown, Ktus waits, sends HEAD and
  continues from the server's offset, so a retry costs one extra request. If that HEAD reports an offset below
  one the server already confirmed, the upload fails with `TusOffsetMismatchException` instead of rewinding.
- **A lost final response can look like an expired upload.** If the server stores the last chunk but its `204`
  never reaches the client, the follow-up HEAD can answer 404/410 because the server has already finished
  (and, on some servers, removed or moved) the upload. That surfaces as `TusUploadExpiredException`.
  When it is thrown while the last chunk was in flight, check on your server whether the file arrived before
  uploading it again.
- **Exceptions thrown by `onProgress`** are rethrown as they are and end the upload; they are never treated as
  network errors and retried.

### Build and CI
- Every GitHub Action is pinned to a full commit SHA; workflows default to `permissions: contents: read`.
- Publishing runs only for full releases, runs the JVM tests first, checks that the release tag equals the
  Gradle version, and reads its secrets from the `maven-central` environment.
- Gradle wrapper validation in every workflow, and `distributionSha256Sum` in `gradle-wrapper.properties`.
- A macOS CI job compiles the iOS targets.
- Build outputs are no longer committed.

## 1.0.3
- Retry on 401 Unauthorized so the request block can refresh an expired token.

## 1.0.2
- `ktus-okio` version bump.

## 1.0.1 and earlier
- Initial releases.
