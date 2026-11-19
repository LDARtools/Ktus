package com.ldartools.ktus.test

/**
 * Measures, from the public callbacks, how long the first cut request had been silent when it was cut: the time
 * from that attempt's last `onProgress` to the moment the attempt ended.
 *
 * Ktus calls `onActivity` when a request gets its outcome (an answer or a failure, a cut included), and on every
 * body progress event just before `onProgress`. So the first `onActivity` after the cut attempt's last
 * `onProgress` is the end of that attempt. The first retry is where the reported position goes back. Recovery
 * (backoff, HEAD, the next PATCH) comes after both and is not counted. Use with `activityHeartbeatMillis = null`.
 */
class CutGapProbe {
    private sealed class Event(val atMillis: Long) {
        class Progress(atMillis: Long, val sent: Long) : Event(atMillis)
        class Activity(atMillis: Long) : Event(atMillis)
    }

    private val events = SyncList<Event>()

    fun progress(atMillis: Long, sent: Long) { events += Event.Progress(atMillis, sent) }
    fun activity(atMillis: Long) { events += Event.Activity(atMillis) }

    /** Silence before the first cut in ms, or null when no attempt was retried. */
    fun silenceBeforeFirstCut(): Long? {
        val all = events.snapshot()
        var previous: Event.Progress? = null
        var retryAt = -1
        for ((i, e) in all.withIndex()) {
            if (e !is Event.Progress) continue
            if (previous != null && e.sent < previous.sent) { retryAt = i; break }
            previous = e
        }
        if (retryAt < 0) return null
        val lastIndex = (retryAt - 1 downTo 0).first { all[it] is Event.Progress }
        val last = all[lastIndex]
        val end = (lastIndex + 1 until retryAt).map { all[it] }.firstOrNull { it is Event.Activity } ?: return null
        return end.atMillis - last.atMillis
    }
}
