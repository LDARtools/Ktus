package com.ldartools.ktus

//TODO docs
data class TusUploadOptions(
    val checkServerCapabilities: Boolean = true,
    val chunkSize: Long = 2 * 1024 * 1024,// 2MB default
    val useFileLock: Boolean = false,
    val retryOptions: RetryOptions = RetryOptions())