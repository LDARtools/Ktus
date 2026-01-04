# Ktus ([Kotlin Multiplatform](https://kotlinlang.org/multiplatform/) + [Ktor](https://ktor.io/) + [Tus](https://tus.io))

<img alt="Ktus Logo" height="100" src="./Ktus.svg" title="Logo"/>

> tus is a protocol based on HTTP for resumable file uploads. Resumable means that an upload can be interrupted at any moment and can be resumed without re-uploading the previous data again. An interruption may happen willingly, if the user wants to pause, or by accident in case of a network issue or server outage.

**Ktus** is a multiplatform client library for uploading files using the [tus resumable upload protocol](https://tus.io) to any remote server supporting it. It is build on top of the [Ktor](https://ktor.io/) client library and supports all platforms supported by Ktor.

## Usage

Below are several usage examples showing basic and advanced flows.

### Basic

Ktus has a convenience function that combines both the create and upload phases of a Tus upload into a single call.

```kotlin
// import com.ldartools.ktus.createAndUploadTus
// import com.ldartools.ktus.okio.OkioTusFile

val file = OkioTusFile(filePath)

httpClient.createAndUploadTus(createUrl = url, file = file, metadata = mapOf("filename" to file.name)) { 
    // optional per-request configuration
    setAuthorizationHeader(anonymous = false)
}
```

### Create and upload separately

You can also split the create and upload phases into separate calls. This is useful for persisting the upload URL for later continuation or other advanced use cases where upload might not need to start right away.

```kotlin
// import com.ldartools.ktus.createTus
// import com.ldartools.ktus.uploadTus
// import com.ldartools.ktus.okio.OkioTusFile

val file = OkioTusFile(filePath)

// 1) Create the upload on the server and get the upload URL
val uploadUrl = httpClient.createTus(createUrl = url, file = file, metadata = mapOf("filename" to file.name))

// 2) Upload the file to the returned upload URL
httpClient.uploadTus(uploadUrl = uploadUrl, file = file) {
    // optional per-request configuration
    setAuthorizationHeader(anonymous = false)
}
```

### Passing options

Customize upload behavior (chunk size, retries, protocol extensions, file locking, etc.) via `TusUploadOptions`.

```kotlin
// import com.ldartools.ktus.TusUploadOptions
// import com.ldartools.ktus.RetryOptions

val options = TusUploadOptions(
    checkServerCapabilities = true,
    chunkSize = 4 * 1024 * 1024, // 4 MB
    useFileLock = false,
    retryOptions = RetryOptions(
        maxRetries = 5,
        initialDelayMillis = 1_000L,
        maxDelayMillis = 60_000L,
        factor = 2.0
    )
)

httpClient.createAndUploadTus(createUrl = url, file = file, options = options, onProgress = { sent, total ->
    println("Uploaded $sent / $total")
})
```

### Persisting the upload URL for continuation

Ktus does not have a built-in persistence mechanism, but it provides the necessary hooks to allow you to persist the upload URL so that uploads can be resumed later.

You can save the returned `uploadUrl` (for example, to local storage or a database) and later resume the upload by calling `uploadTus` with that URL.

```kotlin
// Persist the uploadUrl after creation
val uploadUrl = httpClient.createTus(createUrl = url, file = file)
saveToLocalStore("pendingUploadUrl", uploadUrl)

// Later (possibly after app restart) retrieve and resume
val persisted = loadFromLocalStore("pendingUploadUrl")
if (persisted != null) {
    httpClient.uploadTus(uploadUrl = persisted, file = file)
}
```

If you prefer a single call that still allows persisting the upload URL immediately after creation, use `createAndUploadTus` with an `onCreate` callback.

```kotlin
httpClient.createAndUploadTus(createUrl = url, file = file, onCreate = { uploadUrl ->
    // Persist the upload URL so you can resume later if needed
    saveToLocalStore("pendingUploadUrl", uploadUrl)
}, onProgress = { sent, total ->
    println("Uploaded $sent / $total")
})
```

### Pause and resume uploads

If you want to pause and resume uploads, you can achieve this by managing the upload coroutine job yourself.

```kotlin
//import kotlinx.coroutines.*

val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
var persistedUploadUrl: String? = null
lateinit var uploadJob: Job

// Start (create + upload) and persist uploadUrl immediately via onCreate
fun startUpload(httpClient: HttpClient, createUrl: String, file: ITusFile, options: TusUploadOptions) {
    uploadJob = scope.launch {
        try {
            httpClient.createAndUploadTus(
                createUrl = createUrl,
                file = file,
                options = options,
                onProgress = { sent, total -> println("Uploaded $sent / $total") },
                onCreate = { url ->
                    // persist the url to disk/db if you want durable resume across restarts
                    persistedUploadUrl = url
                }
            )
        } catch (e: CancellationException) {
            // paused by caller — safe to ignore or log
        } catch (t: Throwable) {
            // handle other errors
        }
    }
}

// Pause (cancel the running job)
suspend fun pauseUpload() {
    if (::uploadJob.isInitialized && uploadJob.isActive) {
        uploadJob.cancelAndJoin() // stops the upload coroutine and waits for cleanup
    }
}

// Resume (use persistedUploadUrl)
fun resumeUpload(httpClient: HttpClient, file: ITusFile, options: TusUploadOptions) {
    val url = persistedUploadUrl ?: throw IllegalStateException("No persisted upload URL")
    scope.launch {
        try {
            httpClient.uploadTus(
                uploadUrl = url,
                file = file,
                options = options,
                onProgress = { sent, total -> println("Uploaded $sent / $total") }
            )
        } catch (e: CancellationException) {
            // paused again
        } catch (t: Throwable) {
            // handle other errors
        }
    }
}
```

Notes:
- `OkioTusFile` is a convenient `ITusFile` implementation; you can implement `ITusFile` differently for other platforms.
- `onProgress` receives (sent, total) bytes and can be used to update UI progress bars.

## Installation

Add the following libraries to your `.toml` file.

```toml
[versions]
ktus = "1.0.0"

[libraries]
ktus = {module = "com.ldartools.ktus" , version.ref = "ktus"}
ktus-okio = {module = "com.ldartools.ktus-okio" , version.ref = "ktus"}
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
* Full support for Ktor DSL
* Cross platform
* [Metadata](https://tus.io/protocols/resumable-upload#upload-metadata)
* Progress
* [Creation](https://tus.io/protocols/resumable-upload#creation)
* [Creation With Upload](https://tus.io/protocols/resumable-upload#creation-with-upload)
* File read locks (optional)
* Retries with exponential backoff

## Limitations

The following optional [Tus protocol extensions](https://tus.io/protocols/resumable-upload#protocol-extensions) **have not** been implemented.
* [Expiration](https://tus.io/protocols/resumable-upload#expiration)
* [Checksum](https://tus.io/protocols/resumable-upload#checksum)
* [Termination](https://tus.io/protocols/resumable-upload#termination)
* [Concatenation](https://tus.io/protocols/resumable-upload#concatenation)
* File read locks (optional) are not supported by `OkioTusFile` because file read locks are not supported by Okio. If you want this feature, please upvote this issue https://github.com/square/okio/issues/1464.
* File locks (optional) are volatile. They will survive an application restart.

## Documentation

### ITusFile

Because Ktus is cross platform it must be told how to retrieve the bytes that are being uploaded. This is done via the `ITusFile` interface.
You must provide an `ITusFile` implementation(s) that will work for your platform(s).

```kotlin
/**
 * An interface abstracting the source of a file to be uploaded via TUS.
 * This allows the upload logic to be independent of whether the file is on disk,
 * in memory, or from another source.
 */
interface ITusFile {
    /** The total size of the file in bytes. */
    val size: Long

    /** The name of the file, which may be used for metadata. */
    val name: String

    /**
     * Reads a specific range of the file into a ByteReadChannel.
     * Implementations should ensure this operation is efficient and does not load
     * the entire file into memory, especially for large files.
     *
     * @param offset The byte offset to start reading from.
     * @param length The number of bytes to read.
     * @return A ByteReadChannel containing the specified section of the file.
     */
    suspend fun readSection(offset: Long, length: Long): ByteReadChannel

    /**
     * Creates a read lock on the file that will be closed when the upload is complete.
     * This is optional and helps prevent the file from being modified during an upload.
     * The returned AutoCloseable will be invoked at the end of the upload process.
     */
    suspend fun fileReadLock(): AutoCloseable
}
```

Note: While most use cases will be uploading a file from the file system. It is possible to upload from other sources (memory, streams, etc.) by providing a custom `ITusFile` implementation.
Whatever the source of the `ITusFile`, it MUST be re-readable. By its nature, Tus will re-request bytes to be read if a chunk fails.

#### OkioTusFile

While Ktus does provide an `ITusFile` implementation built using [Okio](https://github.com/square/okio).
This implementation should work for all platforms supported by Okio.
In order to keep the dependencies of Ktus at minimum, the `OkioTusFile` is provided in a separate module.

### File locks

The `uploadTus` function has a parameter that enables a file lock. This will lock the file, provided the `ITusFile` implementation supports it, in order to ensure that bytes are not mutated while an upload is in progress.

## Future Work

- [ ] Add Unit tests
    - [ ] Test Tus features
      - [ ] Core Protocol
        - [ ] Basic Upload
        - [ ] Resume Upload
        - [ ] Upload Offset (rewind)
        - [ ] Error Handling
      - [ ] Metadata
      - [ ] Progress
      - [ ] Creation
      - [ ] Creation With Upload
      - [ ] File read locks
      - [ ] Retries with exponential backoff
    - [x] Test `OkioTusFile` implementation
- [ ] Add `ITusFile` implementations for common platforms (e.g., Android, iOS, JVM)
- [ ] Add support for more Tus protocol extensions
    - [ ] Expiration
    - [ ] Checksum
    - [ ] Termination
    - [ ] Concatenation

PRs are welcome! :)