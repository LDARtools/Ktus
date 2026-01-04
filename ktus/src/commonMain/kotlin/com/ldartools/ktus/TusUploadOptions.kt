package com.ldartools.ktus

/**
 * Options for TUS uploads.
 *
 * @param checkServerCapabilities whether to check server capabilities before upload (default true)
 * @param chunkSize size of each upload chunk in bytes (default 2MB)
 * @param useFileLock whether to use file locking during upload (default false)
 * @param retryOptions options for retrying failed operations (default RetryOptions())
 */
data class TusUploadOptions(
    val checkServerCapabilities: Boolean = true,
    val chunkSize: Long = 2 * 1024 * 1024,// 2MB default
    val useFileLock: Boolean = false,
    val retryOptions: RetryOptions = RetryOptions())