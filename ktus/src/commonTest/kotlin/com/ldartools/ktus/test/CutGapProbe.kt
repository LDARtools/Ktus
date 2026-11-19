package com.ldartools.ktus.test

/**
 * Measures, from the public callbacks, how long the first cut request had been silent when it was cut: the time
 * from that attempt's last `onProgress` to the moment the attempt ended.
 *
 * Ktus calls `onActivity` when a request starts and when it gets its outcome (an answer or a failure, a cut
 * included), and on every body progress event just before `onProgress`. So the first `onActivity` after an
 * attempt's last `onProgress` is the end of that attempt. A PATCH that succeeded is followed IMMEDIATELY by
 * `onConfirmed` (Ktus calls nothing else between the 204 and it); a cut one is not: the next event is the
 * recovery HEAD's `onActivity`, even when that HEAD then shows more data (an abandoned PATCH the server still
 * applied) and fires its own `onConfirmed`. Attempt boundaries are therefore read from the callbacks, never
 * from the reported position, which a retry can repeat or exceed. Use with `activityHeartbeatMillis = null`,
 * and pass `onConfirmed` to [confirmed].
 */
class CutGapProbe {
    sealed class Event(val atMillis: Long) {
        class Progress(atMillis: Long, val sent: Long) : Event(atMillis)
        class Activity(atMillis: Long) : Event(atMillis)
        class Confirmed(atMillis: Long) : Event(atMillis)
    }

    private val events = SyncList<Event>()

    fun progress(atMillis: Long, sent: Long) { events += Event.Progress(atMillis, sent) }
    fun activity(atMillis: Long) { events += Event.Activity(atMillis) }
    fun confirmed(atMillis: Long) { events += Event.Confirmed(atMillis) }

    /** Silence before the first cut in ms, or null when no attempt with body progress was cut. */
    fun silenceBeforeFirstCut(): Long? = silenceBeforeFirstCut(events.snapshot())

    companion object {
        internal fun silenceBeforeFirstCut(all: List<Event>): Long? {
            var i = 0
            while (i < all.size) {
                if (all[i] !is Event.Progress) { i++; continue }
                // The attempt's progress run: up to the first Activity that is not the one paired with a Progress.
                var lastProgress = i
                var j = i + 1
                var end = -1
                while (j < all.size) {
                    val e = all[j]
                    if (e is Event.Progress) { lastProgress = j; j++; continue }
                    // An Activity immediately followed by a Progress is that progress event's own activity.
                    if (e is Event.Activity && j + 1 < all.size && all[j + 1] is Event.Progress) { j++; continue }
                    if (e is Event.Activity) { end = j; break }
                    j++
                }
                if (end < 0) return null
                val succeeded = end + 1 < all.size && all[end + 1] is Event.Confirmed
                if (!succeeded) return all[end].atMillis - all[lastProgress].atMillis
                i = end + 1
            }
            return null
        }
    }
}
