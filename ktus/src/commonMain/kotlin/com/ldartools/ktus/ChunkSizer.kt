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
 * straight to [cap]. A timeout drops the size straight to [min] and the estimate to match: a cut yields no
 * goodput sample, and halving from [cap] on a link that has just become much slower would cost one more cut
 * (one more full silence limit) per halving. Growth back is again at most 2x per confirmed PATCH.
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
        val doubled = if (size > Long.MAX_VALUE / 2) Long.MAX_VALUE else size * 2
        size = wanted.coerceIn(min, cap).coerceAtMost(max(min, doubled))
    }

    /**
     * The PATCH timed out (an engine timeout or a Ktus silence limit). Without a target: halve (1.1). With a
     * target: back to [min], with the estimate set so the next size is the floor.
     */
    fun timedOut() {
        val target = targetMillis
        if (target == null) {
            size = max(min, size / 2)
            return
        }
        size = min
        bytesPerMillis = min.toDouble() / target
    }

    private companion object {
        const val ALPHA = 0.5
    }
}
