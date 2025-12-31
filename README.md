# Ktus ([Kotlin Multiplatform](https://kotlinlang.org/multiplatform/) + [Ktor](https://ktor.io/) + [Tus](https://tus.io))

<img alt="Ktus Logo" height="100" src="./Ktus.svg" title="Logo"/>

> tus is a protocol based on HTTP for resumable file uploads. Resumable means that an upload can be interrupted at any moment and can be resumed without re-uploading the previous data again. An interruption may happen willingly, if the user wants to pause, or by accident in case of a network issue or server outage.

**Ktus** is a multiplatform client library for uploading files using the [tus resumable upload protocol](https://tus.io) to any remote server supporting it. It is build on top of the [Ktor](https://ktor.io/) client library and supports all platforms supported by Ktor.

## Usage

```kotlin
//Create an ITusFile instance. Here we use the OkioTusFile as an example.
val file = OkioTusFile(filePath)

//Create an object for holding any metadata (optional)
val metadata = mapOf(
    "fileCreated" to fileCreatedDate.toString(),
    "meaningOfLife" to 42.toString(),
    //...
)

//uploadTus is an extension function on the Ktor HttpClient
httpClient.uploadTus(createUrl = url, file = file, metadata = metadata, onProgress = onProgress){
    //here you can call any Ktor domain functions just like any other Ktor client API call
    setAuthorizationHeader(anonymous = false)
    //...
}
```

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

- [x] Update README.md
- [x] Create an icon for Ktus
- [ ] Remove Fibonacci example code
- [ ] Add Unit tests
- [ ] Publish alpha version

---

## Other resources

* Please find the detailed guide [here](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html).
* [Publishing via the Central Portal](https://central.sonatype.org/publish-ea/publish-ea-guide/)
* [Gradle Maven Publish Plugin \- Publishing to Maven Central](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)

## What is it?

This repository contains a simple library project, intended to demonstrate a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) library that is deployable to [Maven Central](https://central.sonatype.com/).

The library has only one function: generate the [Fibonacci sequence](https://en.wikipedia.org/wiki/Fibonacci_sequence) starting from platform-provided numbers. Also, it has a test for each platform just to be sure that tests run.

Note that no other actions or tools usually required for the library development are set up, such as [tracking of backwards compatibility](https://kotlinlang.org/docs/jvm-api-guidelines-backward-compatibility.html#tools-designed-to-enforce-backward-compatibility), explicit API mode, licensing, contribution guideline, code of conduct and others. You can find a guide for best practices for designing Kotlin libraries [here](https://kotlinlang.org/docs/api-guidelines-introduction.html).
