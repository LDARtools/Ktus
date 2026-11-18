package com.ldartools.ktus

import kotlin.math.max

/**
 * Chooses the size of each PATCH.
 *
 * Without a target ([TusUploadOptions.targetChunkMillis] null) it is the 1.1 behaviour: [cap], halved after a
 * timeout (when [TusUploadOptions.adaptiveChunkSize] is on) down to [min].
 *
 * With a target, the first PATCH of a call uses [min]. After each PATCH the server confirmed, the measured
 * goodput (bytes confirmed / time from the PATCH's start to its 204) updates an exponentially weighted
 * estimate, and the next PATCH is `estimate × target`, kept within [min]..[cap] and never more than twice the
 * previous size. The weight [ALPHA] = 0.5 follows a slowdown within two PATCHes while one outlier moves the
 * estimate only halfway; the 2× cap on growth stops one fast sample (a near-instant answer) from jumping
 * straight to [cap]. A timeout halves the size and lowers the estimate to match.
 */
internal class ChunkSizer(private val min: Long, private val cap: Long, private val targetMillis: Long?) {
    var size: Long = if (targetMillis != null) min else cap
        private set

    /** Estimated goodput in bytes per millisecond, or null before the first sample. */
    private var bytesPerMillis: Double? = null

    /** The server confirmed [bytes] of a PATCH that took [elapsedMillis] from its start to the 204. */
    fun confirmed(bytes: Long, elapsedMillis: Long) {
        val target = targetMillis ?: return
        if (bytes <= 0) return
        val sample = bytes.toDouble() / max(elapsedMillis, 1L)
        val estimate = bytesPerMillis?.let { ALPHA * sample + (1 - ALPHA) * it } ?: sample
        bytesPerMillis = estimate
        val wanted = (estimate * target).coerceAtMost(Long.MAX_VALUE / 4.0).toLong()
        size = wanted.coerceIn(min, cap).coerceAtMost(max(min, size * 2))
    }

    /** The PATCH timed out (an engine timeout or a Ktus silence limit): halve, and lower the estimate to match. */
    fun timedOut() {
        size = max(min, size / 2)
        val target = targetMillis ?: return
        bytesPerMillis = bytesPerMillis?.let { minOf(it, size.toDouble() / target) }
    }

    private companion object {
        const val ALPHA = 0.5
    }
}
