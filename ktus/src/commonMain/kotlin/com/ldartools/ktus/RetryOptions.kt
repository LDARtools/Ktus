package com.ldartools.ktus

/**
 * Retry options for retrying operations.
 *
 * @param times number of retry attempts (default 3)
 * @param initialDelayMillis initial delay in milliseconds before first retry (default 500)
 * @param maxDelayMillis maximum delay in milliseconds (default 10000)
 * @param factor multiplier applied to delay between attempts (default 2.0)
 */
data class RetryOptions(
    val times:Int = 3,
    val initialDelayMillis:Long = 500L,
    val maxDelayMillis:Long = 10_000L,
    val factor: Double = 2.0
)
